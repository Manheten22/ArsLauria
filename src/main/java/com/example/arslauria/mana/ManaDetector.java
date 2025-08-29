package com.example.arslauria.mana;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ManaDetector {
    public static List<LivingEntity> detect(ServerLevel level, LivingEntity caster,
                                            double base, int amplify, int dampen,
                                            double tolerancePercent, double radius,
                                            boolean randomise, boolean includeSelf) {
        double threshold = base + amplify * 500 - dampen * 500;
        double lower = threshold * (1.0 - tolerancePercent / 100.0);
        double upper = threshold * (1.0 + tolerancePercent / 100.0);

        AABB box = new AABB(caster.blockPosition()).inflate(radius);
        List<LivingEntity> found = level.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive());
        List<LivingEntity> matched = new ArrayList<>();
        for (LivingEntity e : found) {
            if (!includeSelf && e == caster) continue;
            IMana m = e.getCapability(ManaCapability.MANA).orElse(null);
            if (m == null) continue;
            double cur = m.getCurrent();
            if (cur >= lower && cur <= upper) matched.add(e);
        }

        if (matched.isEmpty()) return Collections.emptyList();
        if (randomise) {
            Collections.shuffle(matched);
            return List.of(matched.get(0));
        }
        return matched;
    }

    public static void markMatches(java.util.List<LivingEntity> matches, long ticksDuration) {
        if (matches.isEmpty()) return;
        long expire = matches.get(0).getServer().getTickCount() + ticksDuration;
        for (LivingEntity e : matches) {
            ManaMarkManager.mark(e.getUUID(), expire);
        }
    }
}
