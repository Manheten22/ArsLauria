package com.example.arslauria.client;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ClientBarrierData {
    // expiry in millis (System.currentTimeMillis())
    private static final Map<Integer, Long> EXPIRIES = new ConcurrentHashMap<>();

    /** DurationTicks — количество тиков на сервере; конвертируем в миллисекунды. */
    public static void add(int entityId, int durationTicks) {
        long expiry = System.currentTimeMillis() + durationTicks * 50L;
        EXPIRIES.put(entityId, expiry);
    }

    public static void remove(int entityId) { EXPIRIES.remove(entityId); }

    /** true если есть запись и она ещё не истекла */
    public static boolean contains(int entityId) {
        Long expiry = EXPIRIES.get(entityId);
        if (expiry == null) return false;
        if (expiry < System.currentTimeMillis()) {
            EXPIRIES.remove(entityId);
            return false;
        }
        return true;
    }

    public static void clear() { EXPIRIES.clear(); }

    public static Set<Integer> getEntities() {
        return Collections.unmodifiableSet(new HashSet<>(EXPIRIES.keySet()));
    }

    public static int size() { return EXPIRIES.size(); }

    /** Возвращает оставшиеся миллисекунды (или <=0 если нет) — полезно для отладки */
    public static long getRemainingMillis(int entityId) {
        Long expiry = EXPIRIES.get(entityId);
        return expiry == null ? 0L : (expiry - System.currentTimeMillis());
    }
}
