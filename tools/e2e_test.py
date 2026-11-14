#!/usr/bin/env python3
"""LanDrop 双端联调脚本。

覆盖：手机→电脑、电脑→手机（含文件夹/中文名/空目录）、网速测试。
可选：断点续传（--with-resume，会额外花约 40 秒）。

用法：
    python tools/e2e_test.py --win-ip 192.168.5.4
    python tools/e2e_test.py --win-ip 192.168.5.4 --with-resume
"""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EXE = os.path.join(ROOT, "win", "build", "out", "LanDrop.exe")
ADB = os.path.join(
    os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe"
)
if not os.path.exists(ADB):
    ADB = "adb"
PKG = "com.landrop.transfer"
RECV_ACT = f"{PKG}/.debug.DebugCommandReceiver"
WORK = os.path.join(ROOT, "build", "_e2e")

PASS, FAIL = [], []


def log(msg):
    print(msg, flush=True)


def check(name, ok, detail=""):
    (PASS if ok else FAIL).append(name)
    log(("  [PASS] " if ok else "  [FAIL] ") + name + (("  " + detail) if detail else ""))


def run(cmd, timeout=120, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout,
                          encoding="utf-8", errors="replace", **kw)


def adb(*args, timeout=120):
    return run([ADB, *args], timeout=timeout)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def phone_ip():
    out = adb("shell", "ip", "-f", "inet", "addr", "show", "wlan0").stdout
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("inet "):
            return line.split()[1].split("/")[0]
    return None


def kill_gui():
    run(["taskkill", "/F", "/IM", "LanDrop.exe"], timeout=20)


def start_server(recv_dir, port=47821):
    os.makedirs(recv_dir, exist_ok=True)
    p = subprocess.Popen(
        [EXE, "--serve", "--dir", recv_dir, "--port", str(port)],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        encoding="utf-8", errors="replace",
    )
    time.sleep(1.5)
    return p


def wait_for(path, timeout=60):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if os.path.exists(path) and os.path.getsize(path) > 0:
            return True
        time.sleep(0.4)
    return False


def make_samples():
    src = os.path.join(WORK, "samples")
    os.makedirs(src, exist_ok=True)
    small = os.path.join(src, "small.bin")
    with open(small, "wb") as f:
        f.write(os.urandom(3 * 1024 * 1024))
    big = os.path.join(src, "big.bin")
    with open(big, "wb") as f:
        for _ in range(64):
            f.write(os.urandom(1024 * 1024))
    empty = os.path.join(src, "empty.txt")
    open(empty, "wb").close()
    folder = os.path.join(src, "文件夹 测试")
    os.makedirs(os.path.join(folder, "子目录"), exist_ok=True)
    os.makedirs(os.path.join(folder, "空目录"), exist_ok=True)
    with open(os.path.join(folder, "子目录", "照片.dat"), "wb") as f:
        f.write(os.urandom(1200 * 1024))
    with open(os.path.join(folder, "readme 说明.txt"), "w", encoding="utf-8") as f:
        f.write("LanDrop e2e\n")
    return src


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--win-ip", required=True, help="电脑在局域网里的 IPv4")
    ap.add_argument("--with-resume", action="store_true")
    ap.add_argument("--skip-speed", action="store_true")
    args = ap.parse_args()

    if not os.path.exists(EXE):
        log(f"找不到 {EXE}，先跑 win/build/build.bat")
        return 2
    dev = adb("devices").stdout
    if "\tdevice" not in dev:
        log("没有连接的 Android 设备")
        return 2

    shutil.rmtree(WORK, ignore_errors=True)
    os.makedirs(WORK, exist_ok=True)
    samples = make_samples()
    pip = phone_ip()
    log(f"电脑 {args.win_ip}  ←→  手机 {pip}")
    kill_gui()

    # ---------------- 1. 手机 → 电脑 ----------------
    log("\n[1] 手机 → 电脑（单文件 3MB）")
    recv = os.path.join(WORK, "recv1")
    srv = start_server(recv)
    try:
        adb("push", os.path.join(samples, "small.bin"), "/sdcard/Download/ld_small.bin")
        adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
        time.sleep(3)
        adb("shell", "am", "broadcast", "-n", RECV_ACT, "-a", "com.landrop.DEBUG_CMD",
            "--es", "cmd", "auto", "--ez", "on", "true")
        adb("shell", "am", "broadcast", "-n", RECV_ACT, "-a", "com.landrop.DEBUG_CMD",
            "--es", "cmd", "send", "--es", "peer", args.win_ip,
            "--es", "path", "/sdcard/Download/ld_small.bin")
        target = os.path.join(recv, "ld_small.bin")
        ok = wait_for(target, 60)
        check("手机→电脑 文件到达", ok)
        if ok:
            check("手机→电脑 内容一致", sha256(target) == sha256(os.path.join(samples, "small.bin")))
    finally:
        srv.terminate()

    # ---------------- 2. 电脑 → 手机 ----------------
    log("\n[2] 电脑 → 手机（文件夹 + 中文名 + 空目录）")
    adb("shell", "rm", "-rf", "/sdcard/Download/LanDrop")
    r = run([EXE, "--send", pip, os.path.join(samples, "文件夹 测试")], timeout=180)
    ok = '"ok":true' in r.stdout
    check("电脑→手机 任务完成", ok, r.stdout.strip().splitlines()[-1] if r.stdout else "")
    listing = adb("shell", "find", "/sdcard/Download/LanDrop", "-type", "f").stdout
    check("电脑→手机 两个文件都在", listing.count("照片.dat") == 1 and "readme" in listing)
    dirs = adb("shell", "find", "/sdcard/Download/LanDrop", "-type", "d").stdout
    check("电脑→手机 空目录已建", "空目录" in dirs)
    src_photo = os.path.join(samples, "文件夹 测试", "子目录", "照片.dat")
    adb("pull", "/sdcard/Download/LanDrop/文件夹 测试/子目录/照片.dat",
        os.path.join(WORK, "photo_back.dat"))
    back = os.path.join(WORK, "photo_back.dat")
    if os.path.exists(back):
        check("电脑→手机 内容一致", sha256(back) == sha256(src_photo))
    else:
        check("电脑→手机 内容一致", False, "pull 失败")

    # ---------------- 3. 网速 ----------------
    if not args.skip_speed:
        log("\n[3] 网速测试（双向 6 秒）")
        r = run([EXE, "--speedtest", pip, "--dir", "both", "--secs", "6", "--conns", "4"],
                timeout=120)
        try:
            res = json.loads(r.stdout.strip().splitlines()[-1])
            check("测速有结果", res.get("ok") is True)
            check("双向 ≥ 200 Mbps", res.get("avgMbps", 0) >= 200, f"{res.get('avgMbps'):.0f} Mbps")
            log(f"      avg={res.get('avgMbps'):.1f} peak={res.get('peakMbps'):.1f} "
                f"p50={res.get('p50Mbps'):.1f} Mbps")
        except Exception as e:
            check("测速有结果", False, str(e))

    # ---------------- 4. 断点续传 ----------------
    if args.with_resume:
        log("\n[4] 断点续传（传 300MB，2 秒后强杀手机进程）")
        big = os.path.join(samples, "big.bin")
        recv2 = os.path.join(WORK, "recv2")
        srv = start_server(recv2)
        try:
            adb("push", big, "/sdcard/Download/ld_big.bin", timeout=300)
            adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
            time.sleep(3)
            adb("shell", "am", "broadcast", "-n", RECV_ACT, "-a", "com.landrop.DEBUG_CMD",
                "--es", "cmd", "send", "--es", "peer", args.win_ip,
                "--es", "path", "/sdcard/Download/ld_big.bin")
            time.sleep(2.5)
            adb("shell", "am", "force-stop", PKG)
            log("      已强杀，等待接收端标记暂停…")
            time.sleep(28)
            adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
            time.sleep(4)
            adb("shell", "am", "broadcast", "-n", RECV_ACT, "-a", "com.landrop.DEBUG_CMD",
                "--es", "cmd", "send", "--es", "peer", args.win_ip,
                "--es", "path", "/sdcard/Download/ld_big.bin")
            target = os.path.join(recv2, "ld_big.bin")
            ok = wait_for(target, 120)
            check("续传后文件到达", ok)
            if ok:
                check("续传后内容一致", sha256(target) == sha256(big))
        finally:
            srv.terminate()

    log("\n================ 结果 ================")
    log(f"通过 {len(PASS)} 项，失败 {len(FAIL)} 项")
    for f in FAIL:
        log("  FAIL: " + f)
    return 0 if not FAIL else 1


if __name__ == "__main__":
    sys.exit(main())
