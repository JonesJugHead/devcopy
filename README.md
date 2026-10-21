# DevCopy

A client-side Fabric mod for Minecraft **26.3** that copies what the HUD displays (scoreboard, tab list, boss bars,
titles, action bar) to your clipboard, in plain text or with its exact formatting. It also records particle packets
so you can reverse-engineer an animation you see on a server.

## Why

When you write a plugin, a datapack or a server config, you often need the *exact* text another server shows: the
colors of a scoreboard line, the hex gradient of a tab header, the MiniMessage of a boss bar. Retyping it from a
screenshot is slow and error-prone. DevCopy copies it straight from what the client received.

The same goes for particle effects: instead of guessing an animation frame by frame, DevCopy records every particle
packet and turns it into data, a 3D replay, an analysis, and ready-to-use commands or Paper code.

## Screenshot / GIF

<!-- TODO: add a GIF of /devcopy scoreboard minimessage, then the particle viewer. -->
_Coming soon._

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.3 and [Fabric API](https://modrinth.com/mod/fabric-api).
2. Drop `devcopy-<version>.jar` into your `mods` folder.

Client-side only: it works on any server, nothing is needed on the server.

## Usage

### Copying the HUD

```
/devcopy <target> [format]
```

| Target | Content |
|---|---|
| `scoreboard` | Sidebar title + lines (vanilla order, max 15), with the score on the right when shown |
| `tab` | Header, players (name as displayed in the tab list), footer |
| `bossbar` | Every active boss bar |
| `title` | Current title and subtitle |
| `actionbar` | Last action bar message |
| `all` | All of the above, in sections |

| Format | Output |
|---|---|
| `plain` (default) | Plain text |
| `legacy` | `§` codes (hex as `§x§r§r§g§g§b§b`) |
| `minimessage` | `<red>`, `<#ff8800>`, `<b>`... tags |
| `json` | Raw JSON component |

### Particle recorder

For reverse-engineering animations (colored dust in particular).

```
/devcopy particles record <seconds> [radius] [levelEvents]
/devcopy particles stop
/devcopy particles status
```

- Captures **every** `ClientboundLevelParticlesPacket`, without sampling, timestamped in client ticks relative to
  the start (+ ms).
- Origin = your position when the recording starts; every position is relative to it. Default radius 64 blocks,
  `0` = unlimited.
- `levelEvents` set to `true` also captures `ClientboundLevelEventPacket`, to check whether an effect goes through them.
- `count = 0`: the offsets are a direction (velocity = dist × speed per axis), not a spread. The flag is kept as-is.
- 26.3: per-axis speed (`xSpeed/ySpeed/zSpeed`) and `RandomizationType` are both recorded.

The export goes to `.minecraft/devcopy/particles/<date>/`, with clickable links in the chat:

| File | Content |
|---|---|
| `capture.json` | Every raw packet (options through the codec, colors, scale…) + metadata |
| `viewer.html` | Standalone 3D replay (three.js from a CDN, internet required): play, speed, step, ticks/frame, trails, click a point |
| `analysis.md` | Emission rate, palette and gradients, per-frame geometry, rotation / rise / pulsation |
| `replay.java` | Paper class (`BukkitRunnable`) that replays the capture, for a side-by-side comparison |
| `commands.mcfunction` | The equivalent `/particle` commands in `~` coordinates |

## Build

```
./gradlew build
```

The jar lands in `build/libs/devcopy-<version>.jar`. Gradle needs Java 25.

## License

[MIT](LICENSE) © JonesJugHead
