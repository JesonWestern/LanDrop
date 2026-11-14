#include "resource.h"
#include "ui.h"

#include <dwmapi.h>
#include <shellapi.h>
#include <shobjidl.h>
#include <windowsx.h>

#include <cwchar>
#include <string>
#include <vector>

namespace ui {

namespace {

const wchar_t* kCls = L"LanDropSettingsWnd";

struct Ctl {
    HWND name = nullptr;
    HWND saveDir = nullptr;
    HWND conns = nullptr;
    HWND ckTrust = nullptr;
    HWND ckHidden = nullptr;
    HWND ckTray = nullptr;
    HWND ckAuto = nullptr;
    HWND ckShell = nullptr;
    HWND btnBrowse = nullptr;
    HWND btnFw = nullptr;
    HWND btnSave = nullptr;
    HWND btnCancel = nullptr;
    HWND info = nullptr;
    HFONT font = nullptr;
    HBRUSH bg = nullptr;
};

Ctl* GetCtl(HWND h) { return (Ctl*)GetWindowLongPtrW(h, GWLP_USERDATA); }

bool PickFolderDlg(HWND owner, std::wstring* out) {
    IFileOpenDialog* dlg = nullptr;
    bool ok = false;
    if (SUCCEEDED(CoCreateInstance(CLSID_FileOpenDialog, nullptr, CLSCTX_INPROC_SERVER,
                                   IID_IFileOpenDialog, (void**)&dlg))) {
        DWORD opts = 0;
        dlg->GetOptions(&opts);
        dlg->SetOptions(opts | FOS_PICKFOLDERS | FOS_FORCEFILESYSTEM | FOS_PATHMUSTEXIST);
        if (SUCCEEDED(dlg->Show(owner))) {
            IShellItem* item = nullptr;
            if (SUCCEEDED(dlg->GetResult(&item)) && item) {
                PWSTR path = nullptr;
                if (SUCCEEDED(item->GetDisplayName(SIGDN_FILESYSPATH, &path)) && path) {
                    *out = path;
                    CoTaskMemFree(path);
                    ok = true;
                }
                item->Release();
            }
        }
        dlg->Release();
    }
    return ok;
}

int S(int v) { return MulDiv(v, (int)th::g_dpi, 96); }

void Layout(HWND hwnd, Ctl* c) {
    RECT rc;
    GetClientRect(hwnd, &rc);
    const int pad = S(20);
    const int labelH = S(20);
    const int rowH = S(30);
    int y = pad;

    auto mk = [&](HWND w, int x, int yy, int ww, int hh) { MoveWindow(w, x, yy, ww, hh, TRUE); };

    // 设备名
    mk(GetDlgItem(hwnd, 3001), pad, y, S(90), labelH);
    mk(c->name, pad + S(96), y - S(4), rc.right - pad * 2 - S(96), rowH);
    y += rowH + S(10);

    // 保存目录
    mk(GetDlgItem(hwnd, 3002), pad, y, S(90), labelH);
    mk(c->saveDir, pad + S(96), y - S(4), rc.right - pad * 2 - S(96) - S(76), rowH);
    mk(c->btnBrowse, rc.right - pad - S(70), y - S(4), S(70), rowH);
    y += rowH + S(10);

    // 并发
    mk(GetDlgItem(hwnd, 3003), pad, y, S(130), labelH);
    mk(c->conns, pad + S(136), y - S(4), S(60), rowH);
    y += rowH + S(14);

    mk(c->ckTrust, pad, y, rc.right - pad * 2, S(24)); y += S(26);
    mk(c->ckHidden, pad, y, rc.right - pad * 2, S(24)); y += S(26);
    mk(c->ckTray, pad, y, rc.right - pad * 2, S(24)); y += S(26);
    mk(c->ckAuto, pad, y, rc.right - pad * 2, S(24)); y += S(26);
    mk(c->ckShell, pad, y, rc.right - pad * 2, S(24)); y += S(30);

    mk(c->btnFw, pad, y, S(150), rowH);
    mk(c->info, pad, y + S(38), rc.right - pad * 2, S(58));

    mk(c->btnSave, rc.right - pad - S(170), rc.bottom - pad - rowH, S(80), rowH);
    mk(c->btnCancel, rc.right - pad - S(84), rc.bottom - pad - rowH, S(80), rowH);
}

void FillFields(Ctl* c) {
    SetWindowTextW(c->name, g_app.cfg.deviceName.c_str());
    SetWindowTextW(c->saveDir, g_app.cfg.saveDir.c_str());
    wchar_t buf[16];
    swprintf(buf, 16, L"%d", g_app.cfg.dataConns);
    SetWindowTextW(c->conns, buf);
    SendMessageW(c->ckTrust, BM_SETCHECK, g_app.cfg.autoAcceptTrusted ? BST_CHECKED : BST_UNCHECKED, 0);
    SendMessageW(c->ckHidden, BM_SETCHECK, g_app.cfg.skipHiddenSystem ? BST_CHECKED : BST_UNCHECKED, 0);
    SendMessageW(c->ckTray, BM_SETCHECK, g_app.cfg.minimizeToTray ? BST_CHECKED : BST_UNCHECKED, 0);
    SendMessageW(c->ckAuto, BM_SETCHECK, cfg::GetAutoStart() ? BST_CHECKED : BST_UNCHECKED, 0);
    SendMessageW(c->ckShell, BM_SETCHECK, shellx::IsInstalled() ? BST_CHECKED : BST_UNCHECKED, 0);

    wchar_t info[512];
    swprintf(info, 512,
             L"本机地址 %s · 端口 TCP %d / UDP %d\n临时目录 %s\n日志 %s",
             g_app.localIpText.empty() ? L"--" : g_app.localIpText.c_str(),
             g_app.cfg.listenPort, g_app.cfg.discoverPort, g_app.cfg.tmpDir.c_str(),
             logx::Path().c_str());
    SetWindowTextW(c->info, info);
}

void ReadAndSave(HWND hwnd, Ctl* c) {
    wchar_t buf[1024];
    GetWindowTextW(c->name, buf, 1024);
    std::wstring name = buf;
    if (!name.empty()) g_app.cfg.deviceName = name;

    GetWindowTextW(c->saveDir, buf, 1024);
    if (buf[0]) g_app.cfg.saveDir = buf;

    GetWindowTextW(c->conns, buf, 16);
    int n = _wtoi(buf);
    g_app.cfg.dataConns = (n < 1 || n > 8) ? 2 : n;

    g_app.cfg.autoAcceptTrusted =
        SendMessageW(c->ckTrust, BM_GETCHECK, 0, 0) == BST_CHECKED;
    g_app.cfg.skipHiddenSystem =
        SendMessageW(c->ckHidden, BM_GETCHECK, 0, 0) == BST_CHECKED;
    g_app.cfg.minimizeToTray =
        SendMessageW(c->ckTray, BM_GETCHECK, 0, 0) == BST_CHECKED;

    const bool wantAuto = SendMessageW(c->ckAuto, BM_GETCHECK, 0, 0) == BST_CHECKED;
    if (wantAuto != cfg::GetAutoStart()) {
        cfg::SetAutoStart(wantAuto);
        g_app.cfg.autoStart = wantAuto;
    }

    const bool wantShell = SendMessageW(c->ckShell, BM_GETCHECK, 0, 0) == BST_CHECKED;
    if (wantShell) shellx::InstallContextMenu();
    else shellx::RemoveContextMenu();

    cfg::EnsureDirs();
    cfg::Save();
    Refresh();
    DestroyWindow(hwnd);
}

LRESULT CALLBACK SettingsProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    Ctl* c = GetCtl(hwnd);
    switch (msg) {
        case WM_CREATE: {
            auto* cc = new Ctl();
            cc->bg = CreateSolidBrush(RGB(0x17, 0x1A, 0x1F));
            LOGFONTW lf{};
            lf.lfHeight = -S(13);
            lf.lfWeight = FW_NORMAL;
            lf.lfCharSet = DEFAULT_CHARSET;
            wcscpy(lf.lfFaceName, L"Microsoft YaHei UI");
            cc->font = CreateFontIndirectW(&lf);
            SetWindowLongPtrW(hwnd, GWLP_USERDATA, (LONG_PTR)cc);

            auto mkText = [&](const wchar_t* t, int id) {
                HWND h = CreateWindowExW(0, L"STATIC", t, WS_CHILD | WS_VISIBLE, 0, 0, 10, 10,
                                         hwnd, (HMENU)(INT_PTR)id, g_app.inst, nullptr);
                SendMessageW(h, WM_SETFONT, (WPARAM)cc->font, TRUE);
                return h;
            };
            auto mkCheck = [&](const wchar_t* t) {
                HWND h = CreateWindowExW(0, L"BUTTON", t,
                                         WS_CHILD | WS_VISIBLE | BS_AUTOCHECKBOX, 0, 0, 10, 10,
                                         hwnd, nullptr, g_app.inst, nullptr);
                SendMessageW(h, WM_SETFONT, (WPARAM)cc->font, TRUE);
                return h;
            };
            auto mkEdit = [&]() {
                HWND h = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"",
                                         WS_CHILD | WS_VISIBLE | ES_AUTOHSCROLL, 0, 0, 10, 10,
                                         hwnd, nullptr, g_app.inst, nullptr);
                SendMessageW(h, WM_SETFONT, (WPARAM)cc->font, TRUE);
                return h;
            };
            auto mkBtn = [&](const wchar_t* t, int id) {
                HWND h = CreateWindowExW(0, L"BUTTON", t, WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON, 0,
                                         0, 10, 10, hwnd, (HMENU)(INT_PTR)id, g_app.inst, nullptr);
                SendMessageW(h, WM_SETFONT, (WPARAM)cc->font, TRUE);
                return h;
            };

            mkText(L"设备名称", 3001);
            cc->name = mkEdit();
            mkText(L"接收目录", 3002);
            cc->saveDir = mkEdit();
            cc->btnBrowse = mkBtn(L"浏览…", 3010);
            mkText(L"并发连接", 3003);
            cc->conns = mkEdit();
            cc->ckTrust = mkCheck(L"信任的设备自动接收，不再弹窗确认");
            cc->ckHidden = mkCheck(L"发送时跳过隐藏文件与系统文件");
            cc->ckTray = mkCheck(L"关闭窗口时最小化到托盘（推荐）");
            cc->ckAuto = mkCheck(L"开机自动启动");
            cc->ckShell = mkCheck(L"在资源管理器右键菜单中加入「用内网快传发送」");
            cc->btnFw = mkBtn(L"修复防火墙规则", 3011);
            cc->info = mkText(L"", 3004);
            cc->btnSave = mkBtn(L"保存", IDOK);
            cc->btnCancel = mkBtn(L"取消", IDCANCEL);

            FillFields(cc);
            Layout(hwnd, cc);
            return 0;
        }
        case WM_SIZE:
            if (c) Layout(hwnd, c);
            return 0;
        case WM_CTLCOLORSTATIC:
        case WM_CTLCOLORBTN: {
            if (!c) break;
            HDC dc = (HDC)wp;
            SetTextColor(dc, RGB(0xE6, 0xE9, 0xEF));
            SetBkColor(dc, RGB(0x17, 0x1A, 0x1F));
            return (LRESULT)c->bg;
        }
        case WM_CTLCOLOREDIT: {
            if (!c) break;
            HDC dc = (HDC)wp;
            SetTextColor(dc, RGB(0xE6, 0xE9, 0xEF));
            SetBkColor(dc, RGB(0x1E, 0x22, 0x28));
            static HBRUSH eb = CreateSolidBrush(RGB(0x1E, 0x22, 0x28));
            return (LRESULT)eb;
        }
        case WM_ERASEBKGND: {
            if (!c) break;
            RECT rc;
            GetClientRect(hwnd, &rc);
            FillRect((HDC)wp, &rc, c->bg);
            return 1;
        }
        case WM_COMMAND: {
            const int id = LOWORD(wp);
            if (!c) break;
            if (id == 3010) {
                std::wstring dir;
                if (PickFolderDlg(hwnd, &dir)) SetWindowTextW(c->saveDir, dir.c_str());
                return 0;
            }
            if (id == 3011) {
                fw::InstallAsync(g_app.mainWnd);
                MessageBoxW(hwnd, L"已发起管理员授权请求，完成后状态会更新。", L"内网快传",
                            MB_ICONINFORMATION);
                return 0;
            }
            if (id == IDOK) { ReadAndSave(hwnd, c); return 0; }
            if (id == IDCANCEL) { DestroyWindow(hwnd); return 0; }
            return 0;
        }
        case WM_CLOSE:
            DestroyWindow(hwnd);
            return 0;
        case WM_DESTROY:
            if (c) {
                if (c->font) DeleteObject(c->font);
                if (c->bg) DeleteObject(c->bg);
                delete c;
                SetWindowLongPtrW(hwnd, GWLP_USERDATA, 0);
            }
            return 0;
        default:
            break;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

}  // namespace

void OpenSettings(HWND owner) {
    static bool registered = false;
    if (!registered) {
        WNDCLASSEXW wc{};
        wc.cbSize = sizeof(wc);
        wc.lpfnWndProc = SettingsProc;
        wc.hInstance = g_app.inst;
        wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
        wc.lpszClassName = kCls;
        wc.hbrBackground = nullptr;
        RegisterClassExW(&wc);
        registered = true;
    }
    RECT orc{};
    if (owner) GetWindowRect(owner, &orc);
    const int w = S(560), h = S(600);
    int x = orc.left + (orc.right - orc.left - w) / 2;
    int y = orc.top + (orc.bottom - orc.top - h) / 2;
    HWND hwnd = CreateWindowExW(WS_EX_DLGMODALFRAME, kCls, L"设置 · 内网快传",
                                WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_VISIBLE, x, y, w, h,
                                owner, nullptr, g_app.inst, nullptr);
    if (!hwnd) return;
    // 暗色标题栏
    BOOL dark = TRUE;
    if (FAILED(DwmSetWindowAttribute(hwnd, 20, &dark, sizeof(dark)))) {
        DwmSetWindowAttribute(hwnd, 19, &dark, sizeof(dark));
    }
    EnableWindow(owner, FALSE);
    MSG msg;
    while (IsWindow(hwnd)) {
        if (!GetMessageW(&msg, nullptr, 0, 0)) break;
        if (msg.message == WM_QUIT) break;
        if (!IsDialogMessageW(hwnd, &msg)) {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
    }
    EnableWindow(owner, TRUE);
    SetForegroundWindow(owner);
}

}  // namespace ui
