# ZstdNet Technical Notes

This document describes the current implementation. The Chinese version follows the English version.

## English

### Modules

- `core` contains the wire protocol, Netty codecs, traffic statistics, and dictionary storage, training, and synchronization.
- `mod-common` contains client configuration, connection selection, and shared client hooks.
- `fabric` and `neoforge` provide loader entry points and Minecraft mixins. NeoForge 1.21.1 additionally includes the dedicated-server implementation; newer NeoForge builds are client-only.
- `spigot` injects handlers into the existing Minecraft server listener and does not open a second listening port.

### Connection and pipeline

The client checks `enabled` and `servers` in `config/zstdnet-client.properties` while a connection is started. It keeps the original server address and marks the pending connection for up to 15 seconds. When Minecraft configures the channel, a Mixin installs the shared Netty codec. The inbound codec is placed after AES decryption and before Minecraft packet splitting; outbound encoding is placed before AES encryption and after packet framing. The pipeline is repositioned when Minecraft enables encryption.

On the server, an accept handler adds a per-connection detector to each accepted channel. The detector recognizes the Zstandard frame magic used to begin a ZstdNet stream, then validates the one-byte protocol version. ZstdNet traffic receives the codec pipeline, and vanilla login compression negotiation is suppressed for that connection. A raw Minecraft status ping is passed through; a raw login is rejected with the configured message. Spigot discovers the existing listener channels through cached MethodHandles; the NeoForge 1.21.1 implementation uses its server listener directly.

### Wire format

Each data frame contains two VarInts followed by its payload: the uncompressed length, then a stored tag. A zero tag means the payload is uncompressed; otherwise the tag stores the payload length shifted left by one, with the low bit indicating dictionary compression. Compression is used only when the complete encoded frame is smaller than the raw payload. Frames are limited to 8 MiB. A zero uncompressed length denotes a control record, currently used for dictionary offers and acknowledgements. Dictionary-enabled streams begin with protocol version `1`.

### Dictionaries

Dictionary support is wired into NeoForge 1.21.1. The server can collect live inbound and outbound traffic, then train a dictionary asynchronously (128 KiB target, up to 16 MiB of samples). A connecting client receives the selected dictionary in a control record and acknowledges its ID before dictionary-compressed data frames are used. The client validates and caches the dictionary under `config/zstdnet/dictionary.zdict`; an in-memory copy remains usable if caching fails. Changing the selected dictionary affects new connections, while active connections retain their negotiated dictionary.

## 中文

### 模块结构

- `core` 提供网络协议、Netty 编解码器、流量统计，以及字典存储、训练和同步。
- `mod-common` 负责客户端配置、连接选择和共享客户端钩子。
- `fabric` 与 `neoforge` 提供加载器入口和 Minecraft Mixin。NeoForge 1.21.1 还包含专用服务端实现；较新版本的 NeoForge 产物仅面向客户端。
- `spigot` 将处理器注入 Minecraft 服务端现有 listener，不会另行监听端口。

### 连接与 Pipeline

客户端在开始连接时根据 `config/zstdnet-client.properties` 中的 `enabled` 和 `servers` 配置决定是否启用 ZstdNet。服务端地址保持不变，待处理连接标记最多保留 15 秒。Minecraft 配置连接通道时，Mixin 安装共享 Netty 编解码器：入站解码器位于 AES 解密之后、Minecraft packet splitter 之前；出站编码器位于 AES 加密之前、packet framing 之后。Minecraft 启用加密时会重新调整处理器位置。

服务端 accept handler 为每个新连接添加协议检测器。检测器识别 ZstdNet 流起始处的 Zstandard frame magic，再验证单字节协议版本。确认后安装 ZstdNet pipeline，并抑制该连接的原版登录压缩协商。原版状态 ping 会透传；原版登录会按配置提示拒绝。Spigot 通过缓存的 MethodHandle 查找现有 listener 通道；NeoForge 1.21.1 则直接访问服务端 listener。

### 网络帧格式

每个数据帧由两个 VarInt 和负载组成：未压缩长度，以及存储标记。标记为零时负载原样存储；非零时，其高位部分记录负载长度，最低位表示是否使用字典压缩。只有压缩后的编码帧小于原始负载时才使用压缩。单帧上限为 8 MiB。未压缩长度为零表示控制记录，目前用于字典发送和确认。启用字典的流以协议版本 `1` 开始。

### 字典同步

字典功能接入 NeoForge 1.21.1。服务端可采集连接的上下行流量，并异步训练字典（目标容量 128 KiB，样本上限 16 MiB）。新连接会通过控制记录接收当前字典，并确认字典 ID；确认前不会使用字典压缩数据帧。客户端校验字典并缓存到 `config/zstdnet/dictionary.zdict`；即使缓存失败，当前连接仍可使用内存中的字典。切换字典只影响新连接，已有连接继续使用协商过的字典。
