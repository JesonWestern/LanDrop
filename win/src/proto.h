// proto.h -- LanDrop 线协议契约（唯一真源，改这里必须同步改 Android）
// 注意：FD_SETSIZE 必须在 winsock2.h 之前定义，否则 select() 会越界写栈。
#pragma once

#ifndef FD_SETSIZE
#define FD_SETSIZE 256
#endif

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif

#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>

#include <cstdint>
#include <cstddef>
#include <cstring>
#include <string>
#include <vector>

namespace proto {

// ---- 端口与版本 -----------------------------------------------------------
constexpr uint16_t kDiscoverPort = 47820;   // UDP 广播发现
constexpr uint16_t kTransferPort = 47821;   // TCP 控制 + 数据 + 测速
constexpr int      kProtoVersion = 1;
constexpr const char* kAppId = "landrop";

// ---- 传输参数 -------------------------------------------------------------
constexpr uint32_t kChunkBytes   = 1024u * 1024u;  // 1 MiB
constexpr uint32_t kWindowChunks = 8;              // 每连接滑动窗口
constexpr uint32_t kMaxDataConns = 8;
constexpr uint32_t kMaxLineBytes = 1024u * 1024u;  // 单行 JSON 上限
constexpr uint32_t kHeartbeatMs  = 20000;          // 20s 无字节判死链
constexpr uint32_t kDeviceTtlMs  = 12000;          // 设备 TTL

// ---- 帧头 -----------------------------------------------------------------
constexpr uint32_t kMagicData = 0x5441444Cu;  // 'LDAT' 小端
constexpr uint32_t kMagicAck  = 0x4B41444Cu;  // 'LDAK'

// flags 位
constexpr uint16_t F_LAST   = 0x0001;  // 该文件最后一块
constexpr uint16_t F_SPEED  = 0x0002;  // 测速流量（无 ACK）
constexpr uint16_t F_HELLO  = 0x0004;  // 数据连接首帧，携带任务号
constexpr uint16_t F_NAK    = 0x0008;  // ACK 帧的否定标记

#pragma pack(push, 1)
struct DataHdr {
    uint32_t magic;
    uint32_t taskId;
    uint16_t streamSeq;
    uint16_t flags;
    uint32_t fileIndex;
    uint32_t chunkIndex;
    uint32_t payloadLen;
    uint32_t payloadCrc32;  // ACK 帧复用为 credit
};
#pragma pack(pop)

static_assert(sizeof(DataHdr) == 28, "wire header must stay 28 bytes");

// ---- 小端读写（x86/x64 上直接 memcpy 即可，但显式写出避免对齐问题）--------
inline void PutU16(void* p, uint16_t v) { std::memcpy(p, &v, 2); }
inline void PutU32(void* p, uint32_t v) { std::memcpy(p, &v, 4); }
inline uint16_t GetU16(const void* p) { uint16_t v; std::memcpy(&v, p, 2); return v; }
inline uint32_t GetU32(const void* p) { uint32_t v; std::memcpy(&v, p, 4); return v; }

inline void EncodeHdr(uint8_t out[28], const DataHdr& h) {
    PutU32(out + 0,  h.magic);
    PutU32(out + 4,  h.taskId);
    PutU16(out + 8,  h.streamSeq);
    PutU16(out + 10, h.flags);
    PutU32(out + 12, h.fileIndex);
    PutU32(out + 16, h.chunkIndex);
    PutU32(out + 20, h.payloadLen);
    PutU32(out + 24, h.payloadCrc32);
}

inline bool DecodeHdr(const uint8_t in[28], DataHdr* h) {
    h->magic        = GetU32(in + 0);
    h->taskId       = GetU32(in + 4);
    h->streamSeq    = GetU16(in + 8);
    h->flags        = GetU16(in + 10);
    h->fileIndex    = GetU32(in + 12);
    h->chunkIndex   = GetU32(in + 16);
    h->payloadLen   = GetU32(in + 20);
    h->payloadCrc32 = GetU32(in + 24);
    return h->magic == kMagicData || h->magic == kMagicAck;
}

// ---- CRC32 (IEEE) ---------------------------------------------------------
namespace detail {
inline const uint32_t* CrcTable() {
    static uint32_t table[256];
    static bool init = false;
    if (!init) {
        for (uint32_t i = 0; i < 256; ++i) {
            uint32_t c = i;
            for (int k = 0; k < 8; ++k) c = (c & 1) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
            table[i] = c;
        }
        init = true;
    }
    return table;
}
}  // namespace detail

inline uint32_t Crc32(const void* data, size_t len) {
    const uint32_t* t = detail::CrcTable();
    const uint8_t* p = static_cast<const uint8_t*>(data);
    uint32_t c = 0xFFFFFFFFu;
    for (size_t i = 0; i < len; ++i) c = t[(c ^ p[i]) & 0xFF] ^ (c >> 8);
    return c ^ 0xFFFFFFFFu;
}

// ---- 任务号（32 位，取 FNV-1a 低 32 位；0 保留）-------------------------
inline uint32_t TaskIdFromKey(const std::string& key) {
    uint64_t h = 0xcbf29ce484222325ull;
    for (unsigned char ch : key) { h ^= ch; h *= 0x100000001b3ull; }
    uint32_t v = (uint32_t)(h ^ (h >> 32));
    return v ? v : 1u;
}

}  // namespace proto
