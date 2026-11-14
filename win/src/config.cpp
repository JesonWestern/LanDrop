#include "app.h"

#include <algorithm>
#include <cstdio>
#include <cwchar>

namespace cfg {

namespace {

std::wstring IniPath() { return util::JoinPath(util::AppDataDir(), L"config.ini"); }
std::wstring HistPath() { return util::JoinPath(util::AppDataDir(), L"history.ini"); }
const wchar_t* kSec = L"LanDrop";

std::wstring ReadStr(const wchar_t* key, const wchar_t* def) {
    wchar_t buf[2048];
    buf[0] = 0;
    GetPrivateProfileStringW(kSec, key, def, buf, 2048, IniPath().c_str());
    return buf;
}

int ReadInt(const wchar_t* key, int def, int lo, int hi) {
    int v = (int)GetPrivateProfileIntW(kSec, key, def, IniPath().c_str());
    // 非法值回落默认，不静默截断到边界（避免把损坏配置"洗白"）
    if (v < lo || v > hi) return def;
    return v;
}

bool ReadBool(const wchar_t* key, bool def) {
    int v = (int)GetPrivateProfileIntW(kSec, key, def ? 1 : 0, IniPath().c_str());
    return v != 0;
}

void WriteStr(const wchar_t* key, const std::wstring& val) {
    WritePrivateProfileStringW(kSec, key, val.c_str(), IniPath().c_str());
}

void WriteInt(const wchar_t* key, int v) {
    wchar_t buf[32];
    swprintf(buf, 32, L"%d", v);
    WritePrivateProfileStringW(kSec, key, buf, IniPath().c_str());
}

std::wstring ComputerName() {
    wchar_t buf[256];
    DWORD n = 256;
    if (!GetComputerNameW(buf, &n) || n == 0) return L"Windows";
    return std::wstring(buf, n);
}

std::vector<std::wstring> SplitSemi(const std::wstring& s) {
    std::vector<std::wstring> out;
    size_t start = 0;
    while (start <= s.size()) {
        size_t pos = s.find(L';', start);
        std::wstring item = (pos == std::wstring::npos) ? s.substr(start)
                                                       : s.substr(start, pos - start);
        if (!item.empty()) out.push_back(item);
        if (pos == std::wstring::npos) break;
        start = pos + 1;
    }
    return out;
}

std::wstring JoinSemi(const std::vector<std::wstring>& v) {
    std::wstring s;
    for (size_t i = 0; i < v.size(); ++i) {
        if (i) s += L";";
        s += v[i];
    }
    return s;
}

}  // namespace

void ResetDefaults() {
    AppConfig d;
    d.deviceName = ComputerName();
    if (d.deviceName.empty()) d.deviceName = L"Windows";
    d.saveDir = util::DefaultSaveDir();
    d.tmpDir = util::JoinPath(util::LocalAppDataDir(), L"tmp");
    d.deviceId = util::Widen(util::MakeUuid());
    g_app.cfg = d;
}

bool Load() {
    ResetDefaults();
    AppConfig& c = g_app.cfg;

    c.cfgver = ReadInt(L"cfgver", 1, 0, 1000);

    std::wstring id = ReadStr(L"deviceId", L"");
    if (id.size() < 8) id = c.deviceId;  // 损坏则重建
    c.deviceId = id;

    c.deviceName = ReadStr(L"deviceName", c.deviceName.c_str());
    if (c.deviceName.empty()) c.deviceName = ComputerName();

    c.saveDir = ReadStr(L"saveDir", c.saveDir.c_str());
    c.tmpDir = ReadStr(L"tmpDir", c.tmpDir.c_str());
    if (c.saveDir.empty()) c.saveDir = util::DefaultSaveDir();
    if (c.tmpDir.empty()) c.tmpDir = util::JoinPath(util::LocalAppDataDir(), L"tmp");

    c.dataConns = ReadInt(L"dataConns", 2, 1, 8);
    c.speedConns = ReadInt(L"speedConns", 4, 1, 8);
    c.speedSecs = ReadInt(L"speedSecs", 10, 3, 60);
    c.autoAcceptTrusted = ReadBool(L"autoAcceptTrusted", true);
    c.skipHiddenSystem = ReadBool(L"skipHiddenSystem", true);
    c.minimizeToTray = ReadBool(L"minimizeToTray", true);
    c.autoStart = ReadBool(L"autoStart", false);
    c.firewallPrompted = ReadBool(L"firewallPrompted", false);
    c.listenPort = ReadInt(L"listenPort", proto::kTransferPort, 1024, 65535);
    c.discoverPort = ReadInt(L"discoverPort", proto::kDiscoverPort, 1024, 65535);
    c.trustedIds = SplitSemi(ReadStr(L"trustedIds", L""));

    EnsureDirs();
    // 首次运行就落盘，避免进程被强杀后配置全丢
    if (!util::PathExists(IniPath())) Save();
    return true;
}

bool Save() {
    const AppConfig& c = g_app.cfg;
    util::EnsureDir(util::AppDataDir());
    WriteInt(L"cfgver", c.cfgver);
    WriteStr(L"deviceId", c.deviceId);
    WriteStr(L"deviceName", c.deviceName);
    WriteStr(L"saveDir", c.saveDir);
    WriteStr(L"tmpDir", c.tmpDir);
    WriteInt(L"dataConns", c.dataConns);
    WriteInt(L"speedConns", c.speedConns);
    WriteInt(L"speedSecs", c.speedSecs);
    WriteInt(L"autoAcceptTrusted", c.autoAcceptTrusted ? 1 : 0);
    WriteInt(L"skipHiddenSystem", c.skipHiddenSystem ? 1 : 0);
    WriteInt(L"minimizeToTray", c.minimizeToTray ? 1 : 0);
    WriteInt(L"autoStart", c.autoStart ? 1 : 0);
    WriteInt(L"firewallPrompted", c.firewallPrompted ? 1 : 0);
    WriteInt(L"listenPort", c.listenPort);
    WriteInt(L"discoverPort", c.discoverPort);
    WriteStr(L"trustedIds", JoinSemi(c.trustedIds));
    return true;
}

void EnsureDirs() {
    AppConfig& c = g_app.cfg;
    util::EnsureDir(util::AppDataDir());
    util::EnsureDir(util::JoinPath(util::LocalAppDataDir(), L"logs"));
    util::EnsureDir(c.tmpDir);
    util::EnsureDir(c.saveDir);
}

void SetAutoStart(bool on) {
    HKEY hk = nullptr;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, L"Software\\Microsoft\\Windows\\CurrentVersion\\Run", 0,
                        nullptr, 0, KEY_SET_VALUE, nullptr, &hk, nullptr) != ERROR_SUCCESS) {
        return;
    }
    if (!on) {
        RegDeleteValueW(hk, LANDROP_RUN_KEY);
        RegCloseKey(hk);
        return;
    }
    wchar_t exe[MAX_PATH * 2];
    DWORD n = GetModuleFileNameW(nullptr, exe, (DWORD)(sizeof(exe) / sizeof(exe[0])));
    if (n == 0) { RegCloseKey(hk); return; }
    std::wstring val = L"\"";
    val.append(exe, n);
    val += L"\" --tray";
    RegSetValueExW(hk, LANDROP_RUN_KEY, 0, REG_SZ, (const BYTE*)val.c_str(),
                   (DWORD)((val.size() + 1) * sizeof(wchar_t)));
    RegCloseKey(hk);
}

bool GetAutoStart() {
    HKEY hk = nullptr;
    if (RegOpenKeyExW(HKEY_CURRENT_USER, L"Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                      0, KEY_QUERY_VALUE, &hk) != ERROR_SUCCESS) {
        return false;
    }
    wchar_t buf[MAX_PATH * 2];
    DWORD cb = sizeof(buf);
    DWORD type = 0;
    bool found = RegQueryValueExW(hk, LANDROP_RUN_KEY, nullptr, &type, (BYTE*)buf, &cb) ==
                 ERROR_SUCCESS;
    RegCloseKey(hk);
    return found;
}

bool IsTrusted(const std::string& id) {
    std::wstring w = util::Widen(id);
    for (const auto& t : g_app.cfg.trustedIds) {
        if (t == w) return true;
    }
    return false;
}

void TrustDevice(const std::string& id) {
    std::wstring w = util::Widen(id);
    if (w.empty()) return;
    for (const auto& t : g_app.cfg.trustedIds) {
        if (t == w) return;
    }
    if (g_app.cfg.trustedIds.size() >= 64) g_app.cfg.trustedIds.erase(g_app.cfg.trustedIds.begin());
    g_app.cfg.trustedIds.push_back(w);
    Save();
}

void ForgetDevice(const std::string& id) {
    std::wstring w = util::Widen(id);
    auto& v = g_app.cfg.trustedIds;
    for (auto it = v.begin(); it != v.end(); ++it) {
        if (*it == w) { v.erase(it); Save(); return; }
    }
}

// ---- 历史 -----------------------------------------------------------------
namespace {
const wchar_t* kHistSec = L"meta";
int g_histSeq = 0;  // 内存中的环形槽位
}

void HistoryAppend(const HistRow& r) {
    util::EnsureDir(util::AppDataDir());
    int slot = (int)GetPrivateProfileIntW(L"meta", L"histNext", 0, HistPath().c_str());
    if (slot < 0 || slot > 199) slot = 0;
    // 槽位 0..199 循环使用，每条记录带 unix 时间戳用于排序
    wchar_t sec[32];
    swprintf(sec, 32, L"h%d", slot);

    FILETIME nowFt;
    GetSystemTimeAsFileTime(&nowFt);
    uint64_t ts = ((uint64_t)nowFt.dwHighDateTime << 32) | nowFt.dwLowDateTime;

    wchar_t buf[32];
    WritePrivateProfileStringW(sec, L"title", r.title.c_str(), HistPath().c_str());
    WritePrivateProfileStringW(sec, L"path", r.savePath.c_str(), HistPath().c_str());
    WritePrivateProfileStringW(sec, L"device", r.device.c_str(), HistPath().c_str());
    WritePrivateProfileStringW(sec, L"time", r.timeText.c_str(), HistPath().c_str());
    swprintf(buf, 32, L"%llu", (unsigned long long)r.bytes);
    WritePrivateProfileStringW(sec, L"bytes", buf, HistPath().c_str());
    swprintf(buf, 32, L"%d", r.dir);
    WritePrivateProfileStringW(sec, L"dir", buf, HistPath().c_str());
    swprintf(buf, 32, L"%d", r.state);
    WritePrivateProfileStringW(sec, L"state", buf, HistPath().c_str());
    swprintf(buf, 32, L"%llu", (unsigned long long)ts);
    WritePrivateProfileStringW(sec, L"ts", buf, HistPath().c_str());

    int next = (slot + 1) % 200;
    swprintf(buf, 32, L"%d", next);
    WritePrivateProfileStringW(kHistSec, L"histNext", buf, HistPath().c_str());
    (void)g_histSeq;
}

std::vector<HistRow> HistoryLoad() {
    struct Row {
        HistRow r;
        uint64_t ts = 0;
    };
    std::vector<Row> rows;
    for (int i = 0; i < 200; ++i) {
        wchar_t sec[32];
        swprintf(sec, 32, L"h%d", i);
        wchar_t buf[2048];
        buf[0] = 0;
        GetPrivateProfileStringW(sec, L"title", L"", buf, 2048, HistPath().c_str());
        if (buf[0] == 0) continue;
        Row row;
        row.r.title = buf;
        GetPrivateProfileStringW(sec, L"path", L"", buf, 2048, HistPath().c_str());
        row.r.savePath = buf;
        GetPrivateProfileStringW(sec, L"device", L"", buf, 2048, HistPath().c_str());
        row.r.device = buf;
        GetPrivateProfileStringW(sec, L"time", L"", buf, 2048, HistPath().c_str());
        row.r.timeText = buf;
        row.r.bytes = (uint64_t)GetPrivateProfileIntW(sec, L"bytes", 0, HistPath().c_str());
        row.r.dir = (int)GetPrivateProfileIntW(sec, L"dir", 0, HistPath().c_str());
        row.r.state = (int)GetPrivateProfileIntW(sec, L"state", 3, HistPath().c_str());
        wchar_t tbuf[32];
        tbuf[0] = 0;
        GetPrivateProfileStringW(sec, L"ts", L"0", tbuf, 32, HistPath().c_str());
        row.ts = _wtoi64(tbuf);
        rows.push_back(std::move(row));
    }
    std::sort(rows.begin(), rows.end(), [](const Row& a, const Row& b) { return a.ts > b.ts; });
    std::vector<HistRow> out;
    out.reserve(rows.size());
    for (auto& r : rows) out.push_back(std::move(r.r));
    return out;
}

void HistoryClear() {
    DeleteFileW(util::AddLong(HistPath()).c_str());
}

}  // namespace cfg
