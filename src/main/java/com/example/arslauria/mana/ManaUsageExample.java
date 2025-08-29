package com.example.arslauria.mana;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

public class ManaUsageExample {
    public static void exampleDetectAndDamage(ServerLevel level, LivingEntity caster) {
        List<LivingEntity> matches = ManaDetector.detect(level, caster, 1100, 2, 0, 10.0, 50.0, false, true);
        if (matches.isEmpty()) return;
        ManaDetector.markMatches(matches, 200); // mark for 200 ticks (~10s)

        for (LivingEntity t : matches) {
        }
    }
}
