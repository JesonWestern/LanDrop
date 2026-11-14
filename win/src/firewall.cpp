#include "app.h"

#include <shellapi.h>

#include <cstdio>
#include <thread>
#include <vector>

namespace fw {

namespace {

bool IsElevated() {
    BOOL b = FALSE;
    HANDLE t = nullptr;
    if (OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &t)) {
        TOKEN_ELEVATION e{};
        DWORD n = 0;
        GetTokenInformation(t, TokenElevation, &e, sizeof(e), &n);
        b = e.TokenIsElevated;
        CloseHandle(t);
    }
    return b != FALSE;
}

DWORD RunHidden(const wchar_t* args) {
    wchar_t sys[MAX_PATH];
    GetSystemDirectoryW(sys, MAX_PATH);
    std::wstring exe = std::wstring(sys) + L"\\netsh.exe";
    std::wstring cmd = L"\"" + exe + L"\" " + args;

    STARTUPINFOW si{};
    si.cb = sizeof(si);
    si.dwFlags = STARTF_USESHOWWINDOW;
    si.wShowWindow = SW_HIDE;
    PROCESS_INFORMATION pi{};
    std::vector<wchar_t> buf(cmd.begin(), cmd.end());
    buf.push_back(0);
    if (!CreateProcessW(exe.c_str(), buf.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW, nullptr,
                        nullptr, &si, &pi)) {
        logx::Line("fw: CreateProcess failed %lu", GetLastError());
        return 900;
    }
    WaitForSingleObject(pi.hProcess, 20000);
    DWORD rc = 1;
    GetExitCodeProcess(pi.hProcess, &rc);
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return rc;
}

bool AddOne(const wchar_t* args) {
    DWORD rc = RunHidden(args);
    if (rc != 0) logx::Line("fw: cmd failed rc=%lu args=%s", rc, util::Narrow(args).c_str());
    return rc == 0;
}

}  // namespace

bool RulesExist() {
    DWORD rc = RunHidden(
        L"advfirewall firewall show rule name=\"LanDrop TCP\" >nul");
    return rc == 0;
}

bool AddRules() {
    wchar_t exe[MAX_PATH * 2];
    DWORD n = GetModuleFileNameW(nullptr, exe, (DWORD)(sizeof(exe) / sizeof(exe[0])));
    if (n == 0) exe[0] = 0;

    RemoveRules();

    bool ok = true;
    ok = AddOne(L"advfirewall firewall add rule name=\"LanDrop TCP\" dir=in action=allow "
                L"protocol=TCP localport=47821 profile=private,public") && ok;
    ok = AddOne(L"advfirewall firewall add rule name=\"LanDrop UDP\" dir=in action=allow "
                L"protocol=UDP localport=47820 profile=private,public") && ok;
    std::wstring prog = L"advfirewall firewall add rule name=\"LanDrop Program\" dir=in action=allow "
                        L"program=\"";
    prog += exe;
    prog += L"\" enable=yes profile=private,public";
    ok = AddOne(prog.c_str()) && ok;
    logx::Line("fw: add rules ok=%d", ok ? 1 : 0);
    return ok;
}

bool RemoveRules() {
    RunHidden(L"advfirewall firewall delete rule name=\"LanDrop TCP\"");
    RunHidden(L"advfirewall firewall delete rule name=\"LanDrop UDP\"");
    RunHidden(L"advfirewall firewall delete rule name=\"LanDrop Program\"");
    return true;
}

void InstallAsync(HWND notify) {
    std::thread([notify]() {
        bool ok = false;
        if (IsElevated()) {
            ok = AddRules();
        } else {
            wchar_t exe[MAX_PATH * 2];
            DWORD n = GetModuleFileNameW(nullptr, exe, (DWORD)(sizeof(exe) / sizeof(exe[0])));
            if (n == 0) {
                PostMessageW(notify, WM_APP_FW_DONE, 0, 0);
                return;
            }
            SHELLEXECUTEINFOW sei{};
            sei.cbSize = sizeof(sei);
            sei.fMask = SEE_MASK_NOCLOSEPROCESS;
            sei.lpVerb = L"runas";
            sei.lpFile = exe;
            sei.lpParameters = L"--install-firewall";
            sei.nShow = SW_HIDE;
            if (!ShellExecuteExW(&sei)) {
                logx::Line("fw: elevation cancelled %lu", GetLastError());
                PostMessageW(notify, WM_APP_FW_DONE, 0, 0);
                return;
            }
            WaitForSingleObject(sei.hProcess, 40000);
            DWORD rc = 1;
            GetExitCodeProcess(sei.hProcess, &rc);
            CloseHandle(sei.hProcess);
            ok = (rc == 0);
        }
        PostMessageW(notify, WM_APP_FW_DONE, ok ? 1 : 0, 0);
    }).detach();
}

}  // namespace fw
