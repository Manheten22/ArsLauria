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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.AABB;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * EffectImpact — эффект, который ищет рядом "mage block" (enchanted_mage_block / FallingBlockEntity)
 * и ждёт, пока он "приземлится" или исчезнет, затем резолвит спелл.
 *
 * Обновлён: согласованное использование shared AtomicBoolean guard и вызов конструктора
 * WatchingBlockPosDelayedEvent в порядке (center, radius, vBelow, vAbove, expectedBlock, initialHit, world, resolver, timeout, guard).
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
        String typeStr = e.getType() != null ? e.getType().toString().toLowerCase() : "";
        if (typeStr.contains("mage_block")
                || typeStr.contains("enchanted_mage_block")
                || typeStr.contains("enchanted_falling_block")
                || typeStr.contains("falling")) {
            return true;
        }
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

                // Если это падающий блок из инвентаря — используем комбинированный подход (pos + entity) с guard
                String typeStr = candidate.getType() != null ? candidate.getType().toString().toLowerCase() : "";
                boolean isEnchantedFalling = typeStr.contains("enchanted_falling_block") || (candidate instanceof FallingBlockEntity);

                if (isEnchantedFalling) {
                    LOGGER.info("EffectImpact: candidate looks like enchanted/falling block -> creating combined watchers for entity {}", candidate.blockPosition());
                    AtomicBoolean guard = new AtomicBoolean(false);

                    // попытка извлечь ожидаемый блок из FallingBlockEntity
                    Block expectedBlock = null;
                    if (candidate instanceof FallingBlockEntity fbe) {
                        try {
                            expectedBlock = fbe.getBlockState().getBlock();
                        } catch (Throwable ignored) { expectedBlock = null; }
                    }

                    // прогнозируем позицию через N тиков (эвристика)
                    Vec3 entityPos = candidate.position();
                    Vec3 vel = candidate.getDeltaMovement() != null ? candidate.getDeltaMovement() : Vec3.ZERO;
                    double predictTicks = 10.0; // можно варьировать
                    Vec3 predicted = entityPos.add(vel.scale(predictTicks));
                    BlockPos center = BlockPos.containing(predicted);

                    double horizSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
                    int radiusInt = Math.max(3, (int) Math.ceil(horizSpeed * 5.0)); // минимальный радиус 3
                    int verticalBelow = 2;
                    int verticalAbove = 1;
                    int timeoutTicks = 20 * 10; // 10 секунд

                    LOGGER.info("EffectImpact: predicted landing center {} from pos {} vel={} -> radius={} (predTicks={}) expectedBlock={}",
                            center, entityPos, vel, radiusInt, predictTicks, expectedBlock != null ? expectedBlock.toString() : "null");

                    // Используем конструктор: (center, radius, vBelow, vAbove, expectedBlock, initialHit, world, resolver, timeoutTicks, guard)
                    var posEvent = new WatchingBlockPosDelayedEvent(center, radiusInt, verticalBelow, verticalAbove, expectedBlock,
                            rayTraceResult, world, resolver, timeoutTicks, guard);

                    var entityEvent = new WatchingEntityBlockDelayedEvent(candidate, rayTraceResult, world, resolver, timeoutTicks, guard);

                    spellContext.delay(posEvent);
                    spellContext.delay(entityEvent);
                    LOGGER.info("EffectImpact: delayed combined events (pos + entity) for {} (currentIndex={})", center, spellContext.getCurrentIndex());

                    if (!world.isClientSide()) {
                        EventQueue.getServerInstance().addEvent(posEvent);
                        EventQueue.getServerInstance().addEvent(entityEvent);
                        LOGGER.info("EffectImpact: added combined events to EventQueue for pos {} and entity {}", center, candidate.blockPosition());
                    }
                    return;
                }

                // Оригинальная логика для enchanted_mage_block и других mage-like сущностей
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
            // для одиночного BlockPos можно использовать маленький радиус, ожидаемый блок = текущий блок
            Block expected = state.getBlock();
            int radiusForHit = 1;
            int vBelow = 1;
            int vAbove = 0;
            AtomicBoolean guard = new AtomicBoolean(false);

            var event = new WatchingBlockPosDelayedEvent(hitPos, radiusForHit, vBelow, vAbove, expected,
                    rayTraceResult, world, resolver, timeoutTicks, guard);

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
        String entityInfo = rayTraceResult != null && rayTraceResult.getEntity() != null ? rayTraceResult.getEntity().getType().toString() : "null";
        LOGGER.info("EffectImpact.onResolveEntity called. serverSide={}, entity={}, currentIndex={}, spell={}",
                !world.isClientSide(), entityInfo, spellContext.getCurrentIndex(), spellInfo);

        Entity hit = rayTraceResult != null ? rayTraceResult.getEntity() : null;
        if (hit == null) {
            LOGGER.info("EffectImpact: onResolveEntity: hit entity == null -> immediate resume");
            try {
                resolver.resume(world);
            } catch (Throwable t) {
                LOGGER.warn("EffectImpact: resolver.resume(world) threw when hit==null: {}", t.toString());
            }
            return;
        }

        String typeStr = hit.getType() != null ? hit.getType().toString().toLowerCase() : "";
        boolean looksLikeMage = looksLikeMageBlock(hit);
        boolean isFalling = typeStr.contains("enchanted_falling_block") || (hit instanceof FallingBlockEntity);
        boolean isMageBlock = typeStr.contains("mage_block") || typeStr.contains("enchanted_mage_block");

        LOGGER.info("EffectImpact: hit entity typeStr='{}', looksLikeMage={}, isFalling={}, isMageBlock={}",
                typeStr, looksLikeMage, isFalling, isMageBlock);

        // --------- Special-case: real mage_block (non-falling) ---------------
        if (isMageBlock && !isFalling) {
            LOGGER.info("EffectImpact: Handling mage_block (non-falling) with WatchingDelayedSpellEvent at pos {}", hit.blockPosition());

            if (hit.isRemoved()) {
                LOGGER.info("EffectImpact: mage_block already removed -> immediate resume for pos {}", hit.blockPosition());
                try {
                    resolver.resume(world);
                } catch (Throwable t) {
                    LOGGER.warn("EffectImpact: resolver.resume(world) threw for removed mage_block: {}", t.toString());
                }
                return;
            }

            int timeoutTicks = 20 * 30; // 30 seconds
            var event = new com.example.arslauria.glyphs.events.WatchingDelayedSpellEvent(hit, rayTraceResult, world, resolver, timeoutTicks);

            spellContext.delay(event);
            LOGGER.info("EffectImpact: delayed WatchingDelayedSpellEvent for mage_block at {} (currentIndex={})", hit.blockPosition(), spellContext.getCurrentIndex());

            if (!world.isClientSide()) {
                EventQueue.getServerInstance().addEvent(event);
                LOGGER.info("EffectImpact: added WatchingDelayedSpellEvent to EventQueue for mage_block at {}", hit.blockPosition());
            }
            return;
        }

        // --------- General mage-like / falling handling (combined watchers) ------------
        if (looksLikeMage) {
            LOGGER.info("EffectImpact: Hit entity looks like mage-like (fallback combined watchers): {}, isRemoved={}", hit.getType(), hit.isRemoved());

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

            // Для падающих блоков и других mage-like создаём COMBINED watchers (pos + entity) с guard
            AtomicBoolean guard = new AtomicBoolean(false);

            // попытка извлечь expectedBlock (только применимо к FallingBlockEntity)
            Block expectedBlock = null;
            if (hit instanceof FallingBlockEntity fbe) {
                try {
                    expectedBlock = fbe.getBlockState().getBlock();
                } catch (Throwable ignored) { expectedBlock = null; }
            }

            Vec3 entityPos = hit.position();
            Vec3 vel = hit.getDeltaMovement() != null ? hit.getDeltaMovement() : Vec3.ZERO;
            double predictTicks = 10.0;
            Vec3 predicted = entityPos.add(vel.scale(predictTicks));
            BlockPos center = BlockPos.containing(predicted);

            double horizSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
            int radius = Math.max(3, (int) Math.ceil(horizSpeed * 5.0));
            int verticalBelow = 2;
            int verticalAbove = 1;

            int timeoutTicks = isFalling ? (20 * 10) : (20 * 60);

            LOGGER.info("EffectImpact: predicted landing center {} from pos {} vel={} -> radius={} (predTicks={}) expectedBlock={}",
                    center, entityPos, vel, radius, predictTicks, expectedBlock != null ? expectedBlock.toString() : "null");

            var posEvent = new WatchingBlockPosDelayedEvent(center, radius, verticalBelow, verticalAbove, expectedBlock,
                    rayTraceResult, world, resolver, timeoutTicks, guard);
            var entityEvent = new WatchingEntityBlockDelayedEvent(hit, rayTraceResult, world, resolver, timeoutTicks, guard);

            spellContext.delay(posEvent);
            spellContext.delay(entityEvent);
            LOGGER.info("EffectImpact: delayed BlockPos & Entity events for {} (currentIndex={})", center, spellContext.getCurrentIndex());

            if (!world.isClientSide()) {
                EventQueue.getServerInstance().addEvent(posEvent);
                EventQueue.getServerInstance().addEvent(entityEvent);
                LOGGER.info("EffectImpact: added BlockPos & Entity events to EventQueue for {} and {}", center, hit.blockPosition());
            }
            return;
        }

        // --------- Not mage-like: fallback to searching nearby mage-like entity ----------
        Vec3 hitVec = rayTraceResult.getLocation();
        Entity nearby = findNearbyMageLikeEntity(world, hitVec, 12.0);
        LOGGER.debug("EffectImpact: Nearby search for mage-like entity returned: {}", nearby != null ? nearby.getType().toString() + "@" + nearby.blockPosition() : "null");
        if (nearby != null) {
            LOGGER.info("EffectImpact: Found nearby entity {} at {} (isRemoved={})", nearby.getType(), nearby.blockPosition(), nearby.isRemoved());

            String nearbyTypeStr = nearby.getType() != null ? nearby.getType().toString().toLowerCase() : "";
            boolean nearbyIsFalling = nearbyTypeStr.contains("enchanted_falling_block") || (nearby instanceof FallingBlockEntity);
            boolean nearbyIsMageBlock = nearbyTypeStr.contains("mage_block") || nearbyTypeStr.contains("enchanted_mage_block");

            if (nearbyIsMageBlock && !nearbyIsFalling) {
                LOGGER.info("EffectImpact: nearby is mage_block (non-falling) -> using WatchingDelayedSpellEvent for {}", nearby.blockPosition());
                if (nearby.isRemoved()) {
                    LOGGER.info("EffectImpact: nearby mage_block already removed -> resolver.resume(world)");
                    try {
                        resolver.resume(world);
                    } catch (Throwable t) {
                        LOGGER.warn("EffectImpact: resolver.resume(world) threw for nearby removed mage_block: {}", t.toString());
                    }
                    return;
                }
                int timeoutTicks = 20 * 30;
                var event = new com.example.arslauria.glyphs.events.WatchingDelayedSpellEvent(nearby, rayTraceResult, world, resolver, timeoutTicks);
                spellContext.delay(event);
                if (!world.isClientSide()) {
                    EventQueue.getServerInstance().addEvent(event);
                    LOGGER.info("EffectImpact: added WatchingDelayedSpellEvent to EventQueue for nearby mage_block at {}", nearby.blockPosition());
                }
                return;
            }

            // otherwise use combined watchers for nearby falling or mage-like entities
            LOGGER.info("EffectImpact: nearby is mage-like/falling -> creating combined watchers");
            AtomicBoolean guard = new AtomicBoolean(false);

            Block expectedBlock = null;
            if (nearby instanceof FallingBlockEntity fbeNearby) {
                try {
                    expectedBlock = fbeNearby.getBlockState().getBlock();
                } catch (Throwable ignored) { expectedBlock = null; }
            }

            Vec3 nPos = nearby.position();
            Vec3 nVel = nearby.getDeltaMovement() != null ? nearby.getDeltaMovement() : Vec3.ZERO;
            double predictTicks = 10.0;
            Vec3 predicted = nPos.add(nVel.scale(predictTicks));
            BlockPos center = BlockPos.containing(predicted);
            double horiz = Math.sqrt(nVel.x * nVel.x + nVel.z * nVel.z);
            int radius2 = Math.max(3, (int) Math.ceil(horiz * 5.0));
            int verticalBelow2 = 2;
            int verticalAbove2 = 1;
            int timeoutTicksNearby = nearbyIsFalling ? (20 * 10) : (20 * 60);

            var posEvent2 = new WatchingBlockPosDelayedEvent(center, radius2, verticalBelow2, verticalAbove2, expectedBlock,
                    rayTraceResult, world, resolver, timeoutTicksNearby, guard);
            var entityEvent2 = new WatchingEntityBlockDelayedEvent(nearby, rayTraceResult, world, resolver, timeoutTicksNearby, guard);

            spellContext.delay(posEvent2);
            spellContext.delay(entityEvent2);

            if (!world.isClientSide()) {
                EventQueue.getServerInstance().addEvent(posEvent2);
                EventQueue.getServerInstance().addEvent(entityEvent2);
                LOGGER.info("EffectImpact: added BlockPos & Entity events to EventQueue for {} and {}", center, nearby.blockPosition());
            }
            return;
        }

        // Not mage-like anywhere — resume immediately
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
