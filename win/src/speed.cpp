#include "app.h"
#include "json.h"
#include "protocol.h"

#include <algorithm>
#include <cmath>
#include <map>
#include <memory>
#include <mutex>
#include <thread>

namespace speed {

namespace {

constexpr int kBufBytes = 1024 * 1024;

struct SSession {
    std::string token;
    int conns = 4;
    int dir = 0;  // 0 up 1 down 2 both
    uint64_t deadline = 0;
    std::atomic<bool> stop{false};
};

std::mutex g_mu;
std::map<std::string, std::shared_ptr<SSession>> g_sessions;

std::atomic<bool> g_running{false};
std::thread g_thread;
std::mutex g_resMu;
Result g_last;
std::mutex g_sampMu;
std::vector<double> g_samples;
HWND g_notify = nullptr;
Params g_params;

void PushSample(double mbps) {
    std::lock_guard<std::mutex> lk(g_sampMu);
    g_samples.push_back(mbps);
    if (g_samples.size() > 300) g_samples.erase(g_samples.begin());
}

void ApplySockOpts(SOCKET s) {
    int buf = 4 * 1024 * 1024;
    setsockopt(s, SOL_SOCKET, SO_SNDBUF, (const char*)&buf, sizeof(buf));
    setsockopt(s, SOL_SOCKET, SO_RCVBUF, (const char*)&buf, sizeof(buf));
    netu::SetNoDelay(s);
    DWORD tmo = 200;
    setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, (const char*)&tmo, sizeof(tmo));
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, (const char*)&tmo, sizeof(tmo));
}

bool ConnectTo(uint32_t ip, uint16_t port, SOCKET* out) {
    SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (s == INVALID_SOCKET) return false;
    ApplySockOpts(s);
    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_port = htons(port);
    a.sin_addr.s_addr = htonl(ip);
    netu::SetNonBlocking(s, true);
    int rc = connect(s, (sockaddr*)&a, sizeof(a));
    if (rc != 0) {
        int e = WSAGetLastError();
        if (e != WSAEWOULDBLOCK && e != WSAEINPROGRESS) { closesocket(s); return false; }
        fd_set wr, ex;
        FD_ZERO(&wr);
        FD_ZERO(&ex);
        FD_SET(s, &wr);
        FD_SET(s, &ex);
        timeval tv{5, 0};
        int n = select(0, nullptr, &wr, &ex, &tv);
        if (n <= 0 || FD_ISSET(s, &ex)) { closesocket(s); return false; }
    }
    netu::SetNonBlocking(s, false);
    ApplySockOpts(s);
    *out = s;
    return true;
}

bool ReadLineT(SOCKET s, prot::LineReader* rd, std::string* line) {
    char buf[4096];
    for (;;) {
        int got = recv(s, buf, sizeof(buf), 0);
        if (got <= 0) {
            if (got == SOCKET_ERROR && WSAGetLastError() == WSAETIMEDOUT) continue;
            return false;
        }
        rd->Feed(buf, (size_t)got);
        if (rd->Next(line)) return true;
        if (rd->Overflowed()) return false;
    }
}

bool HandshakeData(SOCKET s, const std::string& token, int stream) {
    js::Writer w;
    w.Obj();
    w.Key("t").S("hello");
    w.Key("kind").S("data");
    w.Key("job").S("speed:" + token);
    w.Key("stream").I(stream);
    w.End();
    if (!prot::SendLine(s, w)) return false;
    prot::LineReader rd;
    std::string line;
    if (!ReadLineT(s, &rd, &line)) return false;
    js::Value v;
    return js::Parse(line, &v) && v.GetStr("t") == "data_ok";
}

// ---- 客户端 worker --------------------------------------------------------
void UpWorker(SOCKET s, std::shared_ptr<std::atomic<uint64_t>> total,
              std::shared_ptr<std::atomic<bool>> stop) {
    g_app.activeWorkers.fetch_add(1);
    std::vector<char> buf(kBufBytes, 0x5A);
    while (!stop->load()) {
        int n = ::send(s, buf.data(), (int)buf.size(), 0);
        if (n > 0) {
            total->fetch_add((uint64_t)n);
            continue;
        }
        if (n == SOCKET_ERROR) {
            const int e = WSAGetLastError();
            if (e == WSAETIMEDOUT || e == WSAEWOULDBLOCK || e == WSAENOBUFS) continue;
            break;
        }
        break;
    }
    closesocket(s);
    g_app.activeWorkers.fetch_sub(1);
}

void DownWorker(SOCKET s, std::shared_ptr<std::atomic<uint64_t>> total,
                std::shared_ptr<std::atomic<bool>> stop) {
    g_app.activeWorkers.fetch_add(1);
    std::vector<char> buf(256 * 1024);
    while (!stop->load()) {
        int n = ::recv(s, buf.data(), (int)buf.size(), 0);
        if (n > 0) {
            total->fetch_add((uint64_t)n);
            continue;
        }
        if (n == SOCKET_ERROR) {
            const int e = WSAGetLastError();
            if (e == WSAETIMEDOUT || e == WSAEWOULDBLOCK) continue;
            break;
        }
        break;
    }
    closesocket(s);
    g_app.activeWorkers.fetch_sub(1);
}

void ClientRun(Params p) {
    auto total = std::make_shared<std::atomic<uint64_t>>(0);
    auto stop = std::make_shared<std::atomic<bool>>(false);
    Result res;
    res.secs = p.secs;
    SOCKET ctrl = INVALID_SOCKET;
    std::vector<std::thread> workers;
    std::string token;

    auto fail = [&](const wchar_t* msg) {
        res.ok = false;
        res.err = msg;
        std::lock_guard<std::mutex> lk(g_resMu);
        g_last = res;
        g_running = false;
        if (ctrl != INVALID_SOCKET) closesocket(ctrl);
        logx::Line("speed: failed %s", util::Narrow(msg).c_str());
        if (g_notify) PostMessageW(g_notify, WM_APP_WAKE, 0, 0);
    };

    if (!ConnectTo(p.ip, p.port, &ctrl)) { fail(L"连不上对方"); return; }

    prot::LineReader rd;
    std::string line;
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
        w.Key("caps").Arr().S("speed").End();
        w.End();
        if (!prot::SendLine(ctrl, w)) { fail(L"握手失败"); return; }
    }
    if (!ReadLineT(ctrl, &rd, &line)) { fail(L"对方未响应"); return; }

    {
        char tok[16];
        snprintf(tok, sizeof(tok), "%08x", util::RandomU32());
        token = tok;
        js::Writer w;
        w.Obj();
        w.Key("t").S("speed_req");
        w.Key("token").S(token);
        w.Key("dir").I(p.dirMode);
        w.Key("conns").I(p.conns);
        w.Key("secs").I(p.secs);
        w.End();
        if (!prot::SendLine(ctrl, w)) { fail(L"发送测速请求失败"); return; }
    }
    rd.Reset();
    if (!ReadLineT(ctrl, &rd, &line)) { fail(L"对方拒绝了测速"); return; }
    {
        js::Value v;
        if (!js::Parse(line, &v) || v.GetStr("t") != "speed_ok") {
            fail(v.GetStr("t") == "busy" ? L"对方正在传文件，稍后再试" : L"对方拒绝了测速");
            return;
        }
    }

    // 建数据连接
    struct Plan { int stream; bool up; };
    std::vector<Plan> plan;
    if (p.dirMode == 0 || p.dirMode == 2) {
        for (int i = 0; i < p.conns; ++i) plan.push_back({i, true});
    }
    if (p.dirMode == 1 || p.dirMode == 2) {
        for (int i = 0; i < p.conns; ++i) plan.push_back({p.conns + i, false});
    }
    for (const auto& pl : plan) {
        SOCKET ds = INVALID_SOCKET;
        if (!ConnectTo(p.ip, p.port, &ds)) { stop->store(true); break; }
        if (!HandshakeData(ds, token, pl.stream)) { closesocket(ds); stop->store(true); break; }
        if (pl.up) workers.emplace_back(UpWorker, ds, total, stop);
        else workers.emplace_back(DownWorker, ds, total, stop);
    }

    const uint64_t t0 = util::NowMs();
    uint64_t last = 0;
    uint64_t lastT = t0;
    {
        std::lock_guard<std::mutex> lk(g_sampMu);
        g_samples.clear();
    }
    while (util::NowMs() - t0 < (uint64_t)p.secs * 1000ull) {
        Sleep(200);
        const uint64_t cur = total->load();
        const uint64_t now = util::NowMs();
        const double dt = (double)(now - lastT) / 1000.0;
        if (dt > 0.05) {
            const double mbps = (double)(cur - last) * 8.0 / dt / 1e6;
            PushSample(mbps);
        }
        last = cur;
        lastT = now;
        if (g_notify) PostMessageW(g_notify, WM_APP_WAKE, 0, 0);
    }
    stop->store(true);
    for (auto& t : workers) {
        if (t.joinable()) t.join();
    }
    workers.clear();

    const double elapsed = (double)(util::NowMs() - t0) / 1000.0;
    const uint64_t bytes = total->load();
    res.ok = bytes > 0;
    res.bytesUp = (p.dirMode == 0 || p.dirMode == 2) ? bytes : 0;
    res.bytesDown = (p.dirMode == 1 || p.dirMode == 2) ? bytes : 0;
    res.avgMbps = elapsed > 0.05 ? (double)bytes * 8.0 / elapsed / 1e6 : 0;
    {
        std::lock_guard<std::mutex> lk(g_sampMu);
        res.conns = p.conns;
        // 去掉首尾各 1 个采样（爬坡与收尾）
        std::vector<double> s = g_samples;
        if (s.size() > 6) s = std::vector<double>(s.begin() + 1, s.end() - 2);
        if (!s.empty()) {
            res.peakMbps = *std::max_element(s.begin(), s.end());
            std::vector<double> q = s;
            std::sort(q.begin(), q.end());
            res.p50Mbps = q[q.size() / 2];
        }
    }
    if (!res.ok) res.err = L"没有收到数据，检查网络";
    {
        std::lock_guard<std::mutex> lk(g_resMu);
        g_last = res;
    }
    closesocket(ctrl);
    g_running = false;
    logx::Line("speed: avg=%.1f peak=%.1f p50=%.1f mbps bytes=%llu", res.avgMbps, res.peakMbps,
               res.p50Mbps, (unsigned long long)bytes);
    if (g_notify) PostMessageW(g_notify, WM_APP_WAKE, 0, 0);
}

// ---- 服务端 --------------------------------------------------------------
void ServerDrain(SOCKET s, std::shared_ptr<SSession> sess) {
    g_app.activeWorkers.fetch_add(1);
    std::vector<char> buf(256 * 1024);
    while (!sess->stop.load()) {
        int n = ::recv(s, buf.data(), (int)buf.size(), 0);
        if (n > 0) continue;
        if (n == SOCKET_ERROR) {
            int e = WSAGetLastError();
            if (e == WSAETIMEDOUT || e == WSAEWOULDBLOCK) continue;
            break;
        }
        break;
    }
    closesocket(s);
    g_app.activeWorkers.fetch_sub(1);
}

void ServerBlast(SOCKET s, std::shared_ptr<SSession> sess) {
    g_app.activeWorkers.fetch_add(1);
    std::vector<char> buf(kBufBytes, 0xA5);
    while (!sess->stop.load()) {
        int n = ::send(s, buf.data(), (int)buf.size(), 0);
        if (n > 0) continue;
        if (n == SOCKET_ERROR) {
            int e = WSAGetLastError();
            if (e == WSAETIMEDOUT || e == WSAEWOULDBLOCK || e == WSAENOBUFS) continue;
            break;
        }
        break;
    }
    closesocket(s);
    g_app.activeWorkers.fetch_sub(1);
}

}  // namespace

void HandleRequest(SOCKET ctrl, const js::Value& req, const Device& dev) {
    (void)dev;
    if (g_running.load()) {
        js::Writer w;
        w.Obj().Key("t").S("busy").End();
        prot::SendLine(ctrl, w);
        return;
    }
    auto sess = std::make_shared<SSession>();
    sess->token = req.GetStr("token");
    sess->conns = (int)req.GetInt("conns", 4);
    sess->dir = (int)req.GetInt("dir", 2);
    const int secs = (int)req.GetInt("secs", 10);
    if (sess->token.empty()) sess->token = "anon";
    sess->deadline = util::NowMs() + (uint64_t)std::max(1, std::min(60, secs)) * 1000ull;

    {
        std::lock_guard<std::mutex> lk(g_mu);
        g_sessions[sess->token] = sess;
    }
    {
        js::Writer w;
        w.Obj().Key("t").S("speed_ok").Key("token").S(sess->token).End();
        if (!prot::SendLine(ctrl, w)) {
            std::lock_guard<std::mutex> lk(g_mu);
            g_sessions.erase(sess->token);
            return;
        }
    }
    logx::Line("speed: serving token=%s dir=%d conns=%d secs=%d", sess->token.c_str(), sess->dir,
               sess->conns, secs);

    while (util::NowMs() < sess->deadline && g_app.stop.load() == false) {
        Sleep(100);
    }
    sess->stop = true;
    Sleep(300);
    {
        js::Writer w;
        w.Obj().Key("t").S("speed_done").End();
        prot::SendLine(ctrl, w);
    }
    {
        std::lock_guard<std::mutex> lk(g_mu);
        g_sessions.erase(sess->token);
    }
}

void AttachData(SOCKET s, const std::string& token, int stream) {
    std::shared_ptr<SSession> sess;
    {
        std::lock_guard<std::mutex> lk(g_mu);
        auto it = g_sessions.find(token);
        if (it != g_sessions.end()) sess = it->second;
    }
    if (!sess) { closesocket(s); return; }
    char buf[512];
    snprintf(buf, sizeof(buf), "%s", token.c_str());
    (void)buf;
    // stream < conns → 上传（服务端收）；否则 → 下载（服务端发）
    if (stream < sess->conns) {
        std::thread(ServerDrain, s, sess).detach();
    } else {
        std::thread(ServerBlast, s, sess).detach();
    }
}

bool Start(const Params& p, const std::wstring& peerName) {
    if (g_running.load()) return false;
    g_running = true;
    g_params = p;
    {
        std::lock_guard<std::mutex> lk(g_sampMu);
        g_samples.clear();
    }
    {
        std::lock_guard<std::mutex> lk(g_resMu);
        g_last = Result();
        g_last.err.clear();
    }
    logx::Line("speed: start to %s dir=%d conns=%d secs=%d",
               util::Narrow(peerName).c_str(), p.dirMode, p.conns, p.secs);
    if (g_thread.joinable()) g_thread.join();
    g_thread = std::thread(ClientRun, p);
    return true;
}

Result RunBlocking(const Params& p) {
    g_running = true;
    {
        std::lock_guard<std::mutex> lk(g_sampMu);
        g_samples.clear();
    }
    ClientRun(p);
    std::lock_guard<std::mutex> lk(g_resMu);
    return g_last;
}

void Stop() {
    // 客户端线程靠 secs 自然结束；这里只标记，服务端会话会被 deadline 收掉
    if (g_thread.joinable()) g_thread.join();
    g_running = false;
}

bool IsRunning() { return g_running.load(); }

void SetNotifyWindow(HWND h) { g_notify = h; }

std::vector<double> SamplesMbps() {
    std::lock_guard<std::mutex> lk(g_sampMu);
    return g_samples;
}

Result LastResult() {
    std::lock_guard<std::mutex> lk(g_resMu);
    return g_last;
}

}  // namespace speed
