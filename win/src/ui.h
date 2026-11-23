// ui.h -- 界面层共享状态与绘制入口
#pragma once

#include "app.h"
#include "theme.h"

#include <gdiplus.h>
#include <vector>

namespace ui {

enum Tab { TabRecv = 0, TabSend = 1, TabSpeed = 2 };

enum HitKind {
    HK_None = 0,
    HK_Tab,
    HK_Device,
    HK_RecvRow,
    HK_RecvBtn,
    HK_SendRow,
    HK_SendBtn,
    HK_DropZone,
    HK_BottomBtn,
    HK_SpeedDir,
    HK_SpeedRun,
    HK_Refresh,
    HK_Network,
    HK_FixFirewall,
};

enum Action {
    A_None = 0,
    A_Open,
    A_OpenFolder,
    A_SaveAs,
    A_Cancel,
    A_Retry,
    A_Remove,
    A_AddFiles,
    A_AddFolder,
    A_SendNow,
    A_ClearFinished,
    A_StartSpeed,
    A_StopSpeed,
    A_DirUp,
    A_DirDown,
    A_DirBoth,
};

struct Hit {
    int kind = HK_None;
    uint32_t id = 0;
    int action = A_None;
    Gdiplus::RectF rc;
};

extern std::vector<Hit> hits;
extern std::vector<Device> devs;
extern std::vector<JobView> jobViews;
extern std::wstring selDevId;
extern int scrollRecv;
extern int scrollSend;
extern int scrollDev;
extern int hoverKind;
extern uint32_t hoverId;
extern int hoverAction;
extern int curTab;
extern int clientW;
extern int clientH;
extern int speedDir;      // 0=up 1=down 2=both
extern std::vector<double> speedSamples;
extern std::wstring statusLine;

bool Create(HINSTANCE inst);
void Destroy();
void ApplyDpi(UINT dpi);
void Refresh();
void SetStatus(const std::wstring& s);

// 主题相关：非客户区配色与页签切换动画
void ApplyTitleBar(HWND hwnd);
bool AnimationsEnabled();
void AnimateTabSwitch(int from, int to);
void CancelTabAnim();

void ClearHits();
void AddHit(int kind, uint32_t id, int action, const Gdiplus::RectF& rc);
const Hit* FindHit(int x, int y);
bool HoverIs(int kind, uint32_t id, int action);

// 各面板绘制（由 Paint 调用）
void DrawTopBar(Gdiplus::Graphics& g, const Gdiplus::RectF& area);
void DrawDevicePanel(Gdiplus::Graphics& g, const Gdiplus::RectF& area);
void DrawTabs(Gdiplus::Graphics& g, const Gdiplus::RectF& area);
void DrawRecvList(Gdiplus::Graphics& g, const Gdiplus::RectF& area);
void DrawSendList(Gdiplus::Graphics& g, const Gdiplus::RectF& area);
void DrawSpeedPanel(Gdiplus::Graphics& g, const Gdiplus::RectF& area);
void DrawBottomBar(Gdiplus::Graphics& g, const Gdiplus::RectF& area);

void OnClick(const Hit& h);
void OnWheel(int delta, int mouseY);
void OpenSettings(HWND owner);

}  // namespace ui
