# TFC Cave Biomes (Forge 1.20.1)

TerraFirmaCraft decides biomes with its own **2D** biome source: one biome per column, no underground biomes.
Cave-biome mods such as **Alex's Caves** inject their biomes into vanilla's 3D `MultiNoiseBiomeSource`, which TFC
replaces, so in a TFC world they never appear (and neither do the creatures and structures that depend on them).

This mod adds the missing third dimension. It lays **cave biome blobs** (a vertical band, a radius, an irregular
edge) under the TFC terrain, deterministically from the world seed, and makes every consumer see them:

* chunk biome data (F3, spawns, sounds, decoration),
* the biome source answers (`/locate biome`, vanilla structure placement, Alex's Caves' own cave volumes),
* TFC's decoration code, which would otherwise throw `NullPointerException: Biome: <id>` for biomes it does not know.

It is configurable, so other mods that add **cave biomes as ordinary biome JSONs** can be added without code.

> **Status: written without being able to compile or run Minecraft.** Treat the first build as a test build.
> See "If something fails" below; the mod logs enough to tell which part did not apply.

## Build

You need nothing installed if you use GitHub:

1. Create an empty GitHub repository and upload this folder's content (keep `.github/workflows/build.yml`).
2. Open the **Actions** tab, open the latest run of "build", download the artifact `tfc_cave_biomes` (a zip with the jar).

Or locally with JDK 17: `./gradlew build` (jar in `build/libs/`, use the one **without** `-sources`/`-dev`).

If compilation fails on TFC classes, `gradle.properties -> tfc_maven_version` is the CurseForge **file id** of the TFC
version used to compile (the number at the end of the file's CurseForge URL). Set it to the file id of the TFC version you
play (3.2.28). Send me the error if it is something else.

## Install and test

1. Put the jar in `mods/` (with TFC and Alex's Caves). **Create a new world** (or explore new chunks): already generated chunks keep their biomes.
2. Check `logs/latest.log` for lines starting with `[TFCCaveBiomes`:
   * `Patched 1 method(s), N return(s) in ...RegionBiomeSource` - the biome-source hook applied.
   * `Active: 6 cave biome(s), cell 640 blocks, ...` - the overlay is on.
3. In game (op): `/tfccavebiomes find` lists the nearest cave blobs with a clickable teleport. `/tfccavebiomes status`
   prints which hooks fired. `/locate biome alexscaves:toxic_caves` should also work. F3 shows the biome under you.
4. Set `"debug": true` in the config to also log every cave cell with its coordinates.

Expect Alex's Caves' structure (the carved cave volume) to appear inside the blob, with its creatures spawning in it.

## Config: `config/tfc_cave_biomes/cave_biomes.json`

Created on first run with Alex's Caves' six biomes. Main fields:

| field | meaning |
|---|---|
| `cell_size` (640) | world is cut in squares; each holds at most one blob |
| `cell_chance` (0.6) | chance that a cell has a blob (lower = rarer) |
| `center_jitter` (0.5) | how far from the cell middle the blob may sit (max 0.6) |
| `spawn_x/spawn_z` | origin for `min_distance_from_spawn` |
| `safety_net` (true) | any non-TFC biome that reaches TFC code is logged and given a fallback instead of crashing |
| `biomes[].biome` | biome id (`alexscaves:toxic_caves`) |
| `biomes[].weight` | relative chance among the biomes allowed in a cell |
| `biomes[].min_y / max_y` | vertical band (Alex's Caves' volumes center between y -48 and +20) |
| `biomes[].radius` | max blob radius; it is clamped so the blob fits its cell |
| `biomes[].min_distance_from_spawn` | blocks from `spawn_x/spawn_z` |
| `biomes[].surface_include / surface_exclude` | regexes on the **TFC biome id at the blob center** (e.g. `tfc:.*ocean.*`) |

Defaults keep land caves out of oceans, lakes, rivers, shores and lowlands, and put the Abyssal Chasm only under deep ocean.

### Adding another mod's cave biomes

Add an entry (any mod, any number). Entries whose biome is not in the registry are skipped, so it is safe to keep entries for
mods that are not installed. This only helps mods whose caves are **biomes** (a `data/<mod>/worldgen/biome/*.json`). Mods whose
caves are carvers or features (for example YUNG's Better Caves) do not need this mod; they never depended on biomes.

## How it works (for debugging)

| piece | does | if it did not apply |
|---|---|---|
| `ChunkAccessMixin` | wraps the resolver that fills chunk biome palettes | no cave biome in chunks (`status`: wrapped = 0) |
| `TFCCaveBiomesMixinPlugin` (ASM) | adds the overlay at every return of `RegionBiomeSource.getNoiseBiome(...)`, found by descriptor | structures/locate do not see caves (`status`: biome-source hook 0 queries) |
| `BiomeSourceMixin` | adds cave biomes to the possible biomes of TFC's source | the mod refuses to activate those biomes (it logs which) |
| `TFCBiomesMixin` | gives TFC an extension for cave biomes and a logged fallback for unknown ones | `NullPointerException: Biome: ...` returns |

Everything is deterministic from the seed and uses one function, so all consumers agree.

## Known limits

* Blobs are simple shapes (lens with an irregular edge), not Alex's Caves' own noise-carved volumes. The carved volume itself is still made by Alex's Caves' structures inside the blob.
* TFC ore veins and features check the biome at their position, so inside a blob they follow the cave biome's own feature list, not TFC's.
* Feature ordering: TFC biomes and Alex's Caves biomes share no features in the vanilla data, so no ordering cycle is expected. Other mods' biome modifiers could still create one; Minecraft would then report `Feature order cycle found` at world load.
* Blobs sit at fixed absolute heights; under mountains they are deeper below the surface, under low land shallower.
* Chunks generated before installing keep their old biomes.

## If something fails

Send me `logs/latest.log` (and the Actions build log if it is a compile error). The useful lines are the ones with `TFCCaveBiomes`
and, for a crash, the first `Caused by`. `/tfccavebiomes status` output is also useful.

MIT licensed.
