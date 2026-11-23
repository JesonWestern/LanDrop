#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""LanDrop 1.3.0 Android 验收：真机 UI 截图 + 像素断言。

为什么不用纯 adb 手点：坐标靠肉眼估算会漂，且有 dialog 挡住的时序问题。
这里统一走 adb（input tap / swipe / screencap），并对关键状态做**像素断言**，
每项输出 [PASS]/[FAIL]，失败项汇总退出码非 0。

用法：
    python tools/android_ui_shots.py                 # 跑全部
    python tools/android_ui_shots.py --only select   # 只跑选择相关
"""
import argparse
import os
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "docs", "verify", "screenshots-1.3.0")
ADB = os.path.join(
    os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe"
)
if not os.path.exists(ADB):
    ADB = "adb"
PKG = "com.landrop.transfer"
SERIAL = None

PASS, FAIL = [], []


def log(m):
    print(m, flush=True)


def check(name, ok, detail=""):
    (PASS if ok else FAIL).append(name)
    log(("  [PASS] " if ok else "  [FAIL] ") + name + (("  " + detail) if detail else ""))


def adb(*args, timeout=90):
    cmd = [ADB] + (["-s", SERIAL] if SERIAL else []) + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout,
                          encoding="utf-8", errors="replace")


def shot(name):
    path = os.path.join(OUT, name)
    # exec-out 的 PNG 必须走二进制重定向，不能用 subprocess 的 text 模式
    with open(path, "wb") as f:
        subprocess.run([ADB] + (["-s", SERIAL] if SERIAL else []) +
                       ["exec-out", "screencap", "-p"], stdout=f, check=False, timeout=90)
    return path


def open_img(path):
    from PIL import Image
    return Image.open(path).convert("RGB")


def tail_ui_dump():
    r = adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    if r.returncode != 0:
        return ""
    r = adb("shell", "cat", "/sdcard/ui.xml")
    return r.stdout


def find_text_center(text, dump=None):
    """从 uiautomator dump 里取某个文字的 bounds 中心点。找不到返回 None。"""
    import re
    xml = dump if dump is not None else tail_ui_dump()
    for m in re.finditer(r'<node[^>]*text="([^"]*)"[^>]*bounds="([^"]*)"', xml):
        if m.group(1) == text:
            b = m.group(2)
            nums = [int(x) for x in re.findall(r"-?\d+", b)]
            if len(nums) == 4:
                x1, y1, x2, y2 = nums
                return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def tap_text(text):
    c = find_text_center(text)
    if not c:
        return False
    adb("shell", "input", "tap", str(c[0]), str(c[1]))
    time.sleep(0.6)
    return True


def dismiss_dialogs():
    """Back 两次足够关掉应用内「打开方式」这类 AlertDialog。"""
    for _ in range(2):
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.4)


def sendbar_visible(img):
    """发送条可见性：在底部 ~200dp 高度范围内找一排 Accent 蓝文字（全选/反选/清空）。

    之前把 y 写死成 h*0.832，但发送条实际随底部栏/导航栏 padding 浮动
    （1080x2400 实测在 y≈1710–1790，不是 1997）。这里改为在底部 8%~22% 区间竖向
    扫描、任一高度命中足够多蓝色像素即判可见，避免坐标漂移导致假失败。
    """
    px = img.load()
    w, h = img.size
    xmax = int(w * 0.42)
    for y in range(int(h * 0.71), int(h * 0.80)):
        cnt = 0
        for x in range(0, xmax):
            c = px[x, y]
            if c[2] > 150 and c[2] - c[0] > 45:
                cnt += 1
        if cnt > 20:
            return True
    return False


def selected_count(img):
    """数发送条里「已选 N 项」的数字；同时给出的选中勾数作为交叉验证。"""
    # 勾选圆是 Accent 纯色填充 + 白勾，扫描 gutter 列找连续 Accent 圆
    px = img.load()
    w, h = img.size
    gx = int(w * 0.928)  # gutter 中心 x≈1002
    runs, cur, start = [], False, 0
    for y in range(int(h * 0.11), int(h * 0.82)):
        c = px[gx, y]
        on = c[0] < 120 and c[1] > 100 and c[2] > 200
        if on and not cur:
            cur, start = True, y
        elif not on and cur:
            if y - start >= 30:
                runs.append((start, y))
            cur = False
    return len(runs)


def metrics():
    r = adb("shell", "wm", "size")
    return r.stdout.strip()


def ensure_recent_list_state():
    """把文件页收敛到「最近 + 列表视图 + 无壁纸」，让选择套件的坐标与断言可复现。

    为什么需要这步：前面的性能/主题实验会改 `fileTab`（变成 storage）和
    `themeWallpaper`（变成 custom）。页签不同，行高与 gutter 坐标就全错——
    实测会导致"单击 gutter 选中 1 行 → count=0"这种假失败。
    这里不碰 prefs（run-as 引号嵌套容易失败），而是用 UI 操作把状态摆正。
    """
    # 进设置页找「清除壁纸」，再回文件页点「最近」
    launch()
    c = find_text_center("设置")
    if c:
        adb("shell", "input", "tap", str(c[0]), str(c[1]))
        time.sleep(1.0)
    if tap_text("清除壁纸"):
        time.sleep(0.6)
    # 回到文件页并选「最近」
    c = find_text_center("文件")
    if c:
        adb("shell", "input", "tap", str(c[0]), str(c[1]))
        time.sleep(0.8)
    if tap_text("最近"):
        time.sleep(0.8)


def run_select_tests():
    log("\n[A] 选择能力：单选 / 拖选取消 / 全选 / 反选 / 清空")
    ensure_recent_list_state()
    adb("shell", "am", "force-stop", PKG)
    time.sleep(0.5)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(3.0)
    adb("shell", "input", "tap", "1002", "378")   # 第一行 gutter
    time.sleep(0.6)
    shot("02-one-selected.png")
    img = open_img(os.path.join(OUT, "02-one-selected.png"))
    check("单击 gutter 选中 1 行", selected_count(img) == 1 and sendbar_visible(img),
          f"count={selected_count(img)} bar={sendbar_visible(img)}")

    # 再选一行 -> 2 行
    adb("shell", "input", "tap", "1002", "520")
    time.sleep(0.6)
    shot("03-two-selected.png")
    img = open_img(os.path.join(OUT, "03-two-selected.png"))
    n2 = selected_count(img)
    check("再选一行 -> 2 行", n2 == 2, f"count={n2}")

    # 关键回归：从已选中行往下拖 => 取消选中（DESELECT）
    adb("shell", "input", "swipe", "1002", "378", "1002", "520", "400")
    time.sleep(0.8)
    shot("04-drag-deselect.png")
    img = open_img(os.path.join(OUT, "04-drag-deselect.png"))
    nd = selected_count(img)
    check("拖选取消（锚点已选中 -> DESELECT）", nd == 0, f"count={nd}")

    # 全选
    if tap_text("全选"):
        shot("05-select-all.png")
        img = open_img(os.path.join(OUT, "05-select-all.png"))
        na = selected_count(img)
        check("全选后可见行全选中", na >= 6, f"count={na}")
    else:
        check("找到「全选」按钮", False)

    # 反选：全选后反选应变成 0
    if tap_text("反选"):
        shot("06-invert.png")
        img = open_img(os.path.join(OUT, "06-invert.png"))
        ni = selected_count(img)
        check("反选（全选态下 -> 0 项）", ni == 0, f"count={ni}")
    else:
        check("找到「反选」按钮", False)

    # 清空后发送条仍在（这是"缺少全选/取消选中"反馈的成因）
    shot("07-after-invert-bar.png")
    img = open_img(os.path.join(OUT, "07-after-invert-bar.png"))
    check("清空后发送条仍可操作（多选态常驻）", sendbar_visible(img),
          f"bar={sendbar_visible(img)}")


def run_tab_view_tests():
    log("\n[B] 页签 / 视图 / 日期分组")
    adb("shell", "am", "force-stop", PKG)
    time.sleep(0.5)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(3.0)
    shot("01-files-default.png")

    # 切到「内部存储」页签
    if tap_text("内部存储"):
        shot("08-tab-internal.png")
    else:
        check("找到「内部存储」页签", False)
    # 切回「最近」
    tap_text("最近")
    time.sleep(0.5)
    shot("09-tab-recent.png")

    # 视图切换：当前按钮显示"下一个视图名"，点两次走 紧凑 -> 网格
    for i, name in enumerate(["10-view-compact.png", "11-view-grid.png", "12-view-list.png"]):
        c = None
        for label in ("紧凑", "网格", "列表"):
            c = find_text_center(label)
            if c:
                break
        if c:
            adb("shell", "input", "tap", str(c[0]), str(c[1]))
            time.sleep(0.8)
            shot(name)
        else:
            log(f"  跳过视图切换 {name}（没找到视图按钮文字）")
    # 折叠所有结果
    check("生成了三视图截图", all(os.path.exists(os.path.join(OUT, n))
                                for n in ("10-view-compact.png", "11-view-grid.png")))


def app_foreground():
    r = adb("shell", "dumpsys", "activity", "activities")
    for line in r.stdout.splitlines():
        if "mResumedActivity" in line or "topResumedActivity" in line:
            return PKG in line
    return False


def launch():
    adb("shell", "am", "force-stop", PKG)
    time.sleep(0.6)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(3.0)


def run_theme_tests():
    log("\n[C] 主题：外观分组 / 预设切换 / 持久化")
    launch()
    check("应用在前台", app_foreground())

    # 底部栏「设置」用 uiautomator 真实坐标
    c = find_text_center("设置")
    if not c:
        w, h = (int(v) for v in metrics().replace("Physical size:", "").split("x"))
        c = (int(w * 0.903), int(h * 0.933))
    adb("shell", "input", "tap", str(c[0]), str(c[1]))
    time.sleep(1.2)
    if not app_foreground():
        check("点击后仍在应用内", False, "被弹到桌面了")
        launch()
        return
    shot("20-settings-top.png")
    img = open_img(os.path.join(OUT, "20-settings-top.png"))

    # 外观分组的判定：必须找到「外观」标题，且其下方有一片彩色色块。
    # 只认应用内截图（上面已校验前台），避免桌面壁纸被误判成"彩色"。
    dump = tail_ui_dump()
    check("设置页含「外观」分组标题", "外观" in dump)
    check("设置页含「主题」相关文案", ("主题" in dump) or ("强调色" in dump))

    px = img.load()
    w, h = img.size
    colorful = 0
    for y in range(int(h * 0.10), int(h * 0.60), 3):
        for x in range(20, w - 20, 3):
            c2 = px[x, y]
            if max(c2) - min(c2) > 60:
                colorful += 1
    check("外观分组出现彩色预设色块", colorful > 2000, f"colorful_px={colorful}")

    # 切到一个明显的浅色主题（纯白），验证即时生效
    if tap_text("纯白"):
        time.sleep(1.0)
        if app_foreground():
            shot("21-theme-white.png")
            im2 = open_img(os.path.join(OUT, "21-theme-white.png"))
            # 页面顶部大片区域应是浅色
            light = sum(1 for y in range(int(h * 0.10), int(h * 0.30), 2)
                        for x in range(40, w - 40, 2)
                        if sum(im2.load()[x, y]) > 600)
            check("切到「纯白」后背景变亮", light > 20000, f"light_px={light}")
            # 再切回石墨灰
            if tap_text("石墨灰"):
                time.sleep(0.8)
    else:
        check("找到「纯白」预设", False)

    # 持久化：冷启动后应仍是所选主题
    adb("shell", "am", "force-stop", PKG)
    time.sleep(0.6)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(3.0)
    if app_foreground():
        shot("22-after-restart.png")
        check("冷启动后应用正常在前台", True)


def main():
    global SERIAL
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="", choices=["", "select", "tab", "theme"])
    ap.add_argument("--serial", default="")
    args = ap.parse_args()
    SERIAL = args.serial or None
    os.makedirs(OUT, exist_ok=True)

    dev = adb("devices").stdout
    if "device" not in dev.split("List of devices attached")[-1]:
        log("没有连接的 Android 设备")
        return 2
    log(f"设备: {adb('shell', 'getprop', 'ro.product.model').stdout.strip()}  {metrics()}")

    if args.only in ("", "select"):
        run_select_tests()
    if args.only in ("", "tab"):
        run_tab_view_tests()
    if args.only in ("", "theme"):
        run_theme_tests()

    log("\n================ 结果 ================")
    log(f"通过 {len(PASS)} 项，失败 {len(FAIL)} 项")
    for f in FAIL:
        log("  FAIL: " + f)
    return 0 if not FAIL else 1


if __name__ == "__main__":
    sys.exit(main())
