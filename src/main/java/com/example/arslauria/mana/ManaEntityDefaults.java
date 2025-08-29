package com.example.arslauria.mana;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Initialize default mana on entity spawn/join. */
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE, modid = "arslauria")
public class ManaEntityDefaults {
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent ev) {
        if (!(ev.getEntity() instanceof LivingEntity le)) return;

        le.getCapability(ManaCapability.MANA).ifPresent(m -> {
            if (m.getMax() <= 0) {
                m.setMax(1100.0);
                m.setCurrent(1100.0);
                m.setVisibleFactor(1.0);
            }
        });
    }
}
