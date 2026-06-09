# ZstdNet

ZstdNet 是一个同端口 ZSTD 网络插件，并提供 Fabric 和 NeoForge 客户端模组。

英文文档：[README.md](README.md)

## 构建

```powershell
.\build.bat
```

构建脚本会在需要时自动创建 `target` 目录，编译当前支持的两个 Minecraft 版本，并把所有发布 jar 复制到：

```text
target/ZstdNet-1.21.11-spigot-0.1.0.jar
target/ZstdNet-1.21.11-fabric-0.1.0.jar
target/ZstdNet-1.21.11-neoforge-0.1.0.jar
target/ZstdNet-26.1-spigot-0.1.0.jar
target/ZstdNet-26.1-fabric-0.1.0.jar
target/ZstdNet-26.1-neoforge-0.1.0.jar
```

1.21.11 产物使用 Java 21 字节码。26.1 产物使用 Java 25 字节码。

## Spigot 行为

Spigot 插件使用同端口 Netty 注入。它不会绑定第二个 TCP 端口，也不会把 Minecraft 服务端迁移到本地后端端口。

运行时行为：

- 玩家仍然连接正常的 Minecraft 服务端端口
- 插件向现有 Minecraft listener 注入 Netty handler
- ZstdNet 客户端连接会在 Minecraft packet splitter 前被解码
- Minecraft AES 加密启用后，ZstdNet frame 会在 AES 解密后解码，并在 AES 加密前编码
- 服务端发给 ZstdNet 客户端的响应会在 Minecraft 加密前重新编码为 ZSTD frame
- ZstdNet 客户端连接会抑制原版 Minecraft 登录压缩协商，因此这些连接不会使用原版 `compress` / `decompress` handler
- 当 ZSTD 会让 frame 变大时，ZstdNet 会自动使用 raw passthrough，避免小型客户端上行包严重膨胀
- 原版状态 ping 会以 raw 方式透传
- 未安装 ZstdNet 客户端的原版登录会被断开，并显示提示信息

这适合只开放一个可用端口的主机，因为 Spigot 和 ZstdNet 共享同一个已经打开的服务端 socket。

管理员命令：

```text
/zstdnet <status|reload|start|stop|setup>
```

权限：

```text
zstdnet.admin
```

## 客户端模组

默认会构建 Fabric 和 NeoForge 客户端 jar。客户端 mixin 只使用 `ConnectScreen.startConnecting` 判断下一次连接是否应该启用 ZstdNet。实际 ZSTD frame codec 会注入到 Minecraft 的 `Connection` Netty pipeline 中，位置在 Minecraft 完成 packet framing 之后、执行 AES 加密之前。

26.1 客户端 jar 使用 Mojang 的 named 26.1 client jar 作为 compile-only 输入，因为 26.1 没有发布 Fabric intermediary/Yarn mappings。它仍然会打包自动连接 mixin 和 Netty pipeline mixin。

客户端配置会写入：

```text
config/zstdnet-client.properties
```

服务端安装 ViaVersion 与 Bukkit 侧插件路径兼容，但它不能替代客户端 ZstdNet 模组。需要使用压缩连接的客户端仍然必须安装对应的 ZstdNet 客户端模组。本仓库会构建 1.21.11 和 26.1 产物。

## 鸣谢

感谢 [wish131400/zstdnet](https://github.com/wish131400/zstdnet)。本项目参考了该项目的 ZSTD 网络加速思路和整体工作流程。
