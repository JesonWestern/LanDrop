#include "app.h"
#include "json.h"
#include "protocol.h"

#include <cstdarg>
#include <cstdio>
#include <string>
#include <vector>

namespace cli {

namespace {

bool g_console = false;

void AttachParentConsole() {
    HANDLE out = GetStdHandle(STD_OUTPUT_HANDLE);
    if (out == nullptr || out == INVALID_HANDLE_VALUE) {
        if (!AttachConsole(ATTACH_PARENT_PROCESS)) return;
        FILE* f = nullptr;
        freopen_s(&f, "CONOUT$", "w", stdout);
        freopen_s(&f, "CONOUT$", "w", stderr);
        freopen_s(&f, "CONIN$", "r", stdin);
    }
    setvbuf(stdout, nullptr, _IOLBF, 0);
    SetConsoleOutputCP(CP_UTF8);
    g_console = true;
}

void Out(const char* fmt, ...) {
    if (!g_console) return;
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stdout, fmt, ap);
    va_end(ap);
    fputc('\n', stdout);
    fflush(stdout);
}

int SelfTest() {
    int fail = 0;
    // 1) CRC32 标准向量
    const char* check = "123456789";
    uint32_t crc = proto::Crc32(check, 9);
    Out("{\"step\":\"crc32\",\"ok\":%s,\"got\":\"%08x\"}", crc == 0xCBF43926u ? "true" : "false",
        crc);
    if (crc != 0xCBF43926u) ++fail;

    // 2) 帧头编解码
    proto::DataHdr h{};
    h.magic = proto::kMagicData;
    h.taskId = 0xDEADBEEF;
    h.streamSeq = 7;
    h.flags = proto::kProtoVersion == 1 ? proto::F_LAST : 0;
    h.fileIndex = 3;
    h.chunkIndex = 42;
    h.payloadLen = 1048576;
    h.payloadCrc32 = 0x12345678;
    uint8_t raw[28];
    proto::EncodeHdr(raw, h);
    proto::DataHdr d2{};
    bool dOk = proto::DecodeHdr(raw, &d2) && d2.taskId == h.taskId && d2.streamSeq == h.streamSeq &&
               d2.fileIndex == h.fileIndex && d2.chunkIndex == h.chunkIndex &&
               d2.payloadLen == h.payloadLen && d2.payloadCrc32 == h.payloadCrc32 &&
               d2.flags == h.flags;
    Out("{\"step\":\"frame\",\"ok\":%s,\"size\":%d}", dOk ? "true" : "false", (int)sizeof(raw));
    if (!dOk) ++fail;

    // 3) 中文 JSON 往返
    js::Writer w;
    w.Obj();
    w.Key("t").S("probe");
    w.Key("name").WS(L"我的手机 · 测试");
    w.Key("n").I(1234567890123LL);
    w.Key("arr").Arr().S("a").S("b").End();
    w.End();
    js::Value v;
    bool jOk = js::Parse(w.Str(), &v);
    std::wstring name = jOk ? v.GetWStr("name") : L"";
    jOk = jOk && name == L"我的手机 · 测试" && v.GetInt("n") == 1234567890123LL;
    Out("{\"step\":\"json\",\"ok\":%s,\"bytes\":%d}", jOk ? "true" : "false", (int)w.Str().size());
    if (!jOk) ++fail;

    // 4) 任务键稳定性
    std::vector<FileEntry> fs;
    FileEntry f;
    f.rel = L"DCIM/照片.jpg";
    f.size = 1234;
    f.mtime = 133000000000000000ull;
    fs.push_back(f);
    FileEntry g2 = f;
    g2.rel = L"a.txt";
    std::vector<FileEntry> fs2{f, g2};
    std::vector<FileEntry> fs3{g2, f};
    const std::wstring k1 = storage::MakeJobKey("dev-1", fs2, proto::kChunkBytes);
    const std::wstring k2 = storage::MakeJobKey("dev-1", fs3, proto::kChunkBytes);
    bool kOk = (k1 == k2) && k1.size() == 16;
    Out("{\"step\":\"jobkey\",\"ok\":%s,\"key\":\"%s\"}", kOk ? "true" : "false",
        util::Narrow(k1).c_str());
    if (!kOk) ++fail;

    Out("{\"result\":\"%s\",\"failed\":%d}", fail == 0 ? "pass" : "fail", fail);
    return fail == 0 ? 0 : 1;
}

int Discover(int secs) {
    g_app.headless = true;
    disc::Start();
    const uint64_t t0 = util::NowMs();
    std::vector<Device> last;
    while (util::NowMs() - t0 < (uint64_t)secs * 1000ull) {
        Sleep(100);
    }
    disc::Snapshot(&last);
    for (const auto& d : last) {
        Out("{\"id\":\"%s\",\"name\":\"%s\",\"ip\":\"%s\",\"port\":%d,\"plat\":\"%s\",\"ver\":\"%s\"}",
            d.id.c_str(), util::Narrow(d.name).c_str(), util::Narrow(netu::IpToText(d.ip)).c_str(),
            (int)d.port, d.plat.c_str(), d.ver.c_str());
    }
    Out("{\"result\":\"ok\",\"count\":%d}", (int)last.size());
    disc::Stop();
    return 0;
}

int Serve(const std::wstring& dir, int port) {
    g_app.headless = true;
    if (!dir.empty()) g_app.cfg.saveDir = dir;
    util::EnsureDir(g_app.cfg.saveDir);
    if (!srv::Start((uint16_t)port)) {
        Out("{\"event\":\"error\",\"reason\":\"bind_failed\",\"port\":%d}", port);
        return 2;
    }
    Out("{\"event\":\"listen\",\"port\":%d,\"dir\":\"%s\"}", port,
        util::Narrow(g_app.cfg.saveDir).c_str());

    std::vector<JobView> views;
    std::vector<JobState> seen;
    uint64_t lastPrint = 0;
    while (true) {
        Sleep(100);
        jobs::Snapshot(&views);
        for (const auto& v : views) {
            bool known = false;
            size_t i = 0;
            for (; i < seen.size(); ++i) {
                if (views[i].id == v.id) { known = true; break; }
            }
            (void)known;
            static std::vector<uint32_t> ids;
            static std::vector<int> states;
            bool found = false;
            for (size_t k = 0; k < ids.size(); ++k) {
                if (ids[k] == v.id) {
                    found = true;
                    if (states[k] != (int)v.state) {
                        states[k] = (int)v.state;
                        Out("{\"event\":\"state\",\"id\":%u,\"state\":\"%s\",\"pct\":%.1f}",
                            v.id, util::Narrow(StateText(v.state)).c_str(),
                            v.Progress() * 100.0);
                    }
                    break;
                }
            }
            if (!found) {
                ids.push_back(v.id);
                states.push_back((int)v.state);
                Out("{\"event\":\"job\",\"id\":%u,\"title\":\"%s\",\"bytes\":%llu,\"files\":%u}",
                    v.id, util::Narrow(v.title).c_str(), (unsigned long long)v.totalBytes,
                    v.totalFiles);
            }
        }
        if (util::NowMs() - lastPrint > 2000) {
            lastPrint = util::NowMs();
            fflush(stdout);
        }
    }
    return 0;
}

int Send(const std::wstring& ipText, const std::vector<std::wstring>& paths, int port) {
    const uint32_t ip = netu::ParseIp(ipText);
    if (ip == 0) {
        Out("{\"event\":\"error\",\"reason\":\"bad_ip\"}");
        return 2;
    }
    client::SendOptions opt;
    opt.ip = ip;
    opt.port = (uint16_t)port;
    opt.conns = g_app.cfg.dataConns;
    if (!client::StartSend(paths, opt, L"")) {
        Out("{\"event\":\"error\",\"reason\":\"nothing_to_send\"}");
        return 2;
    }
    // 等最后一个任务结束
    uint64_t t0 = util::NowMs();
    int lastPct = -1;
    while (util::NowMs() - t0 < 30 * 60 * 1000ull) {
        Sleep(150);
        std::vector<JobView> views;
        jobs::Snapshot(&views);
        if (views.empty()) continue;
        const JobView& v = views.front();
        const int pct = (int)(v.Progress() * 100);
        if (pct / 5 != lastPct / 5) {
            lastPct = pct;
            Out("{\"event\":\"progress\",\"pct\":%.1f,\"bps\":%.0f,\"state\":\"%s\"}",
                v.Progress() * 100.0, v.bps, util::Narrow(StateText(v.state)).c_str());
        }
        if (!StateIsActive(v.state)) {
            Out("{\"event\":\"done\",\"ok\":%s,\"state\":\"%s\",\"msg\":\"%s\"}",
                v.state == JobState::Done ? "true" : "false",
                util::Narrow(StateText(v.state)).c_str(), util::Narrow(v.errMsg).c_str());
            return v.state == JobState::Done ? 0 : 1;
        }
    }
    Out("{\"event\":\"done\",\"ok\":false,\"state\":\"timeout\"}");
    return 1;
}

int SpeedTest(const std::wstring& ipText, int dirMode, int secs, int conns, int port) {
    const uint32_t ip = netu::ParseIp(ipText);
    if (ip == 0) {
        Out("{\"result\":\"error\",\"reason\":\"bad_ip\"}");
        return 2;
    }
    speed::Params p;
    p.ip = ip;
    p.port = (uint16_t)port;
    p.conns = conns;
    p.secs = secs;
    p.dirMode = dirMode;
    speed::Result r = speed::RunBlocking(p);
    Out("{\"dir\":\"%s\",\"conns\":%d,\"avgMbps\":%.2f,\"peakMbps\":%.2f,\"p50Mbps\":%.2f,"
        "\"bytes\":%llu,\"ok\":%s,\"err\":\"%s\"}",
        dirMode == 0 ? "up" : (dirMode == 1 ? "down" : "both"), conns, r.avgMbps, r.peakMbps,
        r.p50Mbps, (unsigned long long)(r.bytesUp + r.bytesDown), r.ok ? "true" : "false",
        util::Narrow(r.err).c_str());
    return r.ok ? 0 : 1;
}

bool IsSwitch(const wchar_t* a) { return a && a[0] == L'-'; }

}  // namespace

int Run(int argc, wchar_t** argv) {
    bool hasCli = false;
    for (int i = 1; i < argc; ++i) {
        const std::wstring a = argv[i];
        if (a.rfind(L"--", 0) == 0 &&
            a != L"--send-many" && a != L"--tray") {
            hasCli = true;
            break;
        }
    }
    if (!hasCli) return -1;

    AttachParentConsole();

    // 这些模式不需要配置/日志（避免提权实例锁文件）
    for (int i = 1; i < argc; ++i) {
        const std::wstring a = argv[i];
        if (a == L"--install-firewall") {
            bool ok = fw::AddRules();
            Out("{\"ok\":%s,\"rules\":3}", ok ? "true" : "false");
            return ok ? 0 : 1;
        }
        if (a == L"--setup-ctxmenu") {
            bool ok = shellx::InstallContextMenu();
            Out("{\"ok\":%s}", ok ? "true" : "false");
            return ok ? 0 : 1;
        }
        if (a == L"--uninstall-ctxmenu") {
            shellx::RemoveContextMenu();
            Out("{\"ok\":true}");
            return 0;
        }
        if (a == L"--version") {
            Out("{\"name\":\"LanDrop\",\"version\":\"%s\",\"protocol\":%d}", LANDROP_VERSION_A,
                proto::kProtoVersion);
            return 0;
        }
    }

    cfg::Load();
    logx::Init();
    util::EnsureDir(g_app.cfg.tmpDir);
    if (!netu::WsaInit()) {
        Out("{\"result\":\"error\",\"reason\":\"wsa\"}");
        return 3;
    }
    jobs::Init();

    int rc = 0;
    for (int i = 1; i < argc; ++i) {
        const std::wstring a = argv[i];
        if (a == L"--selftest") {
            rc = SelfTest();
            goto done;
        }
        if (a == L"--discover") {
            int secs = 5;
            for (int k = i + 1; k < argc; ++k) {
                if (!wcscmp(argv[k], L"--secs") && k + 1 < argc) { secs = _wtoi(argv[k + 1]); break; }
            }
            rc = Discover(secs);
            goto done;
        }
        if (a == L"--serve") {
            std::wstring dir;
            int port = g_app.cfg.listenPort;
            for (int k = i + 1; k < argc; ++k) {
                if (!wcscmp(argv[k], L"--dir") && k + 1 < argc) dir = argv[++k];
                else if (!wcscmp(argv[k], L"--port") && k + 1 < argc) port = _wtoi(argv[++k]);
            }
            rc = Serve(dir, port);
            goto done;
        }
        if (a == L"--send") {
            if (i + 1 >= argc) { Out("{\"error\":\"need ip\"}"); rc = 2; goto done; }
            std::wstring ip = argv[i + 1];
            std::vector<std::wstring> paths;
            int port = g_app.cfg.listenPort;
            for (int k = i + 2; k < argc; ++k) {
                std::wstring v = argv[k];
                if (v == L"--port" && k + 1 < argc) { port = _wtoi(argv[++k]); continue; }
                if (IsSwitch(v.c_str())) continue;
                paths.push_back(v);
            }
            rc = Send(ip, paths, port);
            goto done;
        }
        if (a == L"--speedtest") {
            if (i + 1 >= argc) { Out("{\"error\":\"need ip\"}"); rc = 2; goto done; }
            std::wstring ip = argv[i + 1];
            int dir = 2, secs = 10, conns = 4, port = g_app.cfg.listenPort;
            for (int k = i + 2; k < argc; ++k) {
                std::wstring v = argv[k];
                if (v == L"--dir" && k + 1 < argc) {
                    std::wstring d = argv[++k];
                    dir = (d == L"up") ? 0 : (d == L"down" ? 1 : 2);
                } else if (v == L"--secs" && k + 1 < argc) {
                    secs = _wtoi(argv[++k]);
                } else if (v == L"--conns" && k + 1 < argc) {
                    conns = _wtoi(argv[++k]);
                } else if (v == L"--port" && k + 1 < argc) {
                    port = _wtoi(argv[++k]);
                }
            }
            rc = SpeedTest(ip, dir, secs, conns, port);
            goto done;
        }
    }
    rc = 0;

done:
    jobs::Shutdown();
    netu::WsaCleanup();
    logx::Flush();
    logx::Shutdown();
    return rc;
}

}  // namespace cli
