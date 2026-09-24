# ZstdNet

ZstdNet is a same-port ZSTD network plugin with Fabric and NeoForge client mods.

Chinese documentation: [README.zh-CN.md](README.zh-CN.md)

## Build

```powershell
.\build.bat
```

The build script creates `target` when needed, builds the supported Minecraft
versions, and copies all release jars to:

```text
target/ZstdNet-1.21.1-neoforge-server-client-1.0.1-prerelease.jar
target/ZstdNet-1.21.11-spigot-0.1.0.jar
target/ZstdNet-1.21.11-fabric-0.1.0.jar
target/ZstdNet-1.21.11-neoforge-0.1.0.jar
target/ZstdNet-26.1-spigot-0.1.0.jar
target/ZstdNet-26.1-fabric-0.1.0.jar
target/ZstdNet-26.1-neoforge-0.1.0.jar
```

The 1.21.1 and 1.21.11 artifacts target Java 21 bytecode. The 26.1 artifacts target Java
25 bytecode.

NeoForge 1.21.1 uses the `server-1211` variant with shared client sources in
`neoforge/src/client` and server/client entry sources in `neoforge/src/mc1211`.
Its version is set by `neoforge_1211_mod_version` in `gradle.properties`;
the other variants use `mod_version`.
The 1.21.11 and 26.1 `client` variant uses `neoforge/src/client-entry`.
The current 1.21.1 local build requires the mapped Minecraft and NeoForge JARs
in the Gradle cache; a fresh CI runner still needs dependency bootstrapping.

## NeoForge 1.21.1 Commands

Dedicated-server operators (permission level 2) can run
`/zstdnet <status|start|stop|reload>`. Compression starts automatically on
dedicated servers. Clients default to `enabled=true` and `servers=*`;
explicit settings in an existing client configuration are respected.
`start` initializes same-port compression or resumes it after `stop`.
`stop` stops intercepting new connections; existing connections keep their
codec. `reload` reloads the dictionary from disk and starts compression.
There is no `setup` command or port migration for this variant.

`status` (also the default for `/zstdnet`) reports total upstream plus
downstream traffic in bytes (compressed / uncompressed), compression ratio
(compressed divided by uncompressed, as a percentage; zero without traffic),
and the number of currently active ZstdNet connections. Counters cover the
current server session and survive stop/start. Command messages, including
dictionary commands, use English and Simplified Chinese language resources.

## Dictionaries (NeoForge 1.21.1)

Install the server-client JAR on both the dedicated server and its clients.
Operators (permission level 2) can use:

```text
/zstdnet dictionary train [seconds]
/zstdnet dictionary status
/zstdnet dictionary stop
/zstdnet dictionary cancel
/zstdnet dictionary export
/zstdnet dictionary import <path>
/zstdnet dictionary list
/zstdnet dictionary switch <path>
/zstdnet dictionary unload
/zstdnet dictionary name <pending-file> <name>
```

`switch` and `name` provide Tab completion for dictionary files. Normal saves prompt operators to name the dictionary within one minute, then apply it; timeout names it `untitled_yyyyMMdd_HH-mm-ss.SSS`. Shutdown saves use `temp_yyyyMMdd_HH-mm-ss.SSS` and are selected at the next startup. Operators receive a naming prompt on login until the file has been named. Renaming preserves the dictionary instance and updates the file and selection records.

The selected dictionary path is saved in `config/zstdnet/dictionary-selection.txt` and restored on server startup. If no selection exists, the server discovers the first valid `.zdict` below `config/zstdnet`. `switch` changes the dictionary for new connections; `unload` selects no dictionary. Existing connections keep their negotiated dictionary until they reconnect.

Training collects live ZstdNet traffic for 600 seconds by default (range 1–86400).
`stop` finishes collection and trains asynchronously; `cancel` discards the
session. Status reports samples, remaining time, and the final result. Server
shutdown ends collection immediately, waits for training, and saves the
dictionary before exiting. Saving progress and completion are logged at INFO;
collection, training and five-second waiting messages use DEBUG.
Insufficient samples or a training failure preserve the previous dictionary.

Training targets a 128 KiB dictionary with a 16 MiB sample budget (128 times
the dictionary size). Each sample contributes at most 4 KiB, so a full budget
contains at least 4096 samples; reaching the budget starts training early.
ZSTD may return a dictionary smaller than the target capacity.
One long-lived server store shares the current immutable dictionary between
connections; existing connections retain their negotiated version.

The active dictionary is stored at `config/zstdnet/dictionary.zdict`. Export
creates a snapshot under `config/zstdnet/exports` and prints a clickable,
copyable absolute path. Import accepts an absolute path or a path relative to
`config/zstdnet`, including spaces; invalid dictionaries preserve the current one.

New connections automatically download the server dictionary with a percentage
and byte-count screen. Clients ignore local dictionaries for negotiation and
acknowledge receipt immediately. If the server has no dictionary, the connection
uses ordinary ZSTD. Training/import changes apply to new connections; existing
connections retain their negotiated dictionary until reconnecting.

Commands and progress UI are currently provided only for NeoForge 1.21.1.
All Java packages use the version-independent prefix `mys.zstdnet.reborn`.

## Spigot Behavior

The Spigot plugin uses same-port Netty injection. It does not bind a second TCP
port and does not move the Minecraft server to a localhost backend port.

At runtime:

- players connect to the normal Minecraft server port
- the plugin injects a Netty handler into the existing Minecraft listener
- ZstdNet client connections are decoded before the Minecraft packet splitter
- when Minecraft AES encryption is enabled, ZstdNet frames are decoded after AES
  decryption and encoded before AES encryption
- server responses to ZstdNet clients are encoded back into ZSTD frames before
  Minecraft encrypts the wire bytes
- the vanilla Minecraft login compression negotiation is suppressed for ZstdNet
  clients, so vanilla `compress`/`decompress` handlers are not used on those
  connections
- ZstdNet frames use raw passthrough automatically when ZSTD would make a frame
  larger, which avoids heavy upload expansion on small client packets
- vanilla status ping is passed through raw
- vanilla raw login is rejected with a disconnect message

This works on hosts that expose only one usable port because Spigot and ZstdNet
share the same already-open server socket.

Admin command:

```text
/zstdnet <status|reload|start|stop|setup>
```

Permission:

```text
zstdnet.admin
```

## Client Modules

Fabric and NeoForge client jars are built by default. The client mixins use
`ConnectScreen.startConnecting` only to decide whether the next connection should
use ZstdNet. The actual ZSTD frame codec is injected into Minecraft's
`Connection` Netty pipeline, after Minecraft has framed packets and before
Minecraft applies AES encryption.

The 26.1 client jars use Mojang's named 26.1 client jar as a compile-only input
because 26.1 does not publish Fabric intermediary/Yarn mappings. They still
package the automatic connection and Netty pipeline mixins.

Client configuration is written to:

```text
config/zstdnet-client.properties
```

ViaVersion on the server is compatible with the Bukkit-side plugin path, but it
does not remove the need for a ZstdNet client mod on clients that should use the
compressed ZstdNet connection. This repository builds 1.21.11 and 26.1
artifacts.

## Acknowledgements

Thanks to [wish131400/zstdnet](https://github.com/wish131400/zstdnet). This
project was written with reference to its ZSTD network acceleration idea and
overall workflow.
