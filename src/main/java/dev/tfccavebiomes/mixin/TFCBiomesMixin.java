package dev.tfccavebiomes.mixin;

import dev.tfccavebiomes.CaveBiomes;
import net.dries007.tfc.world.biome.BiomeExtension;
import net.dries007.tfc.world.biome.TFCBiomes;
import net.minecraft.world.level.CommonLevelAccessor;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TFC only knows its own biomes and throws "Biome: &lt;id&gt;" for anything else that shows up in a chunk. Cave biomes get a
 * proper extension here, and (safety net) any other foreign biome gets a logged fallback instead of a crash.
 */
@Mixin(value = TFCBiomes.class, remap = false)
public abstract class TFCBiomesMixin
{
    @Inject(method = "getExtension", at = @At("RETURN"), cancellable = true, require = 0)
    private static void tfccb$caveExtension(CommonLevelAccessor level, Biome biome, CallbackInfoReturnable<BiomeExtension> cir)
    {
        if (cir.getReturnValue() == null)
        {
            final BiomeExtension extension = CaveBiomes.extensionFor(level, biome);
            if (extension != null)
            {
                cir.setReturnValue(extension);
            }
        }
    }
}
