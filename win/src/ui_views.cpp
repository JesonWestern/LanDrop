#include "ui.h"

#include <algorithm>
#include <cmath>
#include <cwchar>

namespace ui {

namespace {

constexpr int kPad = 16;

std::wstring JobSubtitle(const JobView& v) {
    std::wstring s = StateText(v.state);
    if (v.state == JobState::Transferring) {
        s += L" · ";
        s += util::FormatSpeed(v.bps);
        if (v.bps > 1 && v.totalBytes > v.doneBytes) {
            const double left = (double)(v.totalBytes - v.doneBytes) / v.bps;
            if (left < 36000) s += L" · " + util::FormatEta(left);
        }
    } else if (v.totalBytes > 0) {
        s += L" · " + util::FormatBytes(v.doneBytes) + L" / " + util::FormatBytes(v.totalBytes);
    }
    if (v.errCode != 0 && !v.errMsg.empty()) s += L" · " + v.errMsg;
    return s;
}

void EmptyState(Gdiplus::Graphics& g, const Gdiplus::RectF& area, const std::wstring& title,
                const std::wstring& sub, bool iconFile) {
    const float cy = area.Y + area.Height / 2;
    Gdiplus::RectF ic(area.X + area.Width / 2 - th::D(20), cy - th::D(72), th::D(40), th::D(40));
    if (iconFile) th::IconFile(g, ic, th::cBorder2, false);
    else th::IconFolder(g, ic, th::cBorder2);

    Gdiplus::RectF t(area.X, ic.Y + th::D(52), area.Width, th::D(24));
    th::DrawText(g, title, th::Font(th::D(14)), t, th::cTextDim, 1, false, true);
    if (!sub.empty()) {
        Gdiplus::RectF s(area.X + th::D(20), t.Y + th::D(26), area.Width - th::D(40), th::D(44));
        th::DrawText(g, sub, th::Font(th::D(12)), s, th::cTextFaint, 1, true, false);
    }
}

Gdiplus::RectF ClipRect(const Gdiplus::RectF& a, const Gdiplus::RectF& b) {
    const float x1 = (std::max)(a.X, b.X);
    const float y1 = (std::max)(a.Y, b.Y);
    const float x2 = (std::min)(a.X + a.Width, b.X + b.Width);
    const float y2 = (std::min)(a.Y + a.Height, b.Y + b.Height);
    return Gdiplus::RectF(x1, y1, (std::max)(0.0f, x2 - x1), (std::max)(0.0f, y2 - y1));
}

// 画一个行内小按钮，返回宽度
float RowButton(Gdiplus::Graphics& g, float right, float cy, const std::wstring& label, bool hover,
                bool danger, int action, uint32_t jobId, int kind,
                const Gdiplus::RectF& clip = Gdiplus::RectF(0, 0, 1e6f, 1e6f)) {
    const float h = th::D(24);
    const float w = th::TextWidth(label, th::Font(th::D(12))) + th::D(20);
    Gdiplus::RectF r(right - w, cy - h / 2, w, h);
    uint32_t bg = danger ? 0x33E5645F : th::cPanel3;
    uint32_t fg = danger ? 0xFFFFB4B0 : th::cText;
    if (hover) bg = danger ? 0x55E5645F : th::cBorder2;
    th::FillRound(g, r, th::D(6), bg);
    th::DrawText(g, label, th::Font(th::D(12)), r, fg, 1, false, true);
    AddHit(kind, jobId, action, ClipRect(r, clip));
    return w + th::D(6);
}

void DownloadRow(Gdiplus::Graphics& g, const Gdiplus::RectF& row, const JobView& v,
                 const Gdiplus::RectF& area) {
    // 行命中必须先注册：FindHit 是后注册优先，行整体会盖住行内按钮。
    // 同时把行命中裁剪到列表可视区域内，避免滚动后压住页签/顶栏。
    AddHit(HK_RecvRow, v.id, A_None, ClipRect(row, area));

    const bool hv = HoverIs(HK_RecvRow, v.id, A_None);
    th::FillRound(g, row, th::D(10), hv ? th::cPanel2 : th::cPanel);

    Gdiplus::RectF ic(row.X + th::D(14), row.Y + th::D(16), th::D(20), th::D(20));
    th::IconFile(g, ic, v.state == JobState::Failed ? th::cRed : th::cAccent, false);

    const float rightPad = th::D(14);
    float btnRight = row.X + row.Width - rightPad;
    const float cy = row.Y + th::D(24);

    // 右侧按钮
    if (v.state == JobState::Done) {
        btnRight -= RowButton(g, btnRight, cy, L"另存为", HoverIs(HK_RecvBtn, v.id, A_SaveAs),
                              false, A_SaveAs, v.id, HK_RecvBtn, area);
        btnRight -= RowButton(g, btnRight, cy, L"所在文件夹",
                              HoverIs(HK_RecvBtn, v.id, A_OpenFolder), false, A_OpenFolder, v.id,
                              HK_RecvBtn, area);
        btnRight -= RowButton(g, btnRight, cy, L"打开", HoverIs(HK_RecvBtn, v.id, A_Open), false,
                              A_Open, v.id, HK_RecvBtn, area);
    } else if (StateIsActive(v.state)) {
        btnRight -= RowButton(g, btnRight, cy, L"取消", HoverIs(HK_RecvBtn, v.id, A_Cancel), true,
                              A_Cancel, v.id, HK_RecvBtn, area);
    } else {
        btnRight -= RowButton(g, btnRight, cy, L"移除", HoverIs(HK_RecvBtn, v.id, A_Remove), false,
                              A_Remove, v.id, HK_RecvBtn, area);
    }

    const float textX = ic.X + ic.Width + th::D(10);
    Gdiplus::RectF title(textX, row.Y + th::D(11), btnRight - textX - th::D(10), th::D(20));
    th::DrawText(g, v.title.empty() ? L"文件" : v.title, th::Font(th::D(13.5f), true), title,
                 th::cText, 0, true, false);

    Gdiplus::RectF sub(textX, row.Y + th::D(33), btnRight - textX - th::D(10), th::D(18));
    th::DrawText(g, JobSubtitle(v), th::Font(th::D(11)), sub,
                 v.state == JobState::Failed ? th::cRed : th::cTextDim, 0, true, false);

    if (StateIsActive(v.state) || v.state == JobState::Paused) {
        Gdiplus::RectF bar(row.X + th::D(14), row.Y + row.Height - th::D(14),
                           row.Width - th::D(28), th::D(4));
        th::ProgressBar(g, bar, v.Progress(), th::cAccent, th::cPanel3);
    }
}

}  // namespace

void DrawRecvList(Gdiplus::Graphics& g, const Gdiplus::RectF& area) {
    std::vector<const JobView*> rows;
    for (const auto& v : jobViews) {
        if (v.dir == JobDir::Recv) rows.push_back(&v);
    }
    if (rows.empty()) {
        EmptyState(g, area, L"还没有收到文件",
                   L"当手机把文件发过来时，会先落到临时目录，\n完成后出现在这里，可随时另存到其他文件夹。",
                   true);
        return;
    }

    const float rowH = th::D(72);
    const float gap = th::D(8);
    const float total = (float)rows.size() * (rowH + gap) - gap;
    const float maxScroll = std::max(0.0f, (float)(total - area.Height));
    if ((float)scrollRecv > maxScroll) scrollRecv = (int)maxScroll;
    if (scrollRecv < 0) scrollRecv = 0;

    Gdiplus::GraphicsState st = g.Save();
    g.SetClip(Gdiplus::RectF(area.X, area.Y, area.Width, area.Height));
    float y = area.Y - (float)scrollRecv;
    for (const auto* v : rows) {
        if (y + rowH >= area.Y && y <= area.Y + area.Height) {
            DownloadRow(g, Gdiplus::RectF(area.X, y, area.Width, rowH), *v, area);
        }
        y += rowH + gap;
    }
    g.Restore(st);

    if (total > area.Height) {
        const float th_ = area.Height * (area.Height / total);
        const float pos = area.Height * ((float)scrollRecv / total);
        th::FillRound(g, Gdiplus::RectF(area.X + area.Width - th::D(4), area.Y + pos, th::D(3),
                                        (std::max)((float)th::D(24), th_)),
                      th::D(1.5f), th::cBorder2);
    }
}

void DrawSendList(Gdiplus::Graphics& g, const Gdiplus::RectF& area) {
    const Device* target = nullptr;
    for (const auto& d : devs) {
        if (util::Widen(d.id) == selDevId) { target = &d; break; }
    }
    if (!target && !devs.empty()) target = &devs.front();

    // 目标设备条
    Gdiplus::RectF bar(area.X, area.Y, area.Width, th::D(36));
    th::FillRound(g, bar, th::D(8), th::cPanel);
    std::wstring t = target ? (L"发送到：" + target->name) : L"发送到：（未发现设备）";
    th::DrawText(g, t, th::Font(th::D(12.5f)), Gdiplus::RectF(bar.X + th::D(12), bar.Y, bar.Width - th::D(24), bar.Height),
                 target ? th::cText : th::cTextFaint, 0, true, true);

    // 拖放区
    Gdiplus::RectF drop(area.X, bar.Y + bar.Height + th::D(10), area.Width, th::D(110));
    const bool dh = HoverIs(HK_DropZone, 0, A_None);
    th::FillRound(g, drop, th::D(12), dh ? th::cPanel2 : th::cPanel);
    {
        Gdiplus::Pen pen(th::C(dh ? th::cAccent : th::cBorder2), 1.2f);
        pen.SetDashStyle(Gdiplus::DashStyleDash);
        Gdiplus::GraphicsPath path;
        float r = th::D(12);
        path.AddArc(drop.X, drop.Y, r * 2, r * 2, 180, 90);
        path.AddArc(drop.X + drop.Width - r * 2, drop.Y, r * 2, r * 2, 270, 90);
        path.AddArc(drop.X + drop.Width - r * 2, drop.Y + drop.Height - r * 2, r * 2, r * 2, 0, 90);
        path.AddArc(drop.X, drop.Y + drop.Height - r * 2, r * 2, r * 2, 90, 90);
        path.CloseFigure();
        g.DrawPath(&pen, &path);
    }
    Gdiplus::RectF ic(drop.X + drop.Width / 2 - th::D(11), drop.Y + th::D(26), th::D(22), th::D(22));
    th::IconArrowUp(g, ic, dh ? th::cAccent : th::cTextDim);
    th::DrawText(g, L"把文件或文件夹拖到这里，或点击选择",
                 th::Font(th::D(13)), Gdiplus::RectF(drop.X, drop.Y + th::D(58), drop.Width, th::D(22)),
                 dh ? th::cText : th::cTextDim, 1, false, true);
    th::DrawText(g, L"支持多选、整个文件夹、空目录",
                 th::Font(th::D(11)), Gdiplus::RectF(drop.X, drop.Y + th::D(80), drop.Width, th::D(18)),
                 th::cTextFaint, 1, false, true);
    AddHit(HK_DropZone, 0, A_AddFiles, drop);

    // 发送任务
    std::vector<const JobView*> rows;
    for (const auto& v : jobViews) {
        if (v.dir == JobDir::Send) rows.push_back(&v);
    }
    Gdiplus::RectF list(area.X, drop.Y + drop.Height + th::D(12), area.Width,
                        area.Y + area.Height - (drop.Y + drop.Height + th::D(12)));
    if (rows.empty()) {
        th::DrawText(g, L"还没有发送任务", th::Font(th::D(12)),
                     Gdiplus::RectF(list.X, list.Y + th::D(10), list.Width, th::D(20)),
                     th::cTextFaint, 1, false, true);
        return;
    }

    const float rowH = th::D(64);
    const float gap = th::D(8);
    Gdiplus::GraphicsState st = g.Save();
    g.SetClip(list);
    float y = list.Y - (float)scrollSend;
    for (const auto* v : rows) {
        Gdiplus::RectF row(list.X, y, list.Width, rowH);
        if (y + rowH >= list.Y && y <= list.Y + list.Height) {
            AddHit(HK_SendRow, v->id, A_None, ClipRect(row, list));  // 先注册行，后注册按钮
            const bool hv = HoverIs(HK_SendRow, v->id, A_None);
            th::FillRound(g, row, th::D(10), hv ? th::cPanel2 : th::cPanel);
            Gdiplus::RectF ic(row.X + th::D(14), row.Y + th::D(14), th::D(18), th::D(18));
            th::IconArrowUp(g, ic, th::cGreen);

            float btnRight = row.X + row.Width - th::D(14);
            if (StateIsActive(v->state)) {
                btnRight -= RowButton(g, btnRight, row.Y + th::D(22), L"取消",
                                      HoverIs(HK_SendBtn, v->id, A_Cancel), true, A_Cancel, v->id,
                                      HK_SendBtn, list);
            }
            Gdiplus::RectF title(ic.X + ic.Width + th::D(10), row.Y + th::D(9),
                                 btnRight - ic.X - ic.Width - th::D(24), th::D(20));
            th::DrawText(g, v->title, th::Font(th::D(13), true), title, th::cText, 0, true, false);
            Gdiplus::RectF sub(title.X, row.Y + th::D(30), title.Width, th::D(18));
            th::DrawText(g, JobSubtitle(*v), th::Font(th::D(11)), sub, th::cTextDim, 0, true, false);
            if (StateIsActive(v->state)) {
                th::ProgressBar(g, Gdiplus::RectF(row.X + th::D(14), row.Y + row.Height - th::D(10),
                                                  row.Width - th::D(28), th::D(3)),
                                v->Progress(), th::cGreen, th::cPanel3);
            }
        }
        y += rowH + gap;
    }
    g.Restore(st);
}

void DrawSpeedPanel(Gdiplus::Graphics& g, const Gdiplus::RectF& area) {
    const bool running = speed::IsRunning();
    const auto res = speed::LastResult();
    auto samples = speed::SamplesMbps();

    Gdiplus::RectF card(area.X, area.Y, area.Width, (std::min)(area.Height, (float)th::D(300)));
    th::FillRound(g, card, th::D(12), th::cPanel);

    // 方向选择
    struct D { const wchar_t* label; int mode; int action; };
    D dirs[3] = {{L"上传（本机→对端）", 0, A_DirUp},
                 {L"下载（对端→本机）", 1, A_DirDown},
                 {L"双向", 2, A_DirBoth}};
    float x = card.X + th::D(16);
    const float by = card.Y + th::D(16);
    for (int i = 0; i < 3; ++i) {
        const bool act = speedDir == dirs[i].mode;
        const bool hv = HoverIs(HK_SpeedDir, (uint32_t)dirs[i].mode, A_None);
        float w = th::TextWidth(dirs[i].label, th::Font(th::D(12))) + th::D(26);
        Gdiplus::RectF r(x, by, w, th::D(30));
        th::FillRound(g, r, th::D(7), act ? th::cAccentDk : (hv ? th::cHover : th::cPanel2));
        if (act) th::StrokeRound(g, r, th::D(7), th::cAccent, 1.0f);
        th::DrawText(g, dirs[i].label, th::Font(th::D(12)), r, act ? 0xFFFFFFFF : th::cTextDim, 1,
                     false, true);
        AddHit(HK_SpeedDir, (uint32_t)dirs[i].mode, dirs[i].action, r);
        x += w + th::D(8);
    }

    // 大数字
    double mbps = 0;
    if (!samples.empty()) mbps = samples.back();
    if (!running && res.ok) {
        mbps = speedDir == 1 ? res.avgMbps : res.avgMbps;
    }
    wchar_t big[64];
    swprintf(big, 64, L"%.1f", mbps);
    Gdiplus::RectF bigR(card.X, card.Y + th::D(66), card.Width, th::D(62));
    th::DrawText(g, big, th::Font(th::D(52), true), bigR, th::cText, 1, false, true);
    th::DrawText(g, L"Mbps", th::Font(th::D(13)), Gdiplus::RectF(card.X, card.Y + th::D(126), card.Width, th::D(20)),
                 th::cTextDim, 1, false, true);

    // 折线
    Gdiplus::RectF chart(card.X + th::D(20), card.Y + th::D(154), card.Width - th::D(40), th::D(86));
    th::FillRound(g, chart, th::D(8), th::cPanel2);
    if (samples.size() >= 2) {
        double mx = 1;
        for (double s : samples) mx = std::max(mx, s);
        std::vector<Gdiplus::PointF> pts;
        pts.reserve(samples.size());
        for (size_t i = 0; i < samples.size(); ++i) {
            float px = chart.X + chart.Width * (float)i / (float)(samples.size() - 1);
            float py = chart.Y + chart.Height - (float)(samples[i] / mx) * (chart.Height - th::D(8)) -
                       th::D(4);
            pts.emplace_back(px, py);
        }
        Gdiplus::Pen pen(th::C(th::cAccent), 1.8f);
        g.DrawLines(&pen, pts.data(), (INT)pts.size());
        wchar_t mxbuf[32];
        swprintf(mxbuf, 32, L"峰值 %.0f", mx);
        th::DrawText(g, mxbuf, th::Font(th::D(10)),
                     Gdiplus::RectF(chart.X + th::D(6), chart.Y + th::D(4), chart.Width - th::D(12), th::D(14)),
                     th::cTextFaint, 2, false, true);
    } else {
        th::DrawText(g, running ? L"正在采样…" : L"点击开始测速", th::Font(th::D(12)), chart,
                     th::cTextFaint, 1, false, true);
    }

    // 结果明细
    if (res.ok) {
        wchar_t line[256];
        swprintf(line, 256, L"平均 %.1f · 峰值 %.1f · 中位 %.1f Mbps · %d 连接", res.avgMbps,
                 res.peakMbps, res.p50Mbps, res.conns);
        th::DrawText(g, line, th::Font(th::D(11.5f)),
                     Gdiplus::RectF(card.X, chart.Y + chart.Height + th::D(8), card.Width, th::D(20)),
                     th::cTextDim, 1, true, true);
    } else if (!res.err.empty()) {
        th::DrawText(g, res.err, th::Font(th::D(11.5f)),
                     Gdiplus::RectF(card.X, chart.Y + chart.Height + th::D(8), card.Width, th::D(20)),
                     th::cRed, 1, true, true);
    }

    // 开始/停止
    {
        float w = th::D(130);
        Gdiplus::RectF r(card.X + card.Width / 2 - w / 2, card.Y + card.Height - th::D(46), w,
                         th::D(34));
        const bool hv = HoverIs(HK_SpeedRun, 0, A_None);
        th::FillRound(g, r, th::D(8), running ? 0x33E5645F : (hv ? 0xFF5C9BFB : th::cAccent));
        th::DrawText(g, running ? L"停止" : L"开始测速", th::Font(th::D(13.5f), true), r,
                     running ? 0xFFFFB4B0 : 0xFFFFFFFF, 1, false, true);
        AddHit(HK_SpeedRun, 0, A_None, r);
    }

    // 说明
    Gdiplus::RectF tip(area.X + th::D(4), card.Y + card.Height + th::D(12), area.Width - th::D(8),
                       th::D(60));
    th::DrawText(g,
                 L"测速会在两端之间建立多条并发连接并持续打流 10 秒，"
                 L"测的是本机与对端之间真实可用的 TCP 吞吐。\n"
                 L"测试期间请勿同时传文件；手机端会占用较多电量与流量（仅走局域网，不消耗移动数据）。",
                 th::Font(th::D(11.5f)), tip, th::cTextFaint, 0, true, false);
}

}  // namespace ui
