package com.example.arslauria.mana;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE, modid = "arslauria")
public class ManaMarkManager {
    // map target UUID -> expireTick
    private static final Map<UUID, Long> marked = new ConcurrentHashMap<>();

    public static void mark(UUID target, long expireTick) {
        marked.put(target, expireTick);
    }

    public static void unmark(UUID target) {
        marked.remove(target);
    }

    public static boolean isMarked(UUID target) {
        return marked.containsKey(target);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        long tick = ev.getServer().getTickCount();
        Iterator<Map.Entry<UUID, Long>> it = marked.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> e = it.next();
            if (e.getValue() <= tick) it.remove();
        }
    }
}
