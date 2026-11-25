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
win\build\package.bat      :: -> win\dist\LanDropSetup-1.3.0.exe
```

```bash
# Android（AGP 9.2.1 + Kotlin 2.3.10 + compileSdk 37）
cd android
./gradlew assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
./gradlew assembleDebug     # 带 adb 调试钩子的变体

# 单元测试：中文目录下 gradlew test 的 worker 会因路径编码失败（见 down 说明），用脚本跑
python tools/android_unit_test.py

# Android 交付门禁：版本号 / 签名 / FileProvider / queries / 权限 / SHA-256
powershell -NoProfile -ExecutionPolicy Bypass -File docs\verify\check_apk.ps1

# 真机 UI 验收（选择/页签/三视图截图 + 像素断言）与主题验收
python tools/android_ui_shots.py            # 选择能力 + 页签/视图截图
python tools/android_theme_check.py         # 主题切换 + 持久化（读 shared_prefs 断言）
```

> `tools/android_unit_test.py` 的存在原因：本项目路径含中文（`内网传输`）。Gradle 9.4.1
> 在 Windows 上给测试 worker 子进程传工作目录/类路径时会按错误字符集编码，worker 因此
> 找不到测试类并报 `ClassNotFoundException`。编译与打包不受影响，只有 fork 出来的测试
> 进程受影响；脚本改为直接 `java -cp` 拉起 JUnit。

## 自测

```bat
win\build\out\LanDrop.exe --selftest      :: 协议编解码 + 主题断言
win\build\out\LanDrop.exe --version       :: 版本与协议号
win\build\out\LanDrop.exe --dpi 144       :: 强制 DPI（仅测试用，不写配置）
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

## Android 1.2 新增（2026-11-22）

| 能力 | 说明 |
|---|---|
| 列表缩略图 | 图片显示 40dp 缩略图（EXIF 方向、HEIC，媒体库来源走系统 `loadThumbnail`），APK 显示应用图标，其余类型保持手绘图标；LruCache + 4 路并发解码，零新增依赖 |
| 返回手势 | 子目录返回上一级（先清选择、再返回，钳制在内部存储根内）；分类详情返回宫格；其他 tab 返回文件页；只有根目录才退出 |
| 点文件打开 | 正文区点击走系统默认应用；没有默认应用时用应用内「打开方式」（只列其他应用，杜绝“打开又回到分享弹窗”）；APK 引导「安装未知应用」 |
| 选择区与拖选 | 右侧 56dp 选择区（1dp 分割线 + 底色标注），点圆圈切换选中，按住该区域上下拖动连续选中；正文拖动仍是滚动；选中行整行高亮 |
| 分类计数修正 | 剔除 MediaStore 目录行 / 已删除的失联行 / 隐藏父目录 / 重复路径；修正 `QUERY_ARG_SQL_SORT_ORDER` 参数类型（超 2 万行时不再静默取最旧）；截断在页面上明示 |
| 传输明细 | 任务可展开为按文件列表（名称/大小/单文件进度/完成），收到的文件与发送的本地文件可点击打开；单文件任务在任务卡直接「打开」 |
| 兼容性 | Android 8-10：`isExternalStorageManager` / `MediaStore.Downloads` 分支保护、存储运行时权限、旧版落盘路径；Android 11+ 行为不变 |
| 索引新鲜度 | 接收完成主动让分类缓存失效，并同时观察 Images/Video/Audio/Downloads；分类页 15 秒 TTL |

版本 `1.2.0`（`versionCode 3`），签名与 1.0/1.1 相同，可直接覆盖安装。协议仍为 `v1`，Windows 端未改动。

## 1.3 新增（2026-11-23，双端同步）— 文件页体验 · 主题 · 动画

| 能力 | 说明 |
|---|---|
| 文件页页签 | 顶部左侧「最近 / 内部存储」两个页签；最近文件上限 12 → **200** 条 |
| 最近按日期分组 | 今天 / 昨天 / `M月d日`（跨年补年份），段内按修改时间倒序 |
| 三种视图 | 列表（60dp + 副标题）/ 紧凑（44dp）/ 网格（3 列 96dp 缩略图）；切换即持久化；网格不做拖选 |
| 拖动取消选中 | 同一手势按锚点状态自选方向：锚点已选中 → 本次拖动**取消**，否则选中；区间从基线重算，拖回即还原 |
| 全选 / 反选 / 清空 | 发送条常驻四个操作，**多选态常驻**（清空后条子不消失），作用域 = 当前页签可见行 |
| 切换零等待 | `FileRepo` TTL 缓存 + 启动预热；首帧同步读缓存，`LanDropPerf` 实测 4 次切换全部 `cached=true`（206–626ms） |
| 主题系统（双端同套） | **10 预设**（石墨灰=默认 / 纯黑 / 纯白 / 银灰 / 深海蓝 / 松林绿 / 落日橙 / 夜紫 / 玫瑰紫 / 薄荷青）+ **12 强调色**；强调色派生 `onAccent`，浅色主题不出现白底白字 |
| 自定义背景 | 换背景图 + 0–80% 暗化强度；Android 降采样 ≤1440px 存私有目录，Windows 存路径 + DIB 缓存 |
| 集成进设置 | 两端设置页均新增**置顶「外观」分组**，改动即时生效并持久化 |
| 页面切换动画 | 180ms 方向感 slide+fade；系统/设置关闭动画时自动退化为瞬时切换；窄窗口/DPI 变化不溢出 |
| 兼容性修复 | Windows 800×600 布局重叠（左栏动态收缩 + 顶栏按余量省略 IP + 「清除已完成」按间隙显隐） |

版本 `1.3.0`（Android `versionCode 4` / Windows `1.3.0.0`），签名未变，可直接覆盖安装。
协议仍为 `v1`：UDP 47820 发现、TCP 47821 传输、断点续传全部未改动（e2e 6/6 通过即回归证明）。
验收证据见 `docs/verify/{unit-tests,e2e,check-apk}-1.3.0.log`（真机截图体积较大，未纳入仓库）。

