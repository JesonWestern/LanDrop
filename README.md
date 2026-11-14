# 内网快传 LanDrop

Windows ⇄ Android 局域网文件互传 + 双端网速测试。零配置发现，不经过任何服务器。

```
┌──────────────┐        UDP 47820 广播发现        ┌──────────────┐
│  Windows     │ ⇄                              ⇄ │  Android     │
│  C++17/Win32 │        TCP 47821 控制+数据       │  Kotlin/Compose │
└──────────────┘                                  └──────────────┘
```

## 目录

| 路径 | 说明 |
|---|---|
| `win/` | Windows 端（C++17 + Win32 + GDI+，零第三方依赖） |
| `android/` | Android 端（Kotlin + Jetpack Compose） |
| `protocol/LANPROTO.md` | **线协议规范**，两端实现的唯一事实来源 |
| `tools/e2e_test.py` | 双端自动化联调 |
| `docs/交付说明.md` | 交付物、验收对照、实测数据、已知限制 |
| `docs/变更与升级说明.md` | 版本变更与升级方式 |
| `docs/verify/` | 交付门禁脚本 |

## 构建

```bat
:: Windows（需要 MinGW-w64 g++ 与 Inno Setup 6）
win\build\build.bat        :: -> win\build\out\LanDrop.exe
win\build\package.bat      :: -> win\dist\LanDropSetup-1.0.0.exe
```

```bash
# Android（AGP 9.2.1 + Kotlin 2.3.10 + compileSdk 37）
cd android
gradlew assembleRelease    # -> app/build/outputs/apk/release/app-release.apk
gradlew assembleDebug      # 带 adb 调试钩子的变体
```

## 自测

```bat
win\build\out\LanDrop.exe --selftest      :: 协议编解码断言
win\build\out\LanDrop.exe --discover --secs 5
win\build\out\LanDrop.exe --serve --dir D:\recv
win\build\out\LanDrop.exe --send 192.168.5.3 D:\some\folder
win\build\out\LanDrop.exe --speedtest 192.168.5.3 --dir both --secs 10
```

```bash
python tools/e2e_test.py --win-ip 192.168.5.4
```

## 设计要点

- **发现**：每网卡独立 socket 绑定源地址广播。Windows 默认选路实测 77% 概率发到虚拟网卡且静默丢包。
  设备列表按显示名自然排序（不用 `lastSeen`，否则多设备心跳相位差会让列表持续跳变）。
- **传输**：控制连接 NDJSON + 独立数据连接 28 字节二进制帧，1 MiB 分块、8 块窗口、逐块 CRC。
- **续传**：任务键由 peerId+rel+size+mtime 派生，接收端位图持久化，重连只补缺块。
- **接收**：Windows 先落临时目录再原子移入下载目录（Edge 式）；Android 先写私有目录再导入 `Download/LanDrop`。
- **限制**：手机熄屏后 WiFi 固件丢弃广播，应用层无解 —— 发现只在亮屏时进行。

细节见 `protocol/LANPROTO.md` 与 `docs/交付说明.md`。
