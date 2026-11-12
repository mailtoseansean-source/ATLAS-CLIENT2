# Changelog

## 1.2.0

- **Base Finder v2**
  - Growth focus: amethyst growth, long vines, tall kelp, ripe cocoa and crops, oxidised copper (grows only near players), counted at any depth, biome-aware.
  - Lived-in gate: placed blocks alone stay Low, scattered blocks count less; Medium needs a sign of use, High two.
  - Underground shape analysis: rooms, tunnels (incl. 1x2 branch mines), dug-out areas, shafts, staircases, water channels.
  - Layout patterns: flat walls/floors, regular light spacing, symmetry, storage rows, farm plots, sapling grids, mixed clusters, vines on built blocks.
  - Generated-structure recognition (villages, mineshafts, strongholds, dungeons, ancient cities, trial chambers, temples, mansions, ruined portals, monuments, bastions, fortresses, trail ruins, igloos).
  - Multi-chunk sites; tunnels followed across chunk borders.
  - New confidence levels: Very Low and Confirmed Structure.
  - Range presets (64 to 1024 / all loaded), CPU budget, scan frequency, FPS-aware throttling.
  - Dashboard with stats, sorting, filters, details and alert history.
  - Saved finds with world id, coordinates, evidence, last seen and notes; historical finds drawn when not detected.
  - Grouped alerts with cooldowns and per-kind switches.
- **ESP:** tracers, range presets, new Block ESP module.
- **Auto Mine (since 1.1.0 builds):** area mode clears flat layers to every edge, skips only blocks next to lava/water, never walks into caves, clean-up pass; tool goes back to the pickaxe after pillaring.
- **Auto Mine in real caves (after build 30):** walks may dig through blocks of the job and bridge floor holes inside the box; walks stay inside the box and out of water; paths keep away from lava, drops never land beside it, and it climbs out of lava; centers before pillaring with two blocks of head room; stops if you die; swimming for air no longer hides a stall.
- **X-Ray / Debris Finder vs anti-xray:** a chunk is re-read 1 s and 3 s after you break a block (ores the server reveals show up); a full debris tracker drops enclosed (likely fake) records first.
- **Simpler status text:** Auto Mine shows percent done; X-Ray says how many ores and whether the server hides or fakes them.
