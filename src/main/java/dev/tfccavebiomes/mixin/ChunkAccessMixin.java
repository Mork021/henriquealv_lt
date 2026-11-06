package dev.tfccavebiomes.mixin;

import dev.tfccavebiomes.CaveBiomes;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * TFC fills chunk biome palettes straight from its 2D biome source. Wrapping the resolver lets the cave overlay put
 * cave biomes into the palettes (this is what the game then reads for spawns, F3, sound, decoration...).
 */
@Mixin(ChunkAccess.class)
public abstract class ChunkAccessMixin
{
    @ModifyVariable(method = "fillBiomesFromNoise", at = @At("HEAD"), argsOnly = true, require = 0)
    private BiomeResolver tfccb$wrapResolver(BiomeResolver original)
    {
        return CaveBiomes.wrapResolver(original);
    }
}
