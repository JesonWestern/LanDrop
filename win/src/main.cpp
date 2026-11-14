#include "app.h"
#include "resource.h"
#include "ui.h"

#include <objbase.h>
#include <shellapi.h>

#include <cstdio>
#include <string>
#include <vector>

AppState g_app;

namespace cli {
// 返回 >=0 表示已按 CLI 模式处理完毕，应直接退出；返回 -1 表示进入 GUI
int Run(int argc, wchar_t** argv);
}  // namespace cli

namespace {

HWND FindExistingWindow() {
    return FindWindowW(LANDROP_WND_CLASS, nullptr);
}

void ForwardToExisting(const std::vector<std::wstring>& paths) {
    HWND h = FindExistingWindow();
    if (!h) return;
    ShowWindow(h, SW_SHOWNORMAL);
    SetForegroundWindow(h);
    if (!paths.empty()) {
        // 用 WM_COPYDATA 传路径（单条消息限 64KB，够用）
        std::wstring joined;
        for (const auto& p : paths) {
            if (!joined.empty()) joined += L"\n";
            joined += p;
        }
        size_t bytes = (joined.size() + 1) * sizeof(wchar_t);
        if (bytes <= 60 * 1024) {
            COPYDATASTRUCT cds{};
            cds.dwData = 1;
            cds.cbData = (DWORD)bytes;
            cds.lpData = (void*)joined.c_str();
            SendMessageW(h, WM_COPYDATA, 0, (LPARAM)&cds);
        }
    }
}

std::vector<std::wstring> CollectPathArgs(int argc, wchar_t** argv) {
    std::vector<std::wstring> out;
    for (int i = 1; i < argc; ++i) {
        std::wstring a = argv[i];
        if (a.empty() || a[0] == L'-') continue;
        if (util::PathExists(a)) out.push_back(a);
    }
    return out;
}

}  // namespace

int WINAPI wWinMain(HINSTANCE inst, HINSTANCE, PWSTR, int) {
    int argc = 0;
    LPWSTR* argv = CommandLineToArgvW(GetCommandLineW(), &argc);

    g_app.inst = inst;

    if (argv) {
        int rc = cli::Run(argc, argv);
        if (rc >= 0) {
            LocalFree(argv);
            return rc;
        }
    }
    (void)inst;

    HANDLE mutex = CreateMutexW(nullptr, FALSE, LANDROP_MUTEX_NAME);
    const bool already = mutex && GetLastError() == ERROR_ALREADY_EXISTS;

    std::vector<std::wstring> pathArgs;
    if (argv) {
        pathArgs = CollectPathArgs(argc, argv);
        LocalFree(argv);
    }

    if (already) {
        ForwardToExisting(pathArgs);
        if (mutex) CloseHandle(mutex);
        return 0;
    }

    Gdiplus::GdiplusStartupInput gsi;
    ULONG_PTR gdipToken = 0;
    if (Gdiplus::GdiplusStartup(&gdipToken, &gsi, nullptr) != Gdiplus::Ok) {
        MessageBoxW(nullptr, L"GDI+ 初始化失败，无法启动界面。", L"内网快传", MB_ICONERROR);
        return 2;
    }
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE);

    cfg::Load();
    logx::Init();
    th::Init();
    jobs::Init();

    logx::Line("==== LanDrop %s starting ====", LANDROP_VERSION_A);
    logx::Line("device=%s name=%s", util::Narrow(g_app.cfg.deviceId).c_str(),
               util::Narrow(g_app.cfg.deviceName).c_str());

    if (!netu::WsaInit()) {
        MessageBoxW(nullptr, L"Winsock 初始化失败。", L"内网快传", MB_ICONERROR);
        return 3;
    }

    g_app.localIpText = netu::IpToText(netu::PrimaryIp());
    const int cat = netu::NetCategory();
    g_app.netIsPublic = (cat == 1);
    g_app.firewallOk = fw::RulesExist();

    if (!ui::Create(inst)) {
        MessageBoxW(nullptr, L"主窗口创建失败。", L"内网快传", MB_ICONERROR);
        return 4;
    }

    disc::SetNotifyWindow(g_app.mainWnd);
    srv::SetNotifyWindow(g_app.mainWnd);
    disc::Start();
    srv::Start((uint16_t)g_app.cfg.listenPort);
    tray::Add(g_app.mainWnd);

    if (!pathArgs.empty()) {
        auto* d = &g_app.cfg;  // 站内启动带路径：直接入队发送
        (void)d;
        // 复用 ui 的交互入口：投递自定义消息，UI 线程会处理
        PostMessageW(g_app.mainWnd, WM_APP_IPC_SEND, 0, 0);
        g_app.pendingSendPaths = pathArgs;
    }

    MSG msg;
    while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }

    logx::Line("shutting down");
    disc::Stop();
    srv::Stop();
    speed::Stop();
    jobs::Shutdown();
    tray::Remove();
    netu::WsaCleanup();
    cfg::Save();
    th::Shutdown();
    logx::Flush();
    logx::Shutdown();
    CoUninitialize();
    Gdiplus::GdiplusShutdown(gdipToken);

    if (mutex) CloseHandle(mutex);
    return 0;
}
