package com.example.arslauria.mana;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ClientManaCache {
    private static final Map<UUID, Double> visible = new ConcurrentHashMap<>();
    private static final Map<UUID, Double> max = new ConcurrentHashMap<>();

    public static void setVisible(UUID id, double val, double maxVal) {
        visible.put(id, val);
        max.put(id, maxVal);
    }

    public static Double getVisible(UUID id) { return visible.get(id); }
    public static Double getMax(UUID id) { return max.get(id); }
}
