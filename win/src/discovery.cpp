#include "app.h"
#include "json.h"

#include <shlwapi.h>

#include <algorithm>
#include <mutex>
#include <thread>
#include <vector>

namespace disc {

namespace {

struct SendSock {
    SOCKET s = INVALID_SOCKET;
    uint32_t bcast = 0;
    uint32_t ip = 0;
};

std::vector<SendSock> g_send;
SOCKET g_recv = INVALID_SOCKET;
std::thread g_thread;
std::atomic<bool> g_run{false};
SRWLOCK g_lock = SRWLOCK_INIT;
std::vector<Device> g_devs;
HWND g_notify = nullptr;
uint64_t g_lastProbe = 0;

std::string SelfId() { return util::Narrow(g_app.cfg.deviceId); }

std::string BuildPacket(const char* type) {
    js::Writer w;
    w.Obj();
    w.Key("t").S(type);
    w.Key("v").I(proto::kProtoVersion);
    w.Key("id").S(SelfId());
    w.Key("name").WS(g_app.cfg.deviceName);
    w.Key("plat").S("win");
    w.Key("port").I(g_app.cfg.listenPort);
    w.Key("ver").S(LANDROP_VERSION_A);
    w.Key("caps").Arr().S("file").S("folder").S("resume").S("speed").End();
    w.End();
    return w.Str();
}

void SendTo(const SendSock& ss, const std::string& pkt, uint32_t dstIp, uint16_t port) {
    if (ss.s == INVALID_SOCKET) return;
    sockaddr_in to{};
    to.sin_family = AF_INET;
    to.sin_port = htons(port);
    to.sin_addr.s_addr = htonl(dstIp);
    sendto(ss.s, pkt.data(), (int)pkt.size(), 0, (sockaddr*)&to, sizeof(to));
}

void SendProbeAll() {
    const std::string pkt = BuildPacket("probe");
    for (const auto& ss : g_send) {
        if (ss.bcast) SendTo(ss, pkt, ss.bcast, (uint16_t)g_app.cfg.discoverPort);
        SendTo(ss, pkt, 0xFFFFFFFFu, (uint16_t)g_app.cfg.discoverPort);
    }
    g_lastProbe = util::NowMs();
}

void SendAnnounceBack(uint32_t ip, uint16_t port) {
    if (ip == 0) return;
    const std::string pkt = BuildPacket("announce");
    // 找一个与目标同网段的 socket 用来回复，保证源地址正确
    for (const auto& ss : g_send) {
        if (((ss.ip ^ ip) & 0xFFFFFF00u) == 0 || ss.bcast == 0) {
            SendTo(ss, pkt, ip, port);
            return;
        }
    }
    if (!g_send.empty()) SendTo(g_send.front(), pkt, ip, port);
}

void Upsert(const js::Value& v, uint32_t srcIp) {
    const std::string id = v.GetStr("id");
    if (id.empty() || id == SelfId()) return;
    if (netu::IsLocalAddress(srcIp)) return;  // 同一台机器的其他网卡副本

    std::wstring name = v.GetWStr("name", L"设备");
    const std::string plat = v.GetStr("plat", "win");
    const std::string ver = v.GetStr("ver", "");
    const int port = (int)v.GetInt("port", proto::kTransferPort);
    const uint64_t now = util::NowMs();

    AcquireSRWLockExclusive(&g_lock);
    bool found = false;
    for (auto& d : g_devs) {
        if (d.id == id) {
            d.name = name;
            d.plat = plat;
            d.ver = ver;
            d.ip = srcIp;
            d.port = (uint16_t)((port > 0 && port < 65536) ? port : proto::kTransferPort);
            d.lastSeen = now;
            found = true;
            break;
        }
    }
    if (!found) {
        if (g_devs.size() >= 32) {
            // 淘汰最久未见的一台
            auto it = std::min_element(g_devs.begin(), g_devs.end(),
                                       [](const Device& a, const Device& b) {
                                           return a.lastSeen < b.lastSeen;
                                       });
            if (it != g_devs.end()) g_devs.erase(it);
        }
        Device d;
        d.id = id;
        d.name = name;
        d.plat = plat;
        d.ver = ver;
        d.ip = srcIp;
        d.port = (uint16_t)((port > 0 && port < 65536) ? port : proto::kTransferPort);
        d.lastSeen = now;
        g_devs.push_back(d);
        logx::Line("discovery: found %s (%s) at %s", id.c_str(), plat.c_str(),
                   util::Narrow(netu::IpToText(srcIp)).c_str());
    }
    ReleaseSRWLockExclusive(&g_lock);

    if (g_notify) PostMessageW(g_notify, WM_APP_WAKE, 0, 0);
}

void Loop() {
    std::vector<char> buf(2048);
    while (g_run) {
        const uint64_t now = util::NowMs();
        if (now - g_lastProbe >= 3000) SendProbeAll();

        if (g_recv != INVALID_SOCKET) {
            fd_set rd;
            FD_ZERO(&rd);
            FD_SET(g_recv, &rd);
            timeval tv{0, 200 * 1000};
            int n = select(0, &rd, nullptr, nullptr, &tv);
            if (n > 0 && FD_ISSET(g_recv, &rd)) {
                for (int i = 0; i < 16; ++i) {
                    sockaddr_in from{};
                    int fl = sizeof(from);
                    int got = recvfrom(g_recv, buf.data(), (int)buf.size(), 0, (sockaddr*)&from, &fl);
                    if (got <= 0) break;
                    std::string text(buf.data(), (size_t)got);
                    js::Value v;
                    if (!js::Parse(text, &v)) continue;
                    const std::string t = v.GetStr("t");
                    const uint32_t srcIp = ntohl(from.sin_addr.s_addr);
                    if (t == "probe") {
                        Upsert(v, srcIp);
                        SendAnnounceBack(srcIp, (uint16_t)g_app.cfg.discoverPort);
                    } else if (t == "announce") {
                        Upsert(v, srcIp);
                    }
                }
            }
        }
    }
}

}  // namespace

bool Start() {
    if (g_run) return true;
    if (!netu::WsaInit()) return false;

    g_devs.clear();
    g_send.clear();

    // 接收 socket：必须绑 0.0.0.0 才能收到广播
    SOCKET rs = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (rs == INVALID_SOCKET) return false;
    BOOL reuse = TRUE;
    setsockopt(rs, SOL_SOCKET, SO_REUSEADDR, (const char*)&reuse, sizeof(reuse));
    sockaddr_in la{};
    la.sin_family = AF_INET;
    la.sin_port = htons((u_short)g_app.cfg.discoverPort);
    la.sin_addr.s_addr = INADDR_ANY;
    if (bind(rs, (sockaddr*)&la, sizeof(la)) != 0) {
        logx::Line("discovery: bind udp %d failed err=%d", g_app.cfg.discoverPort,
                   WSAGetLastError());
        closesocket(rs);
        return false;
    }
    g_recv = rs;

    // 每个网卡一个发送 socket，绑定到该网卡地址（规避默认选路 77% 失败）
    for (const auto& f : netu::EnumIfaces()) {
        SOCKET s = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
        if (s == INVALID_SOCKET) continue;
        sockaddr_in a{};
        a.sin_family = AF_INET;
        a.sin_addr.s_addr = htonl(f.ip);
        a.sin_port = 0;
        if (bind(s, (sockaddr*)&a, sizeof(a)) != 0) {
            closesocket(s);
            continue;
        }
        BOOL on = TRUE;
        setsockopt(s, SOL_SOCKET, SO_BROADCAST, (const char*)&on, sizeof(on));
        SendSock ss;
        ss.s = s;
        ss.ip = f.ip;
        ss.bcast = (f.ip & f.mask) | (~f.mask);
        g_send.push_back(ss);
        logx::Line("discovery: iface %s ip=%s bcast=%s", util::Narrow(f.name).c_str(),
                   util::Narrow(netu::IpToText(f.ip)).c_str(),
                   util::Narrow(netu::IpToText(ss.bcast)).c_str());
    }

    if (g_send.empty()) {
        logx::Line("discovery: no usable iface, fallback to any");
        SOCKET s = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
        if (s != INVALID_SOCKET) {
            BOOL on = TRUE;
            setsockopt(s, SOL_SOCKET, SO_BROADCAST, (const char*)&on, sizeof(on));
            SendSock ss;
            ss.s = s;
            ss.ip = 0;
            ss.bcast = 0;
            g_send.push_back(ss);
        }
    }

    g_run = true;
    g_lastProbe = 0;
    g_thread = std::thread(Loop);
    logx::Line("discovery: started, %zu send sockets", g_send.size());
    return true;
}

void Stop() {
    if (!g_run) return;
    g_run = false;
    for (auto& ss : g_send) {
        if (ss.s != INVALID_SOCKET) { closesocket(ss.s); ss.s = INVALID_SOCKET; }
    }
    if (g_recv != INVALID_SOCKET) { closesocket(g_recv); g_recv = INVALID_SOCKET; }
    if (g_thread.joinable()) g_thread.join();
    g_send.clear();
    AcquireSRWLockExclusive(&g_lock);
    g_devs.clear();
    ReleaseSRWLockExclusive(&g_lock);
    logx::Line("discovery: stopped");
}

void Snapshot(std::vector<Device>* out) {
    if (!out) return;
    out->clear();
    const uint64_t now = util::NowMs();
    AcquireSRWLockShared(&g_lock);
    for (const auto& d : g_devs) {
        if (now - d.lastSeen > proto::kDeviceTtlMs) continue;
        Device c = d;
        c.trusted = cfg::IsTrusted(c.id);
        out->push_back(std::move(c));
    }
    ReleaseSRWLockShared(&g_lock);

    // 排序必须用稳定键：lastSeen 是心跳到达时间，两台设备各自 3s 一次、相位错开，
    // 用它排序会导致列表每 300ms 抖一次。这里按显示名做自然排序（数字按数值比），
    // 同名时用设备 id 兜底，保证任何时刻顺序完全一致。lastSeen 只用于上面的 TTL 淘汰。
    std::sort(out->begin(), out->end(), [](const Device& a, const Device& b) {
        const int c = StrCmpLogicalW(a.name.c_str(), b.name.c_str());
        if (c != 0) return c < 0;
        return a.id < b.id;
    });
}

void SetNotifyWindow(HWND h) { g_notify = h; }

void ForceProbe() {
    g_lastProbe = 0;
}

}  // namespace disc
