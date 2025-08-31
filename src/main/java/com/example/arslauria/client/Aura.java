package com.example.arslauria.client;

import com.example.arslauria.ModCommands;
import com.hollingsworth.arsnouveau.api.mana.IManaCap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.event.TickEvent.PlayerTickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;


 // Клиентский подписчик для отрисовки ауры

@Mod.EventBusSubscriber(
        modid = com.example.arslauria.Lauria.MOD_ID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public class Aura {

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent event) {
        // Только клиент и только локальный игрок, на фазе END
        if (event.player.level().isClientSide()
                && event.phase == PlayerTickEvent.Phase.END
                && event.player == Minecraft.getInstance().player) {

            // Берём ману из capability
            event.player.getCapability(ModCommands.MANA_CAP)
                    .resolve()
                    .ifPresent(cap -> spawnAura(event.player.getX(),
                            event.player.getY() + event.player.getEyeHeight() - 1.3,
                            event.player.getZ(),
                            cap.getMaxMana()));
        }
    }

    // Вычисляем радиус ауры по максимальной мане
    private static float computeRadius(double maxMana) {
        if (maxMana < 1000) {
            return 1f;   // малая аура
        } else if (maxMana < 1500) {
            return 2.0f;   // средняя аура
        } else {
            return 3.0f;   // большая аура
        }
    }

    // Спавнит часть частиц по окружности вокруг (x,y,z)
    private static void spawnAura(double x, double y, double z, double maxMana) {
        float radius = computeRadius(maxMana);
        int points = 20;              // кол-во точек окружности
        double dYaw  = 2 * Math.PI / points;
        var world = Minecraft.getInstance().level;

        for (int i = 0; i < points; i++) {
            double angle = i * dYaw;
            double px = x + radius * Math.cos(angle);
            double pz = z + radius * Math.sin(angle);
            // Используем ENCHANT-партикл, он хорошо смотрится как «аура»
            world.addParticle(ParticleTypes.ENCHANT,
                    px, y, pz,
                    0, 0.02, 0);
        }
    }
}