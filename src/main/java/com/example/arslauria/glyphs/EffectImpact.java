package com.example.arslauria.glyphs;

import com.example.arslauria.Lauria;
import com.example.arslauria.glyphs.events.WatchingBlockPosDelayedEvent;
import com.example.arslauria.glyphs.events.WatchingEntityBlockDelayedEvent;
import com.hollingsworth.arsnouveau.api.event.EventQueue;
import com.hollingsworth.arsnouveau.api.spell.AbstractAugment;
import com.hollingsworth.arsnouveau.api.spell.AbstractEffect;
import com.hollingsworth.arsnouveau.api.spell.SpellContext;
import com.hollingsworth.arsnouveau.api.spell.SpellResolver;
import com.hollingsworth.arsnouveau.api.spell.SpellStats;
import com.hollingsworth.arsnouveau.api.spell.SpellTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.AABB;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * EffectImpact — эффект, который ищет рядом "mage block" (enchanted_mage_block / FallingBlockEntity)
 * и ждёт, пока он "приземлится" или исчезнет, затем резолвит спелл. Дополнительно поддерживает
 * отложение по BlockPos для падающих блоков и логов (BlockTags.LOGS).
 */
public class EffectImpact extends AbstractEffect {

    public static final EffectImpact INSTANCE = new EffectImpact(Lauria.prefix("glyph_impact"), "Impact");

    private static final Logger LOGGER = LogManager.getLogger("arslauria-impact");

    public EffectImpact(ResourceLocation tag, String description) {
        super(tag, description);
    }

    @Override
    public int getDefaultManaCost() {
        return 0;
    }

    /**
     * Быстрая эвристика для определения "mage-like" сущности.
     */
    private boolean looksLikeMageBlock(Entity e) {
        if (e == null) return false;
        // Проверяем реестрный тип сущности (строка), а также конкретный класс для падающих блоков
        String typeStr = e.getType() != null ? e.getType().toString().toLowerCase() : "";
        if (typeStr.contains("mage_block")
                || typeStr.contains("enchanted_mage_block")
                || typeStr.contains("enchanted_falling_block")
                || typeStr.contains("falling")) {
            return true;
        }
        // Подстраховка по классу
        if (e instanceof FallingBlockEntity) {
            return true;
        }
        return false;
    }

    /**
     * Явный вызов getEntitiesOfClass(Entity.class, ...) чтобы избежать неоднозначности перегрузки.
     */
    private Entity findNearbyMageLikeEntity(Level world, Vec3 pos, double maxRadius) {
        BlockPos bp = BlockPos.containing(pos);
        AABB box = new AABB(bp).inflate(maxRadius);
        List<Entity> found = world.getEntitiesOfClass(Entity.class, box, this::looksLikeMageBlock);
        if (found == null || found.isEmpty()) return null;
        return found.stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(pos)))
                .orElse(null);
    }

    @Override
    public void onResolveBlock(BlockHitResult rayTraceResult, Level world, @NotNull LivingEntity shooter,
                               SpellStats spellStats, SpellContext spellContext, SpellResolver resolver) {

        String spellInfo = spellContext.getSpell() != null ? spellContext.getSpell().toString() : "null";
        LOGGER.info("EffectImpact.onResolveBlock called. serverSide={}, hitPos={}, currentIndex={}, spell={}",
                !world.isClientSide(), rayTraceResult.getLocation(), spellContext.getCurrentIndex(), spellInfo);

        Vec3 hitVec = rayTraceResult.getLocation();

        double[] radii = {1.5, 3.0, 6.0, 12.0, 24.0};

        // 1) Сначала ищем nearby mage-like entity (например enchanted_mage_block / enchanted_falling_block)
        for (double r : radii) {
            Entity candidate = findNearbyMageLikeEntity(world, hitVec, r);
            LOGGER.debug("EffectImpact: search radius={}, candidate={}", r,
                    candidate != null ? candidate.getType().toString() + "@" + candidate.blockPosition() : "null");
            if (candidate != null) {
                LOGGER.info("EffectImpact: Found candidate entity of type {} at {} (isRemoved={})",
                        candidate.getType(), candidate.blockPosition(), candidate.isRemoved());
                if (candidate.isRemoved()) {
                    LOGGER.info("EffectImpact: candidate already removed -> calling resolver.resume(world) immediately for pos {}", candidate.blockPosition());
                    try {
                        resolver.resume(world);
                        LOGGER.info("EffectImpact: resolver.resume(world) called successfully for pos {}", candidate.blockPosition());
                    } catch (Throwable t) {
                        LOGGER.warn("EffectImpact: resolver.resume(world) threw exception: {}", t.toString());
                    }
                    return;
                }

                int timeoutTicks = 20 * 60; // 60s по умолчанию
                var event = new WatchingEntityBlockDelayedEvent(candidate, rayTraceResult, world, resolver, timeoutTicks);

                LOGGER.info("EffectImpact: about to delay event for candidate at {} (currentIndex={})", candidate.blockPosition(), spellContext.getCurrentIndex());
                spellContext.delay(event);
                LOGGER.info("EffectImpact: delayed event for candidate at {} (currentIndex={})", candidate.blockPosition(), spellContext.getCurrentIndex());

                if (!world.isClientSide()) {
                    EventQueue.getServerInstance().addEvent(event);
                    LOGGER.info("EffectImpact: added event to EventQueue for {}", candidate.blockPosition());
                }
                return;
            }
        }

        // 2) Если nearby entity не найден — проверяем сам блок, по которому попали.
        BlockPos hitPos = rayTraceResult.getBlockPos();
        BlockState state = world.getBlockState(hitPos);
        boolean isFallingBlock = state.getBlock() instanceof FallingBlock;
        boolean isLog = state.is(BlockTags.LOGS);

        LOGGER.debug("EffectImpact: hit block at {} -> block={} falling={} log={}", hitPos, state.getBlock(), isFallingBlock, isLog);

        if (isFallingBlock || isLog) {
            // Для падающих блоков и логов — создаём WatchingBlockPosDelayedEvent (наблюдение за BlockPos)
            int timeoutTicks = 20 * 60;
            var event = new WatchingBlockPosDelayedEvent(hitPos, world, resolver, timeoutTicks);

            LOGGER.info("EffectImpact: creating WatchingBlockPosDelayedEvent for hitPos {} (falling={} log={})", hitPos, isFallingBlock, isLog);
            spellContext.delay(event);
            if (!world.isClientSide()) {
                EventQueue.getServerInstance().addEvent(event);
                LOGGER.info("EffectImpact: added BlockPos event to EventQueue for {}", hitPos);
            }
            return;
        }

        // 3) Ничего подходящего не найдено — резолвим немедленно
        LOGGER.info("EffectImpact: No mage-like entity or supported block found near hit -> resolving immediately.");
        try {
            resolver.resume(world);
            LOGGER.info("EffectImpact: resolver.resume(world) called (no candidate).");
        } catch (Throwable t) {
            LOGGER.warn("EffectImpact: resolver.resume(world) threw exception (no candidate): {}", t.toString());
        }
    }

    @Override
    public void onResolveEntity(EntityHitResult rayTraceResult, Level world, @NotNull LivingEntity shooter,
                                SpellStats spellStats, SpellContext spellContext, SpellResolver resolver) {

        String spellInfo = spellContext.getSpell() != null ? spellContext.getSpell().toString() : "null";
        String entityInfo = rayTraceResult.getEntity() != null ? rayTraceResult.getEntity().getType().toString() : "null";
        LOGGER.info("EffectImpact.onResolveEntity called. serverSide={}, entity={}, currentIndex={}, spell={}",
                !world.isClientSide(), entityInfo, spellContext.getCurrentIndex(), spellInfo);

        Entity hit = rayTraceResult.getEntity();
        if (looksLikeMageBlock(hit)) {
            LOGGER.info("EffectImpact: Hit entity looks like mage block: {}, isRemoved={}", hit.getType(), hit.isRemoved());
            if (hit.isRemoved()) {
                LOGGER.info("EffectImpact: hit entity already removed -> calling resolver.resume(world) immediately for pos {}", hit.blockPosition());
                try {
                    resolver.resume(world);
                    LOGGER.info("EffectImpact: resolver.resume(world) called successfully for removed hit entity at {}", hit.blockPosition());
                } catch (Throwable t) {
                    LOGGER.warn("EffectImpact: resolver.resume(world) threw exception for removed hit entity: {}", t.toString());
                }
                return;
            }

            int timeoutTicks = 20 * 60; // 60s default
            var event = new WatchingEntityBlockDelayedEvent(hit, rayTraceResult, world, resolver, timeoutTicks);

            LOGGER.info("EffectImpact: about to delay event for hit entity at {} (currentIndex={})", hit.blockPosition(), spellContext.getCurrentIndex());
            spellContext.delay(event);
            LOGGER.info("EffectImpact: delayed event for hit entity at {} (currentIndex={})", hit.blockPosition(), spellContext.getCurrentIndex());

            if (!world.isClientSide()) {
                EventQueue.getServerInstance().addEvent(event);
                LOGGER.info("EffectImpact: added event to EventQueue for hit entity pos {}", hit.blockPosition());
            }
            return;
        }

        // Если не mage-like — попробуем найти nearby mage-like entity в радиусе 12 (как раньше)
        Vec3 hitVec = rayTraceResult.getLocation();
        Entity nearby = findNearbyMageLikeEntity(world, hitVec, 12.0);
        LOGGER.debug("EffectImpact: Nearby search for mage-like entity returned: {}", nearby != null ? nearby.getType().toString() + "@" + nearby.blockPosition() : "null");
        if (nearby != null) {
            LOGGER.info("EffectImpact: Found nearby entity {} at {} (isRemoved={})", nearby.getType(), nearby.blockPosition(), nearby.isRemoved());
            if (nearby.isRemoved()) {
                LOGGER.info("EffectImpact: nearby entity already removed -> calling resolver.resume(world) for pos {}", nearby.blockPosition());
                try {
                    resolver.resume(world);
                    LOGGER.info("EffectImpact: resolver.resume(world) called successfully for nearby removed entity at {}", nearby.blockPosition());
                } catch (Throwable t) {
                    LOGGER.warn("EffectImpact: resolver.resume(world) threw exception for nearby removed entity: {}", t.toString());
                }
                return;
            }
            int timeoutTicks = 20 * 60;
            var event = new WatchingEntityBlockDelayedEvent(nearby, rayTraceResult, world, resolver, timeoutTicks);

            LOGGER.info("EffectImpact: about to delay event for nearby entity at {} (currentIndex={})", nearby.blockPosition(), spellContext.getCurrentIndex());
            spellContext.delay(event);
            LOGGER.info("EffectImpact: delayed event for nearby entity at {} (currentIndex={})", nearby.blockPosition(), spellContext.getCurrentIndex());

            if (!world.isClientSide()) {
                EventQueue.getServerInstance().addEvent(event);
                LOGGER.info("EffectImpact: added event to EventQueue for nearby entity pos {}", nearby.blockPosition());
            }
            return;
        }

        LOGGER.info("EffectImpact: Entity hit is not mage-like -> resolving immediately.");
        try {
            resolver.resume(world);
            LOGGER.info("EffectImpact: resolver.resume(world) called (entity not mage-like).");
        } catch (Throwable t) {
            LOGGER.warn("EffectImpact: resolver.resume(world) threw exception (entity not mage-like): {}", t.toString());
        }
    }

    @Nonnull
    @Override
    public Set<AbstractAugment> getCompatibleAugments() {
        return Set.of();
    }

    @Override
    public SpellTier defaultTier() {
        return SpellTier.THREE;
    }
}
