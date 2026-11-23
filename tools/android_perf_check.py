#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""LanDrop Android 性能门禁：掉帧率 + 单帧阶段分解 + 冷启动。

## 为什么这么测（踩过的坑，别重犯）

1. **不要用 `input swipe` 做滚动基准**。它注入的是一串合成事件，会把输入队列压满
   （`Number High input latency` 一度到 557，远超总帧数），把测量噪音当成真实卡顿。
   实测同一构建：`input swipe` 得 24.4% 掉帧，`motionevent DOWN/MOVE.../UP` 只得 0.82%。
   → 本脚本统一用 motionevent 序列，节奏接近真实手指。

2. **只看掉帧率会漏掉「单次尖峰」**。用户体感卡顿常常只是一帧 150~300ms，
   掉帧率可能只有 5%。所以必须同时看 `framestats` 里的**最慢单帧**与它的阶段分解：
   - `HandleInput` 大 → 主线程在处理输入/点击回调（组合、状态切换）
   - `PerformTraversals` 大 → 测量/布局
   - `IssueDrawCommands` 大 → 绘制命令过多
   - GPU 列大 → 渲染/纹理

3. **稳态 vs 首次**要分开。首次组合/首次解码缩略图的成本只出现一次，
   混在一起会得出「滚动很卡」的错误结论。本脚本先预热两轮再采集。

用法：
    python tools/android_perf_check.py                      # 全套（推荐）
    python tools/android_perf_check.py --only scroll        # 只测滚动
    python tools/android_perf_check.py --only tab           # 只测切页尖峰
    python tools/android_perf_check.py --only cold          # 只测冷启动
    python tools/android_perf_check.py --out docs/verify/perf-1.3.1.log

退出码：0 全通过，1 有失败项，2 环境问题。
"""
import argparse
import os
import re
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk",
                   "platform-tools", "adb.exe")
if not os.path.exists(ADB):
    ADB = "adb"
PKG = "com.landrop.transfer"

# 1080x2400 / 440dpi 实测坐标
TAB_Y = 2265
TABS = {"files": 94, "category": 312, "transfer": 540, "speed": 756, "settings": 972}
FILE_TAB_RECENT = (200, 130)
FILE_TAB_STORAGE = (420, 130)

# 门限。
#
# 这些数值是**实测校准**的，不是理想值：
#  · 滚动：1.3.1 实测 0.0%，卡 1.5% 留余量（1.3.0 为 1.59%）。
#  · 切页 p99：这是「点击到该页最慢单帧」的耗时，包含 Compose 首帧组合 +
#    Android 首次绘制该页的 Skia 管线建立（一次性，之后同页会快很多）。
#    1.3.1 实测最优/最差：transfer 57ms、files 89ms、speed 117ms、
#    settings 150ms、category 200ms。卡 220ms 是"不劣于当前基线"的回归闸，
#    不是体验理想值；要真正压到 <60ms 需要异步分帧组合（见 docs 说明的后续项）。
#  · 冷启动：1.3.1 实测中位 1605ms，其中「进程启动→Engine.init」占 ~555ms
#    是 Application/类加载成本（框架侧），卡 1750ms 防退化。
LIM_SCROLL_JANKY = 1.5     # %  稳态滚动
LIM_TAB_P99_MS = 220       # ms 切页最慢单帧（回归闸，非理想值）
LIM_COLD_MS = 1750         # ms 冷启动 am start -W TotalTime（回归闸）

PASS, FAIL = [], []


def log(msg, out=None):
    print(msg, flush=True)
    if out:
        with open(out, "a", encoding="utf-8") as f:
            f.write(msg + "\n")


def check(name, ok, detail, out=None):
    (PASS if ok else FAIL).append(name)
    log(("  [PASS] " if ok else "  [FAIL] ") + name + (("  " + detail) if detail else ""), out)


def adb(*args, timeout=90, want_binary=False):
    cmd = [ADB, "shell"] + list(args)
    if want_binary:
        return subprocess.run(cmd, capture_output=True, timeout=timeout)
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout,
                          encoding="utf-8", errors="replace")


def gfx_reset():
    adb("dumpsys", "gfxinfo", PKG, "reset")


def gfx_summary():
    o = adb("dumpsys", "gfxinfo", PKG).stdout
    s = {}
    for key, pat, cast in (
        ("frames", r"Total frames rendered: (\d+)", int),
        ("janky", r"Janky frames: (\d+) \(([\d.]+)%\)", None),
        ("p50", r"50th percentile: (\d+)ms", int),
        ("p90", r"90th percentile: (\d+)ms", int),
        ("p99", r"99th percentile: (\d+)ms", int),
        ("gpu90", r"90th gpu percentile: (\d+)ms", int),
        ("slow_ui", r"Number Slow UI thread: (\d+)", int),
        ("slow_draw", r"Number Slow issue draw commands: (\d+)", int),
        ("high_input", r"Number High input latency: (\d+)", int),
    ):
        m = re.search(pat, o)
        if key == "janky":
            s["janky"] = int(m.group(1)) if m else 0
            s["janky_pct"] = float(m.group(2)) if m else 0.0
        else:
            s[key] = cast(m.group(1)) if m else 0
    return s


def gfx_framestats():
    """返回最近一批帧的阶段分解（ms）。列定义见 Android framestats 文档。"""
    o = adb("dumpsys", "gfxinfo", PKG, "framestats").stdout
    idx = o.find("---PROFILEDATA---")
    if idx < 0:
        return []
    rows = [ln for ln in o[idx:].splitlines() if ln.strip().startswith(tuple("0123456789"))]
    frames = []
    for ln in rows:
        c = ln.split(",")
        if len(c) < 17:
            continue
        try:
            intended = int(c[2])
            completed = int(c[16])
            handle_input = (int(c[7]) - int(c[5])) / 1e6 if c[5] and c[7] else 0.0
            traversals = (int(c[8]) - int(c[7])) / 1e6 if c[7] and c[8] else 0.0
            draw_cmd = (int(c[15]) - int(c[14])) / 1e6 if c[14] and c[15] else 0.0
            frames.append({
                "total": (completed - intended) / 1e6,
                "input": handle_input,
                "traverse": traversals,
                "draw": draw_cmd,
            })
        except (ValueError, IndexError):
            continue
    return frames


def gesture_scroll(n, pause=0.28):
    """真实手势滚动。

    ⚠️ 实现要点（踩过坑）：**不要**用逐条 `adb shell input motionevent` 拼手势。
    每次 `adb shell` 调用本身要 30~50ms，6 个 MOVE 就让"手指"在屏上停留 200~300ms，
    与 60fps 的滚动节奏完全不符，输入队列被自己堆满 → 实测同一版本会得出
    0.0% 与 25% 两种结果（纯粹是注入时序噪声）。
    正确做法是让**设备端**一次完成整段滑动：用单条 `input swipe` 配上足够长的
    持续时间（500ms ≈ 手指慢慢划过），一次 adb 调用完成，节奏稳定可复现。
    """
    for _ in range(n):
        adb("input", "swipe", "540", "1500", "540", "900", "500")
        time.sleep(pause)


def tap(x, y=None, wait=0.8):
    """tap(x, y) 或 tap((x, y)) 皆可——两种调用风格混用时容易漏参数，这里统一收口。"""
    if y is None:
        x, y = x
    adb("input", "tap", str(int(x)), str(int(y)))
    time.sleep(wait)


def launch(wait=3.0):
    adb("am", "force-stop", PKG)
    time.sleep(0.6)
    adb("am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(wait)


def warm_up():
    """预热两轮：把首次组合与首次缩略图解码的成本排掉，只测稳态。"""
    launch()
    gesture_scroll(3)
    tap(*FILE_TAB_STORAGE, wait=0.8)
    gesture_scroll(2)
    tap(*FILE_TAB_RECENT, wait=0.8)
    gesture_scroll(3)


def measure_scroll(out):
    log("\n[A] 稳态滚动（最近页签，真实手势）", out)
    warm_up()
    gfx_reset()
    gesture_scroll(8)
    s = gfx_summary()
    frames = gfx_framestats()
    slowest = max((f["total"] for f in frames), default=0.0)
    log(f"  总帧 {s['frames']}  掉帧 {s['janky']} ({s['janky_pct']}%)  "
        f"p50/p90/p99 = {s['p50']}/{s['p90']}/{s['p99']}ms  "
        f"最慢单帧 {slowest:.1f}ms", out)
    log(f"  GPU p90={s['gpu90']}ms  SlowUI={s['slow_ui']}  SlowDraw={s['slow_draw']}", out)
    check("滚动没有采到帧", s["frames"] > 0, f"frames={s['frames']}", out)
    check(f"滚动掉帧率 < {LIM_SCROLL_JANKY}%", s["janky_pct"] < LIM_SCROLL_JANKY,
          f"{s['janky_pct']}%", out)
    return s


def measure_tabs(out):
    log("\n[B] 切换 tab 的单帧尖峰（每页取最慢帧）", out)
    launch()
    tap(TABS["files"], TAB_Y, wait=1.2)
    worst = {}
    for name in ("category", "files", "transfer", "settings", "speed"):
        tap(TABS[name], TABS[name] and TAB_Y, wait=1.0)
        gfx_reset()
        back = TABS["files"] if name != "files" else TABS["category"]
        tap(back, TAB_Y, wait=1.2)
        frames = gfx_framestats()
        s = gfx_summary()
        p99 = s["p99"]
        slowest = max((f["total"] for f in frames), default=0.0)
        hit = next((f for f in frames if abs(f["total"] - slowest) < 0.01), None)
        worst[name] = p99
        log(f"  {name:9s} p99={p99:3d}ms  最慢帧={slowest:6.1f}ms  "
            f"(input={hit['input']:.1f}/layout={hit['traverse']:.1f}/"
            f"draw={hit['draw']:.1f})", out)
    mx = max(worst.values()) if worst else 0
    check(f"切页最慢 p99 < {LIM_TAB_P99_MS}ms", mx < LIM_TAB_P99_MS, f"{mx}ms", out)
    return worst


def measure_cold(out):
    log("\n[C] 冷启动（am start -W，5 次取中位）", out)
    times = []
    for _ in range(5):
        adb("am", "force-stop", PKG)
        time.sleep(0.7)
        o = adb("am", "start", "-W", "-n", f"{PKG}/.MainActivity").stdout
        m = re.search(r"TotalTime: (\d+)", o)
        if m:
            times.append(int(m.group(1)))
        time.sleep(0.4)
    times.sort()
    med = times[len(times) // 2] if times else 0
    log(f"  中位 {med}ms  最小 {times[0] if times else 0}ms  "
        f"最大 {times[-1] if times else 0}ms  样本 {times}", out)
    check(f"冷启动中位 < {LIM_COLD_MS}ms", 0 < med < LIM_COLD_MS, f"{med}ms", out)
    return med


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="all", choices=["all", "scroll", "tab", "cold"])
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    dev = subprocess.run([ADB, "devices"], capture_output=True, text=True,
                         encoding="utf-8", errors="replace").stdout
    if "\tdevice" not in dev:
        log("没有连接的 Android 设备")
        return 2
    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)

    ver = adb("shell", "dumpsys", "package", PKG).stdout
    m = re.search(r"versionName=([\d.]+)", ver)
    model = adb("shell", "getprop", "ro.product.model").stdout.strip()
    sdk = adb("shell", "getprop", "ro.build.version.sdk").stdout.strip()
    size = adb("shell", "wm", "size").stdout.strip()
    log(f"设备 {model} · API {sdk} · 版本 {m.group(1) if m else '?'} · {size}", args.out)

    if args.only in ("all", "scroll"):
        measure_scroll(args.out)
    if args.only in ("all", "tab"):
        measure_tabs(args.out)
    if args.only in ("all", "cold"):
        measure_cold(args.out)

    log("\n================ 结果 ================", args.out)
    log(f"通过 {len(PASS)} 项，失败 {len(FAIL)} 项", args.out)
    for f in FAIL:
        log("  FAIL: " + f, args.out)
    return 0 if not FAIL else 1


if __name__ == "__main__":
    sys.exit(main())
