# Testing and status

Status labels used here and in the release report:

| Label | Meaning |
|---|---|
| **VERIFIED** | Actually run here and passed |
| **FIXED** | Code corrected; not runtime-tested in Minecraft |
| **UNVERIFIED** | Written, not yet run |
| **BLOCKED** | Cannot be done in this environment, and why |

## Summary

| Item | Status | Details |
|---|---|---|
| `./gradlew build` (real Fabric build, GitHub Actions) | **VERIFIED** | Run 4 on the `atlas-client` branch: Minecraft 1.21.11 + Yarn + Loom + Fabric API resolved, `compileJava` 0 errors, `test` passed, `remapJar` produced `build/libs/atlasclient-1.1.0.jar` (599,329 bytes) |
| `./gradlew compileGametestJava` | **VERIFIED** | game-test sources compile against the real API |
| `./gradlew verifyRelease` | **VERIFIED** | mod id/version, `minecraft ~1.21.11`, client environment, mixin config present, mixin targets remapped to intermediary in place (Loom 1.19 needs no refmap), no test classes, icon present |
| `./gradlew runClientGameTest` in a **real Minecraft 1.21.11 client** (GitHub Actions, virtual screen) | **VERIFIED** for everything except AutoBuilder | Commit `d626186`: joined a **real dedicated server** (multiplayer) and passed: ClickGUI on Right Shift, hotkeys X and H on a quick tap, **Auto Mine** 1x2 tunnel with ores (3/3 mined, floor intact), **Auto Mine 3x3** (54/54 open), **Auto Mine stops before lava** (lava never opened), **Base Finder** underground (buried base High, mineshaft None, surface chests None, player on the surface), X-Ray, ESP, Freecam (player drift on the server 0.000). Single-player: X-Ray, ESP, Freecam, settings persistence, world-switch cleanup passed. AutoBuilder: 5 of 12 scenarios passed (platform, redstone, multi-region, missing materials, shulker restock); taller builds (house, stairs) stall partway and excavation of a buried room did not start; see below |
| Core build + unit tests (`core/`, Gradle 8.14.3 and javac + JUnit) | **VERIFIED** | **102 tests, 0 failures** (incl. mineshaft, base-next-to-mineshaft and building-blocks-only scoring) |
| `.litematic` reader against **329 real files saved by Litematica** | **VERIFIED** | 326 match Litematica's own metadata exactly (region count, volume, non-air blocks, enclosing size); 3 pre-1.13 files are rejected with a clear message. All 326 round-trip through our writer and keep their block count under 4 rotations × 3 mirrors |
| Minecraft API signature checks (Fabric layer + game tests, 132 files) | **VERIFIED** (static only) | 0 real findings from the type-aware checker and the arity checker; see below |
| **javac compile of the whole mod against generated 1.21.11 API stubs** | **VERIFIED** (stub model), real build still BLOCKED | 0 errors after fixing 1 real compile error. `javac` compiled `src/main/java`, `src/gametest/java` and `core/` (263 classes) against stubs of all 6,578 Minecraft 1.21.11 classes plus Fabric API, Brigadier, Mixin, JOML, Blaze3D and slf4j. The stubs were generated from the Yarn + intermediary mappings, with hand-checked signatures; see below |
| Mixin targets exist in 1.21.11 Yarn | **VERIFIED** (static) | `MinecraftClient.hasOutline(Entity)`, `EntityRenderer.updateRenderState(Entity, EntityRenderState, float)`, field `EntityRenderState.outlineColor` |
| In-game behaviour | **VERIFIED** for the modules above; others UNVERIFIED | Not covered automatically: anti-xray servers, DonutSMP's real 3x3 plugin, other players for Player ESP, shaders |

### Why the mod could not be built in the original workspace (it is now built on GitHub)

This workspace reaches the internet only through a policy proxy, and that proxy rejected every host the Fabric build needs:

```
connect_rejected  gateway answered 403 to CONNECT (policy denial or upstream failure)
  maven.fabricmc.net:443        (Loom, Yarn, Fabric Loader, Fabric API)
  libraries.minecraft.net:443   (Minecraft libraries)
  piston-meta.mojang.com:443    (Minecraft version manifest and client JAR)
  repo.maven.apache.org:443     (Maven Central)
  services.gradle.org:443       (Gradle distribution)
  plugins.gradle.org:443        (Gradle plugin portal)
```

Without the Minecraft JAR there is nothing to compile the Fabric layer against. Instead:
1. The game-independent core was built and tested for real (above).
2. Every Minecraft/Fabric member access in the mod layer was checked by two scripts against the 1.21.11 mappings (Yarn 1.21.11+build.6 mapping files + the 1.21.11 intermediary, i.e. every class, method and field with its exact descriptor):
   - **type-aware checker:** infers receiver types (variables, fields, return types, casts) and checks the member exists on that class or its supertypes. Tested on a file with 5 deliberate mistakes, and it caught all 5.
   - **arity checker:** every call's argument count must match a real declaration. Static-class members must exist in that class.
3. Uncertain APIs were confirmed against other 1.21.11 sources: Fabric API (1.21.11 branch), plus Blaze3D pipeline builder and camera methods as used by an existing 1.21.11 client. Mojmap names were checked in Sodium/Iris.

4. **Stub compile.** A generator turned the 1.21.11 mappings into compile-only Java stubs: every class, method and field with its exact parameter and return types. Supertypes, constructors, generics, enum constants and the Fabric API signatures the mod uses were added by hand, each checked against Yarn mapping files, the Fabric API 1.21.11 source, Fabric's 1.21.11 docs examples or other 1.21.11 mods. `javac` then compiled the real mod sources against them. It found **one real compile error**, which is now fixed:
   ```
   src/main/java/dev/atlasclient/module/camera/FreecamModule.java:30: error: settings() in FreecamModule cannot override settings() in Module
       private static CameraSettingsModule settings() { ... }
     overriding method is static
   ```
   After the fix the whole project compiles with 0 errors against the stubs. Limits of the stub model:
   - Members are modelled static unless an interface or override requires instance, so instance-vs-static misuse would not show. The 29 static call sites in the mod were reviewed by hand and all are static in Minecraft.
   - Generics are partly raw.
   - Access modifiers are not modelled. Fields and methods the mod touches were checked against public use in other 1.21.11 code.

Static checks catch wrong names, wrong argument counts and wrong receivers. They do **not** catch every compile error (generics, overload resolution, access modifiers). **The first `./gradlew build` on your machine is the real test.** If it fails, the first error line is enough to fix it.

## Core unit tests: `core/src/test/java/dev/atlasclient/core` (99, VERIFIED)

| Area | Tests |
|---|---|
| Module registration, toggles, failure isolation, keybinds | `ModuleFrameworkTest` (9) |
| Config persistence and profiles | `ConfigPersistenceTest` (6) |
| Base Finder scoring, confidence, amethyst/growth inference, discoveries | `WorldAnalysisTest` (16) |
| Ancient debris tracking, anti-xray availability, `.mca` scan | `DebrisTest` (7) |
| Schematic parsing, Litematica bit packing, transforms, placement import | `SchematicTest` (13) |
| Real Litematica files (corpus, see above) | `RealLitematicaFilesTest` (1; runs when `ATLAS_LITEMATIC_CORPUS` points at a folder of .litematic files, otherwise skipped) |
| Shipped sample schematics round trip | `SampleSchematicsTest` (3) |
| AutoBuilder planning/execution in a simulated world, excavation, 3x3 area mining, Regular/Amethyst | `BuilderTest` (16) |
| **Shulker restock and junk dropping** | `RestockTest` (10): chooses the right box, takes only what is missing, keeps a slot for the box, recovers the box when it won't open, never breaks it without a free slot, reports a lost drop, never drops protected items |
| **X-Ray index, anti-xray detection, free camera motion, GUI "Active" view** | `RenderCameraTest` (6) |
| GUI model, culling, cleanup, worker, event bus, notifications, pathfinding, performance | `RuntimeBehaviourTest` (12) |

## In-game tests: `src/gametest/.../AtlasClientGameTest.java`

`./gradlew runClientGameTest` starts Minecraft with Fabric's client game-test API. GitHub Actions runs it on every push (job *ingame-tests*, Java 21, virtual screen).
- **Part 1, real dedicated server** (multiplayer, `singleplayer=false`): ClickGUI + hotkeys, Auto Mine 1x2 with an ore vein, Auto Mine 3x3, Auto Mine before lava, Base Finder underground (buried base vs mineshaft vs surface chests), X-Ray, ESP, Freecam.
- **Part 2, single-player:** the AutoBuilder suite below, then X-Ray, ESP, Freecam, persistence, world switch.
- Every result is checked **on the server's world**, not the client's view. It prints `[Atlas gametest] PASS/FAIL …`, writes `atlas-gametest-report.txt` and screenshots, and fails the run if a non-AutoBuilder scenario fails. AutoBuilder scenarios are reported but do not fail the run.

Latest result (commit `d626186`): all Part 1 scenarios **PASS**; Part 2 X-Ray, ESP, Freecam, persistence, world switch **PASS**; AutoBuilder platform, redstone, multi-region, missing materials, shulker restock **PASS**; small house, rotated house, stairs/slabs, mirrored stairs (stall partway), pause/resume (2 blocks short), excavation (did not start: the top layers of a buried room had no face to click; a fallback to lower layers was added after this run) **FAIL**.

| # | Scenario | Pass condition |
|---|---|---|
| 1–7 | Platform, house, stairs/slabs, redstone, multi-region, rotated house, mirrored stairs | every block correct on the server |
| 8 | Missing materials (survival) | warning shown, nothing placed until `/give`, then complete |
| 9 | Pause / resume | nothing changes while paused, then complete |
| 10 | Excavation (creative) | room dug out (air checked too), floor built |
| 11 | Excavation, survival, Amethyst mode on a vanilla server | completes even though the 3x3 effect is absent |
| 12 | **Shulker restock + junk dropping** with a full inventory | junk thrown, stone taken from the box, platform complete, **box back in inventory, none left in the world** |
| 13 | **X-Ray** | a placed diamond ore is found, disappears from the list after it is removed, memory released on disable |
| 14 | **ESP** | mob, item and chest found; nothing after disable; screenshot `atlas-esp` |
| 15 | **Freecam** | camera moves > 3 blocks, **player drift on the server < 0.01**, a click from the camera changes nothing, view returns to the player; screenshot `atlas-freecam` |
| 16 | Settings persistence | X-Ray opacity survives save and reload |
| 17 | World switch | Freecam released and AutoBuilder state cleared after leaving a world with Freecam and X-Ray on |

Not covered automatically:
- the real DonutSMP 3x3 effect (server plugin)
- Litematica live import
- other players for Player ESP
- shaders
- anti-xray servers

Those are on the manual checklist.

## Feature status

| Feature | Status | Notes |
|---|---|---|
| .litematic / .schem parsing, transforms | **VERIFIED** (core) | including 329 real Litematica files |
| Litematica placement-file import | **VERIFIED** (core) | in-game use UNVERIFIED |
| Litematica live bridge | **UNVERIFIED**, experimental | reflection; reports an error if it fails |
| AutoBuilder planning, verification, retry, pause/resume (logic) | **VERIFIED** (core simulation) | in-game UNVERIFIED |
| Wall torches/signs/banners/heads placement | **FIXED** | previously never placed (build could not finish) |
| Hotbar swap stall between two unplaceable targets | **FIXED** | solve first, then select |
| Rotation/mirror state mapping in production | **FIXED** | no longer relies on enum names |
| Pre-1.13 .litematic with data version 0 | **FIXED** | rejected with a clear message instead of loading broken names |
| Shulker restock, junk dropping | logic **VERIFIED** (core), in-game **UNVERIFIED** | |
| X-Ray, ESP (4 modules), Freecam + Camera Speed/Settings | **VERIFIED** in a real client, single-player and dedicated server | |
| Auto Mine (1x2, 3x3, ores, lava stop) | **VERIFIED** in a real client on a dedicated server | DonutSMP's real 3x3 effect not tested |
| Base Finder underground / mineshaft rejection | **VERIFIED** (core + real client on a dedicated server) | |
| Ancient Debris Finder "real only" on anti-xray servers | **UNVERIFIED** in game | no anti-xray server in the tests |
| Simple menu, default keys X/H/G/N, Right Shift | **VERIFIED** (opens, X and H toggle on a quick tap) | |
| Nether↔Overworld on the same server wiping Base Finder/Debris results | **FIXED** | |
| Waypoints could be saved to the wrong world's file | **FIXED** | |
| "Join a world first" warnings at every game start (AutoBuilder/Flight saved as on) | **FIXED** | |
| GUI "Active" view, Camera category | logic **VERIFIED** (core), drawing **UNVERIFIED** | |

## Manual in-game checklist

**Startup and GUI**
- [ ] Starts with only Fabric API + Atlas. No `atlasclient` or mixin errors in the log
- [ ] Right Shift opens the GUI and shows **Active** + 7 categories. Search works. Switches, sliders, dropdowns, colours and keybind capture work
- [ ] Settings survive a restart. Profiles switch

**AutoBuilder (highest priority)**
- [ ] Every sample loads. Preview and materials look right. Rotate and mirror match the preview
- [ ] The same `.litematic` placed in Litematica, then *Import Litematica placement* after rejoining, lines up exactly with Litematica's overlay
- [ ] Wall torch in `small_house` is placed on the wall
- [ ] Missing materials → warning. Restock from a shulker box: box placed outside the build, items taken, **box mined with a normal pickaxe and picked back up**
- [ ] Inventory full of cobblestone → junk thrown away from the build. Shulkers, renamed and enchanted items and diamond blocks are kept
- [ ] Excavation underground: fluids left alone. Amethyst mode on DonutSMP (only if allowed) uses 3x3 only where safe
- [ ] Pause/resume during a restock continues the restock. Cancel during a restock warns where the box is

**X-Ray / ESP / Freecam**
- [ ] X-Ray in singleplayer: diamonds and debris highlighted through walls. Mined ores disappear. Off → normal world
- [ ] X-Ray with Sodium, and with Iris with and without a shader pack
- [ ] Player ESP on a LAN or test server with a friend: name, health, distance
- [ ] Mob, Item, Container ESP: all modes. Glow shows coloured outlines, or Box mode with shaders
- [ ] Freecam: WASD, up/down, boost, smoothing, faster/slower keys. Your player doesn't move. Clicking does nothing. The "You" marker shows. Exits on damage. Disabling returns the view
- [ ] On a server: X-Ray, ESP, Freecam and Auto Mine work with no setup
- [ ] Switching worlds or dimensions with everything on: no crash, Freecam released

**With other mods** (then upgrade entries in COMPATIBILITY.md)
- [ ] Sodium, Iris, Litematica + MaLiLib, MiniHUD, Tweakeroo, Mod Menu, Xaero's, ReplayMod

## 1.2.0 (Base Finder v2, ESP) - results

- **Core unit tests:** 128 pass (26 new in `BaseFinderV2Test` on synthetic chunk data: natural vs long vines, natural vs overgrown geodes, ripe crops and aged copper, box rooms vs natural caves, tunnels/shafts/staircases, storage areas, redstone, farm plots, repeated patterns, villages, mineshafts without rails, dungeons/ancient cities/trial chambers/strongholds, multi-chunk sites and tunnels, random block spam, confidence levels, range presets, memory bounds, rescans after changes, ESP distance filtering, world switching, persistence including old files, alert cooldowns and grouping, new block mappings).
- **In real Minecraft 1.21.11 (dedicated server, GitHub Actions run 42415050179, build 30): all blocking tests PASS**, including:
  - base 70 blocks below the player found (High);
  - buried base seen from the surface: Confirmed Structure, mineshaft and surface chests ignored;
  - fully grown amethyst buried 20 blocks down found as overgrown;
  - randomly placed blocks stay Low;
  - base split over two chunks joined into one High site;
  - a real generated village (`/place structure minecraft:village_plains`, 21 chunks) recognised and kept at Low;
  - X-Ray, ESP, Freecam and all Auto Mine tests (incl. 20x20x20 area).
- AutoBuilder singleplayer scenarios that timed out before 1.2.0 still do (not blocking; AutoBuilder was set aside on request).
- Not tested in game: the dashboard screen's layout by eye, tracers' look, Block ESP rendering (they compile and run in the same overlay pass as tested features).

## 1.2.0 test rounds after build 30 (real terrain, anti-xray)

New in-game suites (GitHub Actions matrix): `mine`, `finder`, `builder` and real generated terrain
`caves L1`-`L5` (L1 20^3 stone caves, L2 15^3 deepslate with lava, L3 15^3 water caves, L4 12^3 with a
plain diamond pickaxe and no Haste, L5 20^3 deepslate with lava). Results as logged by those runs:

- **Anti-xray (finder suite, build 38 / run 42439105263 and build 40): PASS.** X-Ray on a server that
  hides enclosed ores (only the exposed real ones shown, nothing buried shown); X-Ray on a server that
  sends fake ores (fakes detected, only exposed real ores shown); Base Finder still finds the buried base
  among fake ores. Debris Finder on a fake-debris server passed in build 38 but was flaky in build 39
  (the 20 000-record tracker filled with fakes and dropped the real exposed debris); fixed in 43af39e
  (enclosed records are dropped first, unit test added) - **not yet re-run in game**.
- **Flat 20^3 area (mine suite):** build 36: 0 left, 12 550 ticks (1.58 ticks/block). Build 40 ("break
  while walking", 3 slices per job): much slower and stalled at layer 4 (5181 left after 30 000 ticks),
  so that change is switched off again in 43af39e.
- **Real caves:**
  - L4 (12^3, ~54% cave air): build 37 left 736 of 751 blocks (boxed in, no path). Build 40 with
    dig-through walking and bridging: 34 left (23 allowed), no deaths, never in lava. The leftovers were
    bridge blocks placed into finished layers; 43af39e only bridges into cells a later layer digs.
  - L1 (20^3, ~50% air): stopped with "Could not climb out of the hole": it had walked out of the box
    into the caves around it and pillared half over the next block. 43af39e fences walks to the box and
    centers before pillaring - **not yet re-run**.
  - L3 (water caves): build 40 stalled standing in water (nothing can be mined from there, and bobbing
    for air kept the watchdog quiet). 43af39e keeps walks out of water and fixes the watchdog - **not yet
    re-run**.
  - L2 (deepslate with lava, build 33): the player ended up in lava ("tried to swim in lava") and died
    repeatedly. Since then: paths cost much more next to lava, drops never land beside it, it climbs out
    of lava toward the last solid ground, and Auto Mine stops if you die. **Not yet re-run.**
- **Blocked since 2026-11-08 ~03:40 UTC:** GitHub Actions refuses to start jobs on this account
  (billing / spending limit), so commits after build 40 have not been built or tested in game.
