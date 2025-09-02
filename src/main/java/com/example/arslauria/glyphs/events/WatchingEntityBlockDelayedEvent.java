package com.example.arslauria.glyphs.events;

import com.hollingsworth.arsnouveau.api.event.DelayedSpellEvent;
import com.hollingsworth.arsnouveau.api.spell.SpellResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WatchingEntityBlockDelayedEvent — ждёт появления блока под/вокруг наблюдаемой сущности (обычно FallingBlockEntity).
 *
 * Улучшения по сравнению с оригиналом:
 *  - более строгая фильтрация появления блока (detect zone);
 *  - fallback-скан вниз, если baseline не дал результата;
 *  - не форсит super.tick(true) при планировании резолва (устанавливает duration = RESOLVE_DELAY_TICKS);
 *  - аккуратно заменяет resolver.hitResult если это EntityHitResult от mage-like сущности;
 *  - подробное логирование и защита от исключений.
 *
 * Изменение: добавлена дополнительная задержка RESUME_DELAY_TICKS перед фактическим вызовом resolver.resume(world).
 */
public class WatchingEntityBlockDelayedEvent extends DelayedSpellEvent {
    private static final Logger LOGGER = LogManager.getLogger("arslauria-impact");

    private final Entity watched;
    private boolean resolvedScheduled = false;
    private boolean manuallyResolved = false;
    private int tickCounter = 0;

    private final Map<BlockPos, Block> baselineBlocks = new HashMap<>();
    private static final int R = 3; // baseline radius (3 -> 7x7x7 snapshot)
    private BlockPos lastKnownPos = null;

    private static final int GRACE_TICKS = 3; // ticks to wait after entity removal to re-check

    private final AtomicBoolean guard; // may be null

    // Detection tuning: horizontal distance (blocks) and vertical below (blocks)
    private static final int H_DETECT = 1; // horizontal radius
    private static final int V_DETECT = 4; // how far below the center we consider relevant

    // delay between scheduleResolveNextTickAndForceTick() and first resolveSpell() invocation (already used).
    private static final int RESOLVE_DELAY_TICKS = 10; // default deferred resolve (10 ticks = 0.5s)

    // additional delay *inside* resolveSpell before calling resolver.resume(world)
    private static final int RESUME_DELAY_TICKS = 10; // extra ticks to wait before actual resume

    // fields for deferred resume inside resolveSpell
    private boolean resumePending = false;
    private BlockPos deferredUsePos = null;

    public WatchingEntityBlockDelayedEvent(Entity watched, HitResult initialHit, Level world, SpellResolver resolver, int timeoutTicks, AtomicBoolean guard) {
        super(timeoutTicks,
                (initialHit instanceof BlockHitResult)
                        ? initialHit
                        : new BlockHitResult(
                        (initialHit != null && initialHit.getLocation() != null) ? initialHit.getLocation() : Vec3.ZERO,
                        net.minecraft.core.Direction.UP,
                        (initialHit instanceof BlockHitResult) ? ((BlockHitResult) initialHit).getBlockPos() : BlockPos.ZERO,
                        false),
                world,
                resolver);

        this.watched = watched;
        this.guard = guard;

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

    // backward-compatible constructor (no guard)
    public WatchingEntityBlockDelayedEvent(Entity watched, HitResult initialHit, Level world, SpellResolver resolver, int timeoutTicks) {
        this(watched, initialHit, world, resolver, timeoutTicks, new AtomicBoolean(false));
    }

    @Override
    public void tick(boolean serverSide) {
        if (manuallyResolved || world == null) return;

        if (guard != null && guard.get()) {
            LOGGER.debug("WatchingEntityBlockDelayedEvent: guard already set -> expiring event for watched {}", watched != null ? watched.getType() : "null");
            manuallyResolved = true;
            duration = 0;
            return;
        }

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
                if (!manuallyResolved) {
                    // start grace period (логируем только)
                    LOGGER.info("WatchingEntityBlockDelayedEvent: watched removed -> entering grace checks at lastKnownPos={}", lastKnownPos);
                }
                // During grace, continue checking for new block around lastKnownPos
                if (checkForNewBlockAround(lastKnownPos)) {
                    LOGGER.info("WatchingEntityBlockDelayedEvent: new block detected during grace -> schedule resolve (pos={})", lastKnownPos);
                    scheduleResolveNextTickAndForceTick();
                    return;
                }
                super.tick(serverSide);
                return;
            } else {
                // entity still exists
                if (checkForNewBlockAround(lastKnownPos)) {
                    LOGGER.info("WatchingEntityBlockDelayedEvent: detected NEW block near entity at {} -> schedule resolve", lastKnownPos);
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
                            LOGGER.info("WatchingEntityBlockDelayedEvent: low vy={} and non-air below at {} -> schedule resolve", vy, below);
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

    private void scheduleResolveNextTickAndForceTick() {
        synchronized (this) {
            if (resolvedScheduled || manuallyResolved) {
                LOGGER.debug("scheduleResolveNextTick: already scheduled or manuallyResolved -> skip");
                return;
            }
            // отложим резолв на N тиков — это первый уровень задержки перед вызовом resolveSpell()
            this.duration = Math.max(1, RESOLVE_DELAY_TICKS);
            resolvedScheduled = true;
            LOGGER.info("WatchingEntityBlockDelayedEvent: resolve scheduled (duration set to {}). Will reach resolveSpell() in {} ticks.", this.duration, RESOLVE_DELAY_TICKS);
        }
        // НЕ вызывать super.tick(true) — пусть событие уменьшится естественным образом в следующих тиках.
    }

    /**
     * Новая, более строгая проверка.
     *
     * Логика:
     *  - перебираем позиции в baseline (R=3) вокруг center;
     *  - если позиции нет в baseline:
     *      - если сейчас непустой блок и позиция удовлетворяет критериям близости к lastKnownPos -> детектим NEW
     *      - иначе запоминаем baseline=now и продолжаем
     *  - если позиция есть в baseline:
     *      - если now != baseline и now != air и позиция удовлетворяет критериям близости -> детектим NEW
     *      - иначе игнорируем
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

                    boolean isWithinDetectZone = isPositionInDetectZone(p, center);

                    if (baseline == null) {
                        // если baseline ещё не было — считаем baseline=air, но если сейчас блок и он релевантен — detect
                        if (!state.isAir()) {
                            if (isWithinDetectZone) {
                                LOGGER.debug("checkForNewBlockAround: new block at {} (no baseline) now={} -> DETECT (within detect zone)", p, now);
                                return true;
                            } else {
                                // Игнорируем изменения вне detect zone — и запомним baseline=now, чтобы не реагировать впоследствии
                                baselineBlocks.put(p, now);
                                LOGGER.debug("checkForNewBlockAround: new block at {} (no baseline) now={} -> IGNORED (outside detect zone)", p, now);
                                continue;
                            }
                        } else {
                            // сейчас air -> запомним baseline=air
                            baselineBlocks.put(p, now);
                            continue;
                        }
                    }

                    // baseline был — обычная проверка, но только если позиция релевантна
                    if (!now.equals(baseline) && !state.isAir()) {
                        if (isWithinDetectZone) {
                            LOGGER.debug("checkForNewBlockAround: new block at {} now={} baseline={} -> DETECT (within detect zone)", p, now, baseline);
                            return true;
                        } else {
                            LOGGER.debug("checkForNewBlockAround: new block at {} now={} baseline={} -> IGNORED (outside detect zone)", p, now, baseline);
                            // обновим baseline на now, чтобы не повторять, но не триггерим
                            baselineBlocks.put(p, now);
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Условие релевантности позиции p относительно центра (lastKnownPos / center):
     *  - горизонтальная дистанция (max(abs(dx), abs(dz))) <= H_DETECT
     *  - позиция не выше центра по Y (p.y <= center.y)
     *  - глубина относительно центра (center.y - p.y) <= V_DETECT
     */
    private boolean isPositionInDetectZone(BlockPos p, BlockPos center) {
        if (center == null || p == null) return false;
        int dx = Math.abs(p.getX() - center.getX());
        int dz = Math.abs(p.getZ() - center.getZ());
        int dyDown = center.getY() - p.getY(); // положительное если p ниже center

        return dx <= H_DETECT && dz <= H_DETECT && dyDown >= 0 && dyDown <= V_DETECT;
    }

    /**
     * Ищет первый непустой блок под start (включая start.below()), до заданной глубины.
     * Возвращает найденную позицию блока или null.
     */
    private BlockPos findFirstNonAirBelow(BlockPos start, int maxDepth) {
        if (start == null || world == null) return null;
        for (int dy = 0; dy <= maxDepth; dy++) {
            BlockPos p = start.below(dy);
            if (!world.getBlockState(p).isAir()) {
                return p;
            }
        }
        return null;
    }

    @Override
    public void resolveSpell() {
        LOGGER.info("WatchingEntityBlockDelayedEvent.resolveSpell called (resolvedScheduled={}, manuallyResolved={}, resumePending={}) for watched={}",
                resolvedScheduled, manuallyResolved, resumePending, watched != null ? watched.getType() : "null");

        synchronized (this) {
            if (manuallyResolved) {
                LOGGER.debug("resolveSpell: manuallyResolved=true -> skipping super.resolveSpell()");
                return;
            }

            // Если это первый вход в resolveSpell после scheduleResolveNextTickAndForceTick(),
            // откладываем фактический resume на RESUME_DELAY_TICKS и сохраняем usePos.
            if (!resumePending) {
                // Найдём usePos как обычно, но **не** будем вызывать resume сейчас — отложим.
                BlockPos usePos = null;

                try {
                    // 1) Попытка найти изменённый блок в baselineBlocks (приоритет)
                    for (Map.Entry<BlockPos, Block> entry : baselineBlocks.entrySet()) {
                        BlockPos p = entry.getKey();
                        Block prev = entry.getValue();
                        if (p == null) continue;
                        var stateNow = world.getBlockState(p);
                        Block now = stateNow.getBlock();
                        if (!stateNow.isAir() && !now.equals(prev) && isPositionInDetectZone(p, lastKnownPos)) {
                            usePos = p;
                            LOGGER.info("resolveSpell (defer): found changed block at {} now={} baseline={}", p, now, prev);
                            break;
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.debug("resolveSpell (defer): baseline scan threw: {}", t.toString());
                }

                // fallback to ground or lastKnownPos
                if (usePos == null && lastKnownPos != null) {
                    BlockPos ground = findFirstNonAirBelow(lastKnownPos, Math.max(V_DETECT, 6));
                    if (ground != null) {
                        usePos = ground;
                        LOGGER.info("resolveSpell (defer): fallback to ground-scan result {}", usePos);
                    } else {
                        usePos = lastKnownPos;
                        LOGGER.info("resolveSpell (defer): no baseline/ground found -> falling back to lastKnownPos {}", usePos);
                    }
                }

                // Сохраним позицию и запланируем отложенное resume (RESUME_DELAY_TICKS)
                deferredUsePos = usePos;
                resumePending = true;
                // Устанавливаем duration, чтобы событие снова истекло через RESUME_DELAY_TICKS и resolveSpell() вызвался повторно.
                this.duration = Math.max(1, RESUME_DELAY_TICKS);
                LOGGER.info("resolveSpell: deferring actual resolver.resume(world) by {} ticks (deferredUsePos={})", RESUME_DELAY_TICKS, deferredUsePos);
                return;
            }

            // Если resumePending == true -> это второй вход, выполняем фактический replace + resume
            // Ставим manuallyResolved чтобы избежать повторных вызовов
            manuallyResolved = true;
        }

        // ниже — фактическая логика замены hitResult и вызова resolver.resume(world)
        if (world == null) {
            LOGGER.warn("resolveSpell: world == null -> skipping resume");
            return;
        }

        BlockPos usePos = deferredUsePos;
        if (usePos == null) {
            usePos = lastKnownPos != null ? lastKnownPos : BlockPos.ZERO;
        }

        boolean replacedHit = false;
        try {
            if (resolver.hitResult == null ||
                    (resolver.hitResult instanceof EntityHitResult eRes && (eRes.getEntity() == null || eRes.getEntity().isRemoved()))) {
                BlockHitResult bhr = new BlockHitResult(Vec3.atCenterOf(usePos), net.minecraft.core.Direction.UP, usePos, false);
                Object old = null;
                try { old = resolver.hitResult; } catch (Throwable ignored) {}
                resolver.hitResult = bhr;
                replacedHit = true;
                LOGGER.info("resolveSpell: replaced resolver.hitResult with BlockHitResult at {} (was {}).", usePos, old);
            } else if (resolver.hitResult instanceof EntityHitResult eRes) {
                // если это EntityHitResult, но entity - mage-like, заменим
                try {
                    Entity target = eRes.getEntity();
                    boolean entityRemoved = (target == null || target.isRemoved());
                    boolean isMageLike = false;
                    if (target != null && target.getType() != null) {
                        String typeStr = target.getType().toString().toLowerCase();
                        if (typeStr.contains("mage") || typeStr.contains("mage_block") || typeStr.contains("enchanted")) {
                            isMageLike = true;
                        }
                    }
                    if (entityRemoved || isMageLike) {
                        BlockHitResult bhr = new BlockHitResult(Vec3.atCenterOf(usePos), net.minecraft.core.Direction.UP, usePos, false);
                        Object old = null;
                        try { old = resolver.hitResult; } catch (Throwable ignored) {}
                        resolver.hitResult = bhr;
                        replacedHit = true;
                        LOGGER.info("resolveSpell: replaced resolver.hitResult (was EntityHitResult to {} removed={} mageLike={}) with BlockHitResult at {}.",
                                target != null ? target.getType() : "null", entityRemoved, isMageLike, usePos);
                    } else {
                        LOGGER.info("resolveSpell: resolver.hitResult is present and not removed and not mage-like -> leaving as-is: {}", resolver.hitResult);
                    }
                } catch (Throwable t) {
                    LOGGER.warn("resolveSpell: error while inspecting EntityHitResult -> leaving hitResult as-is: {}", t.toString());
                }
            } else {
                LOGGER.info("resolveSpell: resolver.hitResult is present and not EntityHitResult -> leaving as-is: {}", resolver.hitResult);
            }
        } catch (Throwable t) {
            LOGGER.warn("resolveSpell: unexpected error during hitResult replacement: {}", t.toString());
        }

        // resume через guard
        try {
            LOGGER.info("resolveSpell: calling resolver.resume(world). final usePos={} replacedHit={}", usePos, replacedHit);
            if (guard == null || guard.compareAndSet(false, true)) {
                resolver.resume(world);
                LOGGER.info("resolveSpell: resolver.resume(world) completed successfully.");
            } else {
                LOGGER.info("resolveSpell: skip resume because guard already set.");
            }
        } catch (Throwable t) {
            LOGGER.error("resolveSpell: resolver.resume(world) threw exception:", t);
        } finally {
            // очистим поля на всякий случай
            resumePending = false;
            deferredUsePos = null;
            resolvedScheduled = false;
            duration = 0;
        }
    }

    @Override
    public boolean isExpired() {
        return manuallyResolved || super.isExpired();
    }
}
