#include "ui.h"
#include "resource.h"

#include <commdlg.h>
#include <dwmapi.h>
#include <shellapi.h>
#include <shobjidl.h>
#include <windowsx.h>

#include <algorithm>
#include <cwchar>

namespace ui {

std::vector<Hit> hits;
std::vector<Device> devs;
std::vector<JobView> jobViews;
std::wstring selDevId;
int scrollRecv = 0;
int scrollSend = 0;
int scrollDev = 0;
int hoverKind = HK_None;
uint32_t hoverId = 0;
int hoverAction = A_None;
int curTab = TabRecv;
int clientW = 0;
int clientH = 0;
int speedDir = 2;
std::vector<double> speedSamples;
std::wstring statusLine;

// ---- 非客户区配色 -------------------------------------------------------
namespace {
inline COLORREF RgbOf(uint32_t argb) {
    return RGB((BYTE)((argb >> 16) & 0xFF), (BYTE)((argb >> 8) & 0xFF), (BYTE)(argb & 0xFF));
}
}  // namespace

// DWM 非客户区配色：深/浅由 g_pal.light 决定，颜色全部取自当前调色板。
// 老系统不支持 DWMWA_CAPTION_COLOR(35)/TEXT_COLOR(36)/BORDER_COLOR(34)，失败忽略即可。
void ApplyTitleBar(HWND hwnd) {
    if (!hwnd) return;
    BOOL dark = th::g_pal.light ? FALSE : TRUE;
    if (FAILED(DwmSetWindowAttribute(hwnd, 20 /*USE_IMMERSIVE_DARK_MODE*/, &dark, sizeof(dark)))) {
        DwmSetWindowAttribute(hwnd, 19 /*旧编号，Win10 1809 以前*/, &dark, sizeof(dark));
    }
    COLORREF cap = RgbOf(th::g_pal.bg);
    DwmSetWindowAttribute(hwnd, 35, &cap, sizeof(cap));   // DWMWA_CAPTION_COLOR
    COLORREF txt = RgbOf(th::g_pal.text);
    DwmSetWindowAttribute(hwnd, 36, &txt, sizeof(txt));   // DWMWA_TEXT_COLOR
    COLORREF bd = RgbOf(th::g_pal.border);
    DwmSetWindowAttribute(hwnd, 34, &bd, sizeof(bd));     // DWMWA_BORDER_COLOR
    // 边框/标题栏变了要重绘非客户区
    SetWindowPos(hwnd, nullptr, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER |
                                             SWP_NOACTIVATE | SWP_FRAMECHANGED);
}

namespace {

HDC g_memDC = nullptr;
HBITMAP g_memBmp = nullptr;
HGDIOBJ g_oldBmp = nullptr;
int g_bw = 0;
int g_bh = 0;

constexpr int kTopH = 52;
constexpr int kBottomH = 54;
constexpr int kLeftW = 280;
constexpr int kPad = 16;

void EnsureBackbuffer(HDC ref, int w, int h) {
    if (w <= 0 || h <= 0) return;
    if (g_memDC && g_bw == w && g_bh == h) return;
    if (g_memDC) {
        if (g_oldBmp) SelectObject(g_memDC, g_oldBmp);
        DeleteObject(g_memBmp);
        DeleteDC(g_memDC);
        g_memDC = nullptr;
        g_memBmp = nullptr;
        g_oldBmp = nullptr;
    }
    g_memDC = CreateCompatibleDC(ref);
    g_memBmp = CreateCompatibleBitmap(ref, w, h);
    g_oldBmp = SelectObject(g_memDC, g_memBmp);
    g_bw = w;
    g_bh = h;
}

void FreeBackbuffer() {
    if (g_memDC) {
        if (g_oldBmp) SelectObject(g_memDC, g_oldBmp);
        if (g_memBmp) DeleteObject(g_memBmp);
        DeleteDC(g_memDC);
    }
    g_memDC = nullptr;
    g_memBmp = nullptr;
    g_oldBmp = nullptr;
    g_bw = g_bh = 0;
}

// ---- 页签切换动画 -------------------------------------------------------
// 内容区的 32bpp DIB 快照：动画期先画新内容，再用 GdiAlphaBlend 按 EaseOutCubic 的
// 全局 alpha 把旧快照叠上去（并横向位移 24dp）。用 DIB + AlphaBlend 而不是 GDI+ 的
// ColorMatrix，是因为前者的"整体透明度"语义确定，不受位图 alpha 通道里残留 0 的影响。
HDC g_animDC = nullptr;        // 快照 DC（DIB section）
HBITMAP g_animBmp = nullptr;   // 快照位图
HGDIOBJ g_animOld = nullptr;
int g_animW = 0;
int g_animH = 0;
RECT g_animRect{0, 0, 0, 0};   // 快照在客户区里的位置
bool g_animOn = false;
uint64_t g_animStart = 0;
constexpr int kAnimMs = 180;

void FreeAnimSnapshot() {
    if (g_animDC) {
        if (g_animOld) SelectObject(g_animDC, g_animOld);
        DeleteDC(g_animDC);
    }
    if (g_animBmp) DeleteObject(g_animBmp);
    g_animDC = nullptr;
    g_animBmp = nullptr;
    g_animOld = nullptr;
    g_animW = g_animH = 0;
    g_animRect = RECT{0, 0, 0, 0};
    g_animOn = false;
}

// 内容区几何：与 Paint 里 tabs/content 的算式保持一致
//
// 响应式：窗口窄时左侧设备面板按比例收窄，保证右侧内容区至少留出 ~300dp。
// 800x600 下原本左栏固定 280dp，右侧只剩不到 300dp，「接收/发送/测速」页签会和
// 右上角「清除已完成」压在一起（实测重叠）。这里按可用宽度动态取左栏宽度。
int LeftPanelWidth(int w) {
    const int minRight = th::D(300);
    int avail = w - th::D(kPad) * 3 - th::D(14);   // 左右 padding + 中缝
    int maxLeft = avail - minRight;
    int want = th::D(kLeftW);
    if (want > maxLeft) want = maxLeft;
    const int minLeft = th::D(180);
    if (want < minLeft) want = minLeft;
    return want;
}
int ContentTop() { return th::D(kTopH) + th::D(2) + th::D(42) + th::D(4); }
int ContentLeft(int w) { return th::D(kPad) + LeftPanelWidth(w) + th::D(14); }
int ContentWidth(int w) { return w - ContentLeft(w) - th::D(kPad); }
int ContentHeight(int h) { return (h - th::D(kBottomH) - th::D(8)) - ContentTop(); }

// 从后备缓冲取旧内容快照（必须在上一帧绘制完成后、内容切换之前调用）
void CaptureContentSnapshot(int w, int h) {
    FreeAnimSnapshot();
    if (!g_memDC || w <= 0 || h <= 0) return;
    const int left = ContentLeft(w), top = ContentTop();
    const int cw = ContentWidth(w), ch = ContentHeight(h);
    if (cw <= 0 || ch <= 0) return;

    BITMAPINFO bi{};
    bi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    bi.bmiHeader.biWidth = cw;
    bi.bmiHeader.biHeight = -ch;  // top-down
    bi.bmiHeader.biPlanes = 1;
    bi.bmiHeader.biBitCount = 32;
    bi.bmiHeader.biCompression = BI_RGB;

    void* bits = nullptr;
    HDC dc = CreateCompatibleDC(g_memDC);
    if (!dc) return;
    HBITMAP bmp = CreateDIBSection(dc, &bi, DIB_RGB_COLORS, &bits, nullptr, 0);
    if (!bmp) {
        DeleteDC(dc);
        return;
    }
    HGDIOBJ old = SelectObject(dc, bmp);
    BitBlt(dc, 0, 0, cw, ch, g_memDC, left, top, SRCCOPY);

    g_animDC = dc;
    g_animBmp = bmp;
    g_animOld = old;
    g_animW = cw;
    g_animH = ch;
    g_animRect = RECT{left, top, left + cw, top + ch};
}

// 把旧快照按当前 alpha/位移叠到后备缓冲。必须在 GDI+ Graphics 析构之后调用。
void BlendAnimOverlay(HDC target) {
    if (!g_animOn || !g_animDC || !g_animBmp || !target) return;
    const uint64_t elapsed = GetTickCount64() - g_animStart;
    float t = (float)elapsed / (float)kAnimMs;
    if (t >= 1.0f) t = 1.0f;
    // 按规格：旧内容的 alpha = EaseOutCubic(1-t)，t=0 时 1（完全盖住）→ t=1 时 0（让出）
    const float alpha = th::EaseOutCubic(1.0f - t);
    if (alpha <= 0.01f) return;

    // 24dp 横向位移，随 alpha 一起收敛到 0；窄窗口（逻辑宽 < 480dp）只淡入不位移
    const int logicW = th::g_dpi ? MulDiv(g_bw, 96, th::g_dpi) : g_bw;
    const int slide = (logicW < 480) ? 0 : (int)(th::Df(24.0f) * alpha);

    const BYTE a = (BYTE)(alpha * 255.0f + 0.5f);
    if (a == 0) return;
    BLENDFUNCTION bf{};
    bf.BlendOp = AC_SRC_OVER;
    bf.BlendFlags = 0;
    bf.SourceConstantAlpha = a;
    bf.AlphaFormat = 0;  // 不用逐像素 alpha：整幅按 SourceConstantAlpha 淡化
    GdiAlphaBlend(target, g_animRect.left + slide, g_animRect.top, g_animW, g_animH, g_animDC,
                  0, 0, g_animW, g_animH, bf);
}

bool AnimStillRunning() {
    return g_animOn && (GetTickCount64() - g_animStart) < (uint64_t)kAnimMs;
}

void Button(Gdiplus::Graphics& g, const Gdiplus::RectF& rc, const std::wstring& label,
            bool primary, bool hover, uint32_t fgOverride = 0, int action = A_None,
            uint32_t btnId = 0, int kind = HK_None) {
    uint32_t bg = primary ? th::cAccent : th::cPanel3;
    uint32_t fg = primary ? th::cOnAccent : th::cText;
    if (!primary && hover) bg = th::cBorder2;
    if (primary && hover) bg = th::Shade(th::cAccent, 1.12f);
    if (fgOverride) fg = fgOverride;
    th::FillRound(g, rc, th::D(7), bg);
    th::DrawText(g, label, th::Font(th::D(13)), rc, fg, 1, false, true);
    if (kind != HK_None) AddHit(kind, btnId, action, rc);
}

void Badge(Gdiplus::Graphics& g, Gdiplus::RectF rc, const std::wstring& text, uint32_t fg,
           uint32_t bg) {
    th::FillRound(g, rc, rc.Height / 2.0f, bg);
    th::DrawText(g, text, th::Font(th::D(11.5f)), rc, fg, 1, false, true);
}

std::wstring DeviceSubtitle(const Device& d) {
    std::wstring s = netu::IpToText(d.ip);
    if (d.trusted) s += L" · 已信任";
    if (d.plat == "android") s += L" · 手机";
    return s;
}

// ---- 文件夹选择 -----------------------------------------------------------
bool PickFolder(HWND owner, std::wstring* out) {
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

std::vector<std::wstring> PickFiles(HWND owner) {
    std::vector<std::wstring> out;
    wchar_t buf[64 * 1024];
    buf[0] = 0;
    OPENFILENAMEW ofn{};
    ofn.lStructSize = sizeof(ofn);
    ofn.hwndOwner = owner;
    ofn.lpstrFilter = L"所有文件\0*.*\0";
    ofn.lpstrFile = buf;
    ofn.nMaxFile = 64 * 1024;
    ofn.Flags = OFN_EXPLORER | OFN_ALLOWMULTISELECT | OFN_FILEMUSTEXIST | OFN_NOCHANGEDIR;
    if (!GetOpenFileNameW(&ofn)) return out;
    // 多选时：目录\0文件1\0文件2\0\0
    std::wstring first = buf;
    const wchar_t* p = buf + first.size() + 1;
    if (*p == 0) {
        out.push_back(first);
        return out;
    }
    while (*p) {
        std::wstring name = p;
        out.push_back(util::JoinPath(first, name));
        p += name.size() + 1;
    }
    return out;
}

const Device* SelectedDevice() {
    for (const auto& d : devs) {
        if (util::Widen(d.id) == selDevId) return &d;
    }
    return devs.empty() ? nullptr : &devs.front();
}

void StartSendToSelected(const std::vector<std::wstring>& paths) {
    if (paths.empty()) return;
    const Device* d = SelectedDevice();
    if (!d) {
        MessageBoxW(g_app.mainWnd, L"还没有发现任何设备。\n请确认手机已打开本应用并保持亮屏，且与本机连在同一个 WiFi。",
                    L"内网快传", MB_ICONINFORMATION);
        return;
    }
    client::SendOptions opt;
    opt.ip = d->ip;
    opt.port = d->port;
    opt.conns = g_app.cfg.dataConns;
    client::StartSend(paths, opt, L"");
}

}  // namespace

bool AnimationsEnabled() {
    if (!g_app.cfg.themeAnimations) return false;
    BOOL anim = TRUE;
    if (SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, &anim, 0) && !anim) return false;
    return true;
}

void CancelTabAnim() {
    const bool had = g_animOn || g_animBmp != nullptr;
    if (g_app.mainWnd) KillTimer(g_app.mainWnd, TIMER_ANIM);
    FreeAnimSnapshot();
    if (had) Refresh();
}

void AnimateTabSwitch(int from, int to) {
    if (from == to) return;
    // 再切一次：取消上一个动画；此刻后备缓冲里还是上一帧画面，正好重新取快照
    CancelTabAnim();
    if (!AnimationsEnabled() || !g_app.mainWnd) return;
    if (!g_memDC || g_bw <= 0 || g_bh <= 0) return;  // 还没绘制过，没有可用的旧画面
    CaptureContentSnapshot(g_bw, g_bh);
    if (!g_animDC) return;
    g_animStart = GetTickCount64();
    g_animOn = true;
    SetTimer(g_app.mainWnd, TIMER_ANIM, 16, nullptr);
}

// ---- 对外 ---------------------------------------------------------------
void ClearHits() { hits.clear(); }
void AddHit(int kind, uint32_t id, int action, const Gdiplus::RectF& rc) {
    Hit h;
    h.kind = kind;
    h.id = id;
    h.action = action;
    h.rc = rc;
    hits.push_back(h);
}
const Hit* FindHit(int x, int y) {
    // 后加入的优先（按钮压在行上面）
    for (auto it = hits.rbegin(); it != hits.rend(); ++it) {
        if (th::HitRect(it->rc, x, y)) return &*it;
    }
    return nullptr;
}
bool HoverIs(int kind, uint32_t id, int action) {
    return hoverKind == kind && hoverId == id && hoverAction == action;
}

void SetStatus(const std::wstring& s) { statusLine = s; }

void Refresh() {
    if (g_app.mainWnd) InvalidateRect(g_app.mainWnd, nullptr, FALSE);
}

void ApplyDpi(UINT dpi) {
    th::g_dpi = th::g_dpiForce > 0 ? th::g_dpiForce : (dpi ? (int)dpi : 96);
    CancelTabAnim();
    FreeBackbuffer();
    Refresh();
}

// ---- 顶栏 ---------------------------------------------------------------
// 响应式：窄窗口优先保留右侧状态徽标，把左边「内网快传 + 本机 IP」压缩；
// 余量不足时干脆不画 IP 文本，避免与徽标叠字（800x600 实测会重叠）。
void DrawTopBar(Gdiplus::Graphics& g, const Gdiplus::RectF& area) {
    const float rightEdge = area.X + area.Width - th::D(kPad);

    // 先量出右侧徽标总宽，才知道左边还剩多少空间
    const bool showFw = !g_app.firewallOk && !g_app.sawInbound.load();
    const wchar_t* netLabel = g_app.netIsPublic ? L"公用网络 · 建议切换" : L"专用网络";
    const float netW = th::TextWidth(netLabel, th::Font(th::D(11.5f))) + th::D(24);
    float badgesW = netW;
    if (showFw) {
        badgesW += th::TextWidth(L"防火墙规则未添加 · 点击修复", th::Font(th::D(11.5f))) + th::D(24) + th::D(8);
    }

    const float titleW = th::TextWidth(L"内网快传", th::Font(th::D(17), true));
    const float titleEnd = area.X + th::D(kPad) + titleW;

    // 标题始终画
    Gdiplus::RectF title(area.X + th::D(kPad), area.Y, titleW + th::D(8), area.Height);
    th::DrawText(g, L"内网快传", th::Font(th::D(17), true), title, th::cText, 0, false, true);

    // IP 只在左侧还有余量时画（不够就整段省略，宁可少显示也不要叠字）
    const float ipRoom = rightEdge - badgesW - th::D(12) - (titleEnd + th::D(16));
    if (ipRoom >= th::D(110)) {
        std::wstring ipText = g_app.localIpText.empty() ? L"未联网" : (L"本机 " + g_app.localIpText);
        Gdiplus::RectF ip(titleEnd + th::D(16), area.Y, ipRoom, area.Height);
        th::DrawText(g, ipText, th::Font(th::D(12)), ip, th::cTextFaint, 0, true, true);
    }

    float right = rightEdge;

    if (showFw) {
        std::wstring t = L"防火墙规则未添加 · 点击修复";
        float w = th::TextWidth(t, th::Font(th::D(11.5f))) + th::D(24);
        Gdiplus::RectF r(right - w, area.Y + (area.Height - th::D(24)) / 2, w, th::D(24));
        Badge(g, r, t, th::TintText(th::cRed), th::TintBg(th::cRed));
        AddHit(HK_FixFirewall, 0, A_None, r);
        right -= w + th::D(8);
    }

    {
        const uint32_t base = g_app.netIsPublic ? th::cAmber : th::cGreen;
        Gdiplus::RectF r(right - netW, area.Y + (area.Height - th::D(24)) / 2, netW, th::D(24));
        Badge(g, r, netLabel, th::TintText(base), th::TintBg(base));
        AddHit(HK_Network, 0, A_None, r);
    }
}

// ---- 设备面板 -----------------------------------------------------------
void DrawDevicePanel(Gdiplus::Graphics& g, const Gdiplus::RectF& area) {
    th::FillRound(g, area, th::D(12), th::cPanel);
    th::StrokeRound(g, area, th::D(12), th::cBorder, 1.0f);

    Gdiplus::RectF hdr(area.X + th::D(14), area.Y + th::D(12), area.Width - th::D(28), th::D(24));
    wchar_t buf[64];
    swprintf(buf, 64, L"附近设备 (%d)", (int)devs.size());
    th::DrawText(g, buf, th::Font(th::D(13.5f), true), hdr, th::cText, 0, false, true);

    Gdiplus::RectF refresh(hdr.X + hdr.Width - th::D(48), hdr.Y + th::D(1), th::D(48), th::D(22));
    bool rh = HoverIs(HK_Refresh, 0, A_None);
    if (rh) th::FillRound(g, refresh, th::D(6), th::cHover);
    th::DrawText(g, L"刷新", th::Font(th::D(11.5f)), refresh, rh ? th::cText : th::cTextDim, 1,
                 false, true);
    AddHit(HK_Refresh, 0, A_None, refresh);

    Gdiplus::RectF list(area.X + th::D(8), area.Y + th::D(42), area.Width - th::D(16),
                        area.Height - th::D(50));
    if (devs.empty()) {
        Gdiplus::RectF t(list.X, list.Y + th::D(24), list.Width, th::D(22));
        th::DrawText(g, L"正在搜索…", th::Font(th::D(13)), t, th::cTextDim, 1, false, true);
        Gdiplus::RectF s(list.X + th::D(8), t.Y + th::D(24), list.Width - th::D(16), th::D(48));
        th::DrawText(g, L"请让手机端打开本应用\n并保持屏幕亮着", th::Font(th::D(11.5f)), s,
                     th::cTextFaint, 1, true, false);
        return;
    }

    if (scrollDev < 0) scrollDev = 0;
    const float rowH = th::D(60);
    const float gap = th::D(6);
    float y = list.Y - (float)scrollDev;
    for (const auto& d : devs) {
        Gdiplus::RectF row(list.X, y, list.Width, rowH);
        if (y + rowH > list.Y && y < list.Y + list.Height) {
            const bool sel = util::Widen(d.id) == selDevId ||
                             (selDevId.empty() && &d == &devs.front());
            const bool hv = HoverIs(HK_Device, d.ip, A_None);
            th::FillRound(g, row, th::D(9), sel ? th::cSel : (hv ? th::cHover : th::cPanel));
            if (sel) th::StrokeRound(g, row, th::D(9), th::cAccentDk, 1.0f);

            Gdiplus::RectF ic(row.X + th::D(10), row.Y + (rowH - th::D(22)) / 2, th::D(22),
                              th::D(22));
            if (d.plat == "android") th::IconPhone(g, ic, sel ? th::cAccent : th::cTextDim);
            else th::IconPC(g, ic, sel ? th::cAccent : th::cTextDim);

            Gdiplus::RectF nm(ic.X + ic.Width + th::D(8), row.Y + th::D(11),
                              row.Width - th::D(60), th::D(20));
            th::DrawText(g, d.name, th::Font(th::D(13), sel), nm, th::cText, 0, true, false);
            Gdiplus::RectF sub(nm.X, nm.Y + th::D(20), nm.Width, th::D(18));
            th::DrawText(g, DeviceSubtitle(d), th::Font(th::D(11)), sub, th::cTextFaint, 0, true,
                         false);

            if (d.trusted) {
                Gdiplus::RectF dot(row.X + row.Width - th::D(18), row.Y + row.Height / 2 - th::D(3),
                                   th::D(6), th::D(6));
                th::FillRound(g, dot, th::D(3), th::cGreen);
            }
            AddHit(HK_Device, d.ip, A_None, row);
        }
        y += rowH + gap;
    }
}

// ---- 标签页 ------------------------------------------------------------
void DrawTabs(Gdiplus::Graphics& g, const Gdiplus::RectF& area) {
    struct T { const wchar_t* label; int id; int count; };
    int recvActive = 0, sendActive = 0;
    for (const auto& j : jobViews) {
        if (!StateIsActive(j.state)) continue;
        if (j.dir == JobDir::Recv) ++recvActive; else ++sendActive;
    }
    T tabs[3] = {{L"接收", TabRecv, recvActive}, {L"发送", TabSend, sendActive}, {L"测速", TabSpeed, 0}};

    float x = area.X;
    for (int i = 0; i < 3; ++i) {
        std::wstring label = tabs[i].label;
        wchar_t cnt[32] = {0};
        if (tabs[i].count > 0) swprintf(cnt, 32, L" %d", tabs[i].count);
        label += cnt;
        const bool act = curTab == tabs[i].id;
        const bool hv = HoverIs(HK_Tab, (uint32_t)tabs[i].id, A_None);
        float w = th::TextWidth(label, th::Font(th::D(14), act)) + th::D(28);
        Gdiplus::RectF r(x, area.Y, w, area.Height);
        if (hv && !act) th::FillRound(g, Gdiplus::RectF(r.X + 2, r.Y + th::D(6), r.Width - 4,
                                                        r.Height - th::D(10)),
                                      th::D(7), th::cHover);
        th::DrawText(g, label, th::Font(th::D(14), act), r, act ? th::cText : th::cTextDim, 1, false,
                     true);
        if (act) {
            Gdiplus::RectF ind(r.X + th::D(10), area.Y + area.Height - th::D(3),
                               r.Width - th::D(20), th::D(2.5f));
            th::FillRound(g, ind, th::D(1.5f), th::cAccent);
        }
        AddHit(HK_Tab, (uint32_t)tabs[i].id, A_None, r);
        x += w;
    }

    // 右侧小工具：只在与页签不重叠时画（窄窗口下右栏只有 ~300dp，硬画会压住「测速」）
    if (curTab == TabRecv) {
        const wchar_t* label = L"清除已完成";
        float w = th::TextWidth(label, th::Font(th::D(11.5f))) + th::D(20);
        if (x + th::D(8) < area.X + area.Width - w) {   // 至少有 8dp 间隙才画
            Gdiplus::RectF r(area.X + area.Width - w, area.Y + (area.Height - th::D(24)) / 2, w,
                             th::D(24));
            bool hv = HoverIs(HK_BottomBtn, 0, A_ClearFinished);
            if (hv) th::FillRound(g, r, th::D(6), th::cHover);
            th::DrawText(g, label, th::Font(th::D(11.5f)), r, th::cTextFaint, 1, false, true);
            AddHit(HK_BottomBtn, 0, A_ClearFinished, r);
        }
    }
}

// ---- 底部栏 -----------------------------------------------------------
void DrawBottomBar(Gdiplus::Graphics& g, const Gdiplus::RectF& area) {
    float x = area.X + th::D(kPad);
    const float h = th::D(30);
    const float y = area.Y + (area.Height - h) / 2;

    struct B { const wchar_t* label; int action; };
    (void)sizeof(B);

    {
        float w = th::D(68);
        Gdiplus::RectF r(x, y, w, h);
        Button(g, r, L"设置", false, HoverIs(HK_BottomBtn, 0, 100), 0, 100, 0, HK_BottomBtn);
        x += w + th::D(8);
    }
    {
        float w = th::D(104);
        Gdiplus::RectF r(x, y, w, h);
        Button(g, r, L"打开保存目录", false, HoverIs(HK_BottomBtn, 0, 101), 0, 101, 0,
               HK_BottomBtn);
        x += w + th::D(8);
    }
    {
        float w = th::D(86);
        Gdiplus::RectF r(x, y, w, h);
        Button(g, r, L"发送文件", true, HoverIs(HK_BottomBtn, 0, 102), 0, 102, 0, HK_BottomBtn);
    }

    // 右侧速率汇总
    double total = 0;
    int active = 0;
    for (const auto& j : jobViews) {
        if (StateIsActive(j.state)) { total += j.bps; ++active; }
    }
    wchar_t buf[128];
    // %ls：MinGW 的 swprintf 里 %s 是窄字符，宽字符串只能用 %ls
    if (active > 0) {
        swprintf(buf, 128, L"%ls · %d 个任务进行中", util::FormatSpeed(total).c_str(), active);
    } else if (!statusLine.empty()) {
        swprintf(buf, 128, L"%ls", statusLine.c_str());
    } else {
        swprintf(buf, 128, L"就绪");
    }

    Gdiplus::RectF info(area.X + area.Width - th::D(300) - th::D(kPad), area.Y, th::D(300),
                        area.Height);
    th::DrawText(g, buf, th::Font(th::D(12)), info, active > 0 ? th::cTextDim : th::cTextFaint, 2,
                 true, true);
}

// ---- 绘制主流程 -------------------------------------------------------
void Paint(HWND hwnd) {
    PAINTSTRUCT ps;
    HDC hdc = BeginPaint(hwnd, &ps);
    RECT rc;
    GetClientRect(hwnd, &rc);
    const int w = rc.right, h = rc.bottom;
    if (w <= 0 || h <= 0) { EndPaint(hwnd, &ps); return; }
    EnsureBackbuffer(hdc, w, h);
    if (!g_memDC) { EndPaint(hwnd, &ps); return; }

    clientW = w;
    clientH = h;

    {
        Gdiplus::Graphics g(g_memDC);
        g.SetSmoothingMode(Gdiplus::SmoothingModeAntiAlias);
        g.SetPixelOffsetMode(Gdiplus::PixelOffsetModeHalf);
        g.SetInterpolationMode(Gdiplus::InterpolationModeHighQualityBicubic);
        g.SetTextRenderingHint(Gdiplus::TextRenderingHintClearTypeGridFit);
        g.Clear(th::C(th::cBg));

        // 壁纸层：铺满整个客户区，再叠一层按 g_scrim 调强度的主题底色暗化层
        if (th::HasWallpaper()) {
            HBITMAP wp = th::WallpaperBitmap(w, h);
            if (wp) {
                Gdiplus::Bitmap wpb(wp, nullptr);
                if (wpb.GetLastStatus() == Gdiplus::Ok) {
                    g.DrawImage(&wpb, 0, 0, w, h);
                }
                const int scrim = th::ScrimAlpha();
                if (scrim > 0) {
                    th::FillRect(g, Gdiplus::RectF(0, 0, (float)w, (float)h),
                                 th::WithAlpha(th::cBg, scrim));
                }
            } else {
                // 只报一次，避免每帧刷日志
                static bool warned = false;
                if (!warned) {
                    warned = true;
                    logx::Line("theme: wallpaper decode failed: %s",
                               util::Narrow(th::CurrentSpec().wallpaperPath).c_str());
                }
            }
        }

        hits.clear();

        Gdiplus::RectF top((float)0, 0, (float)w, (float)th::D(kTopH));
        Gdiplus::RectF bottom((float)0, (float)(h - th::D(kBottomH)), (float)w,
                              (float)th::D(kBottomH));
        DrawTopBar(g, top);

        const float bodyY = top.Height + th::D(2);
        const float bodyH = bottom.Y - bodyY - th::D(8);
        const float leftW = (float)LeftPanelWidth(w);
        Gdiplus::RectF left(th::D(kPad), bodyY, leftW, bodyH);
        Gdiplus::RectF right(left.X + left.Width + th::D(14), bodyY,
                             (float)w - (left.X + left.Width + th::D(14)) - th::D(kPad), bodyH);

        DrawDevicePanel(g, left);

        Gdiplus::RectF tabs(right.X, right.Y, right.Width, th::D(42));
        DrawTabs(g, tabs);

        Gdiplus::RectF content(right.X, tabs.Y + tabs.Height + th::D(4), right.Width,
                               right.Height - tabs.Height - th::D(4));
        switch (curTab) {
            case TabRecv: DrawRecvList(g, content); break;
            case TabSend: DrawSendList(g, content); break;
            default: DrawSpeedPanel(g, content); break;
        }

        Gdiplus::RectF bottomBar((float)0, bottom.Y, (float)w, bottom.Height);
        th::Line(g, 0, bottomBar.Y + 0.5f, (float)w, bottomBar.Y + 0.5f, th::cBorder, 1.0f);
        DrawBottomBar(g, bottomBar);
    }

    // 切换动画的旧内容叠层：GDI+ 画笔/画刷已随 Graphics 析构释放，这里改用纯 GDI 合成
    BlendAnimOverlay(g_memDC);

    BitBlt(hdc, 0, 0, w, h, g_memDC, 0, 0, SRCCOPY);
    EndPaint(hwnd, &ps);
}

// ---- 交互 -------------------------------------------------------------
void OnClick(const Hit& h) {
    switch (h.kind) {
        case HK_Tab: {
            const int from = curTab;
            curTab = (int)h.id;
            AnimateTabSwitch(from, curTab);
            Refresh();
            return;
        }
        case HK_Device: {
            for (const auto& d : devs) {
                if (d.ip == h.id) { selDevId = util::Widen(d.id); break; }
            }
            Refresh();
            return;
        }
        case HK_Refresh:
            disc::ForceProbe();
            disc::Snapshot(&devs);
            Refresh();
            return;
        case HK_Network:
            ShellExecuteW(nullptr, L"open", L"ms-settings:network-status", nullptr, nullptr,
                          SW_SHOWNORMAL);
            return;
        case HK_FixFirewall:
            fw::InstallAsync(g_app.mainWnd);
            SetStatus(L"正在申请管理员权限写入防火墙规则…");
            Refresh();
            return;
        case HK_BottomBtn:
            if (h.action == 100) { PostMessageW(g_app.mainWnd, WM_COMMAND, IDM_SETTINGS, 0); return; }
            if (h.action == 101) {
                util::EnsureDir(g_app.cfg.saveDir);
                ShellExecuteW(nullptr, L"open", g_app.cfg.saveDir.c_str(), nullptr, nullptr,
                              SW_SHOWNORMAL);
                return;
            }
            if (h.action == 102) { PostMessageW(g_app.mainWnd, WM_COMMAND, IDM_SEND_FILES, 0); return; }
            if (h.action == A_ClearFinished) {
                jobs::RemoveFinished();
                Refresh();
                return;
            }
            return;
        case HK_DropZone: {
            auto files = PickFiles(g_app.mainWnd);
            if (!files.empty()) StartSendToSelected(files);
            return;
        }
        case HK_SendBtn:
            if (h.action == A_Cancel) jobs::Cancel(h.id);
            Refresh();
            return;
        case HK_RecvBtn: {
            auto j = jobs::Find(h.id);
            if (!j) return;
            switch (h.action) {
                case A_Open: {
                    // 收到的是单文件时打开文件，收到的是目录/多文件时打开目录
                    std::wstring p = j->savePath;
                    if (p.empty()) p = j->saveDir;
                    if (p.empty()) {
                        MessageBoxW(g_app.mainWnd, L"没有找到这个任务的保存位置。", L"内网快传",
                                    MB_ICONINFORMATION);
                        break;
                    }
                    if (!util::PathExists(p)) {
                        MessageBoxW(g_app.mainWnd,
                                    (L"文件已不在原位置：\n" + p +
                                     L"\n\n可能已被移动、重命名或删除。可以用「所在文件夹」查看保存目录。")
                                        .c_str(),
                                    L"内网快传", MB_ICONWARNING);
                        break;
                    }
                    HINSTANCE r = ShellExecuteW(nullptr, L"open", p.c_str(), nullptr, nullptr,
                                                SW_SHOWNORMAL);
                    if ((INT_PTR)r <= 32) {
                        MessageBoxW(g_app.mainWnd,
                                    L"打开失败：没有可用的默认程序来打开这个文件。", L"内网快传",
                                    MB_ICONWARNING);
                    }
                    break;
                }
                case A_OpenFolder: {
                    std::wstring p = j->savePath;
                    if (p.empty() || !util::PathExists(p)) p = j->saveDir;
                    if (p.empty()) {
                        MessageBoxW(g_app.mainWnd, L"没有找到这个任务的保存位置。", L"内网快传",
                                    MB_ICONINFORMATION);
                        break;
                    }
                    if (util::IsDirPath(p)) {
                        ShellExecuteW(nullptr, L"open", p.c_str(), nullptr, nullptr, SW_SHOWNORMAL);
                    } else {
                        std::wstring args = L"/select,\"" + p + L"\"";
                        ShellExecuteW(nullptr, L"open", L"explorer.exe", args.c_str(), nullptr,
                                      SW_SHOWNORMAL);
                    }
                    break;
                }
                case A_SaveAs: {
                    std::wstring dir;
                    if (PickFolder(g_app.mainWnd, &dir)) {
                        std::wstring outPath;
                        std::wstring src = j->savePath;
                        if (src.empty()) src = j->saveDir;
                        if (!src.empty() && storage::MoveToSaveDir(src, dir, &outPath)) {
                            j->savePath = outPath;
                            j->saveDir = util::ParentOf(outPath);
                            cfg::HistRow hr;
                            hr.title = j->title;
                            hr.savePath = outPath;
                            hr.device = j->dev.name;
                            hr.timeText = util::NowTimeText();
                            hr.bytes = j->totalBytes;
                            hr.dir = 0;
                            hr.state = 3;
                            cfg::HistoryAppend(hr);
                            SetStatus(L"已另存到 " + outPath);
                        } else {
                            MessageBoxW(g_app.mainWnd, L"另存失败，目标位置可能不可写。", L"内网快传",
                                        MB_ICONWARNING);
                        }
                    }
                    Refresh();
                    break;
                }
                case A_Cancel:
                    jobs::Cancel(h.id);
                    break;
                case A_Remove:
                    jobs::Remove(h.id);
                    break;
                default:
                    break;
            }
            Refresh();
            return;
        }
        case HK_SpeedDir:
            speedDir = h.action;
            Refresh();
            return;
        case HK_SpeedRun:
            if (speed::IsRunning()) {
                speed::Stop();
            } else {
                const Device* d = SelectedDevice();
                if (!d) {
                    MessageBoxW(g_app.mainWnd, L"还没有发现设备，无法测速。", L"内网快传",
                                MB_ICONINFORMATION);
                    return;
                }
                speed::Params p;
                p.ip = d->ip;
                p.port = d->port;
                p.conns = g_app.cfg.speedConns;
                p.secs = g_app.cfg.speedSecs;
                p.dirMode = speedDir;
                speedSamples.clear();
                speed::Start(p, d->name);
            }
            Refresh();
            return;
        default:
            return;
    }
}

void OnWheel(int delta, int mouseY) {
    const int step = th::D(56);
    const int dir = delta > 0 ? -1 : 1;
    if (curTab == TabRecv) {
        scrollRecv += dir * step;
        if (scrollRecv < 0) scrollRecv = 0;
    } else if (curTab == TabSend) {
        scrollSend += dir * step;
        if (scrollSend < 0) scrollSend = 0;
    }
    (void)mouseY;
    Refresh();
}

// ---- 数据刷新 ---------------------------------------------------------
void RefreshData() {
    static bool prompting = false;
    disc::Snapshot(&devs);
    jobs::Snapshot(&jobViews);
    for (const auto& j : jobViews) {
        if (StateIsActive(j.state)) {
            auto jp = jobs::Find(j.id);
            if (jp) jobs::NoteProgressTick(jp);
        }
    }
    if (!devs.empty() && selDevId.empty()) selDevId = util::Widen(devs.front().id);
    if (!devs.empty()) {
        bool still = false;
        for (const auto& d : devs) {
            if (util::Widen(d.id) == selDevId) still = true;
        }
        if (!still) selDevId = util::Widen(devs.front().id);
    }

    // 接收确认
    if (prompting) return;
    for (const auto& v : jobViews) {
        if (v.dir != JobDir::Recv) continue;
        if (v.state != JobState::WaitingAccept) continue;
        auto jp = jobs::Find(v.id);
        if (!jp || jp->promptShown) continue;
        jp->promptShown = true;
        prompting = true;
        std::wstring msg = L"「" + v.deviceName + L"」想发送 " +
                           std::to_wstring(v.totalFiles) + L" 个项目（" +
                           util::FormatBytes(v.totalBytes) + L"）。\n\n是否接收？";
        int r = MessageBoxW(g_app.mainWnd, msg.c_str(), L"接收文件确认",
                            MB_YESNO | MB_ICONQUESTION | MB_DEFBUTTON1);
        if (r == IDYES) jp->acceptFlag = true;
        else jp->cancel = true;
        prompting = false;
        break;
    }
}

static void UpdateHover(int x, int y) {
    const Hit* h = FindHit(x, y);
    int k = h ? h->kind : HK_None;
    uint32_t id = h ? h->id : 0;
    int a = h ? h->action : A_None;
    if (k != hoverKind || id != hoverId || a != hoverAction) {
        hoverKind = k;
        hoverId = id;
        hoverAction = a;
        Refresh();
    }
}

static void AddPathsAndSend(const std::vector<std::wstring>& paths) {
    StartSendToSelected(paths);
}

// ---- 窗口过程 --------------------------------------------------------
LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
        case WM_CREATE: {
            g_app.mainWnd = hwnd;
            ApplyTitleBar(hwnd);
            DragAcceptFiles(hwnd, TRUE);
            SetTimer(hwnd, TIMER_UI, 200, nullptr);
            RefreshData();
            return 0;
        }
        case WM_SIZE:
            CancelTabAnim();  // 尺寸变了快照就失效，直接取消动画
            FreeBackbuffer();
            InvalidateRect(hwnd, nullptr, FALSE);
            return 0;
        case WM_ERASEBKGND:
            return 1;
        case WM_PAINT:
            Paint(hwnd);
            return 0;
        case WM_MOUSEMOVE: {
            TRACKMOUSEEVENT tme{sizeof(tme), TME_LEAVE, hwnd, 0};
            TrackMouseEvent(&tme);
            UpdateHover(GET_X_LPARAM(lp), GET_Y_LPARAM(lp));
            return 0;
        }
        case WM_MOUSELEAVE:
            if (hoverKind != HK_None) {
                hoverKind = HK_None;
                hoverId = 0;
                hoverAction = A_None;
                Refresh();
            }
            return 0;
        case WM_LBUTTONDOWN: {
            const Hit* h = FindHit(GET_X_LPARAM(lp), GET_Y_LPARAM(lp));
            if (h) {
                Hit copy = *h;
                OnClick(copy);
            }
            return 0;
        }
        case WM_MOUSEWHEEL:
            OnWheel(GET_WHEEL_DELTA_WPARAM(wp), GET_Y_LPARAM(lp));
            return 0;
        case WM_TIMER:
            if (wp == TIMER_UI) {
                RefreshData();
                const bool active = jobs::AnyActive() || speed::IsRunning();
                SetTimer(hwnd, TIMER_UI, active ? 120 : 300, nullptr);
                if (active || curTab == TabSpeed) InvalidateRect(hwnd, nullptr, FALSE);
            } else if (wp == TIMER_ANIM) {
                if (!AnimStillRunning()) {
                    KillTimer(hwnd, TIMER_ANIM);
                    FreeAnimSnapshot();
                }
                InvalidateRect(hwnd, nullptr, FALSE);
            }
            return 0;
        case WM_DROPFILES: {
            HDROP hd = (HDROP)wp;
            UINT n = DragQueryFileW(hd, 0xFFFFFFFF, nullptr, 0);
            std::vector<std::wstring> paths;
            for (UINT i = 0; i < n; ++i) {
                wchar_t buf[32768];
                UINT c = DragQueryFileW(hd, i, buf, 32768);
                if (c > 0) paths.emplace_back(buf, c);
            }
            DragFinish(hd);
            AddPathsAndSend(paths);
            return 0;
        }
        case WM_DPICHANGED: {
            UINT dpi = HIWORD(wp);
            ApplyDpi(dpi);
            RECT* r = (RECT*)lp;
            SetWindowPos(hwnd, nullptr, r->left, r->top, r->right - r->left, r->bottom - r->top,
                         SWP_NOZORDER | SWP_NOACTIVATE);
            return 0;
        }
        case WM_APP_WAKE:
            RefreshData();
            InvalidateRect(hwnd, nullptr, FALSE);
            return 0;
        case WM_APP_FW_DONE: {
            bool ok = wp != 0;
            g_app.firewallOk = ok;
            if (ok) SetStatus(L"防火墙规则已写入");
            else SetStatus(L"防火墙规则写入失败");
            Refresh();
            return 0;
        }
        case WM_SETTINGCHANGE:
            ApplyTitleBar(hwnd);
            return 0;
        case WM_COPYDATA: {
            auto* cds = (COPYDATASTRUCT*)lp;
            if (cds && cds->dwData == 1 && cds->lpData && cds->cbData > 0) {
                const wchar_t* p = (const wchar_t*)cds->lpData;
                const size_t n = cds->cbData / sizeof(wchar_t);
                std::wstring all(p, n);
                std::vector<std::wstring> paths;
                size_t start = 0;
                while (start <= all.size()) {
                    size_t pos = all.find(L'\n', start);
                    std::wstring item = (pos == std::wstring::npos) ? all.substr(start)
                                                                   : all.substr(start, pos - start);
                    if (!item.empty()) paths.push_back(item);
                    if (pos == std::wstring::npos) break;
                    start = pos + 1;
                }
                if (!paths.empty()) StartSendToSelected(paths);
            }
            return TRUE;
        }
        case WM_APP_IPC_SEND:
            if (!g_app.pendingSendPaths.empty()) {
                auto paths = g_app.pendingSendPaths;
                g_app.pendingSendPaths.clear();
                StartSendToSelected(paths);
            }
            return 0;
        case WM_COMMAND: {
            const UINT id = LOWORD(wp);
            switch (id) {
                case IDM_SEND_FILES: {
                    auto files = PickFiles(hwnd);
                    if (!files.empty()) StartSendToSelected(files);
                    return 0;
                }
                case IDM_OPEN_SAVE_DIR:
                    util::EnsureDir(g_app.cfg.saveDir);
                    ShellExecuteW(nullptr, L"open", g_app.cfg.saveDir.c_str(), nullptr, nullptr,
                                  SW_SHOWNORMAL);
                    return 0;
                case IDM_SETTINGS:
                    OpenSettings(hwnd);
                    return 0;
                case IDM_AUTOSTART: {
                    bool on = !cfg::GetAutoStart();
                    cfg::SetAutoStart(on);
                    g_app.cfg.autoStart = on;
                    cfg::Save();
                    return 0;
                }
                case IDM_FIX_FIREWALL:
                    fw::InstallAsync(hwnd);
                    return 0;
                case IDM_ABOUT:
                    MessageBoxW(hwnd,
                                L"内网快传 LanDrop " LANDROP_VERSION_W L"\n\n"
                                L"Windows ⇄ Android 局域网文件互传与网速测试。\n"
                                L"零配置发现 · 断点续传 · 不经过任何服务器。\n\n"
                                L"协议 v1 · 端口 UDP 47820 / TCP 47821",
                                L"关于", MB_ICONINFORMATION);
                    return 0;
                case IDM_EXIT:
                    DestroyWindow(hwnd);
                    return 0;
                default:
                    return 0;
            }
        }
        case WM_CLOSE:
            if (g_app.cfg.minimizeToTray) {
                ShowWindow(hwnd, SW_HIDE);
                return 0;
            }
            DestroyWindow(hwnd);
            return 0;
        case WM_DESTROY:
            KillTimer(hwnd, TIMER_UI);
            KillTimer(hwnd, TIMER_ANIM);
            FreeAnimSnapshot();
            FreeBackbuffer();
            g_app.mainWnd = nullptr;
            PostQuitMessage(0);
            return 0;
        default:
            break;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

bool Create(HINSTANCE inst) {
    WNDCLASSEXW wc{};
    wc.cbSize = sizeof(wc);
    wc.style = CS_HREDRAW | CS_VREDRAW;
    wc.lpfnWndProc = WndProc;
    wc.hInstance = inst;
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wc.hIcon = LoadIconW(inst, MAKEINTRESOURCEW(IDI_APP));
    wc.hIconSm = wc.hIcon;
    wc.lpszClassName = LANDROP_WND_CLASS;
    if (!RegisterClassExW(&wc)) {
        if (GetLastError() != ERROR_CLASS_ALREADY_EXISTS) return false;
    }

    UINT dpi = 96;
    HMODULE user32 = GetModuleHandleW(L"user32.dll");
    if (user32) {
        // 强制 Per-Monitor V2：即便 manifest 未生效也不会被 DPI 虚拟化
        typedef BOOL(WINAPI * FnSetCtx)(void*);
        auto setCtx = (FnSetCtx)(void*)GetProcAddress(user32, "SetProcessDpiAwarenessContext");
        if (setCtx) setCtx((void*)-4);
    }
    th::g_dpi = 96;

    RECT wr{0, 0, 1120, 720};
    AdjustWindowRectEx(&wr, WS_OVERLAPPEDWINDOW, FALSE, 0);
    int ww = wr.right - wr.left, wh = wr.bottom - wr.top;

    int sw = GetSystemMetrics(SM_CXSCREEN), sh = GetSystemMetrics(SM_CYSCREEN);
    int x = (sw - ww) / 2, y = (sh - wh) / 2;
    if (x < 0) x = 0;
    if (y < 0) y = 0;

    HWND hwnd = CreateWindowExW(0, LANDROP_WND_CLASS, LANDROP_APP_NAME, WS_OVERLAPPEDWINDOW, x, y,
                                ww, wh, nullptr, nullptr, inst, nullptr);
    if (!hwnd) return false;

    // 创建后再按真实 DPI 调整尺寸（PerMonitorV2 下这才是准确值）
    if (user32) {
        typedef UINT(WINAPI * FnGetDpi)(HWND);
        auto fn = (FnGetDpi)(void*)GetProcAddress(user32, "GetDpiForWindow");
        if (fn) dpi = fn(hwnd);
    }
    if (dpi == 0) dpi = 96;
    // --dpi N（仅测试用）强制覆盖缩放；否则跟随窗口真实 DPI
    if (th::g_dpiForce > 0) dpi = (UINT)th::g_dpiForce;
    th::g_dpi = (int)dpi;
    if (dpi != 96) {
        // 高 DPI 下按缩放后尺寸重排，但不超出屏幕可用区域
        int cw = MulDiv(1120, (int)dpi, 96), chh = MulDiv(720, (int)dpi, 96);
        if (cw > (int)(sw * 0.94)) cw = (int)(sw * 0.94);
        if (chh > (int)(sh * 0.90)) chh = (int)(sh * 0.90);
        RECT r2{0, 0, cw, chh};
        AdjustWindowRectEx(&r2, WS_OVERLAPPEDWINDOW, FALSE, 0);
        int w2 = r2.right - r2.left, h2 = r2.bottom - r2.top;
        int x2 = (sw - w2) / 2, y2 = (sh - h2) / 2;
        if (x2 < 0) x2 = 0;
        if (y2 < 0) y2 = 0;
        SetWindowPos(hwnd, nullptr, x2, y2, w2, h2, SWP_NOZORDER | SWP_NOACTIVATE);
    }
    ApplyTitleBar(hwnd);  // 主题在窗口创建后才生效（WM_CREATE 里也调过一次）
    logx::Line("ui: dpi=%u screen=%dx%d", dpi, sw, sh);

    ShowWindow(hwnd, SW_SHOW);
    UpdateWindow(hwnd);
    return true;
}

void Destroy() {
    if (g_app.mainWnd) DestroyWindow(g_app.mainWnd);
}

}  // namespace ui
