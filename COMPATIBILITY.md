# Compatibility with other mods

Target: Minecraft **1.21.11**, Fabric. Researched November 2026.

## How to read this

| Classification | Meaning |
|---|---|
| **Compatible** | Verified **and tested in-game together with Atlas Client** |
| **Likely Compatible** | A 1.21.11 Fabric build exists, and code inspection found no overlapping mixins, keybinds or files. **Not tested together** |
| Incompatible | Known to break or conflict |
| Unavailable | No 1.21.11 Fabric build found |
| Unknown | Not enough information |

**No mod is marked Compatible**, because Atlas Client has not been launched in this environment (Minecraft and Fabric's Maven could not be downloaded here). Every entry below is at most *Likely Compatible*. After you run the [in-game checklist](TESTING.md) with a given mod set, you can upgrade entries yourself.

**Sources.** `api.modrinth.com` was blocked from this workspace, so versions came from Modrinth web pages found through search, plus project changelogs and mirrors.
- **Verified page:** a Modrinth version page for 1.21.11 Fabric was found.
- **Indirect:** only a changelog or mirror listing was found. Confirm in the Modrinth App, which filters by game version and loader.

Version numbers are the newest found for 1.21.11 Fabric. Newer patch builds for 1.21.11 should be fine.

## Why conflicts are unlikely

Atlas Client uses Fabric API hooks:
- `ClientTickEvents`, `ClientChunkEvents`, `ClientPlayConnectionEvents`, `ClientWorldEvents`, `ClientLifecycleEvents`
- `WorldRenderEvents.BEFORE_TRANSLUCENT` (overlays) and `WorldRenderEvents.END_EXTRACTION` (freecam look direction)
- `HudElementRegistry.addLast` (HUD)
- `KeyBindingHelper` (unbound by default, except the GUI key Right Shift, which goes through Atlas's own dispatcher)
- `ClientCommandRegistrationCallback` (`/atlas`)

It also has **two small, optional mixins**, both used only by ESP *Glow* mode:

| Mixin | Target | What it does |
|---|---|---|
| `MinecraftClientMixin` | `MinecraftClient.hasOutline(Entity)`, HEAD | returns true for entities an ESP module asked to glow |
| `EntityRendererMixin` | `EntityRenderer.updateRenderState(...)`, TAIL | replaces the outline colour of those entities with the ESP colour |

Both use `require = 0` (and `defaultRequire: 0` in `atlasclient.mixins.json`). If another mod or a game update makes a target disappear, Mixin skips the injection instead of crashing the game, and ESP's Box/Filled modes keep working. They change only visuals, nothing that is sent to the server.

**Checked against the actual 1.21.11 sources** of Sodium (`1.21.11/stable`), Iris (`1.21.11`), MaLiLib (`LTS/1.21.11`), Mod Menu (`1.21.11`) and Litematica (0.26.16 source). None of them injects into either method; the Mojang names are `Minecraft.shouldEntityAppearGlowing` and `EntityRenderer.extractRenderState`. Sodium *calls* `shouldEntityAppearGlowing` to decide whether an entity may be culled, so entities glowing for ESP are simply never culled by Sodium, which is the desired result. Another client doing the same thing would also just return "glow".

Other facts that matter for compatibility:
- Atlas reads chunks the client already has. It writes only under `config/atlasclient/`, and reads (never writes) Litematica's `schematics/` and `config/litematica/` files.
- **Freecam** uses vanilla's own camera-entity mechanism (the one spectator mode uses), not a mixin.
- **X-Ray** draws boxes over the world and does not touch chunk meshes or block rendering. That is why it doesn't conflict with Sodium's renderer.

Conflicts can therefore only come from:
- overlays drawn in the same render pass
- HUD elements that overlap
- other mods that also own the camera or press keys and click while AutoBuilder runs

## Summary table

| Mod | Modrinth | 1.21.11 Fabric version found | Source | Depends on | Needed by Atlas? | Classification |
|---|---|---|---|---|---|---|
| Fabric API | https://modrinth.com/mod/fabric-api | 0.141.6+1.21.11 | Fabric API 1.21.11 source branch | — | **Required** | Likely Compatible (built against it, not run) |
| Sodium | https://modrinth.com/mod/sodium/version/mc1.21.11-0.8.7-fabric | mc1.21.11-0.8.7-fabric | Verified page | — | Optional (recommended) | Likely Compatible |
| Lithium | https://modrinth.com/mod/lithium/version/Ow7wA0kG | mc1.21.11-0.21.4-fabric | Verified page | — | Optional | Likely Compatible |
| FerriteCore | https://modrinth.com/mod/ferrite-core/version/Ii0gP3D8 | 8.2.0-fabric | Verified page | — | Optional | Likely Compatible |
| ImmediatelyFast | https://modrinth.com/mod/immediatelyfast/version/XCnavm6o | 1.14.1+1.21.11-fabric | Verified page | — | Optional | Likely Compatible |
| Entity Culling | https://modrinth.com/mod/entityculling | 1.9.4 (changelog says 1.21.11 Fabric added) | Indirect | see its page | Optional | Likely Compatible (Fabric file not directly confirmed) |
| Mod Menu | https://modrinth.com/mod/modmenu | 17.0.x (17.0.0-alpha.1 seen) | Indirect (mirror) | Fabric API | Optional | Likely Compatible (version unconfirmed) |
| Cloth Config | https://modrinth.com/mod/cloth-config | v21.11.153 | Indirect (mirror) | — | Not used by Atlas | Likely Compatible (version unconfirmed) |
| Litematica | https://modrinth.com/project/bEpr0Arc/version/R9maucI8 | 1.21.11-0.26.11 or newer (**minimum for security**) | Verified page | MaLiLib | Optional | Likely Compatible |
| MaLiLib | https://modrinth.com/mod/malilib/version/0.27.6 | 0.27.6 (newer source builds need ≥ 0.27.20; let the app pick) | Verified page | — | Only for masa mods | Likely Compatible |
| MiniHUD | https://modrinth.com/project/UMxybHE8/version/JJwjAw45 | 1.21.11-0.38.13 | Verified page | MaLiLib | Optional | Likely Compatible (HUD overlap, see below) |
| Tweakeroo | https://modrinth.com/project/t5wuYk45/version/7hikIkyO | 1.21.11-0.27.6 | Verified page | MaLiLib | Optional | Likely Compatible (some tweaks conflict with AutoBuilder) |
| ReplayMod | https://modrinth.com/mod/replaymod | 1.21.11-2.6.26 / 2.6.27 | Indirect (mirrors) | see its page | Optional | Likely Compatible (version unconfirmed) |
| Iris | https://modrinth.com/mod/iris/version/ZQx4ktUs | 1.10.5+1.21.11-fabric | Verified page | Sodium | Optional | Likely Compatible (overlays under shaders, see below) |
| Xaero's Minimap | https://modrinth.com/mod/xaeros-minimap/version/8MdqDp18 | fabric-1.21.11-26.4.2 | Verified page | see its page | Optional | Likely Compatible |
| Xaero's World Map | https://modrinth.com/mod/xaeros-world-map/version/uuPYtgMh | fabric-1.21.11-1.44.2 | Verified page | see its page | Optional | Likely Compatible |

*"see its page"*: the dependency list was not re-verified here. The Modrinth App installs required dependencies automatically.

## Per-mod notes

### Fabric API (required)
Atlas uses the rendering, HUD, keybinding, lifecycle, networking-event and client-command modules.
- **Install order:** first. It is the only hard dependency.

### 1.2.0 changes

Base Finder v2, Block ESP and ESP tracers add **no new mixins**. Tracers are line segments drawn by the same world overlay pass as the boxes (`WorldRenderEvents.BEFORE_TRANSLUCENT`), and the analysis reads copies of chunk sections on a background thread, exactly like 1.1.0. Nothing changes for Sodium, Iris, Litematica or MaLiLib.

### Sodium, Iris
- **Sodium** replaces chunk rendering, but Fabric's `WorldRenderEvents` still fire. Atlas overlays, including X-Ray and ESP boxes, are drawn as separate line and box batches, so they should render normally. X-Ray never edits chunk meshes, so Sodium's block renderer is not involved.
- **Freecam** works with Sodium the same way as vanilla spectating does. Sodium's occlusion culling follows the camera, so chunks behind walls near the camera can pop in.
- **Iris with a shader pack** may draw Atlas outlines with the shader's lighting, or hide the *see-through* (no depth test) layer.
  - **Affected:** Block Highlight, Chunk Outlines, Waypoint beams, Schematic Preview.
  - **Workaround:** turn off shaders, or turn off *See-through* in Block Highlight.
  - The pipelines are created in a `try/catch` and fall back to the normal depth-tested layers if registering them fails.
- **Iris + ESP Glow:** shader packs usually replace vanilla's entity-outline pass, so Glow may show nothing. Use Box, Filled or Box and fill with shaders.

### Lithium, FerriteCore, ImmediatelyFast, Entity Culling
These are server-logic, memory and render-batching optimisations that don't touch anything Atlas uses.
- **ImmediatelyFast** batches HUD drawing. If Atlas HUD text ever flickers, try ImmediatelyFast's config first.

### Mod Menu, Cloth Config
- **Mod Menu** lists Atlas Client. Atlas does not register a Mod Menu config screen: use Right Shift. Mod Menu has no keybinds or mixins that overlap with Atlas.
- **Cloth Config** is not used by Atlas.

### Litematica + MaLiLib
- **Use 1.21.11-0.26.11 or newer.** Earlier 1.21.11 builds had a remote-code-execution vulnerability fixed in July 2026. If you run Servux, use 0.9.5 or newer.
- **Shared files:** both mods read `schematics/`. Atlas can import Litematica placements from `config/litematica/litematica_<world>_dim_<dim>.json`, and uses Litematica's exact origin, rotation and mirror rules (per region too). It never writes Litematica files.
  - Litematica writes that file **when you leave the world**. To pick up a placement you just made, rejoin first, or try *Import live (experimental)*.
- **Live integration:** *Import live* reads Litematica's in-memory placements by reflection. It is **experimental and untested**: if the internals differ it reports the error and does nothing.
- **Overlays:** with Litematica's overlay on, turn off Atlas *Schematic Preview* (or vice versa) to avoid two sets of ghost blocks.
- **Keybinds:** checked against Litematica's 1.21.11 source (`Hotkeys.java`). Its defaults are M-combos (M, M+R, M+S…), Ctrl/Alt combinations, Page Up/Down, keypad keys and I, and **none uses Right Shift**. Atlas only binds Right Shift by default (Freecam, X-Ray and ESP are unbound until you choose keys). Iris's defaults (K, O, R) and Sodium/Mod Menu (none) don't overlap either.
- **Printers:** do not run a Litematica printer fork, or Litematica's *Easy Place*, together with AutoBuilder. Both will click.

### MiniHUD
Both mods draw text in screen corners.
- Atlas's active-module list is at the top right and its toasts slide in at the top right.
- Move Atlas's info column with *Client → GUI Settings → HUD X / HUD Y*, or turn off *Module list*.

### Tweakeroo
While AutoBuilder is running, disable these tweaks:
- *tweakHotbarSlotCycle / Randomizer*
- *tweakToolSwitch*
- *tweakFastBlockPlacement*
- *tweakFlexibleBlockPlacement*
- *tweakAccuratePlacement*
- *tweakHandRestock*

They change the selected slot or how a click turns into a block placement, which AutoBuilder relies on. The **affected feature** is AutoBuilder placement orientation and item selection. AutoBuilder detects wrong results and retries, but repeated failures are likely.

### ReplayMod
- Recording is unaffected.
- During replay playback Atlas overlays may show up in the replay world, because Atlas renders in that world too.
- AutoBuilder and Auto Mine do nothing useful during a replay (there is no server to send block breaks to).

### Xaero's Minimap / World Map
- Atlas waypoints are kept separately in `config/atlasclient/worlds/<world>/waypoints.json` and are not synced with Xaero's.
- Both mods render beams. Turn off Atlas beams if they overlap.

## Not supported / incompatible

| Item | Why |
|---|---|
| Another freecam mod at the same time as Atlas Freecam | both try to own the camera entity; turn one off |
| Another X-Ray mod that hides terrain (resource-pack or mesh based) | works, but you get two different X-Ray views at once |
| Minecraft versions other than 1.21.11 | Built against 1.21.11 APIs (`fabric.mod.json`: `"minecraft": "~1.21.11"`) |
| Forge / NeoForge | Fabric only |
| OptiFine / OptiFabric | Replaces the render pipeline Atlas hooks into |
| Litematica 1.21.11 builds before 0.26.11 | Security vulnerability |
| Baritone (build/mine processes), Litematica printer forks, other auto-placers running at the same time | They compete with AutoBuilder for movement keys, hotbar slot and clicks. Run one at a time |

## Recommended install order

Fabric API → Sodium → Lithium / FerriteCore / ImmediatelyFast / Entity Culling → Iris → MaLiLib → Litematica / MiniHUD / Tweakeroo → Mod Menu / Cloth Config → Xaero's Minimap / World Map → ReplayMod → Atlas Client.

Fabric doesn't depend on load order. Installing in this order just makes a missing dependency obvious at each step.
