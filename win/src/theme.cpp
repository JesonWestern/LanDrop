#include "theme.h"

#include <algorithm>
#include <cmath>

using namespace Gdiplus;

namespace th {

int g_dpi = 96;

namespace {
std::unordered_map<uint64_t, Gdiplus::Font*> g_fonts;
std::unordered_map<uint32_t, SolidBrush*> g_brushes;
std::unordered_map<uint64_t, float> g_textW;
bool g_inited = false;

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
    static std::unordered_map<uint64_t, Pen*> pens;
    auto it = pens.find(key);
    if (it != pens.end()) return it->second;
    auto* p = new Pen(Color(argb), q / 2.0f);
    pens[key] = p;
    return p;
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

void Init() {
    if (g_inited) return;
    g_inited = true;
}

void Shutdown() {
    for (auto& kv : g_fonts) delete kv.second;
    g_fonts.clear();
    for (auto& kv : g_brushes) delete kv.second;
    g_brushes.clear();
    g_textW.clear();
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
