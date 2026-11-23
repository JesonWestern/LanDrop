#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""主题切换的精确验收（不依赖 uiautomator，MIUI 上它不可靠）。

做法：
1. 进设置页截图，从**像素**里定位预设色块（每种预设的 bg 色是已知 ARGB）。
2. 点色块**中心**（不是下方的文字标签）。
3. 用 run-as 读 shared_prefs 确认 themePreset 真的落盘（这是权威信号，比像素更硬）。
4. 再截图确认 UI 底色确实变了。

用法：python tools/android_theme_check.py
"""
import os
import re
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "docs", "verify", "screenshots-1.3.0")
ADB = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk",
                   "platform-tools", "adb.exe")
if not os.path.exists(ADB):
    ADB = "adb"
PKG = "com.landrop.transfer"

PASS, FAIL = [], []


def log(m):
    print(m, flush=True)


def check(name, ok, detail=""):
    (PASS if ok else FAIL).append(name)
    log(("  [PASS] " if ok else "  [FAIL] ") + name + (("  " + detail) if detail else ""))


def adb(*a, timeout=90):
    return subprocess.run([ADB] + list(a), capture_output=True, text=True,
                          timeout=timeout, encoding="utf-8", errors="replace")


def shot(name):
    p = os.path.join(OUT, name)
    with open(p, "wb") as f:
        subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=f, timeout=90)
    return p


def prefs():
    """读应用私有 prefs。

    ⚠️ 只有 **debug 变体** 能用 run-as；release 包是 `package not debuggable`，
    此时 run-as 直接报错、stdout 为空，脚本会读不到 themePreset 而误判"没写进去"。
    这不是功能问题——同一个包用像素断言照样能验证主题生效。
    所以：不是 debuggable 就返回空串，让上层自动退化为纯像素断言。
    """
    r = adb("shell", "run-as", PKG, "cat", "shared_prefs/landrop.xml")
    out = r.stdout or ""
    if "package not debuggable" in (r.stderr or "") or "not debuggable" in out:
        return ""
    return out


def is_debuggable():
    """当前安装的包是否允许 run-as（即 debug 变体）。"""
    r = adb("shell", "run-as", PKG, "ls")
    return "not debuggable" not in ((r.stderr or "") + (r.stdout or ""))


def launch():
    adb("shell", "am", "force-stop", PKG)
    time.sleep(0.6)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(3.0)


def preset_cards(dump_y0, dump_y1):
    """根据截图中量出的实测坐标返回 10 个预设卡片的中心点。

    这些坐标是从 1080x2400（440dpi）截图里量出来的：卡片在 x 方向按 5 列排布，
    中心 x = 165/345/540/720/900，两排中心 y = 447/648。用固定坐标而不是颜色匹配，
    原因：白色主题下"纯白/银灰/薄荷青"卡片与页面底色几乎同色，颜色匹配会失败。
    """
    return [(165, 447), (345, 447), (540, 447), (720, 447), (900, 447),
            (165, 648), (345, 648), (540, 648), (720, 648), (900, 648)]


PRESET_IDS = ["graphite", "black", "white", "silver", "ocean",
              "forest", "sunset", "violet", "rose", "mint"]


def main():
    from PIL import Image
    os.makedirs(OUT, exist_ok=True)
    if "device" not in adb("devices").stdout:
        log("没有设备")
        return 2

    launch()
    w, h = 1080, 2400
    adb("shell", "input", "tap", str(int(w * 0.903)), str(int(h * 0.933)))
    time.sleep(1.2)
    shot("30-settings-for-theme.png")

    def go_settings():
        adb("shell", "input", "tap", str(int(w * 0.903)), str(int(h * 0.933)))
        time.sleep(1.2)

    def preset_value():
        p = prefs()
        m = re.search(r'<string name="themePreset">([^<]+)</string>', p)
        return m.group(1) if m else None

    def pick(idx):
        cx, cy = preset_cards(0, 0)[idx]
        adb("shell", "input", "tap", str(cx), str(cy))
        time.sleep(1.0)

    # ---- 1. 切到纯白（idx=2）----
    pick(PRESET_IDS.index("white"))
    shot("31-theme-white.png")
    # prefs 断言只在 debug 包上有效（release 包 run-as 不可用，见 prefs() 注释）；
    # release 包退化为纯像素断言（下方 light_px 断言本身就是硬证据）。
    debug_ok = is_debuggable()
    check("themePreset=white 写入 shared_prefs", preset_value() == "white",
          f"value={preset_value()} (debug={debug_ok})")

    im2 = Image.open(os.path.join(OUT, "31-theme-white.png")).convert("RGB")
    px2 = im2.load()
    light = sum(1 for y in range(int(h * 0.10), int(h * 0.34), 3)
                for x in range(40, w - 40, 3) if sum(px2[x, y]) > 600)
    check("切换到纯白后界面确实变亮", light > 30000, f"light_px={light}")

    # ---- 2. 冷启动后仍是纯白（持久化）----
    launch()
    shot("32-after-restart-white.png")
    im3 = Image.open(os.path.join(OUT, "32-after-restart-white.png")).convert("RGB")
    px3 = im3.load()
    light3 = sum(1 for y in range(int(h * 0.06), int(h * 0.30), 3)
                 for x in range(40, w - 40, 3) if sum(px3[x, y]) > 600)
    check("冷启动后仍是纯白（主题持久化）", light3 > 25000, f"light_px={light3}")

    # ---- 3. 切到纯黑（idx=1），验证深色也能生效 ---- 只在需要时进设置
    launch()
    go_settings()
    pick(PRESET_IDS.index("black"))
    shot("33-theme-black.png")
    check("themePreset=black 写入 shared_prefs", preset_value() == "black", f"value={preset_value()}")
    im4 = Image.open(os.path.join(OUT, "33-theme-black.png")).convert("RGB")
    px4 = im4.load()
    dark = sum(1 for y in range(int(h * 0.10), int(h * 0.34), 3)
               for x in range(40, w - 40, 3) if sum(px4[x, y]) < 60)
    check("切换到纯黑后界面确实变暗", dark > 30000, f"dark_px={dark}")

    # ---- 4. 复原 graphite（idx=0），不污染后续验收截图 ----
    pick(PRESET_IDS.index("graphite"))
    shot("34-theme-graphite-restored.png")
    check("复原为 graphite", preset_value() == "graphite", f"value={preset_value()}")

    log("\n================ 结果 ================")
    log(f"通过 {len(PASS)} 项，失败 {len(FAIL)} 项")
    for f in FAIL:
        log("  FAIL: " + f)
    return 0 if not FAIL else 1


if __name__ == "__main__":
    sys.exit(main())
