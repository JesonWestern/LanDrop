#include "app.h"

#include <cstdarg>
#include <cstdio>
#include <mutex>

namespace logx {

namespace {
FILE* g_fp = nullptr;
std::mutex g_mu;
std::wstring g_path;
uint64_t g_bytes = 0;
constexpr uint64_t kRotateBytes = 2ull * 1024 * 1024;

void Rotate() {
    if (!g_fp) return;
    fclose(g_fp);
    g_fp = nullptr;
    std::wstring old1 = g_path + L".1";
    DeleteFileW(util::AddLong(old1).c_str());
    MoveFileW(util::AddLong(g_path).c_str(), util::AddLong(old1).c_str());
    g_fp = _wfopen(util::AddLong(g_path).c_str(), L"ab");
    g_bytes = 0;
}
}  // namespace

void Init() {
    std::lock_guard<std::mutex> lk(g_mu);
    if (g_fp) return;
    std::wstring dir = util::JoinPath(util::LocalAppDataDir(), L"logs");
    util::EnsureDir(dir);
    g_path = util::JoinPath(dir, L"landrop.log");
    g_fp = _wfopen(util::AddLong(g_path).c_str(), L"ab");
    if (g_fp) {
        fseek(g_fp, 0, SEEK_END);
        g_bytes = (uint64_t)ftell(g_fp);
    }
}

void Shutdown() {
    std::lock_guard<std::mutex> lk(g_mu);
    if (g_fp) { fflush(g_fp); fclose(g_fp); g_fp = nullptr; }
}

void Line(const char* fmt, ...) {
    char body[2048];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(body, sizeof(body), fmt, ap);
    va_end(ap);

    SYSTEMTIME st{};
    GetLocalTime(&st);
    char line[2200];
    int n = snprintf(line, sizeof(line), "[%02d:%02d:%02d.%03d] %s\n", st.wHour, st.wMinute,
                     st.wSecond, st.wMilliseconds, body);
    if (n <= 0) return;

    std::lock_guard<std::mutex> lk(g_mu);
    if (!g_fp) return;
    fwrite(line, 1, (size_t)n, g_fp);
    g_bytes += (uint64_t)n;
    if (g_bytes > kRotateBytes) Rotate();
    if (g_bytes < 32 * 1024) fflush(g_fp);
}

void Flush() {
    std::lock_guard<std::mutex> lk(g_mu);
    if (g_fp) fflush(g_fp);
}

std::wstring Path() {
    std::lock_guard<std::mutex> lk(g_mu);
    return g_path;
}

}  // namespace logx
