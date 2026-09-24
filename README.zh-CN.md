# ZstdNet

ZstdNet 是一个同端口 ZSTD 网络插件，并提供 Fabric 和 NeoForge 客户端模组。

英文文档：[README.md](README.md)

## 构建

```powershell
.\build.bat
```

构建脚本会在需要时自动创建 `target` 目录，编译当前支持的 Minecraft 版本，并把所有发布 jar 复制到：

```text
target/ZstdNet-1.21.1-neoforge-server-client-1.0.1-prerelease.jar
target/ZstdNet-1.21.11-spigot-0.1.0.jar
target/ZstdNet-1.21.11-fabric-0.1.0.jar
target/ZstdNet-1.21.11-neoforge-0.1.0.jar
target/ZstdNet-26.1-spigot-0.1.0.jar
target/ZstdNet-26.1-fabric-0.1.0.jar
target/ZstdNet-26.1-neoforge-0.1.0.jar
```

1.21.1 和 1.21.11 产物使用 Java 21 字节码。26.1 产物使用 Java 25 字节码。

NeoForge 1.21.1 使用 `server-1211` 变体，共享客户端代码位于
`neoforge/src/client`，服务端及双端入口位于 `neoforge/src/mc1211`。
其版本由 `gradle.properties` 中的 `neoforge_1211_mod_version` 控制；
其他变体继续使用 `mod_version`。
1.21.11 和 26.1 的 `client` 变体使用 `neoforge/src/client-entry`。
目前 1.21.1 本地构建依赖 Gradle 缓存中的映射 Minecraft 和 NeoForge JAR；
全新的 CI 环境仍需补齐依赖初始化流程。

## NeoForge 1.21.1 管理命令

专用服务端管理员（权限等级 2）可使用
`/zstdnet <status|start|stop|reload>`。专用服务端启动时自动启用压缩，
客户端默认 `enabled=true`、`servers=*`；已有客户端配置中的显式设置仍然有效。
`start` 初始化同端口压缩，或在 `stop` 后恢复压缩。`stop` 停止拦截新连接，
现有连接保留协商时的编解码器。`reload` 从磁盘重新加载字典并启动压缩。
此变体已移除 `setup` 命令，无需迁移端口。

`status`（直接执行 `/zstdnet` 也会显示）报告上下行合计传输字节数
（压缩后／压缩前）、压缩率（压缩后除以压缩前的百分比；无流量时为零）和
当前有效 ZstdNet 连接数。统计覆盖本次服务器运行，停止再启动不会清零。
管理及字典命令反馈均使用英文、简体中文语言文件。

## 字典功能（NeoForge 1.21.1）

专用服务端和客户端均安装双端 JAR。管理员（权限等级 2）可使用：

```text
/zstdnet dictionary train [秒数]
/zstdnet dictionary status
/zstdnet dictionary stop
/zstdnet dictionary cancel
/zstdnet dictionary export
/zstdnet dictionary import <文件路径>
/zstdnet dictionary list
/zstdnet dictionary switch <路径>
/zstdnet dictionary unload
/zstdnet dictionary name <待命名文件> <名称>
```

`switch` 和 `name` 支持字典文件 Tab 补全。普通保存提示管理员在一分钟内命名并应用；超时自动命名为 `untitled_yyyyMMdd_HH-mm-ss.SSS`。关服保存使用 `temp_yyyyMMdd_HH-mm-ss.SSS`，下次启动时自动应用。管理员上线时会收到待命名提示，命名后不再提示。改名保留原字典实例，更新文件及选择记录。

当前字典路径会保存到 `config/zstdnet/dictionary-selection.txt`，服务器启动时自动恢复。未保存选择时，会在 `config/zstdnet` 下发现第一个有效的 `.zdict`。`switch` 为新连接切换字典；`unload` 选择无字典模式。已有连接继续使用协商时的字典，重连后更新。

默认采集 600 秒（10 分钟），可设置 1–86400 秒。训练需要玩家通过
ZstdNet 连接产生实际流量。`stop` 提前结束采集并异步训练，
`cancel` 放弃本次训练。状态命令显示样本数、剩余时间和最终结果。
关服立即结束采集，等待训练及保存完成后再退出。保存进度和结果使用 INFO；
采集、训练及每五秒的等待记录使用 DEBUG。样本不足或训练失败时保留原字典。

训练目标字典容量为 128 KiB，采样目标为其 128 倍，即 16 MiB。每条样本最多
采集 4 KiB，满量至少包含 4096 条样本；达到目标后提前开始训练。
ZSTD 实际生成的字典可能小于目标容量。服务端通过一个长生命周期字典仓库
在连接间共享当前不可变字典，已有连接继续持有协商时的版本。

字典保存在 `config/zstdnet/dictionary.zdict`，启动时自动加载。
导出在 `config/zstdnet/exports` 生成快照，并输出可点击复制的完整路径。
导入接受绝对路径或相对于 `config/zstdnet` 的路径，支持空格。
无效文件不会替换当前字典。

新连接自动同步服务端字典，客户端显示下载百分比和字节数，收到后立即确认。
客户端不使用本地字典决定连接参数；服务端无字典时使用普通 ZSTD。
训练或导入的新字典对新连接生效，已有连接保留协商时的字典，重连后更新。

本轮仅为 NeoForge 1.21.1 接入命令和界面。
所有 Java 包名统一使用不含版本号的 `mys.zstdnet.reborn`。

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
