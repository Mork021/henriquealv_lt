package dev.tfccavebiomes.mixin;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;
import dev.tfccavebiomes.CaveBiomes;
import net.dries007.tfc.world.biome.BiomeSourceExtension;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Adds the configured cave biomes to the possible biomes of TFC's biome source, the same thing Alex's Caves does for
 * vanilla sources. Without it, decoration (feature ordering) and structure sets cannot know about those biomes.
 * Happens once per biome source instance, on its first possibleBiomes() call after the server is ready.
 */
@Mixin(BiomeSource.class)
public abstract class BiomeSourceMixin
{
    @Shadow
    @Mutable
    private Supplier<Set<Holder<Biome>>> possibleBiomes;

    @Unique
    private boolean tfccb$expanded;

    @Inject(method = "possibleBiomes", at = @At("HEAD"), require = 0)
    private void tfccb$expandPossibleBiomes(CallbackInfoReturnable<Set<Holder<Biome>>> cir)
    {
        if (tfccb$expanded)
        {
            return;
        }
        if (!(((Object) this) instanceof BiomeSourceExtension))
        {
            tfccb$expanded = true; // not a TFC source, never touch it
            return;
        }
        final Set<Holder<Biome>> extra = CaveBiomes.possibleBiomesToAdd();
        if (extra == null)
        {
            return; // not ready yet, try again on the next call
        }
        tfccb$expanded = true;
        final Supplier<Set<Holder<Biome>>> previous = possibleBiomes;
        possibleBiomes = Suppliers.memoize(() -> ImmutableSet.<Holder<Biome>>builder().addAll(previous.get()).addAll(extra).build());
        CaveBiomes.countPossibleExpansion();
    }
}
