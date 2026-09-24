# ZstdNet

ZstdNet adds Zstandard-compressed Minecraft connections through a same-port Spigot plugin or NeoForge server mod, with Fabric and NeoForge client mods.

中文文档: [README.zh-CN.md](README.zh-CN.md)

## Supported builds

| Minecraft | Server | Client | Java |
| --- | --- | --- | --- |
| 1.21.1 | NeoForge (server and client) | NeoForge | 21 |
| 1.21.11 | Spigot | Fabric, NeoForge | 21 |
| 26.1 | Spigot | Fabric, NeoForge | 25 |

Install the matching ZstdNet client mod to use compressed connections. For NeoForge 1.21.1, use the same server-client jar on the dedicated server and clients. Spigot uses the existing Minecraft port.

## Features

- Zstandard compression integrated into Minecraft connections.
- Spigot management commands: `/zstdnet <status|reload|start|stop|setup>` (permission: `zstdnet.admin`).
- NeoForge 1.21.1 server commands: `/zstdnet <status|start|stop|reload>`; dictionary training, import, export, and synchronization are available on this version.
- Client settings: `config/zstdnet-client.properties`.

## Build

On Windows, run:

```bat
build.bat
```

The script builds the supported variants and copies the jars to `target/`.

Technical details: [TECHNICAL.md](TECHNICAL.md)

## Acknowledgements

Thanks to [wish131400/zstdnet](https://github.com/wish131400/zstdnet) for the Zstandard networking concept and project inspiration.
