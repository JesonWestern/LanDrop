#include "app.h"

#include <shlobj.h>
#include <shlwapi.h>

#include <cstdarg>
#include <cstdio>
#include <cwchar>
#include <random>

namespace util {

std::wstring Widen(const std::string& s) {
    if (s.empty()) return std::wstring();
    int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), nullptr, 0);
    if (n <= 0) {
        // 非 UTF-8 兜底（极少数 ROM 会发 GBK）
        n = MultiByteToWideChar(CP_ACP, 0, s.c_str(), (int)s.size(), nullptr, 0);
        if (n <= 0) return std::wstring();
        std::wstring w((size_t)n, L'\0');
        MultiByteToWideChar(CP_ACP, 0, s.c_str(), (int)s.size(), &w[0], n);
        return w;
    }
    std::wstring w((size_t)n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), &w[0], n);
    return w;
}

std::string Narrow(const std::wstring& w) {
    if (w.empty()) return std::string();
    int n = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), (int)w.size(), nullptr, 0, nullptr, nullptr);
    if (n <= 0) return std::string();
    std::string s((size_t)n, '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.c_str(), (int)w.size(), &s[0], n, nullptr, nullptr);
    return s;
}

static bool StartsWithLong(const std::wstring& p) {
    return p.size() >= 4 && p[0] == L'\\' && p[1] == L'\\' && p[2] == L'?' && p[3] == L'\\';
}

static std::wstring CollapseSeps(const std::wstring& p) {
    std::wstring out;
    out.reserve(p.size());
    bool lastSep = false;
    for (wchar_t c : p) {
        wchar_t x = (c == L'/') ? L'\\' : c;
        if (x == L'\\') {
            if (lastSep) continue;
            lastSep = true;
        } else {
            lastSep = false;
        }
        out.push_back(x);
    }
    return out;
}

std::wstring AddLong(const std::wstring& p) {
    if (p.empty()) return p;
    // 已经带前缀：假定调用方已规范，原样返回
    if (StartsWithLong(p)) return p;
    if (p.size() >= 2 && p[0] == L'\\' && p[1] == L'\\') {  // UNC
        std::wstring rest = CollapseSeps(p.substr(2));
        return L"\\\\?\\UNC\\" + rest;
    }
    // 统一分隔符并折叠重复分隔符：带 \\?\ 前缀后系统不再做归一化，
    // 出现 "C:\\a\\\\b" 这类路径会直接 BAD_PATHNAME。
    std::wstring s = CollapseSeps(p);
    if (s.size() >= 2 && s[1] == L':') return L"\\\\?\\" + s;
    return s;  // 相对路径不加前缀
}

std::wstring StripLong(const std::wstring& p) {
    if (p.size() > 8 && p.compare(0, 8, L"\\\\?\\UNC\\") == 0) return L"\\\\" + p.substr(8);
    if (StartsWithLong(p)) return p.substr(4);
    return p;
}

bool EnsureDir(const std::wstring& dir) {
    if (dir.empty()) return false;
    std::wstring p = AddLong(dir);
    if (CreateDirectoryW(p.c_str(), nullptr)) return true;
    DWORD e = GetLastError();
    if (e == ERROR_ALREADY_EXISTS) return true;
    if (e != ERROR_PATH_NOT_FOUND) return false;
    // 逐级创建
    size_t pos = p.find_last_of(L'\\');
    if (pos == std::wstring::npos || pos < 4) return false;
    if (!EnsureDir(StripLong(p.substr(0, pos)))) return false;
    return CreateDirectoryW(p.c_str(), nullptr) != 0 || GetLastError() == ERROR_ALREADY_EXISTS;
}

bool PathExists(const std::wstring& p) {
    if (p.empty()) return false;
    return GetFileAttributesW(AddLong(p).c_str()) != INVALID_FILE_ATTRIBUTES;
}

bool IsDirPath(const std::wstring& p) {
    DWORD a = GetFileAttributesW(AddLong(p).c_str());
    return a != INVALID_FILE_ATTRIBUTES && (a & FILE_ATTRIBUTE_DIRECTORY);
}

std::wstring JoinPath(const std::wstring& a, const std::wstring& b) {
    if (a.empty()) return b;
    if (b.empty()) return a;
    wchar_t last = a.back();
    if (last == L'\\' || last == L'/') return a + b;
    return a + L"\\" + b;
}

std::wstring FileNameOf(const std::wstring& p) {
    size_t pos = p.find_last_of(L"\\/");
    return pos == std::wstring::npos ? p : p.substr(pos + 1);
}

std::wstring ExtOf(const std::wstring& p) {
    std::wstring name = FileNameOf(p);
    size_t pos = name.find_last_of(L'.');
    if (pos == std::wstring::npos || pos == 0) return std::wstring();
    return name.substr(pos);
}

std::wstring BaseOf(const std::wstring& p) {
    std::wstring name = FileNameOf(p);
    size_t pos = name.find_last_of(L'.');
    if (pos == std::wstring::npos || pos == 0) return name;
    return name.substr(0, pos);
}

std::wstring ParentOf(const std::wstring& p) {
    size_t pos = p.find_last_of(L"\\/");
    if (pos == std::wstring::npos) return std::wstring();
    if (pos == 2 && p.size() > 2 && p[1] == L':') return p.substr(0, 3);
    return p.substr(0, pos);
}

std::wstring FormatBytes(uint64_t n) {
    const wchar_t* unit[] = {L"B", L"KB", L"MB", L"GB", L"TB"};
    double v = (double)n;
    int i = 0;
    while (v >= 1024.0 && i < 4) { v /= 1024.0; ++i; }
    wchar_t buf[64];
    if (i == 0) swprintf(buf, 64, L"%llu %s", (unsigned long long)n, unit[i]);
    else if (v >= 100) swprintf(buf, 64, L"%.0f %s", v, unit[i]);
    else swprintf(buf, 64, L"%.1f %s", v, unit[i]);
    return buf;
}

std::wstring FormatSpeed(double bps) {
    if (bps <= 0) return L"--";
    wchar_t buf[64];
    if (bps >= 1024.0 * 1024.0) swprintf(buf, 64, L"%.1f MB/s", bps / (1024.0 * 1024.0));
    else if (bps >= 1024.0) swprintf(buf, 64, L"%.0f KB/s", bps / 1024.0);
    else swprintf(buf, 64, L"%.0f B/s", bps);
    return buf;
}

std::wstring FormatEta(double sec) {
    if (sec <= 0) return L"--";
    if (sec < 1) return L"即将完成";
    if (sec > 36000) return L"--";
    wchar_t buf[64];
    if (sec < 60) swprintf(buf, 64, L"剩余 %.0f 秒", sec);
    else if (sec < 3600) swprintf(buf, 64, L"剩余 %.0f 分", sec / 60.0);
    else swprintf(buf, 64, L"剩余 %.1f 小时", sec / 3600.0);
    return buf;
}

std::wstring FormatTimeShort(uint64_t ft100ns) {
    if (ft100ns == 0) return L"";
    FILETIME ft;
    ft.dwLowDateTime = (DWORD)(ft100ns & 0xFFFFFFFFull);
    ft.dwHighDateTime = (DWORD)(ft100ns >> 32);
    SYSTEMTIME st{};
    if (!FileTimeToLocalFileTime(&ft, &ft)) return L"";
    if (!FileTimeToSystemTime(&ft, &st)) return L"";
    wchar_t buf[64];
    swprintf(buf, 64, L"%04d-%02d-%02d %02d:%02d", st.wYear, st.wMonth, st.wDay, st.wHour, st.wMinute);
    return buf;
}

std::wstring NowTimeText() {
    SYSTEMTIME st{};
    GetLocalTime(&st);
    wchar_t buf[64];
    swprintf(buf, 64, L"%04d-%02d-%02d %02d:%02d:%02d", st.wYear, st.wMonth, st.wDay,
             st.wHour, st.wMinute, st.wSecond);
    return buf;
}

uint64_t NowMs() { return GetTickCount64(); }

static std::mt19937_64& Rng() {
    static std::mt19937_64 rng([] {
        std::random_device rd;
        uint64_t seed = ((uint64_t)rd() << 32) ^ rd();
        seed ^= (uint64_t)GetTickCount64() << 17;
        seed ^= (uint64_t)GetCurrentProcessId();
        return seed;
    }());
    return rng;
}

uint32_t RandomU32() { return (uint32_t)(Rng()() & 0xFFFFFFFFull); }

std::string MakeUuid() {
    uint64_t a = Rng()(), b = Rng()();
    unsigned char bytes[16];
    for (int i = 0; i < 8; ++i) bytes[i] = (unsigned char)((a >> (8 * i)) & 0xFF);
    for (int i = 0; i < 8; ++i) bytes[8 + i] = (unsigned char)((b >> (8 * i)) & 0xFF);
    bytes[6] = (unsigned char)((bytes[6] & 0x0F) | 0x40);
    bytes[8] = (unsigned char)((bytes[8] & 0x3F) | 0x80);
    char out[40];
    snprintf(out, sizeof(out),
             "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
             bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
             bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]);
    return std::string(out);
}

std::wstring SanitizeComponent(const std::wstring& in) {
    std::wstring out;
    out.reserve(in.size());
    for (wchar_t c : in) {
        if (c < 32 || wcschr(L"<>:\"/\\|?*", c)) out.push_back(L'_');
        else out.push_back(c);
    }
    while (!out.empty() && (out.back() == L'.' || out.back() == L' ')) out.pop_back();
    if (out.empty()) out = L"_";
    static const wchar_t* kRes[] = {
        L"CON", L"PRN", L"AUX", L"NUL",
        L"COM1", L"COM2", L"COM3", L"COM4", L"COM5", L"COM6", L"COM7", L"COM8", L"COM9",
        L"LPT1", L"LPT2", L"LPT3", L"LPT4", L"LPT5", L"LPT6", L"LPT7", L"LPT8", L"LPT9"};
    for (auto r : kRes) {
        if (_wcsicmp(out.c_str(), r) == 0) { out += L"_"; break; }
    }
    if (out.size() > 200) out.resize(200);
    return out;
}

bool IsSafeRelPath(const std::wstring& rel) {
    if (rel.empty() || rel.size() > 1024) return false;
    if (rel[0] == L'\\' || rel[0] == L'/') return false;
    if (rel.find(L':') != std::wstring::npos) return false;
    size_t start = 0;
    while (start <= rel.size()) {
        size_t pos = rel.find_first_of(L"\\/", start);
        std::wstring seg = (pos == std::wstring::npos) ? rel.substr(start)
                                                      : rel.substr(start, pos - start);
        if (seg.empty() || seg == L"." || seg == L"..") return false;
        if (pos == std::wstring::npos) break;
        start = pos + 1;
    }
    return true;
}

std::wstring UniquePath(const std::wstring& dir, const std::wstring& base, const std::wstring& ext) {
    std::wstring p = JoinPath(dir, base + ext);
    if (!PathExists(p)) return p;
    for (int i = 1; i < 10000; ++i) {
        wchar_t suf[24];
        swprintf(suf, 24, L" (%d)", i);
        p = JoinPath(dir, base + suf + ext);
        if (!PathExists(p)) return p;
    }
    wchar_t suf[32];
    swprintf(suf, 32, L" (%llu)", (unsigned long long)NowMs());
    return JoinPath(dir, base + suf + ext);
}

static std::wstring KnownFolder(REFKNOWNFOLDERID id) {
    PWSTR p = nullptr;
    std::wstring out;
    if (SUCCEEDED(SHGetKnownFolderPath(id, 0, nullptr, &p)) && p) out = p;
    if (p) CoTaskMemFree(p);
    return out;
}

std::wstring AppDataDir() {
    std::wstring d = KnownFolder(FOLDERID_RoamingAppData);
    if (d.empty()) return ExeDir();
    return JoinPath(d, L"LanDrop");
}

std::wstring LocalAppDataDir() {
    std::wstring d = KnownFolder(FOLDERID_LocalAppData);
    if (d.empty()) return AppDataDir();
    return JoinPath(d, L"LanDrop");
}

std::wstring ExeDir() {
    wchar_t buf[MAX_PATH * 2];
    DWORD n = GetModuleFileNameW(nullptr, buf, (DWORD)(sizeof(buf) / sizeof(buf[0])));
    if (n == 0) return L".";
    return ParentOf(std::wstring(buf, n));
}

std::wstring DownLoadsDir() {
    std::wstring d = KnownFolder(FOLDERID_Downloads);
    if (d.empty()) d = JoinPath(KnownFolder(FOLDERID_Profile), L"Downloads");
    return d;
}

std::wstring DefaultSaveDir() {
    return JoinPath(DownLoadsDir(), L"LanDrop");
}

uint64_t Fnv1a64(const void* data, size_t len) {
    uint64_t h = 0xcbf29ce484222325ull;
    const unsigned char* p = static_cast<const unsigned char*>(data);
    for (size_t i = 0; i < len; ++i) { h ^= p[i]; h *= 0x100000001b3ull; }
    return h;
}

std::wstring Hex16(uint64_t v) {
    wchar_t buf[24];
    swprintf(buf, 24, L"%016llx", (unsigned long long)v);
    return buf;
}

}  // namespace util

// ---- 状态文案 -------------------------------------------------------------
const wchar_t* StateText(JobState s) {
    switch (s) {
        case JobState::Queued:        return L"排队中";
        case JobState::WaitingAccept: return L"等待确认";
        case JobState::Handshaking:   return L"连接中";
        case JobState::Transferring:  return L"传输中";
        case JobState::Verifying:     return L"校验中";
        case JobState::Done:          return L"已完成";
        case JobState::Failed:        return L"失败";
        case JobState::Canceled:      return L"已取消";
        case JobState::Paused:        return L"已暂停";
    }
    return L"";
}

bool StateIsActive(JobState s) {
    return s == JobState::Queued || s == JobState::WaitingAccept || s == JobState::Handshaking ||
           s == JobState::Transferring || s == JobState::Verifying;
}
