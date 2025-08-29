package com.example.arslauria.mana.client;

import com.example.arslauria.Lauria;
import com.example.arslauria.mana.ClientManaCache;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Random;
import java.util.UUID;

@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE, modid = Lauria.MOD_ID)
public class ManaClientRender {
    private static final Random RAND = new Random();

    @SubscribeEvent
    public static void onRenderLiving(RenderLivingEvent.Pre<?, ?> ev) {
        LivingEntity e = ev.getEntity();
        UUID id = e.getUUID();
        Double visible = ClientManaCache.getVisible(id);
        if (visible == null) return;

        int count = (int) Mth.clamp(Math.log10(Math.max(1.0, visible)) * 8.0, 0, 80);
        double cx = e.getX();
        double cy = e.getY() + e.getBbHeight() * 0.5;
        double cz = e.getZ();

        for (int i = 0; i < count; i++) {
            double px = cx + (RAND.nextDouble() - 0.5) * e.getBbWidth();
            double py = cy + (RAND.nextDouble() - 0.2) * e.getBbHeight();
            double pz = cz + (RAND.nextDouble() - 0.5) * e.getBbWidth();
            double vx = (RAND.nextDouble() - 0.5) * 0.02;
            double vy = (RAND.nextDouble() - 0.5) * 0.02 + 0.01;
            double vz = (RAND.nextDouble() - 0.5) * 0.02;
            Minecraft.getInstance().level.addParticle(ParticleTypes.ENCHANT, px, py, pz, vx, vy, vz);
        }
    }
}
