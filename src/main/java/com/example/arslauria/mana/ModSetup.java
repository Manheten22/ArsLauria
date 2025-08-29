package com.example.arslauria.mana;

import com.example.arslauria.Lauria;
import com.example.arslauria.mana.network.ModNetwork;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

public class ModSetup {
    public static void init(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // Network registration
            ModNetwork.register();
            // NOTE: capability registration is handled by RegisterCapabilityHandler (MOD bus)
        });
    }

    // helper to register listener from main mod class:
    public static void registerToBus(IEventBus bus) {
        bus.addListener(ModSetup::init);
    }
}
