package com.example.arslauria.mana;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;

/**
 * Регистрируем capability на MOD event bus с помощью RegisterCapabilitiesEvent.
 * Убедись, что модид совпадает с твоим (ArsLauria.MODID) или замени на строку.
 */
@Mod.EventBusSubscriber(modid = "arslauria", bus = Mod.EventBusSubscriber.Bus.MOD)
public class RegisterCapabilityHandler {
    @SubscribeEvent
    public static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.register(IMana.class);
    }
}
