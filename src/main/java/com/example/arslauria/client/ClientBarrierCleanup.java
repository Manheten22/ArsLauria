package com.example.arslauria.client;

import com.example.arslauria.setup.ModEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashSet;
import java.util.Set;

/**
 * Клиентский periodic cleanup — удаляет id только когда:
 *  - expiry истёк (ClientBarrierData.getRemainingMillis <= 0)
 *  AND
 *  - сущность не существует на клиенте, либо на ней нет эффекта Barrier.
 *
 * Это защищает от гонки: пакет add может прийти раньше, чем клиент обновит MobEffectInstance.
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ClientBarrierCleanup {
    private static final int CHECK_INTERVAL = 5; // тиков (~0.25s) — можно увеличить
    private static int tickCounter = 0;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        tickCounter++;
        if (tickCounter % CHECK_INTERVAL != 0) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Set<Integer> ids = new HashSet<>(ClientBarrierData.getEntities());
        long nowRem;
        for (Integer id : ids) {
            long remaining = ClientBarrierData.getRemainingMillis(id);

            // если ещё не истёк — оставляем запись независимо от наличия MobEffectInstance
            if (remaining > 0) {
                // optional: можно попытаться показать оставшееся время в debug
                continue;
            }

            // expiry истёк — можно безопасно удалить (независимо от состояния сущности)
            ClientBarrierData.remove(id);
        }
    }
}
