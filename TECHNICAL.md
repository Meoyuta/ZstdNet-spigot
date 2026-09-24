# ZstdNet Technical Notes

This document describes the current implementation. The Chinese version follows the English version.

## English

### Modules and variants

- core contains the frame protocol, Netty codecs, traffic statistics, compression benchmark, dictionary storage,
  training, and synchronization.
- mod-common contains shared client configuration, connection selection, dictionary caching, and client hooks.
- fabric provides Fabric client entry points for 1.21.11 and 26.1.
- neoforge/src/1_21_1 contains the NeoForge 1.21.1 server-client implementation, including commands, payloads, status
  screens, dictionary transfer, and same-port injection.
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
protocol version 1.

### Compression level and benchmark

The server owns a shared compression-level supplier. Encoders read it for every frame, so /zstdnet complevel set 1-22
applies to existing and new connections. Automatic benchmarking may replace a temporary level.

The benchmark accumulates real packet samples in a bounded FIFO: at most 4096 samples, at most 4 KiB per sample, and at
most 16 MiB total. Fewer than 4096 samples produces waiting and a retry about once per second instead of skipping.
Levels 1 through 22 are round-tripped through the frame codec. Estimated latency combines codec time with a 100 Mbps
network estimate. The fastest estimate is the baseline; candidates under 10 ms above it compete by compressed-size
percentage, and the lowest percentage wins.

benchmark start stores the requesting player but does not send a waiting or running screen. A completion callback sends
the result screen only for a complete result. benchmark info sends the current result on demand.

### Dictionaries

NeoForge 1.21.1 trains dictionaries asynchronously from live traffic. The target dictionary capacity is 128 KiB, the
training sample target is 16 MiB, and each captured sample is capped at 4 KiB. All managed files are below
config/zstdnet/dict/: dictionary.zdict, dictionary-selection.txt, dictionary-naming.properties, pending_*.zdict, and
temp_*.zdict.

Training and import create pending dictionaries. Permission-level-2 players can name them; names expire after 60 seconds
to untitled_yyyyMMdd_HH-mm-ss.SSS. Shutdown saves use temp_ and remain pending across restart. dictionary export returns
the selected existing path and does not create an exports copy. Changing selection or unloading affects new connections;
active connections retain their negotiated dictionary.

The server sends the selected dictionary in a control record. The client validates its ID and caches it below
config/zstdnet/dict/. A disk-cache failure does not prevent in-memory use. The client acknowledges before
dictionary-compressed frames are enabled.

### Status screens

NeoForge 1.21.1 registers separate benchmark, management-status, and dictionary-status payloads. Player commands update
the corresponding client screen; console sources retain translated text output. Screen labels use GOLD, dynamic values
use AQUA, and units such as percent, milliseconds, minutes, seconds, and byte units use GREEN.

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
- neoforge/src/1_21_1 包含 NeoForge 1.21.1 双端实现，包括命令、payload、状态界面、字典传输和同端口注入。
- neoforge/src/1_21_11 与 neoforge/src/26_1 包含较新版本 NeoForge 客户端入口。
- spigot 将处理器注入现有 Minecraft listener，不会额外监听端口。

### 连接与网络帧

客户端读取 config/zstdnet-client.properties，待处理连接标记最多保留 15 秒。共享 Netty 编解码器的入站位置在 AES 解密之后、packet
splitter 之前；出站位置在 AES 加密之前、packet framing 之后。服务端检测器识别 Zstandard frame magic 和协议版本，安装 ZstdNet
Pipeline 并抑制原版压缩协商。原版 status ping 透传，原版 login 返回配置的拒绝提示。

数据帧包含未压缩长度和存储标记两个 VarInt，随后是负载。标记为零时原样存储，否则记录编码长度，最低位表示字典压缩。只有完整编码帧更小时才压缩，单帧上限为
8 MiB。未压缩长度为零表示用于字典发送和确认的控制记录，字典流使用协议版本 1。

### 压缩等级与 benchmark

服务端维护共享压缩等级供应器，编码器每帧读取，因此 /zstdnet complevel set 1-22 会对现有和新连接生效。自动 benchmark
可能覆盖临时等级。

benchmark 将真实数据包样本累加到有界 FIFO：最多 4096 个样本，单个最多 4 KiB，总量最多 16 MiB。样本不足 4096 个时进入
waiting，并约每秒重试，不会跳过。等级 1 到 22 全部进行 frame codec 往返测试。预计延迟由编解码耗时和 100 Mbps
网络估算组成；先取最低延迟作为基线，再在额外延迟小于 10 ms 的候选中选择压缩率最低者。

benchmark start 只保存请求玩家，不发送 waiting 或 running 界面；只有 complete 结果才发送结果界面。benchmark info 可主动查看当前结果。

### 字典

NeoForge 1.21.1 从实时流量异步训练字典。目标容量 128 KiB，训练样本目标 16 MiB，单个样本最多 4 KiB。所有托管文件位于
config/zstdnet/dict/，包括 dictionary.zdict、dictionary-selection.txt、dictionary-naming.properties、pending_*.zdict 和
temp_*.zdict。

训练和导入会创建待命名字典。权限等级 2 的玩家可以命名；60 秒后自动命名为 untitled_yyyyMMdd_HH-mm-ss.SSS。关服保存使用 temp_
并跨重启保持待命名。dictionary export 直接返回现有选中文件路径，不创建 exports 副本。切换或卸载只影响新连接，已有连接保留协商出的字典。

服务端通过控制记录发送字典，客户端校验 ID 并缓存到 config/zstdnet/dict/。磁盘缓存失败不影响当前连接使用内存字典，客户端确认后才启用字典压缩帧。

### 状态界面

NeoForge 1.21.1 为 benchmark、管理状态和字典状态分别注册 payload。玩家命令更新对应客户端界面，控制台继续使用文本输出。界面标签为
GOLD，动态数据为 AQUA，百分比、毫秒、分钟、秒和字节单位为 GREEN。

### 构建与验证

build.sh 脚本创建 target/、清理生成 JAR 并构建支持的变体。core:test 覆盖协议往返、Pipeline、字典生命周期和 benchmark
逻辑。产物检查应确认 1.21.1 JAR 包含双端类，较新 NeoForge JAR 包含客户端入口。完整 Minecraft 运行时行为、实际压缩率、字典传输和界面渲染仍需游戏内验证。