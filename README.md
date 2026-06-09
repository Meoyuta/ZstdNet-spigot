# ZstdNet

ZstdNet is a same-port ZSTD network plugin with Fabric and NeoForge client mods.

## Build

```powershell
.\build.bat
```

The build script creates `target` when needed, builds both supported Minecraft
versions, and copies all release jars to:

```text
target/ZstdNet-1.21.11-spigot-0.1.0.jar
target/ZstdNet-1.21.11-fabric-0.1.0.jar
target/ZstdNet-1.21.11-neoforge-0.1.0.jar
target/ZstdNet-26.1-spigot-0.1.0.jar
target/ZstdNet-26.1-fabric-0.1.0.jar
target/ZstdNet-26.1-neoforge-0.1.0.jar
```

The 1.21.11 artifacts target Java 21 bytecode. The 26.1 artifacts target Java
25 bytecode.

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
