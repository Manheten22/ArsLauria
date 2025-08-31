package com.example.arslauria;

//import com.example.arslauria.item.ModItems;
import com.example.arslauria.network.NetworkHandler;
import com.example.arslauria.registry.ModRegistry;
//import com.example.arslauria.setup.ModEffects;
import com.example.arslauria.setup.ModEffects;
import com.hollingsworth.arsnouveau.setup.registry.CreativeTabRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


// The value here should match an entry in the META-INF/mods.toml file
@Mod(Lauria.MOD_ID)
public class Lauria
{
    public static final String MOD_ID = "arslauria";

    public static final Logger LOGGER = LogManager.getLogger();
    @SuppressWarnings("removal")
    public Lauria() {
        IEventBus modbus = FMLJavaModLoadingContext.get().getModEventBus();
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
        NetworkHandler.init();
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
