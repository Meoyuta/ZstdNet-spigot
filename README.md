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
- An F8 overlay selector for NeoForge 1.21.1 status views.

## NeoForge 1.21.1 commands

Permission level 2 is required for management, benchmark start, compression-level, and dictionary mutation commands.

`/zstdnet start`  
`/zstdnet stop`  
`/zstdnet reload`  
`/zstdnet ping`  
`/zstdnet debug`
`/zstdnet complevel set <1-22>`  
`/zstdnet benchmark start`  
`/zstdnet benchmark interval <minutes>`
`/zstdnet dictionary train [seconds]`  
`/zstdnet dictionary stop`  
`/zstdnet dictionary cancel`  
`/zstdnet dictionary import <path>`  
`/zstdnet dictionary switch <path>`
`/zstdnet dictionary unload`  
`/zstdnet dictionary export`  
`/zstdnet dictionary name <pending-file> <name>`

`ping` measures a direct request/response round trip. Players can use `debug` to write a diagnostic snapshot to `config/debug/zstdnet-debug-*.log`; the full data is not echoed in chat.

`benchmark start` starts a benchmark. `benchmark interval <minutes>` changes and saves the automatic benchmark period (1-10080 minutes). Press F8 to choose the management, benchmark, or dictionary status overlay. Select the current item again to turn it off, or choose “Turn overlay off”.

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
 

Training and import save one compressed dictionary bundle containing separate 64 KiB uplink and 128 KiB downlink dictionaries. `dictionary export` opens the selected bundle's file path. New connections use the selected bundle, while active connections keep the dictionaries negotiated when they connected. The three player information overlays refresh once per second without blocking gameplay. Status upload/download is measured from the server perspective: upload is server-sent traffic and download is server-received traffic.

## Client configuration

The shared client configuration is `config/zstdnet-client.properties`. A server dictionary is validated and cached under `config/zstdnet/dict/`.

## Build

Run `bash ./build.sh`. The script creates `target/`, cleans generated jars there, and builds all supported variants.

Technical details: [TECHNICAL.md](TECHNICAL.md)

## Acknowledgements

Thanks to [wish131400/zstdnet](https://github.com/wish131400/zstdnet) for the Zstandard networking concept and project inspiration.
