package dev.tfccavebiomes;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.dries007.tfc.world.biome.BiomeSourceExtension;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.level.biome.BiomeSource;

import java.util.List;

/**
 * {@code /tfccavebiomes find [cells]} lists the nearest cave biome blobs with a clickable teleport.
 * {@code /tfccavebiomes status} prints which hooks fired (useful to report a problem).
 */
public final class CaveBiomeCommand
{
    private CaveBiomeCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher)
    {
        dispatcher.register(Commands.literal("tfccavebiomes")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("find")
                .executes(ctx -> find(ctx.getSource(), 6))
                .then(Commands.argument("cells", IntegerArgumentType.integer(1, 20))
                    .executes(ctx -> find(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "cells")))))
            .then(Commands.literal("status")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(CaveBiomes.stats()), false);
                    return 1;
                })));
    }

    private static int find(CommandSourceStack source, int cells)
    {
        final BiomeSource biomeSource = source.getLevel().getChunkSource().getGenerator().getBiomeSource();
        if (!CaveBiomes.isActive() || !(biomeSource instanceof BiomeSourceExtension ext))
        {
            source.sendFailure(Component.literal("TFC Cave Biomes is not active in this world. Check latest.log for 'TFCCaveBiomes'."));
            return 0;
        }
        final List<CaveBiomes.Found> found = CaveBiomes.findNearby(ext, (int) Math.floor(source.getPosition().x), (int) Math.floor(source.getPosition().z), cells);
        if (found.isEmpty())
        {
            source.sendFailure(Component.literal("No cave biome within " + cells + " cells. Try a bigger number."));
            return 0;
        }
        final int shown = Math.min(8, found.size());
        for (int i = 0; i < shown; i++)
        {
            final CaveBiomes.Found f = found.get(i);
            final int y = (f.minY() + f.maxY()) / 2;
            final String text = String.format("%s  x=%d z=%d  y %d..%d  (%d blocks)  [tp]", f.biome(), f.x(), f.z(), f.minY(), f.maxY(), (int) f.distance());
            final Style style = Style.EMPTY
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tp @s " + f.x() + " " + y + " " + f.z()))
                .withUnderlined(true);
            source.sendSuccess(() -> Component.literal(text).withStyle(style), false);
        }
        return shown;
    }
}
