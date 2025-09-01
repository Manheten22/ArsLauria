package com.example.arslauria.glyphs.events;

import com.hollingsworth.arsnouveau.api.event.DelayedSpellEvent;
import com.hollingsworth.arsnouveau.api.spell.SpellResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * WatchingEntityBlockDelayedEvent — ждёт появления/появления блока под/вокруг наблюдаемой сущности (обычно FallingBlockEntity).
 *
 * Поведение:
 *  - строит baseline блоков вокруг начальной позиции (радиус R)
 *  - на каждом тике проверяет: исчезла ли сущность, появился ли новый непустой блок в зоне baseline,
 *    или замедлилась ли сущность и под ней есть блок -> тогда планирует разрешение события.
 *  - при детекции ставит duration = 1 и немедленно вызывает super.tick(true), чтобы движок уменьшил duration
 *    и вызвал resolveSpell() в этом же тике.
 *  - в resolveSpell() помечает событие как вручную резолвленное и вызывает resolver.resume(world).
 *    Перед вызовом resume добавлены подробные логи и корректировка resolver.hitResult (если он некорректен).
 */
public class WatchingEntityBlockDelayedEvent extends DelayedSpellEvent {
    private static final Logger LOGGER = LogManager.getLogger("arslauria-impact");

    private final Entity watched;
    private boolean resolvedScheduled = false;
    private boolean manuallyResolved = false;
    private int tickCounter = 0;

    private final Map<BlockPos, Block> baselineBlocks = new HashMap<>();
    private static final int R = 1; // radius for baseline and checks (3x3x3)
    private BlockPos lastKnownPos = null;

    private static final int GRACE_TICKS = 3;
    private boolean removalDetected = false;
    private int removalTickCounter = 0;

    /**
     * @param watched      entity to watch (may be null)
     * @param initialHit   initial hit result (may be BlockHitResult or EntityHitResult or null)
     * @param world        world
     * @param resolver     spell resolver to resume when condition met
     * @param timeoutTicks timeout in ticks for the delayed event
     */
    public WatchingEntityBlockDelayedEvent(Entity watched, HitResult initialHit, Level world, SpellResolver resolver, int timeoutTicks) {
        super(timeoutTicks,
                // Ensure a BlockHitResult so default DelayedSpellEvent.resolveSpell() won't early-return for removed entities.
                (initialHit instanceof BlockHitResult)
                        ? initialHit
                        : new BlockHitResult(
                        (initialHit != null && initialHit.getLocation() != null) ? initialHit.getLocation() : Vec3.ZERO,
                        Direction.UP,
                        (initialHit instanceof BlockHitResult) ? ((BlockHitResult) initialHit).getBlockPos() : BlockPos.ZERO,
                        false),
                world,
                resolver);

        this.watched = watched;

        BlockPos initialPos = (watched != null) ? watched.blockPosition()
                : (initialHit instanceof BlockHitResult ? ((BlockHitResult) initialHit).getBlockPos() : BlockPos.ZERO);

        try {
            for (int dx = -R; dx <= R; dx++) {
                for (int dy = -R; dy <= R; dy++) {
                    for (int dz = -R; dz <= R; dz++) {
                        BlockPos p = initialPos.offset(dx, dy, dz);
                        BlockState state = world.getBlockState(p);
                        baselineBlocks.put(p, state.getBlock());
                    }
                }
            }
            lastKnownPos = initialPos;
            LOGGER.info("WatchingEntityBlockDelayedEvent created for entity {} at initialPos {} timeoutTicks={} baselineSize={}",
                    watched != null ? watched.getType() : "null", initialPos, timeoutTicks, baselineBlocks.size());
        } catch (Throwable t) {
            LOGGER.warn("WatchingEntityBlockDelayedEvent: failed to create baseline: {}", t.toString());
        }
    }

    @Override
    public void tick(boolean serverSide) {
        if (manuallyResolved || world == null) return;
        tickCounter++;

        if (!serverSide) {
            super.tick(serverSide);
            return;
        }

        try {
            if (watched != null && !watched.isRemoved()) {
                lastKnownPos = watched.blockPosition();
            }

            if (watched == null || watched.isRemoved()) {
                if (!removalDetected) {
                    removalDetected = true;
                    removalTickCounter = 0;
                    LOGGER.info("WatchingEntityBlockDelayedEvent: watched removed -> starting grace period ({} ticks) at lastKnownPos={}", GRACE_TICKS, lastKnownPos);
                } else {
                    removalTickCounter++;
                    if (checkForNewBlockAround(lastKnownPos)) {
                        LOGGER.info("WatchingEntityBlockDelayedEvent: new block detected during grace -> schedule resolve next tick (pos={})", lastKnownPos);
                        scheduleResolveNextTickAndForceTick();
                        return;
                    }
                    if (removalTickCounter >= GRACE_TICKS) {
                        LOGGER.info("WatchingEntityBlockDelayedEvent: grace expired without new block -> schedule resolve next tick (pos={})", lastKnownPos);
                        scheduleResolveNextTickAndForceTick();
                        return;
                    } else {
                        LOGGER.debug("WatchingEntityBlockDelayedEvent: still in grace (tick {}/{}) at {}", removalTickCounter, GRACE_TICKS, lastKnownPos);
                        super.tick(serverSide);
                        return;
                    }
                }
            } else {
                // entity still present
                if (checkForNewBlockAround(lastKnownPos)) {
                    LOGGER.info("WatchingEntityBlockDelayedEvent: detected NEW block near entity at {} -> schedule resolve next tick", lastKnownPos);
                    scheduleResolveNextTickAndForceTick();
                    return;
                }

                Vec3 vel = watched.getDeltaMovement();
                if (vel != null) {
                    double vy = vel.y;
                    if (Math.abs(vy) < 0.02) {
                        BlockPos below = lastKnownPos.below();
                        BlockState belowState = world.getBlockState(below);
                        if (!belowState.isAir()) {
                            LOGGER.info("WatchingEntityBlockDelayedEvent: low vy={} and non-air below at {} -> schedule resolve next tick", vy, below);
                            scheduleResolveNextTickAndForceTick();
                            return;
                        }
                    }
                }

                if (tickCounter % 5 == 0) {
                    LOGGER.debug("WatchingEntityBlockDelayedEvent tick: entity at {} (vy={}), no NEW block found (duration={})",
                            lastKnownPos, (watched != null && watched.getDeltaMovement() != null) ? watched.getDeltaMovement().y : Double.NaN, duration);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("WatchingEntityBlockDelayedEvent tick exception: {}", t.toString());
        }

        super.tick(serverSide);
    }

    /**
     * Устанавливает duration = 1 и немедленно вызывает super.tick(true),
     * чтобы гарантированно уменьшить duration и вызвать resolveSpell() в том же тике.
     */
    private void scheduleResolveNextTickAndForceTick() {
        synchronized (this) {
            if (resolvedScheduled || manuallyResolved) {
                LOGGER.debug("scheduleResolveNextTick: already scheduled or manuallyResolved -> skip");
                return;
            }
            this.duration = 1;
            resolvedScheduled = true;
            LOGGER.info("WatchingEntityBlockDelayedEvent: resolve scheduled (duration set to 1). Forcing super.tick(true) to finalize this tick.");
        }

        try {
            // Мы уже в серверном потоке -> прямой вызов безопасен
            super.tick(true); // уменьшит duration и вызовет resolveSpell() если duration <= 0
        } catch (Throwable t) {
            LOGGER.warn("scheduleResolveNextTickAndForceTick: super.tick(true) threw: {}", t.toString());
        }
    }

    /**
     * Проверяет, появился ли непустой блок, который отличается от baseline, в окрестности center (3x3x3).
     */
    private boolean checkForNewBlockAround(BlockPos center) {
        if (center == null) return false;
        for (int dx = -R; dx <= R; dx++) {
            for (int dy = -R; dy <= R; dy++) {
                for (int dz = -R; dz <= R; dz++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    BlockState state = world.getBlockState(p);
                    Block now = state.getBlock();
                    Block baseline = baselineBlocks.get(p);
                    if (baseline == null) {
                        // record baseline for previously unobserved positions
                        baselineBlocks.put(p, now);
                        baseline = now;
                    }
                    if (!now.equals(baseline) && !state.isAir()) {
                        LOGGER.debug("checkForNewBlockAround: new block at {} now={} baseline={}", p, now, baseline);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public void resolveSpell() {
        LOGGER.info("WatchingEntityBlockDelayedEvent.resolveSpell called (resolvedScheduled={}, manuallyResolved={}) for watched={}",
                resolvedScheduled, manuallyResolved, watched != null ? watched.getType() : "null");

        synchronized (this) {
            if (manuallyResolved) {
                LOGGER.debug("resolveSpell: manuallyResolved=true -> skipping super.resolveSpell()");
                return;
            }
            // помечаем как вручную резолвленное, чтобы избежать double-resume
            manuallyResolved = true;
        }

        // Перед вызовом resume — гарантируем корректный HitResult внутри resolver
        try {
            if (world == null) {
                LOGGER.warn("resolveSpell: world == null -> skipping resume");
                return;
            }

            // Если текущий result внутри события — EntityHitResult и сущность удалена, оригинальный DelayedSpellEvent пропускает resume.
            if (result instanceof EntityHitResult ehr && ehr.getEntity().isRemoved()) {
                LOGGER.info("resolveSpell: result is EntityHitResult and entity is removed -> skipping resume");
                return;
            }

            // Логируем текущее состояние resolver/контекста
            try {
                LOGGER.info("resolveSpell: about to call resolver.resume(world). spellContext.currentIndex={}, resolver.hitResult={}, spell={}",
                        resolver.spellContext != null ? resolver.spellContext.getCurrentIndex() : -1,
                        resolver.hitResult,
                        resolver.spell);
            } catch (Throwable t) {
                LOGGER.debug("resolveSpell: failed to log resolver state: {}", t.toString());
            }

            // Если resolver.hitResult некорректен (null или EntityHitResult с удалённой сущностью), заменим его на BlockHitResult по lastKnownPos
            boolean replacedHit = false;
            if (resolver.hitResult == null ||
                    (resolver.hitResult instanceof EntityHitResult eRes && (eRes.getEntity() == null || eRes.getEntity().isRemoved()))) {
                BlockPos usePos = lastKnownPos != null ? lastKnownPos : BlockPos.ZERO;
                BlockHitResult bhr = new BlockHitResult(Vec3.atCenterOf(usePos), Direction.UP, usePos, false);
                resolver.hitResult = bhr;
                replacedHit = true;
                LOGGER.info("resolveSpell: replaced resolver.hitResult with BlockHitResult at {} (was null or removed EntityHitResult).", usePos);
            }

            // Наконец, вызываем resume и логируем исключения (если есть)
            try {
                LOGGER.info("resolveSpell: calling resolver.resume(world) now.");
                resolver.resume(world);
                LOGGER.info("resolveSpell: resolver.resume(world) completed successfully.");
            } catch (Throwable t) {
                LOGGER.warn("resolveSpell: resolver.resume(world) threw exception:", t);
            } finally {
                if (replacedHit) {
                    // Не восстанавливаем старый hitResult — обычно это безопасно и полезно,
                    // т.к. дальнейшая резолюция ожидает BlockHitResult. Если нужно — можно сохранять старое значение.
                }
            }
        } catch (Throwable ex) {
            LOGGER.warn("resolveSpell: outer exception:", ex);
        }
    }

    @Override
    public boolean isExpired() {
        return manuallyResolved || super.isExpired();
    }
}
