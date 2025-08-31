package com.example.arslauria.client;

import com.example.arslauria.setup.ModEffects;
import net.minecraft.client.Minecraft;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ClientBarrierData — теперь хранит expiry и текущие пуулы/max'ы.
 */
public class ClientBarrierData {
    private static final Map<Integer, Entry> ENTRIES = new ConcurrentHashMap<>();

    private static class Entry {
        long expiryMillis;
        int totalMagic;
        int totalMagicMax;
        int totalPhys;
        int totalPhysMax;
        boolean playedCrackSound; // чтобы проиграть звук один раз при начале треска

        Entry(long expiryMillis, int totalMagic, int totalMagicMax, int totalPhys, int totalPhysMax) {
            this.expiryMillis = expiryMillis;
            this.totalMagic = totalMagic;
            this.totalMagicMax = totalMagicMax;
            this.totalPhys = totalPhys;
            this.totalPhysMax = totalPhysMax;
            this.playedCrackSound = false;
        }
    }

    public static void add(int entityId, int durationTicks, int totalMagic, int totalMagicMax, int totalPhys, int totalPhysMax) {
        long expiry = System.currentTimeMillis() + durationTicks * 50L;
        Entry prev = ENTRIES.get(entityId);
        Entry e = new Entry(expiry, totalMagic, totalMagicMax, totalPhys, totalPhysMax);
        ENTRIES.put(entityId, e);

        // Если только что стал в крэкинге — проиграть локальный звук (если есть игрок)
        boolean wasCracking = prev != null && isCrackingEntry(prev);
        boolean nowCracking = isCrackingEntry(e);
        if (!wasCracking && nowCracking) {
            // локальный звук для владельца клиента
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.player.playSound(net.minecraft.sounds.SoundEvents.GLASS_BREAK, 1.0F, 1.0F);
                    e.playedCrackSound = true;
                }
            } catch (Throwable t) { /* ignore */ }
        }
    }

    public static void remove(int entityId) { ENTRIES.remove(entityId); }

    public static boolean contains(int entityId) {
        Entry e = ENTRIES.get(entityId);
        if (e == null) return false;
        if (e.expiryMillis < System.currentTimeMillis()) {
            ENTRIES.remove(entityId);
            return false;
        }
        return true;
    }

    public static Set<Integer> getEntities() {
        return java.util.Collections.unmodifiableSet(ENTRIES.keySet());
    }

    public static long getRemainingMillis(int entityId) {
        Entry e = ENTRIES.get(entityId);
        return e == null ? 0L : (e.expiryMillis - System.currentTimeMillis());
    }

    // проценты 0..1
    public static double getMagicPercent(int entityId) {
        Entry e = ENTRIES.get(entityId);
        if (e == null || e.totalMagicMax <= 0) return 1.0;
        return (double) Math.max(0, e.totalMagic) / (double) e.totalMagicMax;
    }
    public static double getPhysPercent(int entityId) {
        Entry e = ENTRIES.get(entityId);
        if (e == null || e.totalPhysMax <= 0) return 1.0;
        return (double) Math.max(0, e.totalPhys) / (double) e.totalPhysMax;
    }

    // основной критерий треска
    public static boolean isCracking(int entityId) {
        Entry e = ENTRIES.get(entityId);
        if (e == null) return false;
        return isCrackingEntry(e);
    }

    private static boolean isCrackingEntry(Entry e) {
        long rem = e.expiryMillis - System.currentTimeMillis();
        if (rem <= 3000L) return true; // последние 3 секунды
        if (e.totalMagicMax > 0 && ((double) Math.max(0, e.totalMagic) / e.totalMagicMax) <= 0.25) return true;
        if (e.totalPhysMax > 0 && ((double) Math.max(0, e.totalPhys) / e.totalPhysMax) <= 0.25) return true;
        return false;
    }

    public static void clear() { ENTRIES.clear(); }

    public static int size() { return ENTRIES.size(); }
}
