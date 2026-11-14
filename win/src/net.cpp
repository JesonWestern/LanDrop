#include "app.h"

#include <iphlpapi.h>
#include <netlistmgr.h>
#include <objbase.h>

#include <cstdio>
#include <cstring>

namespace netu {

namespace {
bool g_wsa = false;

bool IsVirtualAdapter(const wchar_t* desc, IFTYPE type) {
    if (type == IF_TYPE_TUNNEL || type == IF_TYPE_SOFTWARE_LOOPBACK) return true;
    if (!desc) return false;
    static const wchar_t* kBad[] = {
        L"VMware", L"VirtualBox", L"Hyper-V", L"vEthernet", L"TAP-", L"WireGuard",
        L"OpenVPN", L"Tailscale", L"ZeroTier", L"Loopback", L"Npcap", L"WAN Miniport",
        L"Teredo", L"ISATAP", L"Bluetooth",
    };
    for (auto k : kBad) {
        if (wcsstr(desc, k)) return true;
    }
    return false;
}

uint32_t PrefixToMask(unsigned char prefix) {
    if (prefix == 0) return 0;
    if (prefix >= 32) return 0xFFFFFFFFu;
    return 0xFFFFFFFFu << (32 - prefix);
}
}  // namespace

bool WsaInit() {
    if (g_wsa) return true;
    WSADATA wd{};
    int rc = WSAStartup(MAKEWORD(2, 2), &wd);
    if (rc != 0 || LOBYTE(wd.wVersion) != 2) return false;
    g_wsa = true;
    return true;
}

void WsaCleanup() {
    if (g_wsa) { WSACleanup(); g_wsa = false; }
}

std::vector<Iface> EnumIfaces() {
    std::vector<Iface> out;
    ULONG flags = GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER;
    ULONG size = 16 * 1024;
    std::vector<unsigned char> buf(size);
    ULONG rc = GetAdaptersAddresses(AF_INET, flags, nullptr,
                                    reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buf.data()), &size);
    if (rc == ERROR_BUFFER_OVERFLOW) {
        buf.resize(size);
        rc = GetAdaptersAddresses(AF_INET, flags, nullptr,
                                  reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buf.data()), &size);
    }
    if (rc != NO_ERROR) return out;

    auto* a = reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buf.data());
    for (; a; a = a->Next) {
        if (a->OperStatus != IfOperStatusUp) continue;
        if (IsVirtualAdapter(a->Description, a->IfType)) continue;
        if (a->IfType != IF_TYPE_ETHERNET_CSMACD && a->IfType != IF_TYPE_IEEE80211) continue;
        for (auto* ua = a->FirstUnicastAddress; ua; ua = ua->Next) {
            if (!ua->Address.lpSockaddr || ua->Address.lpSockaddr->sa_family != AF_INET) continue;
            auto* sin = reinterpret_cast<sockaddr_in*>(ua->Address.lpSockaddr);
            uint32_t ip = ntohl(sin->sin_addr.s_addr);
            if ((ip >> 24) == 169 || ip == 0) continue;
            unsigned char plen = ua->OnLinkPrefixLength;
            if (plen == 0 || plen > 30) continue;
            Iface f;
            f.ip = ip;
            f.mask = PrefixToMask(plen);
            f.name = a->FriendlyName ? a->FriendlyName : L"";
            out.push_back(f);
        }
    }
    return out;
}

bool IsLocalAddress(uint32_t ipHostOrder) {
    if (ipHostOrder == 0x7F000001u) return true;
    for (const auto& f : EnumIfaces()) {
        if (f.ip == ipHostOrder) return true;
    }
    return false;
}

uint32_t PrimaryIp() {
    auto v = EnumIfaces();
    return v.empty() ? 0 : v.front().ip;
}

std::wstring IpToText(uint32_t ip) {
    wchar_t buf[32];
    swprintf(buf, 32, L"%u.%u.%u.%u", (ip >> 24) & 0xFF, (ip >> 16) & 0xFF, (ip >> 8) & 0xFF,
             ip & 0xFF);
    return buf;
}

uint32_t ParseIp(const std::wstring& s) {
    unsigned a = 0, b = 0, c = 0, d = 0;
    if (swscanf(s.c_str(), L"%u.%u.%u.%u", &a, &b, &c, &d) != 4) return 0;
    if (a > 255 || b > 255 || c > 255 || d > 255) return 0;
    return (a << 24) | (b << 16) | (c << 8) | d;
}

int NetCategory() {
    int result = 0;
    INetworkListManager* nlm = nullptr;
    HRESULT hr = CoCreateInstance(CLSID_NetworkListManager, nullptr, CLSCTX_ALL,
                                  IID_INetworkListManager, (void**)&nlm);
    if (FAILED(hr) || !nlm) return 0;
    IEnumNetworks* en = nullptr;
    if (SUCCEEDED(nlm->GetNetworks(NLM_ENUM_NETWORK_CONNECTED, &en)) && en) {
        INetwork* net = nullptr;
        ULONG got = 0;
        while (en->Next(1, &net, &got) == S_OK && got == 1) {
            NLM_CONNECTIVITY conn = NLM_CONNECTIVITY_DISCONNECTED;
            net->GetConnectivity(&conn);
            if (conn & (NLM_CONNECTIVITY_IPV4_INTERNET | NLM_CONNECTIVITY_IPV4_SUBNET)) {
                NLM_NETWORK_CATEGORY cat = NLM_NETWORK_CATEGORY_PUBLIC;
                net->GetCategory(&cat);
                if (cat == NLM_NETWORK_CATEGORY_PRIVATE) result = 2;
                else if (cat == NLM_NETWORK_CATEGORY_DOMAIN_AUTHENTICATED) result = 3;
                else if (cat == NLM_NETWORK_CATEGORY_PUBLIC) result = 1;
            }
            net->Release();
            if (result == 2 || result == 3) break;
        }
        en->Release();
    }
    nlm->Release();
    return result;
}

bool SetSockBuf(SOCKET s, int bytes) {
    bool ok = setsockopt(s, SOL_SOCKET, SO_SNDBUF, (const char*)&bytes, sizeof(bytes)) == 0;
    ok = setsockopt(s, SOL_SOCKET, SO_RCVBUF, (const char*)&bytes, sizeof(bytes)) == 0 && ok;
    return ok;
}

bool SetNoDelay(SOCKET s) {
    int one = 1;
    return setsockopt(s, IPPROTO_TCP, TCP_NODELAY, (const char*)&one, sizeof(one)) == 0;
}

bool SetNonBlocking(SOCKET s, bool on) {
    u_long v = on ? 1 : 0;
    return ioctlsocket(s, FIONBIO, &v) == 0;
}

}  // namespace netu
