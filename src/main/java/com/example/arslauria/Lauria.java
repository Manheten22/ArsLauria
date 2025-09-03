package com.example.arslauria;

//import com.example.arslauria.item.ModItems;
import com.example.arslauria.network.NetworkHandler;
import com.example.arslauria.registry.ModRegistry;
//import com.example.arslauria.setup.ModEffects;
import com.example.arslauria.setup.ModEffects;
import com.hollingsworth.arsnouveau.client.gui.book.InfinityGuiSpellBook;
import com.hollingsworth.arsnouveau.setup.config.ServerConfig;
import com.hollingsworth.arsnouveau.setup.registry.CreativeTabRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


// The value here should match an entry in the META-INF/mods.toml file
@Mod(Lauria.MOD_ID)
public class Lauria
{
    public static final String MOD_ID = "arslauria";

    public static final Logger LOGGER = LogManager.getLogger();
    @SuppressWarnings("removal")
    public Lauria() {
        System.err.println("### LAURIA JAR LOADED — BUILD TIME: 2025-09-01 08:00 ###");
        LOGGER.error("### LAURIA LOGGER ERROR TEST — JAR TIMESTAMP: {}", new java.util.Date());
        LOGGER.info(">>> Lauria constructor called (check jar/timestamp)");
        IEventBus modbus = FMLJavaModLoadingContext.get().getModEventBus();

        // Вывести все загруженные моды (id и displayName)
        try {
            ModList.get().getMods().forEach(modInfo ->
                    LOGGER.info("Loaded mod: id='{}' name='{}' file='{}'", modInfo.getModId(), modInfo.getDisplayName(), modInfo.getOwningFile().getFile().getFileName())
            );
        } catch (Throwable t) {
            LOGGER.error("Failed to list mods:", t);
        }

        ModRegistry.registerRegistries(modbus);

        ArsNouveauRegistry.registerGlyphs();

        modbus.addListener(this::setup);
        modbus.addListener(this::doClientStuff);
        modbus.addListener(this::doTabThings);

        MinecraftForge.EVENT_BUS.register(this);
        ModEffects.register(modbus);
    }

    public static ResourceLocation prefix(String path){
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private void setup(final FMLCommonSetupEvent event)
    {
        ArsNouveauRegistry.registerSounds();
        ArsNouveauRegistry.addAugments();
        NetworkHandler.init();
/*
        // Делаем изменение конфигов в рабочем потоке загрузки (после инициализации модов)
        event.enqueueWork(() -> {
            try {
                // Проверяем, что мод вообще загружен, и что ServerConfig доступен
                if (ModList.get().isLoaded("ars_nouveau")
                        && ServerConfig.INFINITE_SPELLS != null
                        && ServerConfig.NOT_SO_INFINITE_SPELLS != null) {

                    int current = ServerConfig.NOT_SO_INFINITE_SPELLS.get();
                    int increased = Math.min(1000, current + 10); // верхний предел из комментария конфига

                    ServerConfig.INFINITE_SPELLS.set(true);
                    ServerConfig.NOT_SO_INFINITE_SPELLS.set(increased);

                    LOGGER.info("Set Ars Nouveau infinite spells in setup; increased NOT_SO_INFINITE_SPELLS from {} to {}", current, increased);
                } else {
                    LOGGER.warn("ArsNouveau not loaded or ServerConfig fields null in setup.");
                }
            } catch (Throwable t) {
                LOGGER.error("Exception while trying to set Ars Nouveau ServerConfig in setup()", t);
            }
        });

*/
    }

    private void doClientStuff(final FMLClientSetupEvent event) {

    }
    @SubscribeEvent
    public void doTabThings(BuildCreativeModeTabContentsEvent event) {
        if (event.getTab() == CreativeTabRegistry.BLOCKS.get()) {
            for (var item : ModRegistry.ITEMS.getEntries()) {
                event.accept(item::get);
            }
        }
    }
/*
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        try {
            // только после старта сервера serverconfig уже загружен
            if (ModList.get().isLoaded("ars_nouveau")
                    && ServerConfig.INFINITE_SPELLS != null
                    && ServerConfig.NOT_SO_INFINITE_SPELLS != null) {

                int current = ServerConfig.NOT_SO_INFINITE_SPELLS.get(); // теперь get() не упадёт
                int increased = Math.min(1000, 10);

                ServerConfig.INFINITE_SPELLS.set(true);
                ServerConfig.NOT_SO_INFINITE_SPELLS.set(increased);

                LOGGER.info("[Lauria] Ars Nouveau: INFINITE_SPELLS=true; limit {} -> {}", current, increased);
            } else {
                LOGGER.warn("[Lauria] Ars Nouveau not loaded or ServerConfig fields are null on ServerStarted.");
            }
        } catch (Throwable t) {
            LOGGER.error("[Lauria] Failed to bump Ars Nouveau infinite spell limit on ServerStarted", t);
        }
    }
*/


    @SubscribeEvent
    public void doCapabilities(RegisterCapabilitiesEvent event){

    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ModCommands.register(event.getDispatcher());
        LOGGER.info("Registered custom /mana command");
    }


    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // do something when the server starts
        LOGGER.info("HELLO from server starting");
    }

}
