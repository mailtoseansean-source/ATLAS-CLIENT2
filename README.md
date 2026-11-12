# Atlas Client 1.2.0

A client-side utility mod for **Minecraft Java 1.21.11** on **Fabric**. It includes:
- **Auto Mine**: digs a tunnel where you look and mines the ores it uncovers
- a **Base Finder** that finds buried bases from the surface, and an Ancient Debris finder
- X-Ray and ESP overlays
- a free camera
- flight where the server allows it
- a schematic AutoBuilder: search for a schematic by name, then *Build here*
- a simple one-page menu (Right Shift), with every setting one click away

> **Build status (please read):** **[TESTING.md](TESTING.md) is the source of truth.**
> - The pure-Java core is **compiled and tested here: 99/99 unit tests pass**. That covers analysis, scoring, schematics (also checked against **329 real Litematica files**), planning, restocking, pathfinding, X-Ray indexing, camera motion, config and the GUI model.
> - The Minecraft-facing layer is **not compiled here**. This workspace's network policy blocks Minecraft's, Fabric's, Gradle's and Maven Central's servers. Every Minecraft call was checked against the Yarn 1.21.11+build.6 signatures by two static checkers, but **no build or game launch has run**.
> - On your machine, run `./gradlew build`, then `./gradlew runClientGameTest`, then `./gradlew verifyRelease`.

| | |
|---|---|
| Minecraft | 1.21.11 (Java Edition) |
| Fabric Loader | 0.20.1 (fabric.mod.json accepts ≥ 0.18.0) |
| Fabric API | 0.141.6+1.21.11 |
| Mappings | Yarn 1.21.11+build.6 (the last Yarn release; 26.1+ is unobfuscated) |
| Loom | `net.fabricmc.fabric-loom-remap` 1.19-SNAPSHOT |
| Gradle | 9.8.2 (wrapper included) |
| Java | 21 |
| Release JAR | `build/libs/atlasclient-1.2.0.jar` (ignore `-sources.jar`) |
| Mixins | 2, both optional (ESP glow only), see [COMPATIBILITY.md](COMPATIBILITY.md) |

Installation: [INSTALLATION.md](INSTALLATION.md) · Other mods: [COMPATIBILITY.md](COMPATIBILITY.md) · Tests and status: [TESTING.md](TESTING.md)

---

## Using it

| Key | Does |
|---|---|
| **Right Shift** | Opens the menu |
| **X** | X-Ray on/off |
| **H** | ESP on/off (players, mobs, items, containers) |
| **G** | Freecam on/off |
| **N** | Auto Mine on/off (face the direction to dig first) |

- The menu opens on one page with the main features. Click a card to switch it on or off, click **>** for its few options. **All settings >** shows every module and setting by category (and *< Simple view* goes back).
- Every feature works in singleplayer and on servers. There is no server list to set up.
- Settings are saved per profile in `config/atlasclient/profiles/`. Keys can be changed in *All settings* (each module's *Keybind*, and *Client → GUI Settings* for the menu and ESP keys).
- **Server rules still apply.** Many servers ban X-Ray, ESP, freecam and mining automation. Nothing in Atlas hides from anti-cheat or moderation.

## Features

### World
- **Auto Mine.** Face where you want to dig and switch it on.
  - **Tunnel mode:** digs a straight tunnel at your feet level, **1x2** or **3x3**, for a set length or until you stop it, and walks forward as it goes.
  - **Area mode:** clears a box of **Area width x Area length x Area depth** (1-128 each, e.g. 50 x 50 x 50) in front of you, **one flat layer at a time from the top down**, every block out to the edges, so the walls come out straight and the floor level. Only the blocks touching lava or water (and bedrock) are left in place, never a whole row; it never opens lava. The bottom blocks over a cave stay as floor so you can't fall through, and inside a layer it never steps down more than 1 block, so it doesn't walk into caves (if it does fall, it pillars back up). Anything it couldn't get on the way (gravel that fell in, a block it got stuck on) is picked up by a clean-up pass at the end. You stand in the middle of the box's back edge; it goes from head height downward.
  - **Pickaxe:** Regular, or Amethyst (DonutSMP's 3x3): the 3x3 is swung only where the whole 3x3 stays inside what it is digging (it aims at the middle of each 3x3 face); elsewhere, or if a 3x3 swing doesn't get the block broken within 5 seconds (blocks above in the way), it switches to your regular pickaxe. Gravel, sand, dirt and clay are dug with a **shovel** if you carry one. It never uses a tool below *Min durability*.
  - **Extra ores** (tunnel mode): *Off* (default) mines only what is in the tunnel and keeps moving; *Touching tunnel* also takes ores touching the walls or ceiling; *Whole vein* follows veins up to 4 blocks into the walls.
  - **Lava, water or bedrock ahead** in a tunnel: it turns (right, then left next time) and carries on from there, up to 4 turns.
  - **Drop junk when full:** throws cobblestone, deepslate, dirt and similar behind you so ores still fit.
  - **Stops by itself** before lava or water (it never opens a block next to a fluid), before a gap or cave in the floor, at bedrock, when your health drops to *Stop at hearts*, when no usable pickaxe is left, or when it gets stuck. It pauses while a menu or Freecam is open.
  - It mines with normal block breaking at vanilla speed, like the AutoBuilder's excavation, using all the reach the server gives you (`block_interaction_range`; the server rejects anything farther).
  - **Eats by itself** when hunger drops to 7 drumsticks: the best food you carry, steak first (never rotten flesh, spider eyes, poisonous potatoes, pufferfish, chorus fruit or golden apples).
  - **Never walks into holes.** A gap in the tunnel floor is filled with a plain block (cobblestone, deepslate, dirt...) placed from the edge while sneaking. In area mode the bottom block over a cave is left as floor.
  - **Caves count as already dug.** Open cave space on the tunnel line is skipped like mined blocks; where the cave has no floor at tunnel level it lays a 1-block cobblestone path and keeps going straight. It only stops for lava or water.
  - **Pause / resume** (on the menu card) stops digging and keeps your place; click again to carry on. Turning it off and on starts a new tunnel where you face.
  - **If it falls anyway** (knock-back, a block giving way) it pillars up with a plain block to the tunnel level, mining anything above its head, then goes back to the tunnel, digging through walls and bridging gaps, and carries on. With no plain blocks it stops and says so.
- **Base Finder (v2, 1.2.0).** Finds hidden bases and places where players stayed, in the chunks the server has sent you. Results are estimates, not proof (except *Confirmed Structure*, see below).
  - **Growth first ("Focus on growth", on by default).** Plants, crystals and copper only grow or age while a player is within about 128 blocks (random ticks). More growth than world generation leaves means someone stayed nearby for a long time: amethyst geodes whose buds are mostly fully grown clusters (a fresh geode has about a quarter), vines hanging longer than leaf decoration makes them, kelp taller than generated kelp, ripe cocoa and crops beyond generated village farms, and unwaxed copper that has oxidised. Growth counts at any depth (also kelp in the sea or vines on trees near a buried base), and jungles, swamps and lush caves count vines much less because vines are everywhere there.
  - **Lived-in signs, not just blocks.** Placed blocks alone (concrete, planks, glass, random lines or piles) never go above **Low**; blocks scattered one here, one there count even less, so random block spam or a pillar is not a "base". Medium needs one sign of use (3+ storage containers, beds and workstations, a farm, redstone, beehives) or growth; High needs two (e.g. storage + farm, or storage + overgrown amethyst).
  - **Shapes underground.** The air under the surface is analysed for things natural caves don't make: box-shaped rooms, straight tunnels (also 1x2 branch mines), big dug-out areas with a flat floor, straight vertical shafts, dug staircases and straight water channels. Placed blocks are checked for flat walls and floors, lights at a regular spacing, mirror-symmetric layouts, containers side by side, farm plots, saplings in a grid, several kinds of player blocks together, and vines on built blocks.
  - **Neighbouring chunks are judged together.** A base with its storage in one chunk and its farm in the next is one site with both kinds of evidence; a tunnel that crosses chunk borders is followed across them.
  - **Generated structures are recognised** and lowered (or ignored, setting *Generated structures*): villages, abandoned mineshafts (also stretches without rails), strongholds, dungeons, ancient cities, trial chambers, desert and jungle temples, woodland mansions, ruined portals, ocean monuments, bastions, nether fortresses, trail ruins and igloos. Blocks world generation never places still count fully, so a base built into a mineshaft is still found.
  - **Confidence:** Very Low, Low, Medium, High, Very High and **Confirmed Structure**. Confirmed is only given when blocks world generation cannot make (concrete, placed leaves, beehives, saplings) were received built into something *and* the place has at least two lived-in signs. Everything else is a weighted estimate, and each find lists its evidence as observed, inference or caveat.
  - **Range:** Small 64, Medium 128, Large 256, Extreme 512, Ultra 1024, Maximum (all loaded). These are upper limits: the client only has the chunks the server sends (its view distance), and nothing else is requested or reconstructed. Finds stay remembered after you walk away and are drawn up to the range with a beam and a label like "Base? High | 340 m north-west | Y -32 (95 below) | storage; farm".
  - **Underground only** (default): blocks must be at least *Min depth* (6) blocks under the top block of their column, so you find buried bases from the surface and surface builds are ignored. Growth always counts.
  - **Dashboard** (*Open discoveries*): scan status, effective range, chunks analysed (per second and ms each), totals, highest confidence; every find with coordinates, dimension, distance, confidence, type, blocks seen, shapes, reasons, last observed time and saved state; sort by confidence, distance, most recent, most evidence or type; filters for overgrown, underground, storage, farms, redstone, artificial terrain and saved/historical; save, hide, dismiss, waypoint; the alert history.
  - **Saved finds** (*Auto-save High+* on by default) are kept per world/server with dimension, coordinates, evidence, confidence, time, last observed state and a note. When a saved place is not detected right now it is still drawn, labelled *Saved (historical)* with when it was last seen.
  - **Alerts** for High+ finds and, from Medium, unusual growth, underground rooms/tunnels, storage, large structures, farms and block clusters (each can be switched). Nearby finds are grouped into one alert ("+2 more nearby"), each kind has a cooldown, and a place is announced again only when its confidence goes up.
  - **Performance:** chunks are copied on the game thread within a time budget (*CPU budget*, default 3 ms) and analysed on one background thread, nearest first; scanning slows down below a set FPS, results are bounded in memory, and changed chunks are re-analysed.
  - Columns topped by water or lava are not counted as underground (shipwrecks, ocean ruins). Container contents are never visible to the client, and anti-xray servers may hide chests and other blocks; underground rooms and growth still show.
- **Suspicious Chunk Analyzer.** The background scanner behind Base Finder: chunks per tick, scan radius (0 = Base Finder's range), scan every N ticks, CPU budget, slow-down FPS, rescan interval and all weights (indicators, growth, amethyst, shapes, layout, structure damping).
- **Ancient Debris Finder.** Lists debris from client chunks or your own singleplayer save, with distance and exposed or enclosed. It does not use the X-Ray module.
  - **On servers** it shows only debris that is confirmed real: touching air, or next to a block you broke (the server sends the real blocks around every block you break). Buried debris nobody has uncovered is hidden, because anti-xray servers fill the ground with fakes. Real buried debris is never sent to the client, so no mod can show it; tunnelling at **Y 13-17** in the Nether with Auto Mine is how you uncover it. *Show possibly fake* shows the buried ones too, labelled *may be fake*. In single-player everything is shown.
  - It shows how trustworthy the data is: authoritative, normal, likely fake ores, or hidden by anti-xray.
  - Export to CSV or JSON.
  - Results survive a trip to the Nether and back on the same server.

### Render
- **X-Ray.** Highlights ores and blocks you choose, through walls.
  - 10 ore groups, each with its own switch and colour: diamond, ancient debris, emerald, gold, iron, redstone, lapis, copper, coal, quartz.
  - **Custom blocks**, each with an optional colour: `spawner=#FF00FF, budding_amethyst`.
  - Opacity, outlines, line width, max distance, max blocks, and *Exposed only* for cave ores.
  - Chunks are scanned in the background. Mined blocks disappear right away and changed chunks are rescanned.
  - It **draws boxes over the normal world instead of hiding terrain**. That keeps it compatible with Sodium and Iris, and the world looks normal the moment it is off.
  - Only data the client has is used. The HUD warns when a server seems to hide ores (only exposed ones visible) or fake them (density far above natural).
- **Player ESP, Mob ESP, Item ESP, Container ESP, Block ESP.** Separate modules, each with its own settings.
  - Modes: **Box**, **Filled**, **Box and fill**, or **Glow** (vanilla outline in your colour). Line width, fill opacity, colours, through-walls toggle.
  - **Tracers** (lines from your view to each target, showing which way to look), labels with name, **health** (when the server sends it) and **distance**, label scale and colour, max shown (nearest first).
  - **Range presets** like Base Finder (64 to 1024, or everything loaded). Entities only exist on your client within the server's tracking range, and Atlas never invents positions beyond it.
  - **Block ESP** highlights any blocks you list (default: spawners, budding amethyst, beehives, end portal frames, trial spawners, vaults) with boxes, labels and tracers, using its own background scanner.
  - Base Finder finds, suspicious chunks (Chunk Outlines) and saved/historical finds are drawn by Base Finder itself.
  - Mob ESP splits hostile, passive and other. Container ESP covers chests, barrels, shulker boxes, ender chests, hoppers, furnaces, dispensers/droppers, crafters and brewing stands, each with its own colour.
- **Block Highlight**, **Chunk Outlines**, **Waypoints** (with beams) and **Detection Labels** style the Base Finder, debris and preview overlays.

### Camera
- **Freecam.** A detached camera.
  - WASD, jump/sneak for up and down, sprint for boost, with smooth acceleration and glide.
  - Your player stays exactly where it is and sends no movement, and nothing can be clicked from the camera.
  - Turning it off (or taking damage, a world change, or death) returns the view and restores your look direction.
- **Camera Speed.** Speed, boost, vertical speed, smoothing, and faster/slower keys.
- **Chunk Outlines.** Your chunk as a bright square on the ground with tall corner posts, marks every 4 blocks and its coordinates in the middle; the chunks around it as darker squares.
- **Camera Settings.** A "You" marker at your real position, HUD coordinates, stay inside loaded chunks, max distance, and exit on damage.
  - **Max distance 0 = Auto** (default): the camera goes as far as the world is loaded and drawn around it (its chunk and two rings of chunks around it must be loaded). The server only sends the world around your player, so that is the real limit; the HUD says *Edge of the loaded world* when you reach it. Entities far from your player are not sent by the server either.

### Building
- **AutoBuilder.** Builds from `.litematic` files (multiple regions, negative sizes) and Sponge `.schem` files (v1, v2, v3).
  - **Placement tool (like Litematica's stick, but a birch plank):** hold birch planks while a schematic is selected or loaded. Right-click a block: move the schematic there. Left-click: rotate 90°. Sneak + left-click: mirror. Sneak + right-click: build / pause / resume. Ctrl + scroll: nudge it a block toward where you look. These clicks never place a plank or hit a block. With no schematic the planks work normally. Change the item under *More settings > AutoBuilder > Placement tool* (empty = off).
  - Eats and climbs out of holes like Auto Mine (only a real hole, 3+ blocks below the build).
  - On the main menu: click *Schematic* and type part of the name (several words work in any order, e.g. `castle big`); Enter or a click picks it. Then *Build here* loads it at your feet and starts. *Pause / resume* and *Stop* are next to it.
  - **Placement:** origin, rotation and mirror, using Litematica's rules exactly. You can import a placement from Litematica's saved files.
  - **How it builds:** it places supports first and works out orientation the way vanilla does, including wall torches, signs, banners and heads. Afterwards it right-clicks to set repeater delay and comparator mode.
  - **Checking:** it checks each block against the world and retries or skips with a reason. Pause, resume, cancel, restart and retry skipped.
  - **Excavate first:** digs out wrong blocks top-down, leaves fluids alone and never digs the block under you.
  - **Pickaxe mode: Regular or Amethyst.** For DonutSMP's 3x3 pickaxe: it uses the 3x3 only where the whole 3x3 is safe to break, otherwise it falls back to a regular pickaxe. Toggle with a key or `/atlas build pickaxe <regular|amethyst>`.
  - **Restock from shulkers (survival):** when a block runs out, it places a shulker box from your inventory next to you, outside the build, takes only what's missing, mines the box with a **normal** pickaxe and walks over the drop until it's back. It always keeps a free slot for the box and never leaves it behind silently.
  - **Drop unneeded blocks:** *When full* (default), *Always* or *Off*. *Junk only* (digging drops, default) or *Any unneeded block*. It faces away from the build to throw.
    - Never drops shulker boxes, renamed or enchanted items, your *Never drop* list (diamond and netherite blocks, beacons, spawners…), or anything the build still needs.
- **Schematic Preview** shows ghost blocks coloured by status. **Material Checklist** compares needed and held, including what's inside your shulker boxes.

### Movement, Utility and Client
- **Flight:** only where the server allows flying (creative, spectator, a server-granted /fly), or in singleplayer.
- **Navigation Assist:** waypoint direction and path preview.
- **Notifications** with history. Saved Discoveries, Config Profiles, GUI Settings (including the HUD position, to avoid MiniHUD), Theme, Keybinds, Performance (pauses scanning at low FPS), Diagnostics.

### Commands
`/atlas modules | toggle <module> | schem list|load|origin|rotate|mirror|unload | build start|pause|resume|cancel|restart|retry|excavate <on|off>|pickaxe <regular|amethyst> | debris status | waypoint add|remove|list | notifications | diag`

## Sample schematics (`schematics/`)

| File | Tests |
|---|---|
| `stone_platform.schem` | the simplest build (49 stone) |
| `small_house.litematic` | walls, glass panes, door, stair and slab roof, wall torch |
| `stairs_and_slabs.schem` | stairs in 4 facings, top/bottom halves, slab types, log axes |
| `redstone_mechanism.litematic` | lever, wire, repeater delay 3, comparator subtract, lamp, observer |
| `multi_region.litematic` | 3 regions, including negative sizes |
| `storage_room.schem` | chests, barrels, hopper, lantern |
| `crop_farm.schem` | a farm frame (farmland and crops are manual) |
| `cactus_farm.litematic` | a simple automated cactus farm |
| `underground_room.litematic` | excavation: dig out a room, then build the floor |

## Architecture

```
core/src/main/java/dev/atlasclient/core   pure Java, no Minecraft classes, unit tested (99 tests)
  event setting module notify perf config util gui nbt
  analysis  debris  region  schematic  build (planner, sessions, restock, area mining)
  path (A*)  render (culling)  xray (index + anti-xray assessment)  camera (free camera motion)
src/main/java/dev/atlasclient              Fabric layer (Yarn names)
  scan (chunk snapshots, X-Ray scanner)  build (state resolution, placement, executor, inventory)
  camera (freecam)  module (29 modules in 7 categories)  render  hud  gui  command  integration  mixin
src/gametest                               Fabric client game tests: ./gradlew runClientGameTest (17 scenarios)
```

Chunk data is copied on the client thread: only the palettes of interesting sections are copied. The copies are analysed on background threads with bounded queues, and the results are applied on the client thread. Every cache is bounded and is released on disconnect, world change or when its module is turned off.

## Security and behavior

The mod does none of the following:
- read account or session tokens
- collect credentials
- send telemetry or make any network connection of its own
- download anything
- run remote commands
- use obfuscated code
- spoof packets or try to bypass anticheat
- read chunks the server hasn't sent

Everything is stored as readable JSON under `config/atlasclient/`. MIT-licensed ([LICENSE](LICENSE)).

## Known limitations

See [TESTING.md](TESTING.md) for what is VERIFIED, FIXED, UNVERIFIED or BLOCKED.
- **Base Finder** is a heuristic. It can miss bases that the server hides (anti-xray) or that sit outside the chunks sent to you, and it can still flag unusual natural terrain or generated structures it does not recognise. Growth shows that players stayed nearby, not that a base is there.
- **X-Ray and ESP** only show what the server sent. On servers with anti-xray, enclosed ores are hidden or faked, and Atlas says so instead of guessing.
- **Glow** mode depends on an optional mixin and vanilla's outline shader. Iris shader packs may not draw it; use Box or Filled with shaders.
- **Freecam:**
  - Your hotbar and hearts are hidden while the camera is detached. Vanilla hides them whenever the camera isn't a player.
  - Your own player model's head follows the mouse on your screen only. Its real rotation is restored on exit and never sent.
- **AutoBuilder:**
  - It doesn't place entities, fill containers or apply NBT. Farmland, paths, portals and similar blocks are skipped with a reason.
  - Shulker restock needs solid ground next to you outside the build, and a server that allows placing and breaking there (claims may block it).
- **Litematica:** importing from saved placement files is tested (core). The live bridge is experimental. Litematica writes its file when you leave a world.
- **Server rules come first.** Many servers forbid X-Ray, ESP, freecam, printers and other automation. I couldn't find DonutSMP's official modification policy, so check it before using these features there.
