// theme.h -- 暗色主题令牌与 GDI+ 绘制工具（字体/画笔/路径全部缓存）
#pragma once

#include "app.h"

// WIN32_LEAN_AND_MEAN 会排除 propidl.h，导致 gdiplus 头里 PROPID 未定义
#include <objidl.h>
#include <gdiplus.h>
#include <string>
#include <unordered_map>

namespace th {

extern int g_dpi;
inline int D(int v) { return MulDiv(v, th::g_dpi, 96); }
inline float Df(float v) { return v * (float)th::g_dpi / 96.0f; }

// 颜色令牌（0xAARRGGBB）
constexpr uint32_t cBg        = 0xFF101215;
constexpr uint32_t cPanel     = 0xFF171A1F;
constexpr uint32_t cPanel2    = 0xFF1E2228;
constexpr uint32_t cPanel3    = 0xFF262B33;
constexpr uint32_t cBorder    = 0xFF2E343D;
constexpr uint32_t cBorder2   = 0xFF3A424D;
constexpr uint32_t cText      = 0xFFE6E9EF;
constexpr uint32_t cTextDim   = 0xFF9AA1AC;
constexpr uint32_t cTextFaint = 0xFF6B7280;
constexpr uint32_t cAccent    = 0xFF4C8DF6;
constexpr uint32_t cAccentDk  = 0xFF2F5FA8;
constexpr uint32_t cGreen     = 0xFF57C98A;
constexpr uint32_t cRed       = 0xFFE5645F;
constexpr uint32_t cAmber     = 0xFFE2B34D;
constexpr uint32_t cHover     = 0xFF232830;
constexpr uint32_t cSel       = 0xFF2A3240;

inline Gdiplus::Color C(uint32_t v) { return Gdiplus::Color(v); }

void Init();
void Shutdown();

Gdiplus::Font* Font(int px, bool bold = false);
Gdiplus::Font* FontMono(int px);
float TextWidth(const std::wstring& s, Gdiplus::Font* f);
void DrawText(Gdiplus::Graphics& g, const std::wstring& s, Gdiplus::Font* f,
              const Gdiplus::RectF& rc, uint32_t color, int align = 0 /*0左 1中 2右*/,
              bool ellipsis = false, bool vcenter = false);
void FillRound(Gdiplus::Graphics& g, const Gdiplus::RectF& rc, float r, uint32_t color);
void StrokeRound(Gdiplus::Graphics& g, const Gdiplus::RectF& rc, float r, uint32_t color,
                 float width = 1.0f);
void FillRect(Gdiplus::Graphics& g, const Gdiplus::RectF& rc, uint32_t color);
void Line(Gdiplus::Graphics& g, float x1, float y1, float x2, float y2, uint32_t color,
          float w = 1.0f);
void ProgressBar(Gdiplus::Graphics& g, const Gdiplus::RectF& rc, double pct, uint32_t fg,
                 uint32_t bg);
// 简易图标：用几何图形画（不依赖图标字体）
void IconFolder(Gdiplus::Graphics& g, const Gdiplus::RectF& rc, uint32_t color);
void IconFile(Gdiplus::Graphics& g, const Gdiplus::RectF& rc, uint32_t color, bool filled);
void IconPhone(Gdiplus::Graphics& g, const Gdiplus::RectF& rc, uint32_t color);
void IconPC(Gdiplus::Graphics& g, const Gdiplus::RectF& rc, uint32_t color);
void IconArrowDown(Gdiplus::Graphics& g, const Gdiplus::RectF& rc, uint32_t color);
void IconArrowUp(Gdiplus::Graphics& g, const Gdiplus::RectF& rc, uint32_t color);

// 命中测试用的圆角矩形
bool HitRect(const Gdiplus::RectF& rc, int x, int y);

}  // namespace th
