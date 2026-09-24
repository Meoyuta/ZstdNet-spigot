# ZstdNet

ZstdNet 通过同端口 Spigot 插件或 NeoForge 服务端模组，为 Minecraft 连接提供 Zstandard 压缩，并提供 Fabric 与 NeoForge 客户端模组。

English documentation: [README.md](README.md)

## 支持版本

| Minecraft | 服务端 | 客户端 | Java |
| --- | --- | --- | --- |
| 1.21.1 | NeoForge（双端） | NeoForge | 21 |
| 1.21.11 | Spigot | Fabric、NeoForge | 21 |
| 26.1 | Spigot | Fabric、NeoForge | 25 |

使用压缩连接的客户端需安装对应版本的 ZstdNet 客户端模组。NeoForge 1.21.1 使用同一个双端 JAR 安装到专用服务端和客户端。Spigot 与 Minecraft 共用现有端口。

## 功能

- 将 Zstandard 压缩接入 Minecraft 网络连接。
- Spigot 管理命令：`/zstdnet <status|reload|start|stop|setup>`（权限：`zstdnet.admin`）。
- NeoForge 1.21.1 服务端命令：`/zstdnet <status|start|stop|reload>`；该版本还支持字典训练、导入、导出和同步。
- 客户端配置：`config/zstdnet-client.properties`。

## 构建

在 Windows 上运行：

```bat
build.bat
```

脚本会构建当前支持的变体，并将 JAR 复制到 `target/`。

技术细节：[TECHNICAL.md](TECHNICAL.md)

## 鸣谢

感谢 [wish131400/zstdnet](https://github.com/wish131400/zstdnet) 提供 Zstandard 网络加速思路与项目参考。
