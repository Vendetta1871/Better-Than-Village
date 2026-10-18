package net.betterthanvillage;

import com.mojang.logging.LogUtils;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.registry.ModRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(BetterThanVillage.MOD_ID)
public final class BetterThanVillage {
    public static final String MOD_ID = "better_than_village";
    /** Prefix for brain tasks owned by this mod (used to find and stop "mod tasks"). */
    public static final String PACKAGE_PREFIX = "net.betterthanvillage.";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BetterThanVillage(IEventBus modBus, ModContainer container) {
        ColonyConfig.load();
        ModRegistries.register(modBus);
        ModEvents.register(NeoForge.EVENT_BUS);
        LOGGER.info("Better Than Village initialized for generated vanilla villages");
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
