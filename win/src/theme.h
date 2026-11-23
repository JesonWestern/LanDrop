// theme.h -- 运行期主题令牌与 GDI+ 绘制工具（字体/画笔/画刷缓存）
#pragma once

#include "app.h"

// WIN32_LEAN_AND_MEAN 会排除 propidl.h，导致 gdiplus 头里 PROPID 未定义
#include <objidl.h>
#include <gdiplus.h>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace th {

extern int g_dpi;
inline int D(int v) { return MulDiv(v, th::g_dpi, 96); }
inline float Df(float v) { return v * (float)th::g_dpi / 96.0f; }

// 当前生效的调色板（由 ApplyTheme 写入；所有绘制都从这里取色）
struct Palette {
    bool light;
    float panelAlpha;
    uint32_t bg;
    uint32_t panel;
    uint32_t panel2;
    uint32_t panel3;
    uint32_t border;
    uint32_t border2;
    uint32_t text;
    uint32_t textDim;
    uint32_t textFaint;
    uint32_t accent;
    uint32_t accentDk;
    uint32_t onAccent;
    uint32_t green;
    uint32_t red;
    uint32_t amber;
    uint32_t purple;
    uint32_t orange;
    uint32_t cyan;
    uint32_t hover;
    uint32_t sel;
};

extern Palette g_pal;

// 颜色令牌（0xAARRGGBB）。刻意保持**变量**而不是函数：
// 全工程 67 处 th::cXxx 调用点因此一行都不用改。
extern uint32_t cBg;
extern uint32_t cPanel;
extern uint32_t cPanel2;
extern uint32_t cPanel3;
extern uint32_t cBorder;
extern uint32_t cBorder2;
extern uint32_t cText;
extern uint32_t cTextDim;
extern uint32_t cTextFaint;
extern uint32_t cAccent;
extern uint32_t cAccentDk;
extern uint32_t cOnAccent;
extern uint32_t cGreen;
extern uint32_t cRed;
extern uint32_t cAmber;
extern uint32_t cPurple;
extern uint32_t cOrange;
extern uint32_t cCyan;
extern uint32_t cHover;
extern uint32_t cSel;

// >0 = 强制 DPI 缩放（`--dpi N` 仅测试用；0 表示跟随系统）
extern int g_dpiForce;
// 壁纸暗化强度 0..80（百分比）
extern int g_scrim;

// 一次"应用到运行期"的主题描述（配置持久化与设置页共用）
struct ThemeSpec {
    std::wstring id;
    uint32_t accentArgb = 0;          // 0 = 沿用预设自带强调色
    std::wstring wallpaperPath;       // 空 = 无壁纸
    int scrim = 55;                   // 0..80
    bool animations = true;

    // 由当前 id/accent 派生出的 accentDk（"AARRGGBB" 文本；诊断与自测用）
    std::wstring toAccentDk();
};

// ---- 纯函数（不依赖 GDI+，可随时调用/自测）--------------------------------
Palette PaletteOf(const std::wstring& id, uint32_t accentArgb);
std::wstring NormalizeThemeId(const std::wstring& id);
std::wstring DefaultThemeId();
// (id, label) 列表，顺序即设置页展示顺序，与 Android ThemePresets.kt 严格一致
std::vector<std::pair<std::wstring, std::wstring>> Presets();
const std::vector<uint32_t>& AccentSwatches();  // 12 个强调色（ARGB）
int ClampScrim(int v);
uint32_t Shade(uint32_t argb, float f);
uint32_t OnAccentFor(uint32_t accentArgb);
uint32_t AccentDkFor(uint32_t accentArgb, bool light);
float EaseOutCubic(float t);

inline Gdiplus::Color C(uint32_t v) { return Gdiplus::Color(v); }
inline uint32_t WithAlpha(uint32_t argb, int alpha) {
    return (argb & 0x00FFFFFFu) | (((uint32_t)alpha & 0xFFu) << 24);
}
// 彩色徽章：底色用低 alpha 的主题色，文字色按当前明暗方向"拉开对比"
// （深色主题向白提亮，浅色主题向黑压暗），否则浅色底上浅色字会糊成一片。
uint32_t TintBg(uint32_t base);
uint32_t TintText(uint32_t base);
// 当前生效的壁纸暗化层 alpha（0..255）
int ScrimAlpha();

// ---- 运行期 ---------------------------------------------------------------
bool ApplyTheme(const ThemeSpec& spec);
bool ApplyFromConfig();          // 用 g_app.cfg 的主题字段构造 spec 并应用
const ThemeSpec& CurrentSpec();
ThemeSpec& MutableSpec();         // 设置页预览用（仅改字段，不生效）

void SetWallpaper(const std::wstring& path);
HBITMAP WallpaperBitmap(int w, int h);  // 离屏缩放缓存；尺寸变化/清空时失效
bool HasWallpaper();

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
