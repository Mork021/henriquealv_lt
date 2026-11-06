package dev.tfccavebiomes.mixin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Makes TFC's biome source Y-aware.
 * <p>
 * Everything that asks a biome source "what biome is at x, y, z" (vanilla structure placement, /locate, Alex's Caves'
 * cave volumes...) ends up in {@code RegionBiomeSource.getNoiseBiome(int, int, int, Climate.Sampler)}, which TFC answers
 * with a 2D biome and ignores y. This plugin inserts, before every return of that method, a call to
 * {@code CaveBiomes.overlayFromSource(result, x, y, z, this)}.
 * <p>
 * It is done with ASM and matched by descriptor instead of a normal {@code @Inject} because the method overrides a
 * vanilla method, whose name in a production jar is an obfuscated SRG name that cannot be written reliably here.
 */
public class TFCCaveBiomesMixinPlugin implements IMixinConfigPlugin
{
    private static final Logger LOGGER = LogManager.getLogger("TFCCaveBiomes/Plugin");

    private static final String TARGET = "net.dries007.tfc.world.biome.RegionBiomeSource";
    private static final String GET_NOISE_BIOME_DESC = "(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;";
    private static final String HOOK_OWNER = "dev/tfccavebiomes/CaveBiomes";
    private static final String HOOK_NAME = "overlayFromSource";
    private static final String HOOK_DESC = "(Lnet/minecraft/core/Holder;IIILjava/lang/Object;)Lnet/minecraft/core/Holder;";

    private boolean patched = false;

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig()
    {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName)
    {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins()
    {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo)
    {
        if (patched || !TARGET.equals(targetClassName))
        {
            return;
        }
        patched = true;

        int returns = 0;
        int methods = 0;
        for (MethodNode method : targetClass.methods)
        {
            if (!GET_NOISE_BIOME_DESC.equals(method.desc)
                || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC | Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC)) != 0)
            {
                continue;
            }
            methods++;
            for (AbstractInsnNode insn : method.instructions.toArray())
            {
                if (insn.getOpcode() != Opcodes.ARETURN)
                {
                    continue;
                }
                // Stack before: [Holder]. After the inserted code: [Holder] again (possibly another one).
                final InsnList call = new InsnList();
                call.add(new VarInsnNode(Opcodes.ILOAD, 1)); // quartX
                call.add(new VarInsnNode(Opcodes.ILOAD, 2)); // quartY
                call.add(new VarInsnNode(Opcodes.ILOAD, 3)); // quartZ
                call.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
                call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false));
                method.instructions.insertBefore(insn, call);
                returns++;
            }
            method.maxStack += 5;
        }
        if (returns == 0)
        {
            LOGGER.error("Could not find RegionBiomeSource.getNoiseBiome{} - structure placement / locate will NOT see cave biomes. TFC changed?", GET_NOISE_BIOME_DESC);
        }
        else
        {
            LOGGER.info("Patched {} method(s), {} return(s) in {}", methods, returns, TARGET);
        }
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
