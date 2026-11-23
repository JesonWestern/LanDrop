#include "theme.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cwchar>

using namespace Gdiplus;

namespace th {

int g_dpi = 96;
int g_dpiForce = 0;
int g_scrim = 55;

// ---- 定义色板全局量 -------------------------------------------------------
// 这里是唯一真源：颜色是**变量**，由 ApplyTheme 运行期写入，67 处 th::cXxx 零改动。
Palette g_pal = Palette{false,
                        1.0f,
                        0xFF101215u,
                        0xFF171A1Fu,
                        0xFF1E2228u,
                        0xFF262B33u,
                        0xFF2E343Du,
                        0xFF5A6472u,
                        0xFFE6E9EFu,
                        0xFF9AA1ACu,
                        0xFF8A93A0u,
                        0xFF4C8DF6u,
                        0xFF2F5FA8u,
                        0xFFFFFFFFu,
                        0xFF57C98Au,
                        0xFFE5645Fu,
                        0xFFE2B34Du,
                        0xFF9B7BF0u,
                        0xFFE0904Fu,
                        0xFF4FC1D9u,
                        0x18FFFFFFu,
                        0x2B4C8DF6u};

uint32_t cBg = g_pal.bg;
uint32_t cPanel = g_pal.panel;
uint32_t cPanel2 = g_pal.panel2;
uint32_t cPanel3 = g_pal.panel3;
uint32_t cBorder = g_pal.border;
uint32_t cBorder2 = g_pal.border2;
uint32_t cText = g_pal.text;
uint32_t cTextDim = g_pal.textDim;
uint32_t cTextFaint = g_pal.textFaint;
uint32_t cAccent = g_pal.accent;
uint32_t cAccentDk = g_pal.accentDk;
uint32_t cOnAccent = g_pal.onAccent;
uint32_t cGreen = g_pal.green;
uint32_t cRed = g_pal.red;
uint32_t cAmber = g_pal.amber;
uint32_t cPurple = g_pal.purple;
uint32_t cOrange = g_pal.orange;
uint32_t cCyan = g_pal.cyan;
uint32_t cHover = g_pal.hover;
uint32_t cSel = g_pal.sel;

namespace {

struct PresetDef {
    const wchar_t* id;
    const wchar_t* label;
    bool light;
    uint32_t bg, panel, panel2, panel3, border, border2;
    uint32_t text, textDim, textFaint, accent;
    uint32_t green, red, amber, purple, orange, cyan;
};

// 数值逐个照抄 android/app/src/main/java/com/landrop/transfer/ui/ThemePresets.kt
// 顺序即设置页展示顺序，graphite 为默认值。
const PresetDef kPresets[] = {
    {L"graphite", L"石墨灰", false,
     0xFF101215u, 0xFF171A1Fu, 0xFF1E2228u, 0xFF262B33u, 0xFF2E343Du, 0xFF5A6472u,
     0xFFE6E9EFu, 0xFF9AA1ACu, 0xFF8A93A0u, 0xFF4C8DF6u,
     0xFF57C98Au, 0xFFE5645Fu, 0xFFE2B34Du, 0xFF9B7BF0u, 0xFFE0904Fu, 0xFF4FC1D9u},
    {L"black", L"纯黑", false,
     0xFF000000u, 0xFF0A0A0Bu, 0xFF141416u, 0xFF1E1E21u, 0xFF2A2A2Eu, 0xFF4E4E56u,
     0xFFF2F3F5u, 0xFFA8ACB4u, 0xFF82868Eu, 0xFF4C8DF6u,
     0xFF57C98Au, 0xFFE5645Fu, 0xFFE2B34Du, 0xFF9B7BF0u, 0xFFE0904Fu, 0xFF4FC1D9u},
    {L"white", L"纯白", true,
     0xFFFFFFFFu, 0xFFFFFFFFu, 0xFFF2F4F7u, 0xFFE7EBF0u, 0xFFDCE1E8u, 0xFFAAB2BDu,
     0xFF14171Cu, 0xFF5A6270u, 0xFF7C8592u, 0xFF2264D8u,
     0xFF1E8E4Eu, 0xFFC93A34u, 0xFFB47A15u, 0xFF6D46C4u, 0xFFC4692Au, 0xFF1E7F94u},
    {L"silver", L"银灰", true,
     0xFFEDEFF3u, 0xFFF8F9FBu, 0xFFE4E7ECu, 0xFFD9DDE4u, 0xFFCFD4DCu, 0xFF9BA3AFu,
     0xFF1B1F25u, 0xFF565E6Bu, 0xFF78818Fu, 0xFF2D6DD6u,
     0xFF1F8A4Cu, 0xFFC43D37u, 0xFFAE7717u, 0xFF6A48BEu, 0xFFBE6628u, 0xFF1B7C90u},
    {L"ocean", L"深海蓝", false,
     0xFF071A2Bu, 0xFF0C2337u, 0xFF123049u, 0xFF193D5Cu, 0xFF1F4A6Bu, 0xFF3C7799u,
     0xFFE4EFF7u, 0xFF95B2C6u, 0xFF7E9DB4u, 0xFF39B6E8u,
     0xFF3FD29Bu, 0xFFE5645Fu, 0xFFE2B34Du, 0xFF9B7BF0u, 0xFFE0904Fu, 0xFF4FC1D9u},
    {L"forest", L"松林绿", false,
     0xFF0B1710u, 0xFF112116u, 0xFF16291Cu, 0xFF1E3624u, 0xFF27422Eu, 0xFF4A6E52u,
     0xFFE6F0E8u, 0xFF9CB3A2u, 0xFF84A08Bu, 0xFF5FD08Au,
     0xFF7BE0A6u, 0xFFE5645Fu, 0xFFE2B34Du, 0xFF9B7BF0u, 0xFFE0904Fu, 0xFF4FC1D9u},
    {L"sunset", L"落日橙", false,
     0xFF1A0F0Au, 0xFF241510u, 0xFF302016u, 0xFF3D2A1Du, 0xFF4B3424u, 0xFF86563Au,
     0xFFF7ECE4u, 0xFFC0A493u, 0xFFA88E7Eu, 0xFFFF9142u,
     0xFF57C98Au, 0xFFE5645Fu, 0xFFFFC24Du, 0xFF9B7BF0u, 0xFFFF9142u, 0xFF4FC1D9u},
    {L"violet", L"夜紫", false,
     0xFF120C1Cu, 0xFF1A1227u, 0xFF241933u, 0xFF2F2242u, 0xFF3D2C54u, 0xFF6C549Bu,
     0xFFF0EAF6u, 0xFFB2A3C4u, 0xFF9A8CADu, 0xFFB07BF5u,
     0xFF57C98Au, 0xFFE5645Fu, 0xFFE2B34Du, 0xFFC9A0FFu, 0xFFE0904Fu, 0xFF4FC1D9u},
    {L"rose", L"玫瑰紫", false,
     0xFF1A0E14u, 0xFF24141Cu, 0xFF321C26u, 0xFF412532u, 0xFF52303Fu, 0xFF8F5468u,
     0xFFF9EBF0u, 0xFFC6A2AFu, 0xFFAE8B98u, 0xFFF2649Au,
     0xFF57C98Au, 0xFFF06A6Au, 0xFFE2B34Du, 0xFF9B7BF0u, 0xFFE0904Fu, 0xFF4FC1D9u},
    {L"mint", L"薄荷青", true,
     0xFFEEF6F4u, 0xFFFAFDFCu, 0xFFE2EFEBu, 0xFFD5E6E1u, 0xFFC7DBD5u, 0xFF94AAA4u,
     0xFF12211Du, 0xFF4F6762u, 0xFF71887Fu, 0xFF0E8C7Au,
     0xFF16824Fu, 0xFFC33F3Au, 0xFFA97612u, 0xFF6446BCu, 0xFFBB6527u, 0xFF12798Cu},
};

const size_t kPresetCount = sizeof(kPresets) / sizeof(kPresets[0]);

const PresetDef* FindPreset(const std::wstring& id) {
    for (size_t i = 0; i < kPresetCount; ++i) {
        if (id == kPresets[i].id) return &kPresets[i];
    }
    return nullptr;
}

// 12 个强调色（ARGB），与 Android LdAccentSwatches 一致
const std::vector<uint32_t> kAccents = {
    0xFF4C8DF6u, 0xFF39B6E8u, 0xFF0E8C7Au, 0xFF5FD08Au, 0xFF7BE0A6u, 0xFFE2B34Du,
    0xFFFF9142u, 0xFFF2649Au, 0xFFE5645Fu, 0xFFB07BF5u, 0xFF9B7BF0u, 0xFF9AA1ACu};

// 运行期状态
bool g_inited = false;
ThemeSpec g_spec;

// ---- 缓存 ---------------------------------------------------------------
std::unordered_map<uint64_t, Gdiplus::Font*> g_fonts;
std::unordered_map<uint32_t, SolidBrush*> g_brushes;
std::unordered_map<uint64_t, Pen*> g_pens;
std::unordered_map<uint64_t, float> g_textW;

HBITMAP g_wpBmp = nullptr;
int g_wpW = 0;
int g_wpH = 0;
std::wstring g_wpPath;
std::wstring g_wpSrc;  // 生成 g_wpBmp 时用的源路径

void FreeWallpaperBmp() {
    if (g_wpBmp) DeleteObject(g_wpBmp);
    g_wpBmp = nullptr;
    g_wpW = g_wpH = 0;
    g_wpSrc.clear();
}

const wchar_t* UiFamily() {
    static int cached = -1;  // -1 未知 0 Segoe 1 YaHei
    if (cached < 0) {
        FontFamily f(L"Microsoft YaHei UI");
        cached = (f.GetLastStatus() == Ok) ? 1 : 0;
    }
    return cached == 1 ? L"Microsoft YaHei UI" : L"Segoe UI";
}

uint64_t FontKey(int px, int style) { return ((uint64_t)px << 4) | (uint64_t)style; }

SolidBrush* Brush(uint32_t argb) {
    auto it = g_brushes.find(argb);
    if (it != g_brushes.end()) return it->second;
    auto* b = new SolidBrush(Color(argb));
    g_brushes[argb] = b;
    return b;
}

Pen* PenOf(uint32_t argb, float w) {
    // 宽度量化到 0.5，避免缓存爆炸
    int q = (int)(w * 2.0f + 0.5f);
    uint64_t key = ((uint64_t)argb << 8) | (uint64_t)q;
    auto it = g_pens.find(key);
    if (it != g_pens.end()) return it->second;
    auto* p = new Pen(Color(argb), q / 2.0f);
    g_pens[key] = p;
    return p;
}

// 颜色/主题变化后必须清掉按 argb 做 key 的画笔与画刷缓存，否则旧色残留。
// 字体按字号做 key、文本宽度按"字体指针+字符串"做 key，与颜色无关，一并清空无副作用。
// ⚠️ 会析构 GDI+ 对象，只能在 GdiplusStartup 之后（即 th::Init 之后）调用；
//    未初始化时（例如 CLI --selftest）直接跳过，避免在 GDI+ 未启动时调 GdipDelete*。
void ClearColorCaches() {
    if (!g_inited) return;
    for (auto& kv : g_brushes) delete kv.second;
    g_brushes.clear();
    for (auto& kv : g_pens) delete kv.second;
    g_pens.clear();
    g_textW.clear();
}

GraphicsPath* RoundPath(const RectF& rc, float r) {
    auto* p = new GraphicsPath();
    float d = r * 2.0f;
    if (d > rc.Width) d = rc.Width;
    if (d > rc.Height) d = rc.Height;
    if (d <= 0.5f) {
        p->AddRectangle(rc);
        return p;
    }
    p->AddArc(rc.X, rc.Y, d, d, 180.0f, 90.0f);
    p->AddArc(rc.X + rc.Width - d, rc.Y, d, d, 270.0f, 90.0f);
    p->AddArc(rc.X + rc.Width - d, rc.Y + rc.Height - d, d, d, 0.0f, 90.0f);
    p->AddArc(rc.X, rc.Y + rc.Height - d, d, d, 90.0f, 90.0f);
    p->CloseFigure();
    return p;
}
}  // namespace

// ---- 纯函数 -------------------------------------------------------------
uint32_t Shade(uint32_t argb, float f) {
    const int a = (int)((argb >> 24) & 0xFF);
    const int r = (int)((argb >> 16) & 0xFF);
    const int g = (int)((argb >> 8) & 0xFF);
    const int b = (int)(argb & 0xFF);
    int rr, gg, bb;
    if (f < 1.0f) {
        rr = (int)((float)r * f);
        gg = (int)((float)g * f);
        bb = (int)((float)b * f);
    } else if (f > 1.0f) {
        const float t = f - 1.0f;
        rr = (int)((float)r + (255.0f - (float)r) * t);
        gg = (int)((float)g + (255.0f - (float)g) * t);
        bb = (int)((float)b + (255.0f - (float)b) * t);
    } else {
        rr = r;
        gg = g;
        bb = b;
    }
    rr = (std::min)(255, (std::max)(0, rr));
    gg = (std::min)(255, (std::max)(0, gg));
    bb = (std::min)(255, (std::max)(0, bb));
    return ((uint32_t)a << 24) | ((uint32_t)rr << 16) | ((uint32_t)gg << 8) | (uint32_t)bb;
}

uint32_t OnAccentFor(uint32_t accentArgb) {
    // 与 Android onColorFor 同口径：WCAG 相对亮度，取黑/白中对比度更高者
    auto lum = [](int c) {
        const float v = (float)c / 255.0f;
        const float lin = v <= 0.03928f ? v / 12.92f : (float)std::pow(((v + 0.055f) / 1.055f), 2.4);
        return lin;
    };
    const float l = 0.2126f * lum((int)((accentArgb >> 16) & 0xFF)) +
                    0.7152f * lum((int)((accentArgb >> 8) & 0xFF)) +
                    0.0722f * lum((int)(accentArgb & 0xFF));
    const float withBlack = (l + 0.05f) / 0.05f;
    const float withWhite = 1.05f / (l + 0.05f);
    return (withBlack >= withWhite) ? 0xFF000000u : 0xFFFFFFFFu;
}

uint32_t AccentDkFor(uint32_t accentArgb, bool light) {
    return Shade((accentArgb & 0x00FFFFFFu) | 0xFF000000u, light ? 0.82f : 0.62f);
}

int ClampScrim(int v) { return v < 0 ? 0 : (v > 80 ? 80 : v); }

std::wstring DefaultThemeId() { return L"graphite"; }

std::wstring NormalizeThemeId(const std::wstring& id) {
    const PresetDef* p = FindPreset(id);
    return p ? std::wstring(p->id) : DefaultThemeId();
}

std::vector<std::pair<std::wstring, std::wstring>> Presets() {
    std::vector<std::pair<std::wstring, std::wstring>> out;
    out.reserve(kPresetCount);
    for (size_t i = 0; i < kPresetCount; ++i) {
        out.emplace_back(std::wstring(kPresets[i].id), std::wstring(kPresets[i].label));
    }
    return out;
}

const std::vector<uint32_t>& AccentSwatches() { return kAccents; }

Palette PaletteOf(const std::wstring& id, uint32_t accentArgb) {
    const PresetDef* p = FindPreset(NormalizeThemeId(id));
    Palette pal{};
    pal.light = p->light;
    pal.panelAlpha = 1.0f;  // 面板保持不透明：壁纸从面板缝隙透出
    pal.bg = p->bg;
    pal.panel = p->panel;
    pal.panel2 = p->panel2;
    pal.panel3 = p->panel3;
    pal.border = p->border;
    pal.border2 = p->border2;
    pal.text = p->text;
    pal.textDim = p->textDim;
    pal.textFaint = p->textFaint;
    pal.accent = accentArgb ? ((accentArgb & 0x00FFFFFFu) | 0xFF000000u) : p->accent;
    pal.accentDk = AccentDkFor(pal.accent, pal.light);
    pal.onAccent = OnAccentFor(pal.accent);
    pal.green = p->green;
    pal.red = p->red;
    pal.amber = p->amber;
    pal.purple = p->purple;
    pal.orange = p->orange;
    pal.cyan = p->cyan;
    // hover/sel 与 Android 一致：用固定基色的低 alpha 叠加
    pal.hover = p->light ? 0x14000000u : 0x18FFFFFFu;
    pal.sel = p->light ? 0x1F4C8DF6u : 0x2B4C8DF6u;
    return pal;
}

float EaseOutCubic(float t) {
    if (t <= 0.0f) return 0.0f;
    if (t >= 1.0f) return 1.0f;
    const float u = 1.0f - t;
    return 1.0f - u * u * u;
}

int ScrimAlpha() { return (ClampScrim(g_scrim) * 255 + 50) / 100; }

uint32_t TintBg(uint32_t base) {
    // 浅色主题上低 alpha 的深色 tint 会更"脏"，改用稍高一点的 alpha 才看得清底
    return WithAlpha(base, g_pal.light ? 0x1F : 0x33);
}

uint32_t TintText(uint32_t base) {
    // 深色主题提亮到能压在暗底上；浅色主题压暗到能压在亮底上
    return g_pal.light ? Shade(base, 0.62f) : Shade(base, 1.55f);
}

std::wstring ThemeSpec::toAccentDk() {
    const Palette pal = PaletteOf(id, accentArgb);
    wchar_t buf[16];
    swprintf(buf, 16, L"%08X", (unsigned int)pal.accentDk);
    return buf;
}

// ---- 运行期 -------------------------------------------------------------
bool ApplyTheme(const ThemeSpec& in) {
    ThemeSpec spec = in;
    spec.id = NormalizeThemeId(spec.id);          // 未知 id 回落 graphite
    spec.scrim = ClampScrim(spec.scrim);
    const Palette pal = PaletteOf(spec.id, spec.accentArgb);

    g_scrim = spec.scrim;
    g_pal = pal;

    cBg = pal.bg;
    cPanel = pal.panel;
    cPanel2 = pal.panel2;
    cPanel3 = pal.panel3;
    cBorder = pal.border;
    cBorder2 = pal.border2;
    cText = pal.text;
    cTextDim = pal.textDim;
    cTextFaint = pal.textFaint;
    cAccent = pal.accent;
    cAccentDk = pal.accentDk;
    cOnAccent = pal.onAccent;
    cGreen = pal.green;
    cRed = pal.red;
    cAmber = pal.amber;
    cPurple = pal.purple;
    cOrange = pal.orange;
    cCyan = pal.cyan;
    cHover = pal.hover;
    cSel = pal.sel;

    // 颜色变了 → 按 argb 做 key 的画笔/画刷缓存必须整体丢弃
    ClearColorCaches();

    SetWallpaper(spec.wallpaperPath);
    g_spec = spec;

    // 诊断：主题生效点（设置页保存后立即生效也走这里）
    logx::Line("theme: apply id=%s light=%d accent=%08X onAccent=%08X dk=%08X scrim=%d wp=%s",
               util::Narrow(spec.id).c_str(), pal.light ? 1 : 0, (unsigned int)pal.accent,
               (unsigned int)pal.onAccent, (unsigned int)pal.accentDk, spec.scrim,
               spec.wallpaperPath.empty() ? "(none)" : "yes");
    return true;
}

bool ApplyFromConfig() {
    ThemeSpec spec;
    spec.id = g_app.cfg.theme;
    spec.accentArgb = g_app.cfg.themeAccent;
    spec.wallpaperPath = g_app.cfg.themeWallpaper;
    spec.scrim = g_app.cfg.themeScrim;
    spec.animations = g_app.cfg.themeAnimations;
    return ApplyTheme(spec);
}

const ThemeSpec& CurrentSpec() { return g_spec; }
ThemeSpec& MutableSpec() { return g_spec; }

void SetWallpaper(const std::wstring& path) {
    if (g_wpPath == path) return;
    g_wpPath = path;
    FreeWallpaperBmp();
}

bool HasWallpaper() {
    return !g_wpPath.empty() && util::PathExists(g_wpPath);
}

HBITMAP WallpaperBitmap(int w, int h) {
    if (w <= 0 || h <= 0) return nullptr;
    if (g_wpPath.empty()) return nullptr;
    if (g_wpBmp && g_wpW == w && g_wpH == h && g_wpSrc == g_wpPath) return g_wpBmp;

    FreeWallpaperBmp();
    if (!util::PathExists(g_wpPath)) return nullptr;

    Gdiplus::Bitmap src(g_wpPath.c_str());
    if (src.GetLastStatus() != Gdiplus::Ok) return nullptr;
    const int sw = (int)src.GetWidth(), sh = (int)src.GetHeight();
    if (sw <= 0 || sh <= 0) return nullptr;

    // cover 缩放：铺满窗口，多出来的部分居中裁掉
    const float scale = (std::max)((float)w / (float)sw, (float)h / (float)sh);
    const float dw = (float)sw * scale, dh = (float)sh * scale;
    const float dx = ((float)w - dw) / 2.0f, dy = ((float)h - dh) / 2.0f;

    HDC dc = CreateCompatibleDC(nullptr);
    if (!dc) return nullptr;
    BITMAPINFO bi{};
    bi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    bi.bmiHeader.biWidth = w;
    bi.bmiHeader.biHeight = -h;  // top-down
    bi.bmiHeader.biPlanes = 1;
    bi.bmiHeader.biBitCount = 32;
    bi.bmiHeader.biCompression = BI_RGB;
    void* bits = nullptr;
    HBITMAP hbm = CreateDIBSection(dc, &bi, DIB_RGB_COLORS, &bits, nullptr, 0);
    if (!hbm) {
        DeleteDC(dc);
        return nullptr;
    }
    HGDIOBJ old = SelectObject(dc, hbm);
    {
        Gdiplus::Graphics g(dc);
        g.SetInterpolationMode(Gdiplus::InterpolationModeHighQualityBicubic);
        g.SetPixelOffsetMode(Gdiplus::PixelOffsetModeHalf);
        g.SetCompositingMode(Gdiplus::CompositingModeSourceCopy);
        // 先把底铺成主题底色，避免缩放后边缘出现透明像素
        g.Clear(th::C((cBg & 0x00FFFFFFu) | 0xFF000000u));
        g.DrawImage(&src, Gdiplus::RectF(dx, dy, dw, dh));
    }
    SelectObject(dc, old);
    DeleteDC(dc);

    // DIB 的 alpha 字节必须全部置 0xFF：GDI+ 再把 HBITMAP 包成 Bitmap 时会认真读 alpha，
    // 源图带透明区域或 GDI 绘制留下的 0 会让壁纸整片"消失"。
    if (bits) {
        auto* px = (uint8_t*)bits;
        const size_t count = (size_t)w * (size_t)h;
        for (size_t i = 0; i < count; ++i) px[i * 4 + 3] = 0xFF;
    }

    g_wpBmp = hbm;
    g_wpW = w;
    g_wpH = h;
    g_wpSrc = g_wpPath;
    return g_wpBmp;
}

void Init() {
    if (g_inited) return;
    g_inited = true;
}

void Shutdown() {
    ClearColorCaches();
    for (auto& kv : g_fonts) delete kv.second;
    g_fonts.clear();
    g_textW.clear();
    FreeWallpaperBmp();
    g_inited = false;
}

Gdiplus::Font* Font(int px, bool bold) {
    if (px < 8) px = 8;
    if (px > 72) px = 72;
    uint64_t key = FontKey(px, bold ? 1 : 0);
    auto it = g_fonts.find(key);
    if (it != g_fonts.end()) return it->second;
    FontFamily fam(UiFamily());
    auto* f = new Gdiplus::Font(&fam, (REAL)px, bold ? FontStyleBold : FontStyleRegular, UnitPixel);
    g_fonts[key] = f;
    return f;
}

Gdiplus::Font* FontMono(int px) {
    uint64_t key = FontKey(px, 2);
    auto it = g_fonts.find(key);
    if (it != g_fonts.end()) return it->second;
    FontFamily fam(L"Consolas");
    auto* f = new Gdiplus::Font(&fam, (REAL)px, FontStyleRegular, UnitPixel);
    g_fonts[key] = f;
    return f;
}

float TextWidth(const std::wstring& s, Gdiplus::Font* f) {
    if (!f || s.empty()) return 0.0f;
    uint64_t key = ((uint64_t)(uintptr_t)f << 24) ^ std::hash<std::wstring>()(s);
    auto it = g_textW.find(key);
    if (it != g_textW.end()) return it->second;
    Bitmap bmp(1, 1, PixelFormat32bppARGB);
    Graphics g(&bmp);
    g.SetTextRenderingHint(TextRenderingHintClearTypeGridFit);
    RectF box;
    g.MeasureString(s.c_str(), (INT)s.size(), f, PointF(0, 0), &box);
    float w = box.Width;
    if (g_textW.size() > 2048) g_textW.clear();
    g_textW[key] = w;
    return w;
}

void DrawText(Graphics& g, const std::wstring& s, Gdiplus::Font* f, const RectF& rc, uint32_t color,
              int align, bool ellipsis, bool vcenter) {
    if (s.empty() || !f) return;
    StringFormat fmt(StringFormat::GenericTypographic());
    fmt.SetFormatFlags(StringFormatFlagsNoWrap);
    switch (align) {
        case 1: fmt.SetAlignment(StringAlignmentCenter); break;
        case 2: fmt.SetAlignment(StringAlignmentFar); break;
        default: fmt.SetAlignment(StringAlignmentNear); break;
    }
    fmt.SetLineAlignment(vcenter ? StringAlignmentCenter : StringAlignmentNear);
    if (ellipsis) fmt.SetTrimming(StringTrimmingEllipsisCharacter);
    g.SetTextRenderingHint(TextRenderingHintClearTypeGridFit);
    g.DrawString(s.c_str(), (INT)s.size(), f, rc, &fmt, Brush(color));
}

void FillRound(Graphics& g, const RectF& rc, float r, uint32_t color) {
    if (rc.Width <= 0 || rc.Height <= 0) return;
    GraphicsPath* p = RoundPath(rc, r);
    g.FillPath(Brush(color), p);
    delete p;
}

void StrokeRound(Graphics& g, const RectF& rc, float r, uint32_t color, float width) {
    if (rc.Width <= 0 || rc.Height <= 0) return;
    GraphicsPath* p = RoundPath(rc, r);
    g.DrawPath(PenOf(color, width), p);
    delete p;
}

void FillRect(Graphics& g, const RectF& rc, uint32_t color) {
    g.FillRectangle(Brush(color), rc);
}

void Line(Graphics& g, float x1, float y1, float x2, float y2, uint32_t color, float w) {
    g.DrawLine(PenOf(color, w), x1, y1, x2, y2);
}

void ProgressBar(Graphics& g, const RectF& rc, double pct, uint32_t fg, uint32_t bg) {
    FillRound(g, rc, rc.Height / 2.0f, bg);
    if (pct <= 0) return;
    if (pct > 1.0) pct = 1.0;
    RectF f = rc;
    f.Width = (float)(rc.Width * pct);
    if (f.Width < rc.Height) f.Width = rc.Height;  // 至少一个圆头
    FillRound(g, f, rc.Height / 2.0f, fg);
}

void IconFolder(Graphics& g, const RectF& rc, uint32_t color) {
    float x = rc.X, y = rc.Y, w = rc.Width, h = rc.Height;
    RectF tab(x, y + h * 0.22f, w * 0.42f, h * 0.18f);
    FillRound(g, tab, h * 0.06f, color);
    RectF body(x, y + h * 0.34f, w, h * 0.52f);
    FillRound(g, body, h * 0.10f, color);
}

void IconFile(Graphics& g, const RectF& rc, uint32_t color, bool filled) {
    float x = rc.X, y = rc.Y, w = rc.Width, h = rc.Height;
    RectF body(x + w * 0.14f, y + h * 0.06f, w * 0.72f, h * 0.88f);
    if (filled) FillRound(g, body, h * 0.10f, color);
    else StrokeRound(g, body, h * 0.10f, color, 1.4f);
    for (int i = 0; i < 3; ++i) {
        float ly = y + h * (0.36f + i * 0.18f);
        g.DrawLine(PenOf(color, 1.2f), x + w * 0.28f, ly, x + w * 0.72f, ly);
    }
}

void IconPhone(Graphics& g, const RectF& rc, uint32_t color) {
    RectF body(rc.X + rc.Width * 0.26f, rc.Y + rc.Height * 0.10f, rc.Width * 0.48f,
               rc.Height * 0.80f);
    StrokeRound(g, body, rc.Height * 0.12f, color, 1.6f);
    g.FillRectangle(Brush(color), rc.X + rc.Width * 0.42f, rc.Y + rc.Height * 0.16f,
                    rc.Width * 0.16f, rc.Height * 0.05f);
}

void IconPC(Graphics& g, const RectF& rc, uint32_t color) {
    RectF mon(rc.X + rc.Width * 0.10f, rc.Y + rc.Height * 0.14f, rc.Width * 0.80f,
              rc.Height * 0.54f);
    StrokeRound(g, mon, rc.Height * 0.08f, color, 1.6f);
    g.FillRectangle(Brush(color), rc.X + rc.Width * 0.44f, rc.Y + rc.Height * 0.68f,
                    rc.Width * 0.12f, rc.Height * 0.12f);
    g.FillRectangle(Brush(color), rc.X + rc.Width * 0.28f, rc.Y + rc.Height * 0.80f,
                    rc.Width * 0.44f, rc.Height * 0.06f);
}

void IconArrowDown(Graphics& g, const RectF& rc, uint32_t color) {
    float cx = rc.X + rc.Width / 2, top = rc.Y + rc.Height * 0.15f;
    float bot = rc.Y + rc.Height * 0.70f;
    g.DrawLine(PenOf(color, 1.8f), cx, top, cx, bot);
    g.DrawLine(PenOf(color, 1.8f), cx, bot, cx - rc.Width * 0.24f, bot - rc.Height * 0.22f);
    g.DrawLine(PenOf(color, 1.8f), cx, bot, cx + rc.Width * 0.24f, bot - rc.Height * 0.22f);
    g.FillRectangle(Brush(color), cx - rc.Width * 0.30f, rc.Y + rc.Height * 0.80f,
                    rc.Width * 0.60f, rc.Height * 0.08f);
}

void IconArrowUp(Graphics& g, const RectF& rc, uint32_t color) {
    float cx = rc.X + rc.Width / 2, top = rc.Y + rc.Height * 0.15f;
    float bot = rc.Y + rc.Height * 0.70f;
    g.DrawLine(PenOf(color, 1.8f), cx, top, cx, bot);
    g.DrawLine(PenOf(color, 1.8f), cx, top, cx - rc.Width * 0.24f, top + rc.Height * 0.22f);
    g.DrawLine(PenOf(color, 1.8f), cx, top, cx + rc.Width * 0.24f, top + rc.Height * 0.22f);
    g.FillRectangle(Brush(color), cx - rc.Width * 0.30f, rc.Y + rc.Height * 0.80f,
                    rc.Width * 0.60f, rc.Height * 0.08f);
}

bool HitRect(const RectF& rc, int x, int y) {
    return x >= rc.X && x <= rc.X + rc.Width && y >= rc.Y && y <= rc.Y + rc.Height;
}

}  // namespace th
