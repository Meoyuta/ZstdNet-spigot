# ZstdNet

ZstdNet 通过同端口 Spigot 插件和 NeoForge 服务端模组，为 Minecraft 连接接入 Zstandard 压缩，并提供匹配的 Fabric 与 NeoForge 客户端模组。

English documentation: [README.md](README.md)

## 支持版本

| Minecraft | 服务端产物 | 客户端 | Java |
| --- | --- | --- | --- |
| 1.21.1 | NeoForge 双端服务端产物 | NeoForge | 21 |
| 1.21.11 | Spigot、NeoForge 客户端 | Fabric 或 NeoForge | 21 |
| 26.1 | Spigot、NeoForge 客户端 | Fabric 或 NeoForge | 25 |

构建脚本会将产物放入 `target/`。1.21.1 需要把 NeoForge 双端 JAR 安装到专用服务端和匹配客户端。Spigot 使用 Minecraft 原有端口，不会额外开启代理端口。

## 功能

- 将 Zstandard 帧接入 Minecraft Netty 网络连接。
- 同端口协议检测与注入。
- 支持运行时调整压缩等级和自动 benchmark。
- NeoForge 1.21.1 支持字典训练、导入、选择、命名、卸载、同步和客户端接收反馈。
- NeoForge 1.21.1 提供便捷的状态和 benchmark 界面。

## NeoForge 1.21.1 命令

管理、启动 benchmark、设置压缩等级和修改字典的命令需要权限等级 2。

`/zstdnet status`  
`/zstdnet start`  
`/zstdnet stop`  
`/zstdnet reload`  
`/zstdnet ping`  
`/zstdnet complevel set <1-22>`  
`/zstdnet benchmark start`  
`/zstdnet benchmark info`  
`/zstdnet dictionary status`  
`/zstdnet dictionary list`  
`/zstdnet dictionary train [秒数]`  
`/zstdnet dictionary stop`  
`/zstdnet dictionary cancel`  
`/zstdnet dictionary import <路径>`  
`/zstdnet dictionary switch [路径]`  
`/zstdnet dictionary unload`  
`/zstdnet dictionary export`  
`/zstdnet dictionary name <待命名文件> <名称>`

`benchmark start` 会启动 benchmark，并在结果准备好后打开结果界面。`benchmark info` 可主动查看当前状态。

玩家执行 `status`、`dictionary status` 或 `dictionary list` 时会打开对应的状态界面。

## 字典文件

字典统一存放在：

```text
config/zstdnet/dict/
  dictionary.zdict
  dictionary-selection.txt
  dictionary-naming.properties
  pending_*.zdict
  temp_*.zdict
```
 

训练和导入会将字典保存到该目录。`dictionary export` 会打开当前选中字典的文件路径。新连接使用当前选中的字典，已有连接继续使用连接建立时协商的字典。

## 客户端配置

共享配置为 `config/zstdnet-client.properties`，服务端字典校验后缓存到 `config/zstdnet/dict/`。

## 构建

运行 `bash ./build.sh`。脚本会创建 `target/`、清理生成 JAR 并构建所有变体。

技术细节：[TECHNICAL.md](TECHNICAL.md)

## 鸣谢

感谢 [wish131400/zstdnet](https://github.com/wish131400/zstdnet) 提供 Zstandard 网络加速思路与项目参考。
