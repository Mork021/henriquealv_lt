package dev.tfccavebiomes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code config/tfc_cave_biomes/cave_biomes.json}. Written with defaults for Alex's Caves on first run.
 * <p>
 * Any biome from any mod can be added to {@link #biomes}: the entry only needs the biome id, a vertical band and a
 * size. Entries whose biome does not exist in the loaded registry are skipped (with a log line), so it is safe to
 * leave entries for mods that are not installed.
 */
public final class CaveBiomeConfig
{
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Everything that is water, shore or marsh-like. Keeps land caves from piercing lakes, rivers and the sea floor. */
    private static final List<String> NOT_ON_WATER = List.of(
        "tfc:.*ocean.*", "tfc:.*lake", "tfc:river", "tfc:shore", "tfc:tidal_flats", "tfc:lowlands", "tfc:salt_marsh", "tfc:low_canyons"
    );

    @SerializedName("_readme")
    public String readme = "Each cell of cell_size x cell_size blocks may hold ONE cave biome blob. cell_chance = chance that a cell has one. "
        + "The blob is centered near the cell middle (center_jitter = how far it can move, 0..1) and is at most 'radius' blocks wide, between min_y and max_y. "
        + "surface_include / surface_exclude are regexes matched against the TFC biome id at the blob center (full id, e.g. tfc:plains). "
        + "Keep radius <= 0.2 * cell_size or the blob is clipped by its cell border. Delete this file to get the defaults back.";

    public boolean enabled = true;
    /** If a biome that TFC has no extension for reaches TFC code, log it and give it a fallback instead of crashing. */
    @SerializedName("safety_net")
    public boolean safetyNet = true;
    /** Log every cave cell the first time it is decided (coordinates included), handy for teleporting to one. */
    public boolean debug = false;

    @SerializedName("cell_size")
    public int cellSize = 640;
    @SerializedName("cell_chance")
    public double cellChance = 0.6;
    @SerializedName("center_jitter")
    public double centerJitter = 0.5;
    @SerializedName("spawn_x")
    public int spawnX = 0;
    @SerializedName("spawn_z")
    public int spawnZ = 0;

    public List<Entry> biomes = new ArrayList<>();

    public static final class Entry
    {
        public String biome;
        public int weight = 1;
        @SerializedName("min_y")
        public int minY = -62;
        @SerializedName("max_y")
        public int maxY = 48;
        public int radius = 125;
        @SerializedName("min_distance_from_spawn")
        public int minDistanceFromSpawn = 400;
        @SerializedName("surface_include")
        public List<String> surfaceInclude = new ArrayList<>();
        @SerializedName("surface_exclude")
        public List<String> surfaceExclude = new ArrayList<>();

        Entry() {}

        Entry(String biome, int minDistanceFromSpawn, List<String> include, List<String> exclude)
        {
            this.biome = biome;
            this.minDistanceFromSpawn = minDistanceFromSpawn;
            this.surfaceInclude = new ArrayList<>(include);
            this.surfaceExclude = new ArrayList<>(exclude);
        }
    }

    private static CaveBiomeConfig instance = defaults();

    public static CaveBiomeConfig get()
    {
        return instance;
    }

    public static CaveBiomeConfig defaults()
    {
        final CaveBiomeConfig c = new CaveBiomeConfig();
        // Alex's Caves. Distances are the ones AC itself uses for its own biome placement.
        c.biomes.add(new Entry("alexscaves:magnetic_caves", 400, List.of(), NOT_ON_WATER));
        c.biomes.add(new Entry("alexscaves:primordial_caves", 450, List.of(), NOT_ON_WATER));
        c.biomes.add(new Entry("alexscaves:toxic_caves", 650, List.of(), NOT_ON_WATER));
        c.biomes.add(new Entry("alexscaves:forlorn_hollows", 650, List.of(), NOT_ON_WATER));
        c.biomes.add(new Entry("alexscaves:candy_cavity", 500, List.of(), NOT_ON_WATER));
        // The abyssal chasm lives under deep ocean, so the surface test is inverted.
        c.biomes.add(new Entry("alexscaves:abyssal_chasm", 400, List.of("tfc:deep_ocean", "tfc:deep_ocean_trench"), List.of()));
        c.biomes.get(5).maxY = 40;
        return c;
    }

    public static Path path()
    {
        return FMLPaths.CONFIGDIR.get().resolve("tfc_cave_biomes").resolve("cave_biomes.json");
    }

    /** Loads the config, writing the defaults first if there is no file. Never throws: a broken file falls back to defaults. */
    public static void load()
    {
        final Path file = path();
        try
        {
            if (!Files.exists(file))
            {
                Files.createDirectories(file.getParent());
                try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8))
                {
                    GSON.toJson(defaults(), w);
                }
                TFCCaveBiomesMod.LOGGER.info("Wrote default config to {}", file);
            }
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8))
            {
                final CaveBiomeConfig read = GSON.fromJson(r, CaveBiomeConfig.class);
                if (read != null && read.biomes != null)
                {
                    instance = read;
                }
            }
        }
        catch (IOException | RuntimeException e)
        {
            TFCCaveBiomesMod.LOGGER.error("Could not read {} - using built-in defaults. Fix or delete the file.", file, e);
            instance = defaults();
        }
        instance.sanitize();
    }

    private void sanitize()
    {
        cellSize = Math.max(64, cellSize);
        cellChance = Math.max(0, Math.min(1, cellChance));
        centerJitter = Math.max(0, Math.min(0.6, centerJitter));
        for (Entry e : biomes)
        {
            if (e.surfaceInclude == null) e.surfaceInclude = new ArrayList<>();
            if (e.surfaceExclude == null) e.surfaceExclude = new ArrayList<>();
            if (e.maxY < e.minY)
            {
                final int t = e.maxY;
                e.maxY = e.minY;
                e.minY = t;
            }
            e.weight = Math.max(1, e.weight);
        }
    }
}
