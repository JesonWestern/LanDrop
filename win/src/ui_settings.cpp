#include "resource.h"
#include "ui.h"

#include <dwmapi.h>
#include <shellapi.h>
#include <shobjidl.h>
#include <windowsx.h>

#include <algorithm>
#include <cwchar>
#include <string>
#include <vector>

namespace ui {

namespace {

const wchar_t* kCls = L"LanDropSettingsWnd";

// 外观控件 ID（避开已有的 3001..3011）
enum : int {
    ID_APPEAR_LABEL = 3090,
    ID_PRESET_BASE = 3100,   // 3100..3109 十个预设色块
    ID_ACCENT_BASE = 3120,   // 3120..3131 十二个强调色圆点
    ID_ACCENT_DEF = 3132,    // 「默认」
    ID_WALL_PICK = 3133,
    ID_WALL_CLEAR = 3134,
    ID_WALL_PATH = 3135,
    ID_SCRIM_MINUS = 3136,
    ID_SCRIM_PLUS = 3137,
    ID_SCRIM_VAL = 3138,
    ID_CK_ANIM = 3139,
};

constexpr int kPresetCount = 10;
constexpr int kAccentCount = 12;

struct Ctl {
    // ---- 常规 ---------------------------------------------------------
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
    HFONT fontBold = nullptr;
    // 主题相关画刷：颜色随调色板变化时按需重建
    HBRUSH bg = nullptr;
    HBRUSH editBg = nullptr;
    uint32_t bgArgb = 0;
    uint32_t editBgArgb = 0;
    // ---- 外观状态 -----------------------------------------------------
    HWND swatches[kPresetCount] = {};
    HWND accents[kAccentCount] = {};
    HWND btnAccentDef = nullptr;
    HWND btnWallPick = nullptr;
    HWND btnWallClear = nullptr;
    HWND wallPath = nullptr;
    HWND btnScrimMinus = nullptr;
    HWND btnScrimPlus = nullptr;
    HWND scrimVal = nullptr;
    HWND ckAnim = nullptr;
    std::wstring preset;      // 当前选中的预设 id
    uint32_t accent = 0;      // 0 = 默认（沿用预设强调色）
    std::wstring wallpaper;   // 空 = 无
    int scrim = 55;
    bool animations = true;
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

// 选择背景图：文件型 IFileOpenDialog（非文件夹），带图片过滤器
bool PickWallpaperDlg(HWND owner, std::wstring* out) {
    IFileOpenDialog* dlg = nullptr;
    bool ok = false;
    if (SUCCEEDED(CoCreateInstance(CLSID_FileOpenDialog, nullptr, CLSCTX_INPROC_SERVER,
                                   IID_IFileOpenDialog, (void**)&dlg))) {
        DWORD opts = 0;
        dlg->GetOptions(&opts);
        dlg->SetOptions(opts | FOS_FORCEFILESYSTEM | FOS_FILEMUSTEXIST | FOS_PATHMUSTEXIST);
        static const COMDLG_FILTERSPEC kTypes[] = {
            {L"图片文件", L"*.png;*.jpg;*.jpeg;*.bmp;*.gif;*.webp;*.tif;*.tiff"},
            {L"所有文件", L"*.*"},
        };
        dlg->SetFileTypes(2, kTypes);
        dlg->SetFileTypeIndex(1);
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

// 主题画刷：颜色变了就重建（设置窗口打开期间主题通常不变，但保持正确性）
HBRUSH BrushFor(HBRUSH* slot, uint32_t* cached, uint32_t argb) {
    if (*slot && *cached == argb) return *slot;
    if (*slot) DeleteObject(*slot);
    *slot = CreateSolidBrush(RGB((BYTE)((argb >> 16) & 0xFF), (BYTE)((argb >> 8) & 0xFF),
                                (BYTE)(argb & 0xFF)));
    *cached = argb;
    return *slot;
}

// 设置页的所有纵向位置都在这里算。k 是纵向间距压缩系数（高 DPI 下窗口高度被
// 「86% 屏高」夹住时，用 k<1 把行距压扁，保证所有控件仍然放得下而不被裁掉）。
// apply=false 只做测量（用来求自然高度），不真正移动窗口。
int LayoutPass(HWND hwnd, Ctl* c, double k, bool apply) {
    RECT rc;
    GetClientRect(hwnd, &rc);
    const int pad = S(20);
    const int labelH = S(20);
    const int rowH = S(30);

    auto mk = [&](HWND w, int x, int yy, int ww, int hh) {
        if (apply && w) MoveWindow(w, x, yy, ww, hh, TRUE);
    };
    // 纵向推进：自身高度不压缩，只压缩行间距
    auto advance = [&](int ownH, int gap) { return ownH + (int)(gap * k); };

    int y = pad;

    // ---- 外观 ---------------------------------------------------------
    mk(GetDlgItem(hwnd, ID_APPEAR_LABEL), pad, y, S(120), labelH);
    y += S(22);

    // 10 个预设色块（圆角矩形，画预设 bg + accent 双色小样）
    // 宽度按可用宽度自适应：10 块固定 S(48) 在 168dpi/窄窗口下会顶到右边框
    {
        const int gap = S(4);
        const int avail = rc.right - pad * 2;
        int swW = (avail - (kPresetCount - 1) * gap) / kPresetCount;
        if (swW > S(48)) swW = S(48);
        if (swW < S(34)) swW = S(34);
        const int swH = S(38);
        int x = pad;
        for (int i = 0; i < kPresetCount; ++i) {
            mk(c->swatches[i], x, y, swW, swH);
            x += swW + gap;
        }
        y += advance(swH, S(8));
    }

    // 12 个强调色圆点 + 「默认」
    {
        const int avail = rc.right - pad * 2;
        int gap = S(6);
        int d = S(24);
        // 同样自适应：圆点 + 右侧「默认」按钮必须塞得进一行
        while (d > S(16) &&
               (kAccentCount * d + (kAccentCount - 1) * gap + S(6) + S(56)) > avail) {
            --d;
            if (gap > S(3)) --gap;
        }
        int x = pad;
        for (int i = 0; i < kAccentCount; ++i) {
            mk(c->accents[i], x, y + S(2), d, d);
            x += d + gap;
        }
        mk(c->btnAccentDef, x + S(6), y, S(56), d);
        y += advance(d, S(10));
    }

    // 背景图：选择… / 路径 / 清除
    mk(c->btnWallPick, pad, y, S(120), rowH);
    mk(c->wallPath, pad + S(126), y + S(2), rc.right - pad * 2 - S(126) - S(92), S(24));
    mk(c->btnWallClear, rc.right - pad - S(84), y, S(84), rowH);
    y += advance(rowH, S(8));

    // 暗化强度 ±（0..80，步进 5）
    mk(GetDlgItem(hwnd, 3141), pad, y + S(5), S(70), labelH);
    mk(c->btnScrimMinus, pad + S(76), y, S(30), rowH);
    mk(c->scrimVal, pad + S(110), y, S(44), rowH);
    mk(c->btnScrimPlus, pad + S(158), y, S(30), rowH);
    y += advance(rowH, S(8));

    mk(c->ckAnim, pad, y, rc.right - pad * 2, S(24));
    y += advance(S(24), S(12));

    // ---- 常规 ---------------------------------------------------------
    mk(GetDlgItem(hwnd, 3001), pad, y, S(90), labelH);
    mk(c->name, pad + S(96), y - S(4), rc.right - pad * 2 - S(96), rowH);
    y += advance(rowH, S(10));

    mk(GetDlgItem(hwnd, 3002), pad, y, S(90), labelH);
    mk(c->saveDir, pad + S(96), y - S(4), rc.right - pad * 2 - S(96) - S(76), rowH);
    mk(c->btnBrowse, rc.right - pad - S(70), y - S(4), S(70), rowH);
    y += advance(rowH, S(10));

    mk(GetDlgItem(hwnd, 3003), pad, y, S(130), labelH);
    mk(c->conns, pad + S(136), y - S(4), S(60), rowH);
    y += advance(rowH, S(12));

    mk(c->ckTrust, pad, y, rc.right - pad * 2, S(24)); y += advance(S(24), S(2));
    mk(c->ckHidden, pad, y, rc.right - pad * 2, S(24)); y += advance(S(24), S(2));
    mk(c->ckTray, pad, y, rc.right - pad * 2, S(24)); y += advance(S(24), S(2));
    mk(c->ckAuto, pad, y, rc.right - pad * 2, S(24)); y += advance(S(24), S(2));
    mk(c->ckShell, pad, y, rc.right - pad * 2, S(24)); y += advance(S(24), S(4));

    mk(c->btnFw, pad, y, S(150), rowH);
    {
        // info 是最后一块可变高度的内容：夹在「修复防火墙」与底部按钮条之间，
        // 极端小屏（高 DPI + 矮屏）下宁可压矮它，也不能压住保存/取消。
        const int footerTop = rc.bottom - pad - rowH - S(8);
        int infoY = y + S(36);
        int infoH = S(58);
        const int room = footerTop - infoY;
        if (infoH > room) infoH = room < 0 ? 0 : room;
        mk(c->info, pad, infoY, rc.right - pad * 2, infoH);
        y = infoY + infoH;
    }

    mk(c->btnSave, rc.right - pad - S(170), rc.bottom - pad - rowH, S(80), rowH);
    mk(c->btnCancel, rc.right - pad - S(84), rc.bottom - pad - rowH, S(80), rowH);
    return y;  // 最后一个"内容"控件的底边
}

void Layout(HWND hwnd, Ctl* c) {
    RECT rc;
    GetClientRect(hwnd, &rc);
    const int rowH = S(30);
    const int avail = rc.bottom - S(20) - rowH - S(20);  // 扣掉底部按钮条
    const int natural = LayoutPass(hwnd, c, 1.0, false);
    double k = 1.0;
    if (natural > avail && natural > 0) {
        // 竖直方向只压缩行距（不压缩控件高度），二分求能把内容塞进 avail 的最大 k
        double lo = 0.10, hi = 1.0;
        for (int i = 0; i < 14; ++i) {
            const double mid = (lo + hi) / 2.0;
            if (LayoutPass(hwnd, c, mid, false) <= avail) lo = mid;
            else hi = mid;
        }
        k = lo;
    }
    LayoutPass(hwnd, c, k, true);
}

void UpdateScrimText(Ctl* c) {
    wchar_t buf[16];
    swprintf(buf, 16, L"%d%%", c->scrim);
    SetWindowTextW(c->scrimVal, buf);
}

void UpdateWallText(Ctl* c) {
    SetWindowTextW(c->wallPath, c->wallpaper.empty() ? L"（无背景图）" : c->wallpaper.c_str());
}

void InvalidateAppearance(HWND hwnd, Ctl* c) {
    for (int i = 0; i < kPresetCount; ++i) {
        if (c->swatches[i]) InvalidateRect(c->swatches[i], nullptr, FALSE);
    }
    for (int i = 0; i < kAccentCount; ++i) {
        if (c->accents[i]) InvalidateRect(c->accents[i], nullptr, FALSE);
    }
    if (c->btnAccentDef) InvalidateRect(c->btnAccentDef, nullptr, FALSE);
    (void)hwnd;
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

    // 外观：从配置回填
    c->preset = th::NormalizeThemeId(g_app.cfg.theme);
    c->accent = g_app.cfg.themeAccent;
    c->wallpaper = g_app.cfg.themeWallpaper;
    c->scrim = th::ClampScrim(g_app.cfg.themeScrim);
    c->animations = g_app.cfg.themeAnimations;
    UpdateScrimText(c);
    UpdateWallText(c);
    SendMessageW(c->ckAnim, BM_SETCHECK, c->animations ? BST_CHECKED : BST_UNCHECKED, 0);

    wchar_t info[512];
    // ⚠️ MinGW 的 swprintf 按 C99 语义：%s = 窄字符（char*），宽字符串必须用 %ls，
    // 写成 %s 只会输出宽字符串的第一个字符（"本机地址 1" 这种截断就来自这里）。
    swprintf(info, 512,
             L"本机地址 %ls · 端口 TCP %d / UDP %d\n临时目录 %ls\n日志 %ls",
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

    // 外观
    g_app.cfg.theme = th::NormalizeThemeId(c->preset);
    g_app.cfg.themeAccent = c->accent;
    g_app.cfg.themeWallpaper = c->wallpaper;
    g_app.cfg.themeScrim = th::ClampScrim(c->scrim);
    g_app.cfg.themeAnimations =
        SendMessageW(c->ckAnim, BM_GETCHECK, 0, 0) == BST_CHECKED;

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

    // 立即生效：主题 → 非客户区配色 → 主窗口重绘
    th::ApplyFromConfig();
    ApplyTitleBar(g_app.mainWnd);
    Refresh();
    DestroyWindow(hwnd);
}

// ---- OWNERDRAW --------------------------------------------------------
// 预设色块：圆角矩形，上半铺预设 bg、底部一条 accent 色带、中间写预设名；
// 选中时内嵌一圈当前强调色描边。
void DrawPresetSwatch(const DRAWITEMSTRUCT* di, Ctl* c, const std::wstring& id,
                      const std::wstring& label, bool selected) {
    HDC dc = di->hDC;
    RECT rc = di->rcItem;
    FillRect(dc, &rc, BrushFor(&c->bg, &c->bgArgb, th::cPanel));

    const th::Palette sw = th::PaletteOf(id, 0);
    const int inset = S(2);
    InflateRect(&rc, -inset, -inset);

    const auto cr = [](uint32_t v) {
        return RGB((BYTE)((v >> 16) & 0xFF), (BYTE)((v >> 8) & 0xFF), (BYTE)(v & 0xFF));
    };

    // 1) 底色圆角块（圆角靠 HBRUSH+HPEN 同色填充实现）
    {
        HBRUSH fill = CreateSolidBrush(cr(sw.bg));
        HPEN pen = CreatePen(PS_SOLID, 0, cr(sw.border));
        HGDIOBJ oldB = SelectObject(dc, fill);
        HGDIOBJ oldP = SelectObject(dc, pen);
        RoundRect(dc, rc.left, rc.top, rc.right - 1, rc.bottom - 1, S(7), S(7));
        SelectObject(dc, oldB);
        SelectObject(dc, oldP);
        DeleteObject(fill);
        DeleteObject(pen);
    }

    // 2) 底部 accent 色带
    {
        RECT band = rc;
        band.left += S(4);
        band.right -= S(4);
        band.top = band.bottom - S(8);
        band.bottom -= S(4);
        HBRUSH fill = CreateSolidBrush(cr(sw.accent));
        FillRect(dc, &band, fill);
        DeleteObject(fill);
    }

    // 3) 预设名（用预设自己的文字色）
    SetBkMode(dc, TRANSPARENT);
    SetTextColor(dc, cr(sw.text));
    RECT tr = rc;
    tr.bottom = rc.bottom - S(13);
    tr.top += S(2);
    DrawTextW(dc, label.c_str(), -1, &tr, DT_CENTER | DT_VCENTER | DT_SINGLELINE);

    // 4) 选中态
    if (selected) {
        RECT sr = rc;
        InflateRect(&sr, -S(1), -S(1));
        HPEN pen = CreatePen(PS_SOLID, S(2), cr(th::cAccent));
        HGDIOBJ oldP = SelectObject(dc, pen);
        HGDIOBJ oldB = SelectObject(dc, GetStockObject(NULL_BRUSH));
        RoundRect(dc, sr.left, sr.top, sr.right - 1, sr.bottom - 1, S(6), S(6));
        SelectObject(dc, oldB);
        SelectObject(dc, oldP);
        DeleteObject(pen);
    }
}

// 强调色圆点；「默认」画成一个小文字块
void DrawAccentDot(const DRAWITEMSTRUCT* di, Ctl* c, uint32_t argb, bool isDefault,
                   bool selected) {
    HDC dc = di->hDC;
    RECT rc = di->rcItem;
    FillRect(dc, &rc, BrushFor(&c->bg, &c->bgArgb, th::cPanel));

    const auto cr = [](uint32_t v) {
        return RGB((BYTE)((v >> 16) & 0xFF), (BYTE)((v >> 8) & 0xFF), (BYTE)(v & 0xFF));
    };

    if (isDefault) {
        RECT rr = rc;
        InflateRect(&rr, -S(1), -S(1));
        HBRUSH fill = CreateSolidBrush(cr(th::cPanel3));
        FillRect(dc, &rr, fill);
        DeleteObject(fill);
        SetBkMode(dc, TRANSPARENT);
        SetTextColor(dc, cr(th::cText));
        DrawTextW(dc, L"默认", -1, &rr, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
    } else {
        HBRUSH fill = CreateSolidBrush(cr(argb));
        HPEN pen = CreatePen(PS_SOLID, 0, cr(argb));
        HGDIOBJ oldB = SelectObject(dc, fill);
        HGDIOBJ oldP = SelectObject(dc, pen);
        const int in = S(2);
        Ellipse(dc, rc.left + in, rc.top + in, rc.right - in - 1, rc.bottom - in - 1);
        SelectObject(dc, oldB);
        SelectObject(dc, oldP);
        DeleteObject(fill);
        DeleteObject(pen);
    }

    if (selected) {
        HPEN pen = CreatePen(PS_SOLID, S(2), cr(th::cText));
        HGDIOBJ oldP = SelectObject(dc, pen);
        HGDIOBJ oldB = SelectObject(dc, GetStockObject(NULL_BRUSH));
        RECT sr = rc;
        InflateRect(&sr, -S(1), -S(1));
        Ellipse(dc, sr.left, sr.top, sr.right - 1, sr.bottom - 1);
        SelectObject(dc, oldB);
        SelectObject(dc, oldP);
        DeleteObject(pen);
    }
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
            lf.lfWeight = FW_SEMIBOLD;
            lf.lfHeight = -S(14);
            cc->fontBold = CreateFontIndirectW(&lf);
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
            auto mkOwnBtn = [&](int id) {
                HWND h = CreateWindowExW(0, L"BUTTON", L"", WS_CHILD | WS_VISIBLE | BS_OWNERDRAW,
                                         0, 0, 10, 10, hwnd, (HMENU)(INT_PTR)id, g_app.inst, nullptr);
                SendMessageW(h, WM_SETFONT, (WPARAM)cc->font, TRUE);
                return h;
            };

            // ---- 外观 -------------------------------------------------
            HWND lbl = mkText(L"外观", ID_APPEAR_LABEL);
            SendMessageW(lbl, WM_SETFONT, (WPARAM)cc->fontBold, TRUE);

            const auto presets = th::Presets();
            for (int i = 0; i < kPresetCount && i < (int)presets.size(); ++i) {
                cc->swatches[i] = mkOwnBtn(ID_PRESET_BASE + i);
            }
            const auto& accents = th::AccentSwatches();
            for (int i = 0; i < kAccentCount && i < (int)accents.size(); ++i) {
                cc->accents[i] = mkOwnBtn(ID_ACCENT_BASE + i);
            }
            cc->btnAccentDef = mkBtn(L"默认", ID_ACCENT_DEF);

            cc->btnWallPick = mkBtn(L"选择背景图…", ID_WALL_PICK);
            cc->wallPath = CreateWindowExW(0, L"STATIC", L"（无背景图）",
                                           WS_CHILD | WS_VISIBLE | SS_ENDELLIPSIS, 0, 0, 10, 10,
                                           hwnd, (HMENU)(INT_PTR)ID_WALL_PATH, g_app.inst, nullptr);
            SendMessageW(cc->wallPath, WM_SETFONT, (WPARAM)cc->font, TRUE);
            cc->btnWallClear = mkBtn(L"清除背景", ID_WALL_CLEAR);

            mkText(L"暗化强度", 3141);
            cc->btnScrimMinus = mkBtn(L"−", ID_SCRIM_MINUS);
            cc->scrimVal = CreateWindowExW(0, L"STATIC", L"55%",
                                           WS_CHILD | WS_VISIBLE | SS_CENTER, 0, 0, 10, 10, hwnd,
                                           (HMENU)(INT_PTR)ID_SCRIM_VAL, g_app.inst, nullptr);
            SendMessageW(cc->scrimVal, WM_SETFONT, (WPARAM)cc->font, TRUE);
            cc->btnScrimPlus = mkBtn(L"+", ID_SCRIM_PLUS);

            cc->ckAnim = mkCheck(L"启用页签切换动画");

            // ---- 常规 -------------------------------------------------
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
            SetTextColor(dc, RGB((BYTE)((th::cText >> 16) & 0xFF), (BYTE)((th::cText >> 8) & 0xFF),
                                 (BYTE)(th::cText & 0xFF)));
            SetBkColor(dc, RGB((BYTE)((th::cPanel >> 16) & 0xFF), (BYTE)((th::cPanel >> 8) & 0xFF),
                               (BYTE)(th::cPanel & 0xFF)));
            return (LRESULT)BrushFor(&c->bg, &c->bgArgb, th::cPanel);
        }
        case WM_CTLCOLOREDIT: {
            if (!c) break;
            HDC dc = (HDC)wp;
            SetTextColor(dc, RGB((BYTE)((th::cText >> 16) & 0xFF), (BYTE)((th::cText >> 8) & 0xFF),
                                 (BYTE)(th::cText & 0xFF)));
            SetBkColor(dc, RGB((BYTE)((th::cPanel2 >> 16) & 0xFF), (BYTE)((th::cPanel2 >> 8) & 0xFF),
                               (BYTE)(th::cPanel2 & 0xFF)));
            return (LRESULT)BrushFor(&c->editBg, &c->editBgArgb, th::cPanel2);
        }
        case WM_ERASEBKGND: {
            if (!c) break;
            RECT rc;
            GetClientRect(hwnd, &rc);
            FillRect((HDC)wp, &rc, BrushFor(&c->bg, &c->bgArgb, th::cPanel));
            return 1;
        }
        case WM_DRAWITEM: {
            auto* di = (DRAWITEMSTRUCT*)lp;
            if (!c || !di) break;
            const int id = (int)di->CtlID;
            if (id >= ID_PRESET_BASE && id < ID_PRESET_BASE + kPresetCount) {
                const auto presets = th::Presets();
                const int idx = id - ID_PRESET_BASE;
                if (idx < (int)presets.size()) {
                    DrawPresetSwatch(di, c, presets[idx].first, presets[idx].second,
                                     c->preset == presets[idx].first);
                    return TRUE;
                }
            }
            if (id == ID_ACCENT_DEF) {
                DrawAccentDot(di, c, c->accent ? c->accent : th::cAccent, true, c->accent == 0);
                return TRUE;
            }
            if (id >= ID_ACCENT_BASE && id < ID_ACCENT_BASE + kAccentCount) {
                const auto& accents = th::AccentSwatches();
                const int idx = id - ID_ACCENT_BASE;
                if (idx < (int)accents.size()) {
                    DrawAccentDot(di, c, accents[idx], false,
                                  c->accent != 0 &&
                                      (c->accent & 0x00FFFFFFu) == (accents[idx] & 0x00FFFFFFu));
                    return TRUE;
                }
            }
            break;
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
            // ---- 外观交互 ---------------------------------------------
            if (id >= ID_PRESET_BASE && id < ID_PRESET_BASE + kPresetCount) {
                const auto presets = th::Presets();
                const int idx = id - ID_PRESET_BASE;
                if (idx < (int)presets.size()) c->preset = presets[idx].first;
                InvalidateAppearance(hwnd, c);
                return 0;
            }
            if (id >= ID_ACCENT_BASE && id < ID_ACCENT_BASE + kAccentCount) {
                const auto& accents = th::AccentSwatches();
                const int idx = id - ID_ACCENT_BASE;
                if (idx < (int)accents.size()) c->accent = accents[idx];
                InvalidateAppearance(hwnd, c);
                return 0;
            }
            if (id == ID_ACCENT_DEF) {
                c->accent = 0;
                InvalidateAppearance(hwnd, c);
                return 0;
            }
            if (id == ID_WALL_PICK) {
                std::wstring path;
                if (PickWallpaperDlg(hwnd, &path) && !path.empty()) {
                    c->wallpaper = path;
                    UpdateWallText(c);
                }
                return 0;
            }
            if (id == ID_WALL_CLEAR) {
                c->wallpaper.clear();
                UpdateWallText(c);
                return 0;
            }
            if (id == ID_SCRIM_MINUS) {
                c->scrim = th::ClampScrim(c->scrim - 5);
                UpdateScrimText(c);
                return 0;
            }
            if (id == ID_SCRIM_PLUS) {
                c->scrim = th::ClampScrim(c->scrim + 5);
                UpdateScrimText(c);
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
                if (c->fontBold) DeleteObject(c->fontBold);
                if (c->bg) DeleteObject(c->bg);
                if (c->editBg) DeleteObject(c->editBg);
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
    const int w = S(560);
    // 高度 780：屏幕矮时按 86% 屏高收敛，避免窗口超出可用区域
    const int sh = GetSystemMetrics(SM_CYSCREEN);
    int h = S(780);
    const int cap = (int)(sh * 0.86);
    if (h > cap) h = cap;
    int x = orc.left + (orc.right - orc.left - w) / 2;
    int y = orc.top + (orc.bottom - orc.top - h) / 2;
    if (x < 0) x = 0;
    if (y < 0) y = 0;
    HWND hwnd = CreateWindowExW(WS_EX_DLGMODALFRAME, kCls, L"设置 · 内网快传",
                                WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_VISIBLE, x, y, w, h,
                                owner, nullptr, g_app.inst, nullptr);
    if (!hwnd) return;
    ApplyTitleBar(hwnd);  // 非客户区配色跟随当前主题

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
