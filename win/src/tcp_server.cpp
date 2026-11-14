#include "app.h"
#include "json.h"
#include "protocol.h"

#include <algorithm>
#include <map>
#include <memory>
#include <mutex>
#include <thread>

namespace srv {

namespace {

struct RecvSession {
    std::shared_ptr<Job> job;
    storage::Layout lay;
    std::mutex mu;                       // 保护 handles 与写盘
    std::map<uint32_t, HANDLE> handles;  // fileIndex -> 已打开的 .part
    std::atomic<uint32_t> received{0};
    std::atomic<bool> complete{false};
    std::atomic<bool> failed{false};
    std::atomic<bool> abort{false};
    SOCKET ctrl = INVALID_SOCKET;
};

std::mutex g_mu;
std::map<std::string, std::shared_ptr<RecvSession>> g_sessions;
SOCKET g_listen = INVALID_SOCKET;
std::thread g_acceptThread;
std::atomic<bool> g_run{false};
HWND g_notify = nullptr;

std::string JobToken(const std::shared_ptr<Job>& j) { return util::Narrow(j->jobKey); }

void Notify() {
    if (g_notify) PostMessageW(g_notify, WM_APP_WAKE, 0, 0);
}

void CloseHandles(RecvSession& s) {
    std::lock_guard<std::mutex> lk(s.mu);
    for (auto& kv : s.handles) {
        if (kv.second != INVALID_HANDLE_VALUE) {
            FlushFileBuffers(kv.second);
            CloseHandle(kv.second);
        }
    }
    s.handles.clear();
}

HANDLE HandleFor(RecvSession& s, uint32_t idx) {
    auto it = s.handles.find(idx);
    if (it != s.handles.end()) return it->second;
    if (idx >= s.job->files.size()) return INVALID_HANDLE_VALUE;
    const FileEntry& f = s.job->files[idx];
    if (f.isDir) return INVALID_HANDLE_VALUE;
    std::wstring part = storage::PartPathFor(s.lay, f.rel);
    storage::EnsureParent(part);
    HANDLE h = CreateFileW(util::AddLong(part).c_str(), GENERIC_WRITE | GENERIC_READ,
                           FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_ALWAYS,
                           FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) {
        logx::Line("recv: open part failed %lu %s", GetLastError(),
                   util::Narrow(part).c_str());
        return INVALID_HANDLE_VALUE;
    }
    s.handles[idx] = h;
    return h;
}

bool WriteChunk(RecvSession& s, uint32_t fileIdx, uint32_t chunkIdx, const char* data,
                uint32_t len) {
    if (fileIdx >= s.job->files.size()) return false;
    FileEntry& f = s.job->files[fileIdx];
    if (f.isDir) return true;
    if (chunkIdx >= f.chunkCount) return false;
    // 已收到过的块直接跳过（重复包）
    if (f.HasChunk(chunkIdx)) return true;

    std::lock_guard<std::mutex> lk(s.mu);
    HANDLE h = HandleFor(s, fileIdx);
    if (h == INVALID_HANDLE_VALUE) return false;
    LARGE_INTEGER off;
    off.QuadPart = (LONGLONG)chunkIdx * (LONGLONG)proto::kChunkBytes;
    if (!SetFilePointerEx(h, off, nullptr, FILE_BEGIN)) return false;
    DWORD written = 0;
    if (!WriteFile(h, data, len, &written, nullptr) || written != len) {
        logx::Line("recv: write failed %lu", GetLastError());
        return false;
    }
    f.MarkChunk(chunkIdx);
    s.job->doneBytes.fetch_add(len);
    s.received.fetch_add(1);
    return true;
}

void SendAck(SOCKET s, uint32_t taskId, uint16_t seq, uint32_t fileIdx, uint32_t chunkIdx,
             bool nak) {
    proto::DataHdr h{};
    h.magic = proto::kMagicAck;
    h.taskId = taskId;
    h.streamSeq = seq;
    h.flags = nak ? proto::F_NAK : 0;
    h.fileIndex = fileIdx;
    h.chunkIndex = chunkIdx;
    h.payloadLen = 0;
    h.payloadCrc32 = 0;
    uint8_t buf[28];
    proto::EncodeHdr(buf, h);
    prot::SendAll(s, buf, sizeof(buf));
}

// 数据连接线程
void DataThread(std::shared_ptr<RecvSession> sess, SOCKET s, uint16_t seq) {
    g_app.activeWorkers.fetch_add(1);
    std::vector<char> payload(proto::kChunkBytes);
    uint8_t hdr[28];
    uint64_t lastRx = util::NowMs();
    while (g_run && !sess->abort) {
        if (sess->job->cancel.load()) break;
        int got = recv(s, (char*)hdr, 28, MSG_WAITALL);
        if (got != 28) {
            if (got == SOCKET_ERROR) {
                int e = WSAGetLastError();
                if (e == WSAETIMEDOUT) {
                    if (util::NowMs() - lastRx > 30000) break;
                    continue;
                }
            }
            break;
        }
        proto::DataHdr h{};
        if (!proto::DecodeHdr(hdr, &h) || h.magic != proto::kMagicData) break;
        if (h.payloadLen > proto::kChunkBytes) break;
        lastRx = util::NowMs();
        if (h.payloadLen > 0 && !prot::RecvExact(s, payload.data(), h.payloadLen)) break;
        const uint32_t crc = proto::Crc32(payload.data(), h.payloadLen);
        const bool okCrc = (h.payloadLen == 0) || (crc == h.payloadCrc32);
        bool okWrite = okCrc && WriteChunk(*sess, h.fileIndex, h.chunkIndex, payload.data(),
                                           h.payloadLen);
        SendAck(s, h.taskId, seq, h.fileIndex, h.chunkIndex, !okWrite);
        if (!okWrite) sess->failed = true;
        if (sess->received.load() >= sess->job->totalChunks) {
            sess->complete = true;
            break;
        }
        if (sess->failed) break;
    }
    closesocket(s);
    g_app.activeWorkers.fetch_sub(1);
}

bool CommitAll(const std::shared_ptr<Job>& j, const storage::Layout& lay, std::wstring* outPath) {
    const std::wstring saveDir = j->saveDir.empty() ? g_app.cfg.saveDir : j->saveDir;
    util::EnsureDir(saveDir);

    // 判断是否带目录结构：有任一 rel 含 '\\'
    bool structured = false;
    std::wstring topPrefix;
    for (const auto& f : j->files) {
        size_t pos = f.rel.find(L'\\');
        if (pos != std::wstring::npos) {
            structured = true;
            topPrefix = f.rel.substr(0, pos);
            break;
        }
    }

    std::wstring finalTop;
    if (structured) {
        finalTop = util::UniquePath(saveDir, topPrefix, L"");
        if (!util::EnsureDir(finalTop)) return false;
    }

    std::wstring firstOut;
    for (auto& f : j->files) {
        if (f.isDir) {
            std::wstring dst = structured ? util::JoinPath(finalTop, f.rel.substr(topPrefix.size() + 1))
                                          : util::JoinPath(saveDir, f.rel);
            util::EnsureDir(dst);
            continue;
        }
        std::wstring dst;
        if (structured) {
            std::wstring rest = f.rel.substr(topPrefix.size() + 1);
            dst = util::JoinPath(finalTop, rest);
        } else {
            dst = util::UniquePath(saveDir, util::BaseOf(f.rel), util::ExtOf(f.rel));
        }
        std::wstring part = storage::PartPathFor(lay, f.rel);
        if (!storage::CommitFile(part, dst)) return false;
        // 还原修改时间
        if (f.mtime) {
            HANDLE h = CreateFileW(util::AddLong(dst).c_str(), FILE_WRITE_ATTRIBUTES, 0, nullptr,
                                   OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
            if (h != INVALID_HANDLE_VALUE) {
                FILETIME ft;
                ft.dwLowDateTime = (DWORD)(f.mtime & 0xFFFFFFFFull);
                ft.dwHighDateTime = (DWORD)(f.mtime >> 32);
                SetFileTime(h, nullptr, nullptr, &ft);
                CloseHandle(h);
            }
        }
        if (firstOut.empty()) firstOut = dst;
    }
    if (outPath) *outPath = firstOut.empty() ? saveDir : firstOut;
    return true;
}

void FailJob(const std::shared_ptr<Job>& j, const std::wstring& msg, JobState st = JobState::Failed) {
    j->state = st;
    j->errMsg = msg;
    j->endTick = util::NowMs();
    logx::Line("job %u %s: %s", j->id, st == JobState::Failed ? "failed" : "done",
               util::Narrow(msg).c_str());
    Notify();
}

// 控制连接线程（hello 已在 AcceptLoop 中解析）
void ControlThread(SOCKET ctrl, js::Value hello) {
    g_app.activeWorkers.fetch_add(1);
    prot::LineReader reader;
    std::string line;
    char buf[8192];
    std::shared_ptr<RecvSession> sess;
    auto cleanup = [&]() {
        if (sess) {
            CloseHandles(*sess);
            std::lock_guard<std::mutex> lk(g_mu);
            g_sessions.erase(JobToken(sess->job));
        }
        if (ctrl != INVALID_SOCKET) closesocket(ctrl);
        g_app.activeWorkers.fetch_sub(1);
    };

    Device dev;
    dev.id = hello.GetStr("id");
    dev.name = hello.GetWStr("name", L"设备");
    dev.plat = hello.GetStr("plat", "android");
    dev.ver = hello.GetStr("ver", "");

    {
        js::Writer w;
        w.Obj();
        w.Key("t").S("hello_ok");
        w.Key("v").I(proto::kProtoVersion);
        w.Key("name").WS(g_app.cfg.deviceName);
        w.Key("chunkBytes").I((long long)proto::kChunkBytes);
        w.Key("caps").Arr().S("file").S("folder").S("resume").S("speed").End();
        w.End();
        if (!prot::SendLine(ctrl, w)) { cleanup(); return; }
    }

    // 2) 下一帧：offer 或 speed_req
    reader.Reset();
    for (;;) {
        int got = recv(ctrl, buf, sizeof(buf), 0);
        if (got <= 0) { cleanup(); return; }
        reader.Feed(buf, (size_t)got);
        if (reader.Next(&line)) break;
        if (reader.Overflowed()) { cleanup(); return; }
    }
    js::Value req;
    if (!js::Parse(line, &req)) { cleanup(); return; }
    const std::string type = req.GetStr("t");
    if (type == "speed_req") {
        speed::HandleRequest(ctrl, req, dev);
        cleanup();
        return;
    }
    if (type != "offer") { cleanup(); return; }

    // 3) 组装任务
    auto job = jobs::Create(JobDir::Recv);
    job->dev = dev;
    job->trustedPeer = cfg::IsTrusted(dev.id);
    job->saveDir = g_app.cfg.saveDir;
    const js::Value* files = req.GetArr("files");
    if (!files || files->arr.empty()) {
        FailJob(job, L"清单为空");
        cleanup();
        return;
    }
    uint64_t totalBytes = 0;
    uint32_t totalChunks = 0;
    uint32_t idx = 0;
    bool badPath = false;
    for (const auto& fv : files->arr) {
        FileEntry f;
        f.rel = fv.GetWStr("rel");
        f.size = (uint64_t)fv.GetInt("size", 0);
        f.mtime = (uint64_t)fv.GetInt("mtime", 0);
        f.isDir = fv.GetBool("isDir", false);
        if (f.isDir && f.rel.empty()) continue;
        // 逐段清洗，拒绝穿越
        if (!util::IsSafeRelPath(f.rel)) { badPath = true; break; }
        std::wstring clean;
        size_t start = 0;
        while (start <= f.rel.size()) {
            size_t pos = f.rel.find(L'\\', start);
            std::wstring seg = (pos == std::wstring::npos) ? f.rel.substr(start)
                                                           : f.rel.substr(start, pos - start);
            if (!clean.empty()) clean += L"\\";
            clean += util::SanitizeComponent(seg);
            if (pos == std::wstring::npos) break;
            start = pos + 1;
        }
        f.rel = clean;
        f.chunkCount = f.isDir ? 0 : (uint32_t)((f.size + proto::kChunkBytes - 1) / proto::kChunkBytes);
        f.InitBits();
        if (!f.isDir) {
            totalBytes += f.size;
            totalChunks += f.chunkCount;
        }
        job->files.push_back(std::move(f));
        ++idx;
    }
    if (badPath || job->files.empty()) {
        js::Writer w;
        w.Obj().Key("t").S("reject").Key("reason").S("unsafe_path").End();
        prot::SendLine(ctrl, w);
        job->state = JobState::Failed;
        cleanup();
        return;
    }
    job->totalBytes = totalBytes;
    job->totalChunks = totalChunks;
    job->totalFiles = (uint32_t)job->files.size();
    if (job->files.size() == 1) {
        job->title = util::FileNameOf(job->files[0].rel);
    } else {
        const size_t slash = job->files[0].rel.find(L'\\');
        if (slash != std::wstring::npos) {
            job->title = job->files[0].rel.substr(0, slash);
        } else {
            job->title = std::to_wstring(job->files.size()) + L" 个文件";
        }
    }
    job->jobKey = storage::MakeJobKey(dev.id, job->files, proto::kChunkBytes);
    job->id = proto::TaskIdFromKey(util::Narrow(job->jobKey));

    // 磁盘空间预检
    ULARGE_INTEGER freeBytes{};
    if (GetDiskFreeSpaceExW(util::AddLong(g_app.cfg.saveDir).c_str(), nullptr, nullptr, &freeBytes)) {
        const uint64_t need = totalBytes + totalBytes / 20 + 200ull * 1024 * 1024;
        if (freeBytes.QuadPart < need) {
            js::Writer w;
            w.Obj().Key("t").S("reject").Key("reason").S("no_space").End();
            prot::SendLine(ctrl, w);
            FailJob(job, L"磁盘空间不足");
            cleanup();
            return;
        }
    }

    job->state = JobState::WaitingAccept;
    jobs::Add(job);
    Notify();

    // 等待用户确认（或信任设备自动通过）
    const bool autoOk = g_app.headless || (job->trustedPeer && g_app.cfg.autoAcceptTrusted);
    const uint64_t t0 = util::NowMs();
    while (!autoOk) {
        if (job->cancel.load()) break;
        if (job->acceptFlag.load()) break;
        if (util::NowMs() - t0 > 60000) break;
        Sleep(50);
    }
    if (job->cancel.load() || (!autoOk && !job->acceptFlag.load())) {
        js::Writer w;
        w.Obj().Key("t").S("reject").Key("reason").S("declined").End();
        prot::SendLine(ctrl, w);
        FailJob(job, L"已拒绝", JobState::Canceled);
        cleanup();
        return;
    }
    if (!job->trustedPeer && job->acceptFlag.load()) {
        cfg::TrustDevice(dev.id);
        job->trustedPeer = true;
    }

    // 4) 建临时目录、尝试续传
    sess = std::make_shared<RecvSession>();
    sess->job = job;
    sess->ctrl = ctrl;
    if (!storage::Prepare(job, &sess->lay)) {
        js::Writer w;
        w.Obj().Key("t").S("reject").Key("reason").S("io").End();
        prot::SendLine(ctrl, w);
        FailJob(job, L"无法创建临时目录");
        cleanup();
        return;
    }
    // 0 字节文件不会有任何数据块，先建出空的 .part，提交阶段才能改名
    for (auto& f : job->files) {
        if (f.isDir || f.size != 0) continue;
        std::wstring part = storage::PartPathFor(sess->lay, f.rel);
        storage::EnsureParent(part);
        HANDLE h = CreateFileW(util::AddLong(part).c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                               FILE_ATTRIBUTE_NORMAL, nullptr);
        if (h != INVALID_HANDLE_VALUE) CloseHandle(h);
    }

    if (storage::LoadMeta(job, sess->lay)) {
        job->resumeAttempted = true;
        uint32_t already = 0;
        for (const auto& f : job->files) already += f.doneChunks;
        sess->received = already;
        job->doneBytes = 0;
        for (const auto& f : job->files) {
            job->doneBytes.fetch_add((uint64_t)f.doneChunks * proto::kChunkBytes);
        }
        logx::Line("recv: resume job %s with %u chunks", util::Narrow(job->jobKey).c_str(), already);
    }

    {
        std::lock_guard<std::mutex> lk(g_mu);
        g_sessions[JobToken(job)] = sess;
    }

    // 5) 回 accept + 已有位图
    {
        js::Writer w;
        w.Obj();
        w.Key("t").S("accept");
        w.Key("job").S(JobToken(job));
        w.Key("chunkBytes").I((long long)proto::kChunkBytes);
        w.Key("have").Arr();
        for (size_t i = 0; i < job->files.size(); ++i) {
            const FileEntry& f = job->files[i];
            if (f.isDir || f.doneChunks == 0) continue;
            w.Obj().Key("i").I((long long)i).Key("bits").Arr();
            for (uint32_t word : f.doneBits) w.I((long long)word);
            w.End().End();
        }
        w.End();
        w.End();
        if (!prot::SendLine(ctrl, w)) {
            FailJob(job, L"发送应答失败");
            cleanup();
            return;
        }
    }

    job->state = JobState::Transferring;
    job->startTick = util::NowMs();
    Notify();

    // 6) 等数据（最长 30 分钟无进展）
    uint64_t lastBytes = job->doneBytes.load();
    uint64_t lastProgress = util::NowMs();
    while (g_run) {
        if (job->cancel.load()) break;
        if (sess->complete.load()) break;
        if (sess->failed.load()) break;
        const uint64_t cur = job->doneBytes.load();
        if (cur != lastBytes) { lastBytes = cur; lastProgress = util::NowMs(); }
        if (util::NowMs() - lastProgress > 25000) {
            FailJob(job, L"连接中断，已暂停（可续传）", JobState::Paused);
            storage::SaveMeta(job, sess->lay);
            cleanup();
            return;
        }
        Sleep(100);
    }

    const bool allDone = sess->received.load() >= job->totalChunks;
    if (job->cancel.load()) {
        storage::SaveMeta(job, sess->lay);
        FailJob(job, L"已取消", JobState::Canceled);
        js::Writer w;
        w.Obj().Key("t").S("error").Key("reason").S("canceled").End();
        prot::SendLine(ctrl, w);
        cleanup();
        return;
    }
    if (!allDone || sess->failed.load()) {
        storage::SaveMeta(job, sess->lay);
        FailJob(job, sess->failed ? L"数据校验失败" : L"未收全", JobState::Failed);
        js::Writer w;
        w.Obj().Key("t").S("error").Key("reason").S("incomplete").End();
        prot::SendLine(ctrl, w);
        cleanup();
        return;
    }

    // 7) 落盘提交
    job->state = JobState::Verifying;
    Notify();
    CloseHandles(*sess);
    std::wstring outPath;
    if (!CommitAll(job, sess->lay, &outPath)) {
        FailJob(job, L"保存文件失败");
        js::Writer w;
        w.Obj().Key("t").S("error").Key("reason").S("commit").End();
        prot::SendLine(ctrl, w);
        cleanup();
        return;
    }
    job->savePath = outPath;
    job->state = JobState::Done;
    job->endTick = util::NowMs();
    job->bps = 0;

    {
        js::Writer w;
        w.Obj();
        w.Key("t").S("done_ok");
        w.Key("job").S(JobToken(job));
        w.Key("path").WS(outPath);
        w.End();
        prot::SendLine(ctrl, w);
    }
    storage::RemoveTree(sess->lay.root);

    cfg::HistRow hr;
    hr.title = job->title;
    hr.savePath = outPath;
    hr.device = dev.name;
    hr.timeText = util::NowTimeText();
    hr.bytes = job->totalBytes;
    hr.dir = 0;
    hr.state = 3;
    cfg::HistoryAppend(hr);
    logx::Line("recv: job %u done -> %s", job->id, util::Narrow(outPath).c_str());
    Notify();
    cleanup();
}

void AcceptLoop() {
    while (g_run) {
        sockaddr_in from{};
        int fl = sizeof(from);
        SOCKET s = accept(g_listen, (sockaddr*)&from, &fl);
        if (s == INVALID_SOCKET) {
            if (!g_run) break;
            int e = WSAGetLastError();
            if (e == WSAEINTR || e == WSAENOTSOCK) break;
            Sleep(50);
            continue;
        }
        g_app.sawInbound = true;
        int rcv = 256 * 1024;
        setsockopt(s, SOL_SOCKET, SO_RCVBUF, (const char*)&rcv, sizeof(rcv));
        int snd = 1024 * 1024;
        setsockopt(s, SOL_SOCKET, SO_SNDBUF, (const char*)&snd, sizeof(snd));
        DWORD tmo = 30000;
        setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, (const char*)&tmo, sizeof(tmo));

        // 先读首行判断类型（数据连接带 kind=data）
        prot::LineReader rd;
        std::string line;
        char buf[2048];
        bool gotHello = false;
        for (;;) {
            int got = recv(s, buf, sizeof(buf), 0);
            if (got <= 0) break;
            rd.Feed(buf, (size_t)got);
            if (rd.Next(&line)) { gotHello = true; break; }
            if (rd.Overflowed()) break;
        }
        if (!gotHello) { closesocket(s); continue; }

        js::Value hello;
        if (!js::Parse(line, &hello) || hello.GetStr("t") != "hello") {
            closesocket(s);
            continue;
        }
        const std::string kind = hello.GetStr("kind", "ctrl");
        if (kind == "data") {
            // 先回 data_ok，客户端收到后才开始发帧 —— 从根上避免"握手行与首帧粘包"
            js::Writer ack;
            ack.Obj().Key("t").S("data_ok").End();
            if (!prot::SendLine(s, ack)) { closesocket(s); continue; }

            const std::string token = hello.GetStr("job");
            const int seq = (int)hello.GetInt("stream", 0);
            if (token.rfind("speed:", 0) == 0) {
                speed::AttachData(s, token.substr(6), seq);
            } else {
                std::shared_ptr<RecvSession> sess;
                {
                    std::lock_guard<std::mutex> lk(g_mu);
                    auto it = g_sessions.find(token);
                    if (it != g_sessions.end()) sess = it->second;
                }
                if (sess) {
                    DWORD t = 5000;
                    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, (const char*)&t, sizeof(t));
                    std::thread(DataThread, sess, s, (uint16_t)seq).detach();
                } else {
                    closesocket(s);
                }
            }
        } else {
            std::thread(ControlThread, s, hello).detach();
        }
    }
}

}  // namespace

bool Start(uint16_t port) {
    if (g_run) return true;
    SOCKET ls = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (ls == INVALID_SOCKET) return false;
    BOOL excl = TRUE;
    setsockopt(ls, SOL_SOCKET, SO_EXCLUSIVEADDRUSE, (const char*)&excl, sizeof(excl));
    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_port = htons(port);
    a.sin_addr.s_addr = INADDR_ANY;
    if (bind(ls, (sockaddr*)&a, sizeof(a)) != 0) {
        logx::Line("srv: bind tcp %u failed %d", port, WSAGetLastError());
        closesocket(ls);
        return false;
    }
    if (listen(ls, 32) != 0) {
        closesocket(ls);
        return false;
    }
    g_listen = ls;
    g_run = true;
    g_acceptThread = std::thread(AcceptLoop);
    logx::Line("srv: listening on %u", port);
    return true;
}

void Stop() {
    if (!g_run) return;
    g_run = false;
    if (g_listen != INVALID_SOCKET) {
        closesocket(g_listen);
        g_listen = INVALID_SOCKET;
    }
    if (g_acceptThread.joinable()) g_acceptThread.join();
    {
        std::lock_guard<std::mutex> lk(g_mu);
        for (auto& kv : g_sessions) {
            if (kv.second) kv.second->abort = true;
        }
        g_sessions.clear();
    }
    logx::Line("srv: stopped");
}

void SetNotifyWindow(HWND h) { g_notify = h; }

}  // namespace srv
