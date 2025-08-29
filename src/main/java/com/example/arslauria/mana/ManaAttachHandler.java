package com.example.arslauria.mana;

import com.example.arslauria.Lauria;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Attach ManaProvider to LivingEntity instances (Forge event bus). */
@Mod.EventBusSubscriber(modid = Lauria.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ManaAttachHandler {
    @SubscribeEvent
    public static void attach(AttachCapabilitiesEvent<Entity> ev) {
        if (ev.getObject() instanceof LivingEntity) {
            ev.addCapability(ManaProvider.KEY, new ManaProvider());
        }
    }
}
