#include "app.h"
#include "json.h"
#include "protocol.h"

#include <algorithm>
#include <map>
#include <mutex>
#include <thread>

namespace client {

namespace {

constexpr uint32_t kWindow = proto::kWindowChunks;

struct ChunkRef {
    uint32_t file = 0;
    uint32_t chunk = 0;
};

struct SendCtx {
    std::shared_ptr<Job> job;
    std::vector<ChunkRef> pending;
    std::atomic<size_t> cursor{0};
    std::atomic<uint32_t> failedChunks{0};
    std::vector<std::thread> threads;
    SOCKET ctrl = INVALID_SOCKET;
    std::string token;
    uint32_t taskId = 0;
    std::atomic<bool> aborted{false};
};

uint64_t FileTimeToU64(const FILETIME& ft) {
    return ((uint64_t)ft.dwHighDateTime << 32) | ft.dwLowDateTime;
}

void WalkDir(const std::wstring& absDir, const std::wstring& relDir, std::vector<FileEntry>* out,
             bool skipHiddenSys) {
    std::wstring pattern = util::AddLong(absDir) + L"\\*";
    WIN32_FIND_DATAW fd{};
    HANDLE h = FindFirstFileExW(pattern.c_str(), FindExInfoBasic, &fd, FindExSearchNameMatch,
                                nullptr, FIND_FIRST_EX_LARGE_FETCH);
    if (h == INVALID_HANDLE_VALUE) {
        logx::Line("send: cannot enumerate %s err=%lu", util::Narrow(absDir).c_str(),
                   GetLastError());
        return;
    }
    size_t before = out->size();
    do {
        if (!wcscmp(fd.cFileName, L".") || !wcscmp(fd.cFileName, L"..")) continue;
        if (fd.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) continue;  // 不跟符号链接
        if (skipHiddenSys && (fd.dwFileAttributes & (FILE_ATTRIBUTE_HIDDEN | FILE_ATTRIBUTE_SYSTEM)))
            continue;
        std::wstring rel = relDir.empty() ? std::wstring(fd.cFileName)
                                          : relDir + L"\\" + fd.cFileName;
        if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
            WalkDir(util::JoinPath(absDir, fd.cFileName), rel, out, skipHiddenSys);
        } else {
            FileEntry f;
            f.rel = rel;
            f.srcAbs = util::JoinPath(absDir, fd.cFileName);
            f.size = ((uint64_t)fd.nFileSizeHigh << 32) | fd.nFileSizeLow;
            f.mtime = FileTimeToU64(fd.ftLastWriteTime);
            f.attrs = fd.dwFileAttributes;
            f.chunkCount = (uint32_t)((f.size + proto::kChunkBytes - 1) / proto::kChunkBytes);
            f.InitBits();
            out->push_back(std::move(f));
        }
    } while (FindNextFileW(h, &fd));
    FindClose(h);
    // 空目录显式入清单
    if (out->size() == before && !relDir.empty()) {
        FileEntry d;
        d.rel = relDir;
        d.isDir = true;
        d.size = 0;
        d.chunkCount = 0;
        out->push_back(std::move(d));
    }
}

std::vector<FileEntry> Enumerate(const std::vector<std::wstring>& paths, bool skipHiddenSys) {
    std::vector<FileEntry> out;
    for (const auto& p : paths) {
        if (util::IsDirPath(p)) {
            std::wstring name = util::FileNameOf(p);
            WalkDir(p, name, &out, skipHiddenSys);
        } else if (util::PathExists(p)) {
            WIN32_FILE_ATTRIBUTE_DATA ad{};
            if (!GetFileAttributesExW(util::AddLong(p).c_str(), GetFileExInfoStandard, &ad)) continue;
            FileEntry f;
            f.rel = util::FileNameOf(p);
            f.srcAbs = p;
            f.size = ((uint64_t)ad.nFileSizeHigh << 32) | ad.nFileSizeLow;
            f.mtime = FileTimeToU64(ad.ftLastWriteTime);
            f.attrs = ad.dwFileAttributes;
            f.chunkCount = (uint32_t)((f.size + proto::kChunkBytes - 1) / proto::kChunkBytes);
            f.InitBits();
            out.push_back(std::move(f));
        }
    }
    return out;
}

bool ConnectWithTimeout(uint32_t ip, uint16_t port, int timeoutMs, SOCKET* out) {
    SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (s == INVALID_SOCKET) return false;
    int snd = 1024 * 1024, rcv = 1024 * 1024;
    setsockopt(s, SOL_SOCKET, SO_SNDBUF, (const char*)&snd, sizeof(snd));
    setsockopt(s, SOL_SOCKET, SO_RCVBUF, (const char*)&rcv, sizeof(rcv));
    netu::SetNoDelay(s);
    netu::SetNonBlocking(s, true);

    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_port = htons(port);
    a.sin_addr.s_addr = htonl(ip);
    int rc = connect(s, (sockaddr*)&a, sizeof(a));
    if (rc != 0) {
        int e = WSAGetLastError();
        if (e != WSAEWOULDBLOCK && e != WSAEINPROGRESS) {
            closesocket(s);
            return false;
        }
        fd_set wr, ex;
        FD_ZERO(&wr);
        FD_ZERO(&ex);
        FD_SET(s, &wr);
        FD_SET(s, &ex);
        timeval tv{timeoutMs / 1000, (timeoutMs % 1000) * 1000};
        int n = select(0, nullptr, &wr, &ex, &tv);
        if (n <= 0 || FD_ISSET(s, &ex)) {
            closesocket(s);
            return false;
        }
        int err = 0;
        int len = sizeof(err);
        if (getsockopt(s, SOL_SOCKET, SO_ERROR, (char*)&err, &len) != 0 || err != 0) {
            closesocket(s);
            return false;
        }
    }
    netu::SetNonBlocking(s, false);
    DWORD tmo = 120000;
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, (const char*)&tmo, sizeof(tmo));
    DWORD stmo = 30000;
    setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, (const char*)&stmo, sizeof(stmo));
    *out = s;
    return true;
}

bool ReadLine(SOCKET s, prot::LineReader* rd, std::string* line) {
    char buf[8192];
    for (;;) {
        int got = recv(s, buf, sizeof(buf), 0);
        if (got <= 0) return false;
        rd->Feed(buf, (size_t)got);
        if (rd->Next(line)) return true;
        if (rd->Overflowed()) return false;
    }
}

bool WaitDataOk(SOCKET s) {
    prot::LineReader rd;
    std::string line;
    if (!ReadLine(s, &rd, &line)) return false;
    js::Value v;
    return js::Parse(line, &v) && v.GetStr("t") == "data_ok";
}

void DataWorker(std::shared_ptr<SendCtx> ctx, SOCKET s, uint16_t seq) {
    g_app.activeWorkers.fetch_add(1);
    std::vector<char> payload;
    payload.resize(proto::kChunkBytes);
    std::map<uint32_t, HANDLE> handles;
    uint32_t inflight = 0;
    uint32_t errors = 0;
    auto job = ctx->job;

    auto closeHandles = [&]() {
        for (auto& kv : handles) {
            if (kv.second != INVALID_HANDLE_VALUE) CloseHandle(kv.second);
        }
        handles.clear();
    };

    auto readAck = [&](bool block) -> bool {
        uint8_t hdr[28];
        int got = recv(s, (char*)hdr, 28, MSG_WAITALL);
        if (got != 28) return false;
        proto::DataHdr h{};
        if (!proto::DecodeHdr(hdr, &h) || h.magic != proto::kMagicAck) return false;
        if (h.flags & proto::F_NAK) {
            ++errors;
            ctx->failedChunks.fetch_add(1);
        } else {
            if (h.fileIndex < job->files.size()) {
                FileEntry& f = job->files[h.fileIndex];
                if (!f.HasChunk(h.chunkIndex)) {
                    f.MarkChunk(h.chunkIndex);
                    uint64_t len = proto::kChunkBytes;
                    if (h.chunkIndex + 1 == f.chunkCount) {
                        len = f.size - (uint64_t)h.chunkIndex * proto::kChunkBytes;
                    }
                    job->doneBytes.fetch_add(len);
                }
            }
        }
        return true;
    };

    while (!ctx->aborted && !job->cancel.load()) {
        size_t i = ctx->cursor.fetch_add(1);
        if (i >= ctx->pending.size()) break;
        const ChunkRef ref = ctx->pending[i];
        if (ref.file >= job->files.size()) continue;
        const FileEntry& f = job->files[ref.file];
        if (f.HasChunk(ref.chunk)) continue;

        HANDLE h = INVALID_HANDLE_VALUE;
        auto it = handles.find(ref.file);
        if (it != handles.end()) {
            h = it->second;
        } else {
            h = CreateFileW(util::AddLong(f.srcAbs).c_str(), GENERIC_READ,
                            FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
                            OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL | FILE_FLAG_SEQUENTIAL_SCAN,
                            nullptr);
            handles[ref.file] = h;
        }
        if (h == INVALID_HANDLE_VALUE) {
            ++errors;
            continue;
        }

        LARGE_INTEGER off;
        off.QuadPart = (LONGLONG)ref.chunk * (LONGLONG)proto::kChunkBytes;
        if (!SetFilePointerEx(h, off, nullptr, FILE_BEGIN)) { ++errors; continue; }
        uint64_t want = f.size - (uint64_t)ref.chunk * proto::kChunkBytes;
        if (want > proto::kChunkBytes) want = proto::kChunkBytes;
        DWORD got = 0;
        if (!ReadFile(h, payload.data(), (DWORD)want, &got, nullptr) || got != want) {
            ++errors;
            continue;
        }

        proto::DataHdr out{};
        out.magic = proto::kMagicData;
        out.taskId = ctx->taskId;
        out.streamSeq = seq;
        out.flags = (ref.chunk + 1 == f.chunkCount) ? proto::F_LAST : 0;
        out.fileIndex = ref.file;
        out.chunkIndex = ref.chunk;
        out.payloadLen = got;
        out.payloadCrc32 = proto::Crc32(payload.data(), got);
        uint8_t hdr[28];
        proto::EncodeHdr(hdr, out);
        if (!prot::SendAll(s, hdr, 28) || !prot::SendAll(s, payload.data(), got)) {
            ctx->aborted = true;
            break;
        }
        ++inflight;

        while (inflight >= kWindow && !ctx->aborted) {
            if (!readAck(true)) { ctx->aborted = true; break; }
            --inflight;
            if (errors > 64) { ctx->aborted = true; break; }
        }
        if (errors > 64) { ctx->aborted = true; break; }
    }

    // 收尾：把剩余 ACK 读完
    int guard = 0;
    while (inflight > 0 && !ctx->aborted && guard++ < 2000) {
        if (!readAck(true)) break;
        --inflight;
    }

    closeHandles();
    closesocket(s);
    g_app.activeWorkers.fetch_sub(1);
}

void SessionThread(std::shared_ptr<SendCtx> ctx) {
    g_app.activeWorkers.fetch_add(1);
    auto job = ctx->job;
    const Device dev = job->dev;
    prot::LineReader rd;
    std::string line;

    auto fail = [&](const std::wstring& msg) {
        job->state = JobState::Failed;
        job->errMsg = msg;
        job->endTick = util::NowMs();
        logx::Line("send: job %u failed: %s", job->id, util::Narrow(msg).c_str());
        if (ctx->ctrl != INVALID_SOCKET) closesocket(ctx->ctrl);
        if (g_app.mainWnd) PostMessageW(g_app.mainWnd, WM_APP_WAKE, 0, 0);
        g_app.activeWorkers.fetch_sub(1);
    };

    if (!ConnectWithTimeout(dev.ip, dev.port, 5000, &ctx->ctrl)) {
        fail(L"连不上对方，请确认手机端已打开并亮屏");
        return;
    }

    // hello
    {
        js::Writer w;
        w.Obj();
        w.Key("t").S("hello");
        w.Key("kind").S("ctrl");
        w.Key("v").I(proto::kProtoVersion);
        w.Key("id").S(util::Narrow(g_app.cfg.deviceId));
        w.Key("name").WS(g_app.cfg.deviceName);
        w.Key("plat").S("win");
        w.Key("ver").S(LANDROP_VERSION_A);
        w.Key("caps").Arr().S("file").S("folder").S("resume").S("speed").End();
        w.End();
        if (!prot::SendLine(ctx->ctrl, w)) { fail(L"握手失败"); return; }
    }
    if (!ReadLine(ctx->ctrl, &rd, &line)) { fail(L"对方未响应"); return; }
    {
        js::Value v;
        if (!js::Parse(line, &v) || v.GetStr("t") != "hello_ok") { fail(L"协议版本不匹配"); return; }
    }

    // offer
    {
        js::Writer w;
        w.Obj();
        w.Key("t").S("offer");
        w.Key("total").U(job->totalBytes);
        w.Key("files").Arr();
        for (const auto& f : job->files) {
            w.Obj();
            w.Key("rel").WS(f.rel);
            w.Key("size").U(f.size);
            w.Key("mtime").U(f.mtime);
            w.Key("isDir").B(f.isDir);
            w.End();
        }
        w.End();
        w.End();
        if (!prot::SendLine(ctx->ctrl, w)) { fail(L"发送清单失败"); return; }
    }

    {
        if (!ReadLine(ctx->ctrl, &rd, &line)) { fail(L"对方未接受"); return; }
        js::Value v;
        if (!js::Parse(line, &v)) { fail(L"应答解析失败"); return; }
        const std::string t = v.GetStr("t");
        if (t == "reject") {
            const std::string reason = v.GetStr("reason");
            std::wstring msg = L"对方拒绝接收";
            if (reason == "no_space") msg = L"对方磁盘空间不足";
            else if (reason == "unsafe_path") msg = L"文件名不合法";
            else if (reason == "declined") msg = L"对方取消了本次接收";
            fail(msg);
            return;
        }
        if (t != "accept") { fail(L"应答异常"); return; }

        job->jobKey = util::Widen(v.GetStr("job"));
        // 应用已有位图（续传）
        const js::Value* have = v.GetArr("have");
        if (have) {
            for (const auto& item : have->arr) {
                const int idx = (int)item.GetInt("i", -1);
                if (idx < 0 || (size_t)idx >= job->files.size()) continue;
                FileEntry& f = job->files[(size_t)idx];
                f.InitBits();
                const js::Value* bits = item.GetArr("bits");
                if (!bits) continue;
                size_t wi = 0;
                for (const auto& wv : bits->arr) {
                    if (wi >= f.doneBits.size()) break;
                    f.doneBits[wi++] = (uint32_t)wv.num;
                }
                uint32_t cnt = 0;
                for (uint32_t c = 0; c < f.chunkCount; ++c) {
                    if (f.HasChunk(c)) ++cnt;
                }
                f.doneChunks = cnt;
            }
            uint64_t done = 0;
            for (const auto& f : job->files) {
                done += (uint64_t)f.doneChunks * proto::kChunkBytes;
            }
            if (done > job->totalBytes) done = job->totalBytes;
            job->doneBytes = done;
        }
    }

    ctx->taskId = proto::TaskIdFromKey(util::Narrow(job->jobKey));
    ctx->token = util::Narrow(job->jobKey);

    // 组装待发块
    for (uint32_t fi = 0; fi < job->files.size(); ++fi) {
        const FileEntry& f = job->files[fi];
        if (f.isDir) continue;
        for (uint32_t c = 0; c < f.chunkCount; ++c) {
            if (f.HasChunk(c)) continue;
            ctx->pending.push_back(ChunkRef{fi, c});
        }
    }
    {
        // 已完成的块计入进度
        uint64_t already = 0;
        for (const auto& f : job->files) {
            already += (uint64_t)f.doneChunks * proto::kChunkBytes;
        }
        if (already >= job->totalBytes) {
            // 极端情况：全部已存在
        }
    }
    job->state = JobState::Transferring;
    job->startTick = util::NowMs();
    if (g_app.mainWnd) PostMessageW(g_app.mainWnd, WM_APP_WAKE, 0, 0);

    if (ctx->pending.empty()) {
        // 无需数据连接，直接 done
    } else {
        const int conns = std::max(1, std::min((int)proto::kMaxDataConns, g_app.cfg.dataConns));
        int n = conns;
        for (int i = 0; i < n; ++i) {
            SOCKET ds = INVALID_SOCKET;
            if (!ConnectWithTimeout(dev.ip, dev.port, 5000, &ds)) {
                ctx->aborted = true;
                break;
            }
            js::Writer w;
            w.Obj();
            w.Key("t").S("hello");
            w.Key("kind").S("data");
            w.Key("job").S(ctx->token);
            w.Key("stream").I(i);
            w.End();
            if (!prot::SendLine(ds, w) || !WaitDataOk(ds)) {
                closesocket(ds);
                ctx->aborted = true;
                break;
            }
            ctx->threads.emplace_back(DataWorker, ctx, ds, (uint16_t)i);
        }
        for (auto& t : ctx->threads) {
            if (t.joinable()) t.join();
        }
    }

    if (job->cancel.load() || ctx->aborted) {
        job->state = job->cancel.load() ? JobState::Canceled : JobState::Failed;
        if (!job->cancel.load()) job->errMsg = L"传输中断（对方可能已关闭）";
        job->endTick = util::NowMs();
        js::Writer w;
        w.Obj().Key("t").S("cancel").Key("job").S(ctx->token).End();
        prot::SendLine(ctx->ctrl, w);
        closesocket(ctx->ctrl);
        if (g_app.mainWnd) PostMessageW(g_app.mainWnd, WM_APP_WAKE, 0, 0);
        g_app.activeWorkers.fetch_sub(1);
        return;
    }

    // done
    {
        js::Writer w;
        w.Obj().Key("t").S("done").Key("job").S(ctx->token).Key("bytes").U(job->doneBytes.load()).End();
        prot::SendLine(ctx->ctrl, w);
    }
    DWORD tmo = 60000;
    setsockopt(ctx->ctrl, SOL_SOCKET, SO_RCVTIMEO, (const char*)&tmo, sizeof(tmo));
    rd.Reset();
    bool ok = false;
    if (ReadLine(ctx->ctrl, &rd, &line)) {
        js::Value v;
        if (js::Parse(line, &v)) {
            const std::string t = v.GetStr("t");
            ok = (t == "done_ok");
            if (!ok) job->errMsg = L"对方校验失败";
        }
    }
    job->state = ok ? JobState::Done : JobState::Failed;
    job->endTick = util::NowMs();
    job->bps = 0;
    if (ok) {
        cfg::HistRow hr;
        hr.title = job->title;
        hr.savePath = job->dev.name;
        hr.device = job->dev.name;
        hr.timeText = util::NowTimeText();
        hr.bytes = job->totalBytes;
        hr.dir = 1;
        hr.state = 3;
        cfg::HistoryAppend(hr);
    }
    closesocket(ctx->ctrl);
    if (g_app.mainWnd) PostMessageW(g_app.mainWnd, WM_APP_WAKE, 0, 0);
    g_app.activeWorkers.fetch_sub(1);
}

}  // namespace

bool StartSend(const std::vector<std::wstring>& paths, const SendOptions& opt,
               const std::wstring& titleHint) {
    if (paths.empty()) return false;

    auto entries = Enumerate(paths, g_app.cfg.skipHiddenSystem);
    if (entries.empty()) {
        logx::Line("send: nothing to send");
        return false;
    }

    auto job = jobs::Create(JobDir::Send);
    job->files = std::move(entries);
    uint64_t total = 0;
    uint32_t chunks = 0, files = 0;
    for (const auto& f : job->files) {
        total += f.size;
        chunks += f.chunkCount;
        if (!f.isDir) ++files;
    }
    job->totalBytes = total;
    job->totalChunks = chunks;
    job->totalFiles = (uint32_t)job->files.size();
    job->dev.ip = opt.ip;
    job->dev.port = opt.port;
    job->dev.name = titleHint.empty() ? netu::IpToText(opt.ip) : titleHint;
    {
        std::vector<Device> found;
        disc::Snapshot(&found);
        for (const auto& d : found) {
            if (d.ip == opt.ip) { job->dev = d; break; }
        }
        if (job->dev.id.empty()) job->dev.id = "win-local";
    }
    if (job->files.size() == 1) {
        job->title = util::FileNameOf(job->files[0].rel);
    } else {
        const size_t slash = job->files[0].rel.find(L'\\');
        if (slash != std::wstring::npos) job->title = job->files[0].rel.substr(0, slash);
        else job->title = std::to_wstring(files) + L" 个文件";
    }
    job->state = JobState::Handshaking;
    job->jobKey = storage::MakeJobKey(util::Narrow(g_app.cfg.deviceId), job->files,
                                      proto::kChunkBytes);
    jobs::Add(job);

    auto ctx = std::make_shared<SendCtx>();
    ctx->job = job;
    std::thread(SessionThread, ctx).detach();
    return true;
}

}  // namespace client
