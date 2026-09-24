# ZstdNet

ZstdNet adds Zstandard-compressed Minecraft connections through same-port Spigot and NeoForge server integrations, with matching Fabric and NeoForge client mods.

中文文档: [README.zh-CN.md](README.zh-CN.md)

## Supported builds

| Minecraft | Server artifact | Client | Java |
| --- | --- | --- | --- |
| 1.21.1 | NeoForge server-client | NeoForge | 21 |
| 1.21.11 | Spigot, NeoForge client | Fabric or NeoForge | 21 |
| 26.1 | Spigot, NeoForge client | Fabric or NeoForge | 25 |

The build script places generated artifacts in `target/`. For 1.21.1, install the server-client NeoForge jar on the dedicated server and matching clients. Spigot uses the existing Minecraft port and does not open a second proxy port.

## Features

- Zstandard framing integrated into the Minecraft Netty connection pipeline.
- Same-port protocol detection and injection.
- Runtime compression-level control with automatic benchmarking.
- NeoForge 1.21.1 dictionary training, import, selection, naming, unloading, synchronization, and client download feedback.
- Convenient status and benchmark screens for NeoForge 1.21.1 players.

## NeoForge 1.21.1 commands

Permission level 2 is required for management, benchmark start, compression-level, and dictionary mutation commands.

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
`/zstdnet dictionary train [seconds]`  
`/zstdnet dictionary stop`  
`/zstdnet dictionary cancel`  
`/zstdnet dictionary import <path>`  
`/zstdnet dictionary switch [path]`  
`/zstdnet dictionary unload`  
`/zstdnet dictionary export`  
`/zstdnet dictionary name <pending-file> <name>`

`benchmark start` starts a benchmark and opens its result screen when the result is ready. `benchmark info` opens the current benchmark state on demand.

Player `status`, `dictionary status`, and `dictionary list` commands open the corresponding status screens.

## Dictionary files

Server and client dictionary files are stored below:

```text
config/zstdnet/dict/
  dictionary.zdict
  dictionary-selection.txt
  dictionary-naming.properties
  pending_*.zdict
  temp_*.zdict
```
 

Training and import save dictionaries in this managed directory. `dictionary export` opens the selected dictionary's file path. New connections use the selected dictionary, while active connections keep the dictionary negotiated when they connected.

## Client configuration

The shared client configuration is `config/zstdnet-client.properties`. A server dictionary is validated and cached under `config/zstdnet/dict/`.

## Build

Run `bash ./build.sh`. The script creates `target/`, cleans generated jars there, and builds all supported variants.

Technical details: [TECHNICAL.md](TECHNICAL.md)

## Acknowledgements

Thanks to [wish131400/zstdnet](https://github.com/wish131400/zstdnet) for the Zstandard networking concept and project inspiration.
