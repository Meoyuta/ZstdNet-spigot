# ZstdNet Technical Notes

This document describes the current implementation. The Chinese version follows the English version.

## English

### Modules and variants

- core contains the frame protocol, Netty codecs, traffic statistics, compression benchmark, dictionary storage,
  training, and synchronization.
- mod-common contains shared client configuration, connection selection, dictionary caching, and client hooks.
- fabric provides Fabric client entry points for 1.21.11 and 26.1.
- neoforge/src/1_21_1 contains the NeoForge 1.21.1 server-client implementation, including commands, payloads, the F8
  overlay selector, dictionary transfer, and same-port injection.
- neoforge/src/1_21_11 and neoforge/src/26_1 contain newer NeoForge client-only entry points.
- spigot injects handlers into the existing Minecraft listener without opening another port.

### Connection and wire format

The client reads config/zstdnet-client.properties and marks a pending connection for up to 15 seconds. The shared Netty
codec is installed after AES decryption and before packet splitting for inbound traffic, and before AES encryption and
after packet framing for outbound traffic. Server detectors recognize the Zstandard frame magic and protocol version,
install the ZstdNet pipeline, and suppress vanilla compression negotiation. Raw status pings pass through; raw logins
receive the configured rejection message.

Each data frame contains the uncompressed length and a stored tag as VarInts followed by the payload. A zero tag stores
raw payload. Otherwise the tag stores encoded length shifted left by one, with the low bit indicating dictionary
compression. Compression is used only if the complete encoded frame is smaller. Frames are limited to 8 MiB. An
uncompressed length of zero denotes a control record for dictionary offers and acknowledgements. Dictionary streams use
protocol version 1. The compatibility session also supports directional offer/acknowledgement records: server outbound and
inbound dictionaries are tracked independently, while legacy factories retain the original single-dictionary semantics.

Protocol v2 enables persistent streaming compression. Each direction owns a zstd output/input context; packets retain a VarInt
raw length and compressed chunk length so Minecraft boundaries remain explicit, while `FLUSH` keeps the zstd window between
packets. A stream-reset control record precedes the first packet after a compression-level change, so the peer recreates its
inbound context before decoding the new stream. Dictionary changes also recreate contexts; all contexts close on disconnect.
Protocol v1 is intentionally not supported because mod versions must be synchronized.

### Compression level and benchmark

The server owns a shared compression-level supplier. Encoders read it for every frame, so `/zstdnet complevel set 1-22`
applies to existing and new connections. The default level is 3. This command sets a temporary level; automatic
benchmarking remains enabled and may replace it after a completed run.
Automatic benchmarking tests levels 5 through 13 regardless of dictionary use. Manual `/zstdnet complevel set 1-22`
continues to allow all Zstd levels supported by the command.

The benchmark accumulates real packet samples in a bounded FIFO: at most 4096 samples, at most 4 KiB per sample, and at
most 16 MiB total. Each run selects a representative subset capped at 1 MiB to limit CPU spikes. A run can start after at
least 512 samples or 2 MiB of sample bytes; if neither threshold is met, it waits and retries about once per second.
The interval is persisted in `config/zstdnet/server.properties`; `/zstdnet benchmark interval` updates the next deadline
immediately, and the benchmark overlay reports the current interval. Samples below 32 bytes are excluded from ratio measurement when
larger samples exist. Each level is warmed up before measurement, without changing the live dictionary adaptation window.
The benchmark scheduler checks once per second independently of the Minecraft server tick. It only starts a run after the
configured interval has elapsed, an active ZstdNet connection exists, and either at least 512 real packet samples or at least
2 MiB of sample bytes are available. If samples are insufficient, it records a waiting state and retries every second.
Levels are round-tripped through the frame codec.
Estimated latency combines codec time with a 100 Mbps network estimate.
The fastest estimate is the baseline; candidates within 25% of that estimate (capped at 3 ms additional latency) compete by compressed-size percentage, and the lowest
percentage wins. Latency is displayed to four decimal places. Once a dictionary wins at least 95% of a rolling 128-frame comparison window, the encoder skips the
no-dictionary comparison and rechecks it once every 64 frames.

benchmark start stores the requesting player but does not send a waiting or running overlay. A completion callback sends
the result overlay only for a complete result. F8 can select the latest benchmark state at any time.

### Dictionaries

NeoForge 1.21.1 trains dictionaries asynchronously from live traffic. The target dictionary capacity is 128 KiB, the
training sample target is 64 MiB, and each captured sample is capped at 64 KiB. Dictionary compression contexts retain
the three most recently used levels and close evicted native contexts. All managed files are below
config/zstdnet/dict/: dictionary.zdict, dictionary-selection.txt, dictionary-naming.properties, pending_*.zdict, and
temp_*.zdict.

Training and import create pending dictionaries. Permission-level-2 players can name them; names expire after 60 seconds
to untitled_yyyyMMdd_HH-mm-ss.SSS. Shutdown saves use temp_ and remain pending across restart. dictionary export returns
the selected existing path and does not create an exports copy. Changing selection or unloading affects new connections;
active connections retain their negotiated dictionary.

The server sends the selected dictionary in a control record. The client validates its ID and caches it below
config/zstdnet/dict/. A disk-cache failure does not prevent in-memory use. The client acknowledges before
dictionary-compressed frames are enabled.

### Status overlays and diagnostics

NeoForge 1.21.1 registers separate benchmark, management-status, and dictionary-status payloads. Press F8 to open the
overlay selector and choose one of the three non-blocking HUD overlays, or turn the current overlay off. Selecting the
currently active overlay again also turns it off. Opening other screens, including the ESC menu, closes the overlay.
The F8 selector itself does not dismiss an active overlay before the player makes a choice. Labels use GOLD, dynamic
values use AQUA, and units such as percent, milliseconds, minutes, seconds, and byte units use GREEN.
An overlay is not persisted and is cleared when leaving the world. Chat, ESC, and other screens merely suspend its drawing;
only the F8 selector can explicitly turn it off. The management payload includes one-second upload/download rates as
compressed-versus-raw byte pairs, the latest directly measured RTT, in-memory ZstdNet runtime duration, and completed
benchmark run count. These directions use the server perspective: upload is server outbound traffic and download is
server inbound traffic. Runtime duration and benchmark count reset on server shutdown and are not persisted.

`/zstdnet ping` sends a nonce-bearing custom payload to the client and measures the request/response round trip with
`System.nanoTime()`; it does not read Minecraft's latency field. The `/zstdnet debug` command is available without an
additional permission requirement and reports
active and total connections, raw/wire byte totals, compression level and ratio, dictionary connections, benchmark state,
sample count and interval, then reports the directly measured round-trip time. This helps correlate latency spikes with
traffic, compression changes, dictionaries, or benchmark activity.

`/zstdnet debug` also writes a one-shot UTF-8 report to `config/debug/zstdnet-debug-*.log`. It captures aggregate
traffic/compression, benchmark and dictionary state, recent/max server tick duration, JVM heap/GC/CPU/thread snapshots,
and each online player's latest RTT split into network round-trip and main-thread queue delay. It intentionally does not
record every packet or continuously write disk logs, avoiding diagnostic overhead during a latency incident.

### Build and validation

`bash ./build.sh` is the local build entry point. It creates `target/`, cleans generated jars, and builds supported
variants. `core:test` covers protocol round trips, pipeline behavior, dictionary lifecycle, and benchmark logic.
Artifact inspection should confirm server-client classes in the 1.21.1 jar and client-only entry points in newer NeoForge
jars. Full Minecraft runtime behavior, real compression ratios, dictionary transfer, and screen rendering still require
in-game validation.

## 中文

### 模块与版本变体

- core 提供网络帧协议、Netty 编解码器、流量统计、压缩 benchmark、字典存储、训练和同步。
- mod-common 提供共享客户端配置、连接选择、字典缓存和客户端钩子。
- fabric 提供 1.21.11 与 26.1 的 Fabric 客户端入口。
- neoforge/src/1_21_1 包含 NeoForge 1.21.1 双端实现，包括命令、payload、F8 overlay 选择界面、字典传输和同端口注入。
- neoforge/src/1_21_11 与 neoforge/src/26_1 包含较新版本 NeoForge 客户端入口。
- spigot 将处理器注入现有 Minecraft listener，不会额外监听端口。

### 连接与网络帧

客户端读取 config/zstdnet-client.properties，待处理连接标记最多保留 15 秒。共享 Netty 编解码器的入站位置在 AES 解密之后、packet
splitter 之前；出站位置在 AES 加密之前、packet framing 之后。服务端检测器识别 Zstandard frame magic 和协议版本，安装 ZstdNet
Pipeline 并抑制原版压缩协商。原版 status ping 透传，原版 login 返回配置的拒绝提示。

数据帧包含未压缩长度和存储标记两个 VarInt，随后是负载。标记为零时原样存储，否则记录编码长度，最低位表示字典压缩。只有完整编码帧更小时才压缩，单帧上限为
8 MiB。未压缩长度为零表示用于字典发送和确认的控制记录，字典流使用协议版本 1。兼容会话同时支持带方向的发送/确认记录，
分别维护服务端上下行字典；旧工厂仍保持单字典语义。

协议版本 2 已启用持久流式压缩。上下行各自维护 zstd 输入/输出 context；每个数据包仍保留未压缩长度和压缩块长度，
因此保留 Minecraft 包边界，同时通过 `FLUSH` 跨包保留 zstd 窗口。压缩等级变化时，发送方会在首个新等级数据包前发送流重置控制记录，接收方先重建对应入站 context 再解码。字典变化也会重建 context，断线时释放。
由于模组版本必须同步，协议版本 1 不再兼容。

### 压缩等级与 benchmark

服务端维护共享压缩等级供应器，编码器每帧读取，因此 `/zstdnet complevel set 1-22` 会对现有和新连接生效。默认等级为 3。
该指令设置的是临时等级；自动 benchmark 仍然启用，完成后可能覆盖手动等级。自动 benchmark 不论是否使用字典，候选范围均为 5 到 13；手动指令仍支持 1 到 22，
等级比较采用相对预算：候选预计延迟不得超过最快等级的 25%，且绝对增量最多 3 ms。

benchmark 将真实数据包样本累加到有界 FIFO：最多 4096 个样本，单个最多 4 KiB，总量最多 16 MiB；每轮择级会均匀抽取最多 1 MiB 的代表样本以限制 CPU 峰值。累计达到 512 个真实数据包样本或 2 MiB 样本字节后即可开始；两项都未达到时进入
waiting，并约每秒重试，不会跳过。等级 1 到 22 全部进行 frame codec 往返测试。预计延迟由编解码耗时和 100 Mbps
网络估算组成；先取最低延迟作为基线，再在额外延迟小于 3 ms 的候选中选择压缩率最低者。benchmark 调度器每秒独立检查一次，不依赖 Minecraft server tick。只有达到设定间隔且存在有效 ZstdNet 连接时才开始；周期持久化在 `config/zstdnet/server.properties`，`/zstdnet benchmark interval` 修改后立即更新下一次截止时间，benchmark overlay 显示当前周期。大于 32 字节样本优先用于压缩率测量，每个等级先预热，且不会改变线上字典自适应窗口。字典在最近 128 帧比较中胜率达到
95% 后跳过无字典比较，每 64 帧复检一次。

benchmark start 只保存请求玩家，不发送 waiting 或 running overlay；只有 complete 结果才发送结果 overlay。玩家可随时按 F8 查看最新 benchmark 状态。

### 字典

NeoForge 1.21.1 从实时流量异步训练字典。目标容量 128 KiB，训练样本目标 64 MiB，单个样本最多 64 KiB。字典压缩上下文最多保留最近
使用的 3 个等级，淘汰时立即释放 native context。所有托管文件位于
config/zstdnet/dict/，包括 dictionary.zdict、dictionary-selection.txt、dictionary-naming.properties、pending_*.zdict 和
temp_*.zdict。

训练和导入会创建待命名字典。权限等级 2 的玩家可以命名；60 秒后自动命名为 untitled_yyyyMMdd_HH-mm-ss.SSS。关服保存使用 temp_
并跨重启保持待命名。dictionary export 直接返回现有选中文件路径，不创建 exports 副本。切换或卸载只影响新连接，已有连接保留协商出的字典。

字典现在以单个 ZIP bundle 文件保存，固定包含 `uplink.zdict`（最大 64 KiB）和 `downlink.zdict`（最大 128 KiB）；旧的单字典文件不会被加载或导入。训练器分别按入站/出站流量收集样本并训练两个方向，服务端和客户端会话分别协商两个 ID。客户端校验 ID 并缓存 bundle 到 config/zstdnet/dict/，确认后才启用对应方向的字典压缩帧。

### 状态 overlay 与诊断

NeoForge 1.21.1 为 benchmark、管理状态和字典状态分别注册 payload。按 F8 打开选择界面后，可选择三类非阻塞 HUD overlay，或关闭当前 overlay；再次选择当前项目也会关闭它。聊天、ESC 菜单和其他 Screen 只会暂时停止绘制，不会关闭 overlay；退出世界后清除，且不持久化。overlay 每秒刷新，标签为 GOLD，动态数据为 AQUA，百分比、毫秒、分钟、秒和字节单位为 GREEN。管理状态同时显示每秒上下行的压缩后/原始字节速率和当前实测 RTT；方向以服务器为视角，上行为服务器发送，下行为服务器接收。

`/zstdnet ping` 向客户端发送带 nonce 的自定义 payload，并用 `System.nanoTime()` 测量请求/响应往返时间，不读取 Minecraft 延迟字段。`/zstdnet debug` 不额外要求权限，会报告当前/累计连接数、上下行原始/线路字节、压缩等级与比例、字典连接数、benchmark 状态/样本数/周期，再输出直接测得的往返时间，便于对照延迟升高与流量、等级切换、字典和 benchmark 活动的关系。

### 构建与验证

build.sh 脚本创建 target/、清理生成 JAR 并构建支持的变体。core:test 覆盖协议往返、Pipeline、字典生命周期和 benchmark
逻辑。产物检查应确认 1.21.1 JAR 包含双端类，较新 NeoForge JAR 包含客户端入口。完整 Minecraft 运行时行为、实际压缩率、字典传输和界面渲染仍需游戏内验证。

`/zstdnet debug` 同时会在 `config/debug/zstdnet-debug-*.log` 写入一次性 UTF-8 诊断报告，不在聊天中回显完整数据。报告包含流量/压缩、benchmark、字典状态、最近和最大服务端 tick 耗时、JVM 堆/GC/CPU/线程快照，以及每个在线玩家将网络往返时间与服务端主线程排队时间拆分后的最近 RTT。不会逐包持续写盘，以避免诊断本身放大延迟。