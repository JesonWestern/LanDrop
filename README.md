# 内网快传 LanDrop

Windows ⇄ Android / Android ⇄ Android 局域网文件互传 + 双端网速测试。零配置发现，不经过任何服务器。

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
| `tools/android_unit_test.py` | Android 单元测试（绕过中文路径下 gradlew test 的限制） |
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
./gradlew assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
./gradlew assembleDebug     # 带 adb 调试钩子的变体

# 单元测试：中文目录下 gradlew test 的 worker 会因路径编码失败（见 down 说明），用脚本跑
python tools/android_unit_test.py
```

> `tools/android_unit_test.py` 的存在原因：本项目路径含中文（`内网传输`）。Gradle 9.4.1
> 在 Windows 上给测试 worker 子进程传工作目录/类路径时会按错误字符集编码，worker 因此
> 找不到测试类并报 `ClassNotFoundException`。编译与打包不受影响，只有 fork 出来的测试
> 进程受影响；脚本改为直接 `java -cp` 拉起 JUnit。

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

## Android 1.1 新增

| 能力 | 说明 |
|---|---|
| 按类型区分的图标 | 图片/视频/音频/文档/压缩包/安装包/其它，Canvas 手绘剪影（零新增依赖） |
| 「分类」页 | 底部第 5 个标签。7 类聚合，点进去按名称/大小/时间/类型排序，可多选发送 |
| 分类数据源 | 默认读系统媒体库索引（秒开）；设置里可开「全盘深度扫描」，结果缓存 24 小时 |
| 系统分享 / 打开方式 | `SEND` / `SEND_MULTIPLE` / `VIEW` 全类型注册。跳转后弹窗勾选在线设备（可多选）再发 |
| content:// 直发 | 能定位读的 Uri 走 `ParcelFileDescriptor` 零拷贝分块读；只有管道型来源才复制到缓存 |
| 手机 ↔ 手机 | 同一 WiFi 下直连，不经电脑；修了确认超时倒挂、文件句柄热路径、换网后广播失效等问题 |

细节见 `protocol/LANPROTO.md` 与 `docs/交付说明.md`。
