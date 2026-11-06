package dev.tfccavebiomes;

import com.google.common.collect.ImmutableSet;
import net.dries007.tfc.world.biome.BiomeBuilder;
import net.dries007.tfc.world.biome.BiomeExtension;
import net.dries007.tfc.world.biome.BiomeSourceExtension;
import net.dries007.tfc.world.surface.builder.NormalSurfaceBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.CommonLevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.dimension.LevelStem;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Core of the mod: a pure, seed-based function that says "at this 3D quart position, the TFC (2D) biome is replaced by
 * this cave biome". The same function is used by every hook, so chunk data, structure placement and /locate agree.
 * <p>
 * The world is cut into square cells of {@code cell_size} blocks. A cell holds at most one blob. The blob has a vertical
 * band (min_y..max_y), a rough radius and an irregular edge. Which biome a cell gets is decided once, from the cell seed
 * and the TFC surface biome at the blob center (so the abyssal chasm only appears under deep ocean, land caves never
 * under water).
 */
public final class CaveBiomes
{
    /** The TFC biome (2D) at a quart column. */
    @FunctionalInterface
    public interface BaseLookup
    {
        Holder<Biome> at(int quartX, int quartZ);
    }

    public record Found(String biome, int x, int z, int minY, int maxY, double distance) {}

    private static final class Entry
    {
        final ResourceKey<Biome> key;
        final Holder<Biome> holder;
        final int weight, minY, maxY, radius, minDistance;
        final List<Pattern> include, exclude;

        Entry(ResourceKey<Biome> key, Holder<Biome> holder, CaveBiomeConfig.Entry c, int radius, List<Pattern> include, List<Pattern> exclude)
        {
            this.key = key;
            this.holder = holder;
            this.weight = c.weight;
            this.minY = c.minY;
            this.maxY = c.maxY;
            this.radius = radius;
            this.minDistance = c.minDistanceFromSpawn;
            this.include = include;
            this.exclude = exclude;
        }
    }

    private static final class Cell
    {
        final Entry entry;
        final int centerX, centerZ;
        final float[] radial;

        Cell(Entry entry, int centerX, int centerZ, float[] radial)
        {
            this.entry = entry;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radial = radial;
        }
    }

    private static final Cell NONE = new Cell(null, 0, 0, null);
    private static final String TFC = "tfc";

    private static volatile boolean tfcWorld = false;
    private static volatile boolean active = false;
    private static volatile Set<Holder<Biome>> possibleExtra = null;

    private static volatile long seed;
    private static volatile int cellSize = 640;
    private static volatile double cellChance = 0.6, jitter = 0.5;
    private static volatile int spawnX, spawnZ;
    private static volatile List<Entry> entries = List.of();
    private static volatile int globalMinY = 0, globalMaxY = -1;
    private static volatile boolean debug;

    private static final Map<Long, Cell> CELLS = new ConcurrentHashMap<>();
    private static final Map<Biome, BiomeExtension> EXTENSIONS = Collections.synchronizedMap(new IdentityHashMap<>());

    // Diagnostics: tell us which hooks actually fired in this session.
    private static final AtomicLong resolverWraps = new AtomicLong();
    private static final AtomicLong chunkFillReplaced = new AtomicLong();
    private static final AtomicLong sourceQueries = new AtomicLong();
    private static final AtomicLong sourceReplaced = new AtomicLong();
    private static final AtomicLong fallbackExtensions = new AtomicLong();
    private static final AtomicLong possibleExpansions = new AtomicLong();

    private CaveBiomes() {}

    // ---------------------------------------------------------------- lifecycle

    public static synchronized void init(MinecraftServer server)
    {
        shutdown();
        final CaveBiomeConfig cfg = CaveBiomeConfig.get();
        final Registry<LevelStem> stems = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
        final LevelStem overworld = stems.get(LevelStem.OVERWORLD);
        if (overworld == null || !(overworld.generator().getBiomeSource() instanceof BiomeSourceExtension))
        {
            TFCCaveBiomesMod.LOGGER.info("The overworld is not a TFC world: nothing to do.");
            return;
        }
        tfcWorld = true;
        if (!cfg.enabled)
        {
            TFCCaveBiomesMod.LOGGER.info("Disabled in the config (the safety net is still on: {}).", cfg.safetyNet);
            return;
        }

        final Registry<Biome> biomes = server.registryAccess().registryOrThrow(Registries.BIOME);
        final List<Entry> built = new ArrayList<>();
        for (CaveBiomeConfig.Entry c : cfg.biomes)
        {
            final ResourceLocation id = ResourceLocation.tryParse(String.valueOf(c.biome));
            if (id == null)
            {
                TFCCaveBiomesMod.LOGGER.error("Bad biome id '{}' in the config, skipped.", c.biome);
                continue;
            }
            final ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, id);
            final Optional<Holder.Reference<Biome>> holder = biomes.getHolder(key);
            if (holder.isEmpty())
            {
                TFCCaveBiomesMod.LOGGER.info("Biome {} is not in the registry (mod not installed?), skipped.", id);
                continue;
            }
            final int maxRadius = Math.max(16, (int) ((0.5 - 0.5 * cfg.centerJitter) * cfg.cellSize / 1.2));
            int radius = c.radius;
            if (radius > maxRadius)
            {
                TFCCaveBiomesMod.LOGGER.warn("{}: radius {} is too big for cell_size {} / center_jitter {}, clamped to {}.", id, radius, cfg.cellSize, cfg.centerJitter, maxRadius);
                radius = maxRadius;
            }
            try
            {
                built.add(new Entry(key, holder.get(), c, radius, compile(c.surfaceInclude), compile(c.surfaceExclude)));
            }
            catch (RuntimeException e)
            {
                TFCCaveBiomesMod.LOGGER.error("{}: bad regex in surface_include / surface_exclude, skipped.", id, e);
            }
        }
        if (built.isEmpty())
        {
            TFCCaveBiomesMod.LOGGER.info("No configured cave biome exists in this pack: nothing to do.");
            return;
        }

        // Make sure every cave biome is in the biome source's possible biomes (needed for feature ordering and structure sets).
        final ImmutableSet.Builder<Holder<Biome>> extra = ImmutableSet.builder();
        for (Entry e : built) extra.add(e.holder);
        possibleExtra = extra.build();
        final BiomeSource source = overworld.generator().getBiomeSource();
        final Set<Holder<Biome>> possible = source.possibleBiomes(); // BiomeSourceMixin expands it on this call
        final List<Entry> usable = new ArrayList<>();
        for (Entry e : built)
        {
            if (possible.contains(e.holder))
            {
                usable.add(e);
            }
            else
            {
                TFCCaveBiomesMod.LOGGER.error("{} is not in the TFC biome source's possible biomes (BiomeSourceMixin did not apply?). Skipping it: using it would crash feature placement.", e.key.location());
            }
        }
        if (usable.isEmpty())
        {
            return;
        }

        for (Entry e : usable)
        {
            EXTENSIONS.put(e.holder.value(), buildExtension(e.key));
        }

        seed = server.getWorldData().worldGenOptions().seed();
        cellSize = cfg.cellSize;
        cellChance = cfg.cellChance;
        jitter = cfg.centerJitter;
        spawnX = cfg.spawnX;
        spawnZ = cfg.spawnZ;
        debug = cfg.debug;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (Entry e : usable)
        {
            lo = Math.min(lo, e.minY);
            hi = Math.max(hi, e.maxY);
        }
        globalMinY = lo;
        globalMaxY = hi;
        entries = List.copyOf(usable);
        active = true;
        TFCCaveBiomesMod.LOGGER.info("Active: {} cave biome(s), cell {} blocks, chance {}, seed {}.", usable.size(), cellSize, cellChance, seed);
    }

    public static synchronized void shutdown()
    {
        if (tfcWorld)
        {
            TFCCaveBiomesMod.LOGGER.info("Session stats: {}", stats());
        }
        active = false;
        tfcWorld = false;
        possibleExtra = null;
        entries = List.of();
        globalMinY = 0;
        globalMaxY = -1;
        CELLS.clear();
        EXTENSIONS.clear();
        resolverWraps.set(0);
        chunkFillReplaced.set(0);
        sourceQueries.set(0);
        sourceReplaced.set(0);
        fallbackExtensions.set(0);
        possibleExpansions.set(0);
    }

    public static boolean isActive()
    {
        return active;
    }

    public static String stats()
    {
        return "active=" + active + ", entries=" + entries.size() + ", cells decided=" + CELLS.size()
            + ", chunk-fill hook: wrapped " + resolverWraps.get() + " resolvers / replaced " + chunkFillReplaced.get() + " cells"
            + ", biome-source hook: " + sourceQueries.get() + " queries / replaced " + sourceReplaced.get()
            + ", possibleBiomes expansions " + possibleExpansions.get()
            + ", fallback extensions " + fallbackExtensions.get();
    }

    private static List<Pattern> compile(List<String> regexes)
    {
        final List<Pattern> out = new ArrayList<>();
        for (String s : regexes) out.add(Pattern.compile(s));
        return out;
    }

    private static BiomeExtension buildExtension(ResourceKey<Biome> key)
    {
        return BiomeBuilder.builder().surface(NormalSurfaceBuilder.INSTANCE).noRivers().build(key);
    }

    // ---------------------------------------------------------------- hooks

    /** Used by BiomeSourceMixin: what to add to a TFC biome source's possible biomes, or null if not ready yet. */
    @Nullable
    public static Set<Holder<Biome>> possibleBiomesToAdd()
    {
        return possibleExtra;
    }

    public static void countPossibleExpansion()
    {
        possibleExpansions.incrementAndGet();
    }

    /** Used by ChunkAccessMixin: wraps the resolver that fills a chunk's biome palettes. */
    public static BiomeResolver wrapResolver(BiomeResolver original)
    {
        if (!active)
        {
            return original;
        }
        resolverWraps.incrementAndGet();
        return (qx, qy, qz, sampler) -> {
            final Holder<Biome> base = original.getNoiseBiome(qx, qy, qz, sampler);
            final Holder<Biome> out = overlay(base, qx, qy, qz, (cqx, cqz) -> original.getNoiseBiome(cqx, 0, cqz, sampler));
            if (out != base)
            {
                chunkFillReplaced.incrementAndGet();
            }
            return out;
        };
    }

    /**
     * Called (through bytecode inserted by {@code TFCCaveBiomesMixinPlugin}) at every return of
     * {@code RegionBiomeSource.getNoiseBiome(int, int, int, Climate.Sampler)}.
     */
    public static Holder<Biome> overlayFromSource(Holder<Biome> base, int qx, int qy, int qz, Object source)
    {
        sourceQueries.incrementAndGet();
        if (!active || !(source instanceof BiomeSourceExtension ext))
        {
            return base;
        }
        final Holder<Biome> out = overlay(base, qx, qy, qz, ext::getBiome);
        if (out != base)
        {
            sourceReplaced.incrementAndGet();
        }
        return out;
    }

    /** Used by TFCBiomesMixin: a TFC extension for a biome TFC does not know. */
    @Nullable
    public static BiomeExtension extensionFor(CommonLevelAccessor level, Biome biome)
    {
        BiomeExtension ext = EXTENSIONS.get(biome);
        if (ext != null || !tfcWorld || !CaveBiomeConfig.get().safetyNet)
        {
            return ext;
        }
        final Optional<ResourceKey<Biome>> key = level.registryAccess().registryOrThrow(Registries.BIOME).getResourceKey(biome);
        if (key.isEmpty() || TFC.equals(key.get().location().getNamespace()))
        {
            return null;
        }
        synchronized (EXTENSIONS)
        {
            ext = EXTENSIONS.get(biome);
            if (ext == null)
            {
                ext = buildExtension(key.get());
                EXTENSIONS.put(biome, ext);
                fallbackExtensions.incrementAndGet();
                TFCCaveBiomesMod.LOGGER.error("Biome {} reached TFC code but TFC has no extension for it. Created a fallback so the world does not crash. "
                    + "Something is placing non-TFC biomes in a TFC world: report this id.", key.get().location());
            }
        }
        return ext;
    }

    // ---------------------------------------------------------------- the overlay

    private static Holder<Biome> overlay(Holder<Biome> base, int qx, int qy, int qz, BaseLookup lookup)
    {
        final int by = (qy << 2) + 2;
        if (by < globalMinY || by > globalMaxY || !isTfc(base))
        {
            return base;
        }
        final int bx = (qx << 2) + 2, bz = (qz << 2) + 2;
        final int size = cellSize;
        final Cell cell = cellFor(Math.floorDiv(bx, size), Math.floorDiv(bz, size), lookup);
        if (cell == NONE)
        {
            return base;
        }
        final Entry e = cell.entry;
        if (by < e.minY || by > e.maxY)
        {
            return base;
        }
        final double dx = bx - cell.centerX, dz = bz - cell.centerZ;
        final double d = Math.sqrt(dx * dx + dz * dz);
        if (d > e.radius * 1.2)
        {
            return base;
        }
        // Lens shape: the blob narrows towards its floor and ceiling.
        final double t = e.maxY == e.minY ? 0.5 : (double) (by - e.minY) / (e.maxY - e.minY);
        final double vertical = 0.55 + 0.45 * Math.sqrt(Math.max(0, Math.sin(Math.PI * t)));
        // Irregular edge: 8 random radii around the center, smoothly interpolated by angle.
        final double f = (Math.atan2(dz, dx) + Math.PI) / (2 * Math.PI) * 8;
        final int i0 = ((int) f) & 7;
        final double frac = f - Math.floor(f);
        final double s = frac * frac * (3 - 2 * frac);
        final double radial = cell.radial[i0] + (cell.radial[(i0 + 1) & 7] - cell.radial[i0]) * s;
        return d <= e.radius * radial * vertical ? e.holder : base;
    }

    private static boolean isTfc(Holder<Biome> holder)
    {
        return holder.unwrapKey().map(k -> TFC.equals(k.location().getNamespace())).orElse(false);
    }

    private static String idOf(Holder<Biome> holder)
    {
        return holder.unwrapKey().map(k -> k.location().toString()).orElse("");
    }

    private static Cell cellFor(int cx, int cz, BaseLookup lookup)
    {
        final long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        Cell cell = CELLS.get(key);
        if (cell == null)
        {
            cell = decide(cx, cz, lookup);
            final Cell prev = CELLS.putIfAbsent(key, cell);
            if (prev != null)
            {
                cell = prev;
            }
            else if (debug && cell != NONE)
            {
                TFCCaveBiomesMod.LOGGER.info("Cave cell: {} around x={} z={} (y {}..{}, radius ~{})", cell.entry.key.location(), cell.centerX, cell.centerZ, cell.entry.minY, cell.entry.maxY, cell.entry.radius);
            }
        }
        return cell;
    }

    private static Cell decide(int cx, int cz, BaseLookup lookup)
    {
        final long s = seed;
        if (unit(hash(s, cx, cz, 1)) >= cellChance)
        {
            return NONE;
        }
        final int size = cellSize;
        final double jx = (unit(hash(s, cx, cz, 2)) - 0.5) * jitter;
        final double jz = (unit(hash(s, cx, cz, 3)) - 0.5) * jitter;
        final int centerX = (int) Math.floor((cx + 0.5 + jx) * size);
        final int centerZ = (int) Math.floor((cz + 0.5 + jz) * size);

        final Holder<Biome> surface = lookup.at(centerX >> 2, centerZ >> 2);
        if (!isTfc(surface))
        {
            return NONE;
        }
        final String surfaceId = idOf(surface);
        final double fromSpawn = Math.hypot(centerX - spawnX, centerZ - spawnZ);

        final List<Entry> eligible = new ArrayList<>();
        int total = 0;
        for (Entry e : entries)
        {
            if (fromSpawn >= e.minDistance && surfaceAllows(e, surfaceId))
            {
                eligible.add(e);
                total += e.weight;
            }
        }
        if (eligible.isEmpty())
        {
            return NONE;
        }
        double pick = unit(hash(s, cx, cz, 4)) * total;
        Entry chosen = eligible.get(eligible.size() - 1);
        for (Entry e : eligible)
        {
            pick -= e.weight;
            if (pick < 0)
            {
                chosen = e;
                break;
            }
        }
        final float[] radial = new float[8];
        for (int i = 0; i < 8; i++)
        {
            radial[i] = (float) (0.8 + 0.4 * unit(hash(s, cx, cz, 10 + i)));
        }
        return new Cell(chosen, centerX, centerZ, radial);
    }

    private static boolean surfaceAllows(Entry e, String surfaceId)
    {
        if (!e.include.isEmpty())
        {
            boolean ok = false;
            for (Pattern p : e.include)
            {
                if (p.matcher(surfaceId).matches())
                {
                    ok = true;
                    break;
                }
            }
            if (!ok) return false;
        }
        for (Pattern p : e.exclude)
        {
            if (p.matcher(surfaceId).matches()) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- /tfccavebiomes find

    public static List<Found> findNearby(BiomeSourceExtension ext, int blockX, int blockZ, int cellRadius)
    {
        final List<Found> found = new ArrayList<>();
        if (!active)
        {
            return found;
        }
        final int size = cellSize;
        final int pcx = Math.floorDiv(blockX, size), pcz = Math.floorDiv(blockZ, size);
        for (int dx = -cellRadius; dx <= cellRadius; dx++)
        {
            for (int dz = -cellRadius; dz <= cellRadius; dz++)
            {
                final Cell cell = cellFor(pcx + dx, pcz + dz, ext::getBiome);
                if (cell != NONE)
                {
                    found.add(new Found(cell.entry.key.location().toString(), cell.centerX, cell.centerZ, cell.entry.minY, cell.entry.maxY,
                        Math.hypot(cell.centerX - blockX, cell.centerZ - blockZ)));
                }
            }
        }
        found.sort((a, b) -> Double.compare(a.distance(), b.distance()));
        return found;
    }

    // ---------------------------------------------------------------- hashing (SplitMix64 style)

    private static long mix(long z)
    {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static long hash(long seed, int cx, int cz, int salt)
    {
        return mix(seed ^ mix(cx * 0x9E3779B97F4A7C15L + mix(cz * 0xC2B2AE3D27D4EB4FL + salt)));
    }

    private static double unit(long h)
    {
        return (h >>> 11) * 0x1.0p-53;
    }
}
