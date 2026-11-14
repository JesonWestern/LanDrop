#include "app.h"

#include <shellapi.h>
#include <commctrl.h>

namespace tray {

namespace {

NOTIFYICONDATAW g_nid{};
bool g_added = false;
HWND g_hwnd = nullptr;
UINT g_taskbarCreated = 0;
constexpr UINT_PTR kSubclassId = 0x1D01;

void ShowMenu(HWND hwnd) {
    HMENU m = CreatePopupMenu();
    AppendMenuW(m, MF_STRING, IDM_SEND_FILES, L"发送文件…");
    AppendMenuW(m, MF_SEPARATOR, 0, nullptr);
    AppendMenuW(m, MF_STRING, IDM_SETTINGS, L"设置");
    AppendMenuW(m, MF_STRING, IDM_OPEN_SAVE_DIR, L"打开保存目录");
    AppendMenuW(m, MF_SEPARATOR, 0, nullptr);
    AppendMenuW(m, MF_STRING | (cfg::GetAutoStart() ? MF_CHECKED : 0), IDM_AUTOSTART, L"开机自启");
    AppendMenuW(m, MF_STRING, IDM_ABOUT, L"关于");
    AppendMenuW(m, MF_SEPARATOR, 0, nullptr);
    AppendMenuW(m, MF_STRING, IDM_EXIT, L"退出");

    POINT pt;
    GetCursorPos(&pt);
    SetForegroundWindow(hwnd);
    UINT cmd = (UINT)TrackPopupMenu(m, TPM_RETURNCMD | TPM_RIGHTBUTTON | TPM_NONOTIFY, pt.x, pt.y, 0,
                                    hwnd, nullptr);
    DestroyMenu(m);
    PostMessageW(hwnd, WM_NULL, 0, 0);
    if (cmd) PostMessageW(hwnd, WM_COMMAND, cmd, 0);
}

LRESULT CALLBACK Sub(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp, UINT_PTR, DWORD_PTR) {
    if (msg == WM_APP_TRAY) {
        switch (LOWORD(lp)) {
            case WM_LBUTTONUP:
            case WM_LBUTTONDBLCLK:
                ShowWindow(hwnd, SW_SHOWNORMAL);
                SetForegroundWindow(hwnd);
                break;
            case WM_RBUTTONUP:
            case WM_CONTEXTMENU:
                ShowMenu(hwnd);
                break;
            default:
                break;
        }
        return 0;
    }
    if (g_taskbarCreated && msg == g_taskbarCreated) {
        g_added = false;
        Add(hwnd);
        return 0;
    }
    return DefSubclassProc(hwnd, msg, wp, lp);
}

}  // namespace

bool Add(HWND hwnd) {
    if (g_added) return true;
    g_hwnd = hwnd;
    g_taskbarCreated = RegisterWindowMessageW(L"TaskbarCreated");

    g_nid.cbSize = sizeof(g_nid);
    g_nid.hWnd = hwnd;
    g_nid.uID = 1;
    g_nid.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
    g_nid.uCallbackMessage = WM_APP_TRAY;
    g_nid.hIcon = (HICON)LoadImageW(g_app.inst, MAKEINTRESOURCEW(101), IMAGE_ICON,
                                    GetSystemMetrics(SM_CXSMICON),
                                    GetSystemMetrics(SM_CYSMICON), LR_SHARED);
    if (!g_nid.hIcon) g_nid.hIcon = LoadIconW(nullptr, IDI_APPLICATION);
    wcsncpy(g_nid.szTip, LANDROP_APP_NAME, 127);
    if (!Shell_NotifyIconW(NIM_ADD, &g_nid)) {
        logx::Line("tray: NIM_ADD failed");
        return false;
    }
    SetWindowSubclass(hwnd, Sub, kSubclassId, 0);
    g_added = true;
    return true;
}

void Remove() {
    if (g_added) {
        Shell_NotifyIconW(NIM_DELETE, &g_nid);
        g_added = false;
    }
    if (g_hwnd) {
        RemoveWindowSubclass(g_hwnd, Sub, kSubclassId);
        g_hwnd = nullptr;
    }
}

void UpdateTip(const std::wstring& text) {
    if (!g_added) return;
    wcsncpy(g_nid.szTip, text.substr(0, 120).c_str(), 127);
    Shell_NotifyIconW(NIM_MODIFY, &g_nid);
}

}  // namespace tray
