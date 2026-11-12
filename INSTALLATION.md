# Installing Atlas Client (Modrinth App)

Target: **Minecraft Java 1.21.11 · Fabric Loader 0.20.1 (or newer 0.x) · Fabric API 0.141.6+1.21.11 (or newer for 1.21.11) · Java 21**

Atlas Client is a local mod JAR. **It does not need to be published on Modrinth for you to use it.** The Modrinth App can run any Fabric JAR you add to an instance yourself. The publishing rules are covered at the end of this guide, separately.

## 1. Build the JAR

**Easiest:** let GitHub build it. Every push to the `atlas-client` branch runs `.github/workflows/build.yml`, and the finished JAR appears under the repository's **Releases**.

To build on your own computer you need **JDK 25** to run Gradle (Loom 1.19 requires it; the mod itself is still compiled for Java 21, which Minecraft 1.21.11 uses) and an internet connection, since Gradle downloads Minecraft, Yarn, Loom and Fabric API on the first build.

```bash
cd atlas-client
./gradlew build          # Windows: gradlew.bat build
```

The JAR you want is **`build/libs/atlasclient-1.2.0.jar`**. Ignore `atlasclient-1.2.0-sources.jar`, which is source code, not a mod.

Then check it:

```bash
./gradlew verifyRelease      # checks the JAR: mod id, version, Minecraft ~1.21.11, mixin config + refmap, no test classes
./gradlew test               # unit tests (core logic)
./gradlew runClientGameTest  # opens Minecraft and runs 17 in-game scenarios, writes atlas-gametest-report.txt in the test run directory
```

If the build fails, the mod layer has a compile error that could not be caught without compiling (see TESTING.md for why). Send the first error line and it can be fixed.

## 2. Install the Modrinth App

Download it from https://modrinth.com/app and sign in with your Microsoft account inside the app. Sign-in is handled entirely by the Modrinth App. Atlas Client never sees your account.

## 3. Create a 1.21.11 Fabric instance

1. Click the **+** (Create instance) button in the left sidebar.
2. Choose **Custom**, enter a name (for example "Atlas 1.21.11"), and select:
   - **Loader:** Fabric
   - **Game version:** 1.21.11 (do not pick 1.21.10 or 26.x)
   - **Loader version:** Stable is fine. Use *Advanced* to choose 0.20.1 or newer.
3. Click **Create**.

Labels can differ slightly between app versions.

## 4. Install Fabric API (required)

Open the instance → **Content** → **Install content**. Search for **Fabric API**. Install the latest version for **1.21.11** (0.141.6+1.21.11 or newer). The instance filter only shows matching versions.

## 5. Add the Atlas Client JAR

Use either method:
- **Drag and drop** `atlasclient-1.2.0.jar` onto the instance's Content page.
- Or right-click the instance → **Open folder** → open `mods/` → copy the JAR in.

Copy **only** `atlasclient-1.2.0.jar`. Do not copy the source folder, the `-sources.jar` or the zip.

Instance folders are under:
- **Windows:** `%APPDATA%\ModrinthApp\profiles\<instance>\mods`
- **macOS:** `~/Library/Application Support/ModrinthApp/profiles/<instance>/mods`
- **Linux:** `~/.local/share/ModrinthApp/profiles/<instance>/mods`

The JAR should then appear in the Content list. The Modrinth App can't check for updates for a local file, so you will need to replace it by hand.

## 6. Add schematics (optional)

Copy `.litematic` or `.schem` files, such as the samples in this project's `schematics/` folder, into the instance's **`schematics/`** folder, next to `mods/`. Create the folder if it doesn't exist. Litematica uses the same folder, so both mods see the same files. Atlas also reads `config/atlasclient/schematics/`.

## 7. Optional mods

See [COMPATIBILITY.md](COMPATIBILITY.md) for versions and caveats. With the app, install them from **Install content**, and it adds their dependencies (for example MaLiLib for Litematica). Suggested order:
1. Fabric API
2. Sodium
3. Lithium, FerriteCore, ImmediatelyFast, Entity Culling
4. Iris (needs Sodium)
5. MaLiLib, then Litematica, MiniHUD and Tweakeroo
6. Mod Menu and Cloth Config
7. Xaero's Minimap and World Map
8. ReplayMod
9. Atlas Client

Fabric doesn't depend on load order. The order above just makes missing dependencies easy to spot.

> **Litematica security note:** install **Litematica 1.21.11-0.26.11 or newer**. Older 1.21.11 builds had a remote-code-execution vulnerability fixed in July 2026. If you use Servux, use 0.9.5 or newer.

## 8. Launch and verify

1. Click **Play**. On the title screen, open **Mods** (if Mod Menu is installed) and check that *Atlas Client* is listed.
2. Join a world. A message lists the keys: **Right Shift** = menu, **X** = X-Ray, **H** = ESP, **G** = Freecam, **N** = Auto Mine.
3. Press **Right Shift**: the simple menu opens with X-Ray, ESP, Freecam, Auto Mine, Base Finder, Ancient Debris Finder and Flight. Click a card to switch it on. **All settings >** shows the full menu with the seven categories and search.
4. Run `/atlas diag` in chat. It prints the module count, event listeners, scan queue, analysed chunks and cached discoveries.
5. AutoBuilder smoke test: stand on flat ground. Open Building → AutoBuilder, select `stone_platform.schem` and click **Load here** (this sets the corner at your feet). Step a few blocks outside the 7x7 area, then click **Start**. It should place 49 stone blocks and report "Construction completed" with verification counts.
6. Everything works in singleplayer and on servers right away. Check a server's rules before using X-Ray, ESP, Freecam or Auto Mine there.
7. Use the in-game checklist in [TESTING.md](TESTING.md) for the rest.

If the game crashes at startup, open the instance's **Logs** tab. Lines mentioning `atlasclient` show which part failed.

## Installation checklist

| Status | Item |
|---|---|
| **Required** | Minecraft Java 1.21.11 |
| **Required** | Fabric Loader 0.18.0+ (0.20.1 recommended) |
| **Required** | Fabric API for 1.21.11 (0.141.6+1.21.11 tested against in source) |
| **Required** | Java 21 (the Modrinth App ships a suitable runtime) |
| **Required** | `atlasclient-1.2.0.jar` in `mods/` |
| Recommended | Sodium, Lithium, FerriteCore, ImmediatelyFast, Mod Menu |
| Optional | Litematica 0.26.11+ with MaLiLib (schematic overlay, placement import), MiniHUD, Tweakeroo, Cloth Config, Iris, Entity Culling, Xaero's Minimap and World Map, ReplayMod |
| Incompatible / not supported | Any Minecraft version other than 1.21.11. Forge or NeoForge (this is a Fabric mod). OptiFine (and OptiFabric). Litematica 1.21.11 builds older than 0.26.11 (security). Other build automation running at the same time as AutoBuilder (Litematica *printer* forks, Baritone build or mine processes, Tweakeroo fast/flexible placement): they fight over the same hotbar slot and clicks. Another freecam mod active at the same time as Atlas Freecam: both try to own the camera. Use one. |

## Publishing on Modrinth (separate from local use)

You don't need to publish to use the mod. If you wanted to publish it, Modrinth's [Content Rules](https://modrinth.com/legal/rules) apply. **As it stands, Atlas Client would not meet them.** The *Cheats and Hacks* rule prohibits client-side features that give an unfair multiplayer advantage without a server-side opt-out. Without a **server-side opt-in**, it also prohibits functions such as **x-ray or seeing through opaque blocks** and **flight, speed or other movement modifications**. That affects:
- **X-Ray**, the **Ancient Debris Finder** (enclosed debris) and **ESP** (through walls): x-ray / see-through.
- **Freecam**: generally treated as seeing hidden areas.
- **AutoBuilder** on servers: automation (Modrinth's list is "including but not limited to").
- **Auto Mine** and **AutoBuilder** on servers: automation (Modrinth's list is "including but not limited to").

To publish, these features would have to be removed or enabled only by a real server-side opt-in, such as a server plugin or channel that turns them on. The other requirements:
- **Descriptions** must clearly and honestly state what the project does, with an English version.
- **Licensing:** you must have the rights to everything you upload (this project is MIT) and fill in license metadata correctly.
- **Networking:** no data may be sent to servers the user didn't choose to connect to, unless disclosed. Atlas makes no network connections.
- A project page, a version upload of the remapped JAR (`build/libs/atlasclient-1.2.0.jar`), loader and game-version tags (Fabric, 1.21.11) and a declared Fabric API dependency.

Moderators review projects by hand, so passing these points does not guarantee approval.
