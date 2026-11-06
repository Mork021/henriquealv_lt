package dev.tfccavebiomes.mixin;

import net.dries007.tfc.world.biome.RegionBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Deliberately empty. It only makes Mixin treat RegionBiomeSource as a target, so that
 * {@link TFCCaveBiomesMixinPlugin#preApply} runs for it and patches its getNoiseBiome(int, int, int, Climate.Sampler)
 * by descriptor (no dependency on obfuscated method names). See the plugin.
 */
@Mixin(RegionBiomeSource.class)
public abstract class RegionBiomeSourceMixin
{
    @Unique
    private boolean tfccb$patched;
}
