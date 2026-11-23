# LanDrop 线协议规范 v1

> **本文件是两端实现的唯一事实来源。** 修改任何字段前，先改这里，再改两端代码，并用
> `protocol/vectors/` 里的测试向量做字节级比对。
>
> 实现参考：`win/src/proto.h`（C++17）、`android/app/src/main/java/com/landrop/transfer/net/Proto.kt`（Kotlin）。

---

## 1. 端口与传输层

| 端口 | 协议 | 用途 |
|---|---|---|
| **47820** | UDP | 设备发现（广播） |
| **47821** | TCP | 控制连接 + 数据连接 + 网速测试 |

- 所有多字节整数均为 **小端序**。
- 所有文本均为 **UTF-8**（Windows 端进出必须显式 `CP_UTF8`，禁止 `CP_ACP`）。
- 传输 **明文**，不做 TLS。安全模型见 §8。

---

## 2. 设备发现（UDP 47820）

每个应用实例每 **3 秒**向本机所有可用网卡的定向广播地址与 `255.255.255.255` 各发一次
`probe`；收到 `probe` 的设备立即用 `announce` **单播回给对方**。

报文是单行 JSON，**必须小于 512 字节**：

```json
{"t":"probe","v":1,"id":"a405b3dc-fd97-4868-bb81-3cd132fbe967","name":"我的手机","plat":"android","port":47821,"ver":"1.1.0","caps":["file","folder","resume","speed"]}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `t` | string | `probe` 或 `announce` |
| `v` | int | 协议版本，当前 `1` |
| `id` | string | 设备 UUID，持久化，**不能用 IP 或主机名** |
| `name` | string | 显示名，允许重名 |
| `plat` | string | `win` / `android` |
| `port` | int | TCP 监听端口（固定 47821，留扩展位） |
| `ver` | string | 应用版本 |
| `caps` | string[] | 能力位：`file` `folder` `resume` `speed` |

**接收方必须做的三件事**（否则会出现"发现自己"或"一台设备变三台"）：

1. 丢弃 `id == 本机 id` 的报文；
2. 丢弃源 IP 属于本机任一网卡地址的报文（同一广播会以 IPv4 / wlan0-IPv6 / dummy0 三份到达）；
3. 按 `id` 去重，只刷新 `lastSeen`。

设备 TTL **12 秒**，超时从列表移除。

**发送侧的硬约束**：每个网卡一个独立 socket 并 `bind` 到该网卡地址后再广播。
Windows 上默认选路会把广播发到 VMware/Hyper-V 虚拟网卡（实测失败率 77%），且**静默丢包无报错**。

---

## 3. 连接建立

TCP 47821 上的**每一条**连接都以一行 JSON `hello` 开头，之后按 `kind` 分流。

### 3.1 控制连接

```json
{"t":"hello","kind":"ctrl","v":1,"id":"...","name":"...","plat":"android","ver":"1.1.0","caps":["file","folder","resume","speed"]}
```

服务端回：

```json
{"t":"hello_ok","v":1,"name":"HUO","chunkBytes":1048576,"caps":["file","folder","resume","speed"]}
```

### 3.2 数据连接

```json
{"t":"hello","kind":"data","job":"<jobKey>","stream":0}
```

服务端回：

```json
{"t":"data_ok"}
```

> **必须先回 `data_ok`，客户端收到后才开始发二进制帧。** 这是消除"握手行与首帧粘包"
> 的唯一手段——不要试图用同一个 buffer 同时解析文本行和二进制帧。

测速的数据连接把 `job` 写成 `"speed:<token>"`，见 §7。

---

## 4. 文件传输

### 4.1 控制帧序列

```
客户端                                    服务端
  |-- hello(ctrl) ----------------------->|
  |<------------------------- hello_ok ---|
  |-- offer ----------------------------->|
  |<-- accept(job, have[]) / reject ------|
  |   （另开 N 条数据连接并并行发块）        |
  |-- done ------------------------------>|
  |<------------------------- done_ok ----|
```

### 4.2 `offer`

```json
{"t":"offer","total":314572800,
 "files":[{"rel":"DCIM/Camera/a.jpg","size":123,"mtime":133485408000000000,"isDir":false}]}
```

| 字段 | 说明 |
|---|---|
| `rel` | 相对路径，**用 `\` 分隔**（Windows 语义），不含盘符、不含 `..` |
| `size` | 字节数 |
| `mtime` | Windows FILETIME（自 1601-01-01 的 100ns 数），Android 需 `ms*10000 + 116444736000000000` |
| `isDir` | `true` 表示空目录占位，`size=0`，不产生数据块 |

### 4.3 `accept` / `reject`

```json
{"t":"accept","job":"<jobKey>","chunkBytes":1048576,
 "have":[{"i":0,"bits":[4294967295,255]}]}
```

- `job`：接收端生成的任务键，**发送端必须用它作为后续 `taskId` 与数据连接 `job` 字段的来源**。
- `have`：断点续传位图，按 `files[i]` 的块序号，`bits[k]` 是第 k 个 32 位字，低位在前。
- 只认位图，**不要相信临时文件长度**（末块可能短）。

```json
{"t":"reject","reason":"declined|no_space|unsafe_path|empty"}
```

### 4.4 数据帧（二进制，28 字节头）

```
偏移  类型   字段
 0    u32    magic        = 0x5441444C ("LDAT")
 4    u32    taskId       = FNV-1a(jobKey) 低 32 位（0 保留）
 8    u16    streamSeq    数据连接序号
10    u16    flags        bit0 F_LAST / bit1 F_SPEED / bit2 F_HELLO / bit3 F_NAK
12    u32    fileIndex    对应 offer.files 的下标
16    u32    chunkIndex   块序号，offset = chunkIndex * chunkBytes
20    u32    payloadLen   实际长度（末块 < chunkBytes）
24    u32    payloadCrc32 CRC32(IEEE) of payload
```

紧跟 `payloadLen` 字节裸数据。

- **块大小固定 1 MiB**，`chunkCount = ceil(size / 1MiB)`。
- 接收端按 `chunkIndex * chunkBytes` 定位写入，**不依赖到达顺序**。
- CRC 不匹配 → 回 NAK，不写盘、不置位图。

### 4.5 ACK 帧

接收端在**同一条数据连接**上回写 28 字节头，`magic = 0x4B41444C ("LDAK")`，
`fileIndex` / `chunkIndex` 原样回传，`flags & F_NAK` 表示该块失败，其余字段填 0。

发送端每条连接维持 **8 块窗口**：未确认块满 8 个就停止发送，等 ACK 再继续。

### 4.6 结束

```json
{"t":"done","job":"<jobKey>","bytes":314572800}
```

接收端校验所有块到齐后落盘，回：

```json
{"t":"done_ok","job":"<jobKey>","path":"Download/LanDrop/ldfolder"}
```

失败回 `{"t":"error","reason":"incomplete|canceled|commit"}`。

---

## 5. 断点续传

任务键必须**对同一批文件稳定**，且两端各自可独立计算（用于匹配自己遗留的临时目录）：

```
fileMaterial = peerId + 0x01 + rel + 0x01 + size + 0x01 + mtime + 0x01 + chunkBytes
jobKey       = FNV1a64( 各文件 fileMaterial 的 FNV1a64 值，升序排列后依次喂入 )  → 16 位小写十六进制
```

接收端把进度写到 `<tmp>/<jobKey>/meta.json`（`files[].bits` 数组），
重启后若同一任务再次到来，读出位图放进 `accept.have`，发送端据此跳过已收块。

**注意**：发送端拿到服务端返回的 `job` 后应覆盖本地计算值——两端键算法不要求一致，
但接收端自身的键必须稳定。

---

## 6. 兼容性规则

1. **未知 `t` 一律忽略**，不得报错退出。
2. 新增能力只加 `caps` 字符串与新的帧类型，**不改已有帧语义**。
3. 新增字段只能是可缺省的（接收方必须有默认值）。
4. `v` 只在破坏性变更时递增；两端按 `min(v)` 降级。

---

## 7. 网速测试

控制连接发：

```json
{"t":"speed_req","token":"1a2b3c4d","dir":2,"conns":4,"secs":10}
```

| 字段 | 说明 |
|---|---|
| `dir` | `0` 客户端上传 / `1` 客户端下载 / `2` 双向 |
| `conns` | 每个方向的数据连接数（1–8） |
| `secs` | 持续秒数（1–60） |

服务端回 `{"t":"speed_ok","token":"..."}` 或 `{"t":"busy"}`。

随后客户端建立 `conns`（双向时为 `2*conns`）条数据连接，`job = "speed:<token>"`：

- `stream < conns` → **客户端上传**（客户端发，服务端丢弃）
- `stream >= conns` → **客户端下载**（服务端发，客户端丢弃）

测速流量带 `F_SPEED | F_NOREPLY`，**不产生 ACK**（ACK 反压会污染测量）。
时长到后双方关闭连接，客户端按自己统计的字节数出结果（`avg / peak / p50`）。

---

## 8. 安全模型

- 首次接收由用户弹窗确认；确认后可选择"信任该设备"（写入信任库，按 `id` 匹配）。
- 信任库上限 64 台，可在设置里清空。
- 接收端**必须**校验 `rel`：拒绝绝对路径、盘符、`..` 段、含 `:` 的段（防路径穿越与 NTFS ADS），
  并对每段做非法字符/保留名清洗（见 `util::SanitizeComponent`）。
- 协议预留 TLS 升级位（`hello` 的 `v` 与 `caps`）。

---

## 9. 测试向量

`protocol/vectors/` 下的 JSON 描述输入与期望字节。两端各有一份断言实现：

- Windows：`LanDrop.exe --selftest`（CRC32 标准向量、28 字节帧头往返、中文 JSON、任务键稳定性）
- Android：`gradlew test`（同一组断言，JVM 单测）

跨端一致性最终由 `tools/e2e_test.py` 用真实设备跑通来保证。
