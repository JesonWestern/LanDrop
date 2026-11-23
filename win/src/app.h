// app.h -- 全局契约：数据结构、消息、定时器、模块接口
#pragma once

#include "proto.h"
#include "json.h"

#include <atomic>
#include <memory>
#include <string>
#include <vector>

// ---- 版本 -----------------------------------------------------------------
#define LANDROP_VERSION_W   L"1.1.0"
#define LANDROP_VERSION_A   "1.1.0"
#define LANDROP_APP_NAME    L"内网快传 LanDrop"
#define LANDROP_WND_CLASS   L"LanDropMainWnd"
#define LANDROP_MUTEX_NAME  L"LanDropSingleInstance_v1"
#define LANDROP_RUN_KEY     L"LanDrop"
#define LANDROP_IPC_CLASS   L"LanDropIpcWnd"

// ---- 自定义消息 / 定时器 --------------------------------------------------
#define WM_APP_WAKE        (WM_APP + 10)  // worker -> UI 唤醒（重绘 + 处理待确认任务）
#define WM_APP_TRAY        (WM_APP + 11)  // 托盘回调
#define WM_APP_FW_DONE     (WM_APP + 12)  // 防火墙安装结果
#define WM_APP_IPC_SEND    (WM_APP + 13)  // 单实例转发待发送路径

#define TIMER_UI           1              // 200ms / 100ms 自适应
#define TIMER_RETRY        2

// ---- 命令 ID --------------------------------------------------------------
enum : UINT {
    IDM_SEND_FILES = 1001,
    IDM_SEND_FOLDER,
    IDM_SETTINGS,
    IDM_SPEEDTEST,
    IDM_OPEN_SAVE_DIR,
    IDM_TOGGLE_TRAY,
    IDM_AUTOSTART,
    IDM_FIX_FIREWALL,
    IDM_ABOUT,
    IDM_EXIT = 1090,
    IDM_CTX_SEND = 1100,   // 资源管理器右键
};

// ---- 任务 -----------------------------------------------------------------
enum class JobDir : uint8_t { Send, Recv };

enum class JobState : uint8_t {
    Queued,          // 已入队
    WaitingAccept,   // 接收端等待用户确认
    Handshaking,     // 握手 / 清单协商
    Transferring,
    Verifying,
    Done,
    Failed,
    Canceled,
    Paused,          // 可续传
};

const wchar_t* StateText(JobState s);
bool StateIsActive(JobState s);

struct FileEntry {
    std::wstring rel;        // 相对路径，'\\' 分隔（接收端已 sanitize）
    std::wstring srcAbs;     // 发送端本地绝对路径（带 \\?\ 前缀）
    uint64_t size = 0;
    uint64_t mtime = 0;      // 100ns since 1601 UTC
    uint32_t attrs = 0;
    uint32_t chunkCount = 0;
    uint32_t crc32 = 0;      // 0 表示未计算
    bool isDir = false;      // 空目录占位
    uint32_t doneChunks = 0;
    std::vector<uint32_t> doneBits;  // 位图：chunk 已落盘 / 已 ACK

    bool HasChunk(uint32_t i) const {
        const uint32_t w = i >> 5, b = 1u << (i & 31);
        return w < doneBits.size() && (doneBits[w] & b) != 0;
    }
    void MarkChunk(uint32_t i) {
        const uint32_t w = i >> 5, b = 1u << (i & 31);
        if (w >= doneBits.size()) return;
        if (doneBits[w] & b) return;
        doneBits[w] |= b;
        ++doneChunks;
    }
    void InitBits() { doneBits.assign((chunkCount + 31) / 32, 0); doneChunks = 0; }
};

struct Device {
    std::string id;          // uuid，广播报文字段
    std::wstring name;
    std::string plat;        // "win" / "android"
    std::string ver;
    uint32_t ip = 0;         // 主机序 IPv4
    uint16_t port = proto::kTransferPort;
    uint32_t caps = 0;
    uint64_t lastSeen = 0;   // GetTickCount64
    bool trusted = false;
};

struct Job {
    uint32_t id = 0;
    JobDir dir = JobDir::Recv;
    std::atomic<JobState> state{JobState::Queued};
    Device dev;
    std::wstring title;                 // 列表显示名（首文件名 / "N 个项目"）
    std::vector<FileEntry> files;
    std::wstring jobKey;                // 续传键（16 位十六进制）
    std::wstring tmpDir;                // 临时根
    std::wstring saveDir;               // 目标目录
    std::wstring savePath;              // 完成后实际路径（文件或文件夹）
    uint64_t totalBytes = 0;
    uint32_t totalFiles = 0;
    std::atomic<uint64_t> doneBytes{0};
    std::atomic<uint32_t> doneFiles{0};
    std::atomic<uint32_t> ackedChunks{0};
    std::atomic<uint32_t> totalChunks{0};
    std::atomic<bool> cancel{false};
    std::atomic<uint32_t> errCode{0};
    std::wstring errMsg;
    std::atomic<double> bps{0.0};
    uint64_t startTick = 0;
    uint64_t endTick = 0;
    uint64_t prevBytes = 0;
    uint64_t prevTick = 0;
    bool accepted = false;              // 接收端：用户已确认
    std::atomic<bool> acceptFlag{false};
    bool promptShown = false;
    bool trustedPeer = false;
    bool resumeAttempted = false;
};

struct JobView {
    uint32_t id = 0;
    JobDir dir = JobDir::Recv;
    JobState state = JobState::Queued;
    std::wstring title;
    std::wstring deviceName;
    std::wstring savePath;
    std::wstring errMsg;
    uint64_t totalBytes = 0;
    uint64_t doneBytes = 0;
    uint32_t totalFiles = 0;
    uint32_t doneFiles = 0;
    double bps = 0.0;
    uint32_t errCode = 0;
    uint64_t startTick = 0;
    uint64_t endTick = 0;
    double Progress() const {
        if (totalBytes == 0) return state == JobState::Done ? 1.0 : 0.0;
        double p = (double)doneBytes / (double)totalBytes;
        return p > 1.0 ? 1.0 : p;
    }
};

// ---- 配置 -----------------------------------------------------------------
struct AppConfig {
    int cfgver = 1;
    std::wstring deviceId;      // 持久化随机 UUID
    std::wstring deviceName;
    std::wstring saveDir;       // 接收落盘目录
    std::wstring tmpDir;        // 临时目录
    int dataConns = 2;          // 传输并发连接
    int speedConns = 4;         // 测速并发连接
    int speedSecs = 10;
    bool autoAcceptTrusted = true;
    bool skipHiddenSystem = true;
    bool minimizeToTray = true;
    bool autoStart = false;
    bool firewallPrompted = false;
    int listenPort = proto::kTransferPort;
    int discoverPort = proto::kDiscoverPort;
    std::vector<std::wstring> trustedIds;
};

struct AppState {
    HINSTANCE inst = nullptr;
    HWND mainWnd = nullptr;
    HWND ipcWnd = nullptr;
    UINT dpi = 96;
    AppConfig cfg;
    std::atomic<bool> stop{false};
    std::atomic<int> activeWorkers{0};
    std::vector<std::wstring> pendingSendPaths;  // 右键 / 二次启动传入
    bool netIsPublic = false;
    bool firewallOk = false;
    std::atomic<bool> sawInbound{false};  // 收到过入站连接 = 防火墙实际是通的
    bool headless = false;   // CLI 模式：自动接受所有传输
    std::wstring localIpText;
};

extern AppState g_app;

// ---- 模块接口 -------------------------------------------------------------
namespace cfg {
bool Load();
bool Save();
void ResetDefaults();
void EnsureDirs();
void SetAutoStart(bool on);
bool GetAutoStart();
bool IsTrusted(const std::string& id);
void TrustDevice(const std::string& id);
void ForgetDevice(const std::string& id);
// 历史（接收/发送记录持久化）
struct HistRow {
    std::wstring title, savePath, device, timeText;
    uint64_t bytes = 0;
    int dir = 0;      // 0=recv 1=send
    int state = 0;
};
void HistoryAppend(const HistRow& r);
std::vector<HistRow> HistoryLoad();
void HistoryClear();
}  // namespace cfg

namespace logx {
void Init();
void Shutdown();
void Line(const char* fmt, ...);
void Flush();
std::wstring Path();
}  // namespace logx

namespace util {
std::wstring Widen(const std::string& s);
std::string Narrow(const std::wstring& s);
std::wstring AddLong(const std::wstring& p);
std::wstring StripLong(const std::wstring& p);
bool EnsureDir(const std::wstring& dir);
bool PathExists(const std::wstring& p);
bool IsDirPath(const std::wstring& p);
std::wstring JoinPath(const std::wstring& a, const std::wstring& b);
std::wstring FileNameOf(const std::wstring& p);
std::wstring ExtOf(const std::wstring& p);
std::wstring BaseOf(const std::wstring& p);       // 去掉扩展名的文件名
std::wstring ParentOf(const std::wstring& p);
std::wstring FormatBytes(uint64_t n);
std::wstring FormatSpeed(double bps);
std::wstring FormatEta(double sec);
std::wstring FormatTimeShort(uint64_t filetime100ns);
std::wstring NowTimeText();
uint64_t NowMs();
uint32_t RandomU32();
std::string MakeUuid();
std::wstring SanitizeComponent(const std::wstring& in);
bool IsSafeRelPath(const std::wstring& rel);
std::wstring UniquePath(const std::wstring& dir, const std::wstring& base, const std::wstring& ext);
std::wstring AppDataDir();       // %APPDATA%\LanDrop
std::wstring LocalAppDataDir();  // %LOCALAPPDATA%\LanDrop
std::wstring ExeDir();
std::wstring DefaultSaveDir();
std::wstring DownLoadsDir();
uint64_t Fnv1a64(const void* data, size_t len);
std::wstring Hex16(uint64_t v);
}  // namespace util

namespace netu {
bool WsaInit();
void WsaCleanup();
struct Iface {
    uint32_t ip = 0;      // 主机序
    uint32_t mask = 0;
    std::wstring name;
};
std::vector<Iface> EnumIfaces();
bool IsLocalAddress(uint32_t ipHostOrder);
uint32_t PrimaryIp();
std::wstring IpToText(uint32_t ipHostOrder);
uint32_t ParseIp(const std::wstring& s);
int NetCategory();  // 0=unknown 1=public 2=private 3=domain
bool SetSockBuf(SOCKET s, int bytes);
bool SetNoDelay(SOCKET s);
bool SetNonBlocking(SOCKET s, bool on);
}  // namespace netu

namespace disc {
bool Start();
void Stop();
void Snapshot(std::vector<Device>* out);
void SetNotifyWindow(HWND h);
void ForceProbe();
}  // namespace disc

namespace jobs {
void Init();
void Shutdown();
std::shared_ptr<Job> Create(JobDir dir);
void Add(const std::shared_ptr<Job>& j);
void Snapshot(std::vector<JobView>* out);
std::shared_ptr<Job> Find(uint32_t id);
void Cancel(uint32_t id);
void Remove(uint32_t id);       // 仅移除已结束任务的记录
void RemoveFinished();
void NoteProgressTick(const std::shared_ptr<Job>& j);  // 每秒刷新 bps
bool AnyActive();
}  // namespace jobs

namespace storage {
struct Layout {
    std::wstring root;    // tmp\<jobKey>
    std::wstring dataDir; // root\data
    std::wstring meta;    // root\meta.json
};
std::wstring MakeJobKey(const std::string& peerId, const std::vector<FileEntry>& files,
                        uint32_t chunkBytes);
bool Prepare(const std::shared_ptr<Job>& j, Layout* out);
bool LoadMeta(const std::shared_ptr<Job>& j, const Layout& lay);
bool SaveMeta(const std::shared_ptr<Job>& j, const Layout& lay);
bool CommitFile(const std::wstring& partPath, const std::wstring& finalPath);
bool MoveToSaveDir(const std::wstring& src, const std::wstring& dstDir, std::wstring* outPath);
void GcTemp(int days);
std::wstring PartPathFor(const Layout& lay, const std::wstring& rel);
bool EnsureParent(const std::wstring& filePath);
bool RemoveTree(const std::wstring& path);
}  // namespace storage

namespace srv {
bool Start(uint16_t port);
void Stop();
void SetNotifyWindow(HWND h);
}  // namespace srv

namespace client {
struct SendOptions {
    uint32_t ip = 0;
    uint16_t port = proto::kTransferPort;
    int conns = 2;
    bool autoAccept = false;   // headless 模式
};
bool StartSend(const std::vector<std::wstring>& paths, const SendOptions& opt,
               const std::wstring& titleHint);
}  // namespace client

namespace speed {
struct Params {
    uint32_t ip = 0;
    uint16_t port = proto::kTransferPort;
    int conns = 4;
    int secs = 10;
    int dirMode = 0;     // 0=up 1=down 2=both
};
struct Result {
    bool ok = false;
    double avgMbps = 0, peakMbps = 0, p50Mbps = 0;
    uint64_t bytesUp = 0, bytesDown = 0;
    int secs = 0;
    int conns = 0;
    std::wstring err;
};
bool Start(const Params& p, const std::wstring& peerName);
Result RunBlocking(const Params& p);
void Stop();
bool IsRunning();
void SetNotifyWindow(HWND h);
std::vector<double> SamplesMbps();
Result LastResult();
// 服务端入口（由 srv 接到 speed_req / 数据连接时调用）
void HandleRequest(SOCKET ctrl, const js::Value& req, const Device& dev);
void AttachData(SOCKET s, const std::string& token, int stream);
}  // namespace speed

namespace fw {
bool RulesExist();
bool AddRules();
bool RemoveRules();
void InstallAsync(HWND notify);
}  // namespace fw

namespace shellx {
bool InstallContextMenu();
bool RemoveContextMenu();
bool IsInstalled();
}  // namespace shellx

namespace tray {
bool Add(HWND hwnd);
void Remove();
void UpdateTip(const std::wstring& text);
}  // namespace tray
