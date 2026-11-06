package dev.tfccavebiomes;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(TFCCaveBiomesMod.MOD_ID)
public class TFCCaveBiomesMod
{
    public static final String MOD_ID = "tfccavebiomes";
    public static final Logger LOGGER = LogManager.getLogger("TFCCaveBiomes");

    public TFCCaveBiomesMod()
    {
        CaveBiomeConfig.load();
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event)
    {
        CaveBiomes.init(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event)
    {
        CaveBiomes.shutdown();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event)
    {
        CaveBiomeCommand.register(event.getDispatcher());
    }
}
