package com.example.arslauria.events;

import com.hollingsworth.arsnouveau.api.event.DelayedSpellEvent;
import com.hollingsworth.arsnouveau.api.spell.SpellResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
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
 * Поведение:
 *  - делает baseline snapshot вокруг initialPos (R)
 *  - детектит появление нового блока в detect zone
 *  - при детекте пытается shortResolveNow (немедленный replace + resolver.resume(world))
 *    если shortResolveNow не удаётся — планирует deferred resolve (RESOLVE_DELAY_TICKS),
 *    внутри resolveSpell откладывает фактическое resume на RESUME_DELAY_TICKS (даёт шанс
 *    соседним событиям скорректировать hitResult) и затем вызывает resolver.resume(world) под guard.
 *
 * Улучшения:
 *  - минимальные задержки (RESOLVE_DELAY_TICKS=1 / RESUME_DELAY_TICKS=1) для быстрого реагирования
 *  - guard устанавливается после успешного resume (страховка)
 *  - лог об entering grace выводится один раз
 *  - rate-limited debug/info логи
 */
public class WatchingEntityBlockDelayedEvent extends DelayedSpellEvent {
    private static final Logger LOGGER = LogManager.getLogger("arslauria-impact");

    private final Entity watched;
    private boolean resolvedScheduled = false;
    private boolean manuallyResolved = false;

    private final Map<BlockPos, Block> baselineBlocks = new HashMap<>();
    private static final int R = 3; // baseline radius (3 -> 7x7x7 snapshot)
    private BlockPos lastKnownPos = null;

    // delays (уменьшены для более быстрой реакции)
    private static final int RESOLVE_DELAY_TICKS = 1; // тики до первого вызова resolveSpell
    private static final int RESUME_DELAY_TICKS = 1;  // дополнительная задержка внутри resolveSpell перед resume

    private static final int GRACE_TICKS = 3; // not heavily used now

    private final AtomicBoolean guard; // may be null

    // detection tuning
    private static final int H_DETECT = 1; // horizontal radius
    private static final int V_DETECT = 4; // how far below the center we consider relevant

    // state for deferred resume
    private boolean resumePending = false;
    private BlockPos deferredUsePos = null;

    private Block expectedBlock; // if known from FallingBlockEntity

    // for suppressing repeated entering-grace log
    private boolean enteredGrace = false;

    // small counters for rate-limited logging / debug
    private int tickCounter = 0;
    private BlockPos lastDetectedPos = null;
    private int lastDetectTick = -9999;

    private boolean resolvedOnce = false;

    /**
     * Конструктор с guard (может быть null).
     */
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

        Block tempExpected = null;
        try {
            if (watched instanceof FallingBlockEntity fbe) {
                tempExpected = fbe.getBlockState().getBlock();
            }
        } catch (Throwable t) {
            LOGGER.debug("Failed to extract expectedBlock from watched: {}", t.toString());
            tempExpected = null;
        }
        this.expectedBlock = tempExpected;

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

    /**
     * Backward-compatible constructor (no guard).
     */
    public WatchingEntityBlockDelayedEvent(Entity watched, HitResult initialHit, Level world, SpellResolver resolver, int timeoutTicks) {
        this(watched, initialHit, world, resolver, timeoutTicks, new AtomicBoolean(false));
    }

    /**
     * Попытка немедленного резюма: заменяем resolver.hitResult на BlockHitResult для usePos и вызываем resolver.resume(world)
     * под защитой guard. Если guard уже занят, возвращаем false (не выполнено).
     * Возвращает true если resume успешно выполнен (или был выполнен кем-то в этот момент).
     */
    private boolean shortResolveNow(BlockPos usePos) {
        if (world == null || resolver == null) return false;
        if (usePos == null) usePos = lastKnownPos != null ? lastKnownPos : BlockPos.ZERO;

        // Попробуем выбрать приоритетную позицию: если baseline показывает изменённую позицию — используем её.
        try {
            for (Map.Entry<BlockPos, Block> entry : baselineBlocks.entrySet()) {
                BlockPos p = entry.getKey();
                Block prev = entry.getValue();
                if (p == null) continue;
                var st = world.getBlockState(p);
                Block now = st.getBlock();
                if (!st.isAir() && !now.equals(prev) && isPositionInDetectZone(p, lastKnownPos)) {
                    usePos = p;
                    break;
                }
            }
        } catch (Throwable ignored) {}

        try {
            // Заменим hitResult если это необходимо
            boolean replaced = false;
            if (resolver.hitResult == null ||
                    (resolver.hitResult instanceof EntityHitResult eRes && (eRes.getEntity() == null || eRes.getEntity().isRemoved()))) {
                BlockHitResult bhr = new BlockHitResult(Vec3.atCenterOf(usePos), net.minecraft.core.Direction.UP, usePos, false);
                Object old = null;
                try { old = resolver.hitResult; } catch (Throwable ignored) {}
                resolver.hitResult = bhr;
                replaced = true;
                LOGGER.info("shortResolveNow: prepared BlockHitResult at {} (old={})", usePos, old);
            }

            // Попытаемся выполнить resume под guard (если guard==null — просто резюмиим)
            if (guard == null || guard.compareAndSet(false, true)) {
                LOGGER.info("shortResolveNow: calling resolver.resume(world) (usePos={}, replacedHit={})", usePos, replaced);
                resolver.resume(world);
                LOGGER.info("shortResolveNow: resolver.resume(world) returned successfully.");
                // дополнительная страховка: явно установим guard = true, чтобы posEvent увидел это
                try { if (guard != null) guard.set(true); } catch (Throwable ignored) {}
                return true;
            } else {
                LOGGER.debug("shortResolveNow: guard already set -> not resuming here.");
                return false;
            }
        } catch (Throwable t) {
            LOGGER.warn("shortResolveNow: resolver.resume(world) threw: {}", t.toString());
            return false;
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

            // CASE A: entity still exists -> try immediate detection & short resolve
            if (watched != null && !watched.isRemoved()) {
                if (checkForNewBlockAround(lastKnownPos)) {
                    if (!resolvedScheduled) {
                        LOGGER.info("WatchingEntityBlockDelayedEvent: detected NEW block near entity at {} -> try immediate shortResolveNow", lastKnownPos);
                        boolean did = false;
                        try {
                            // pick chosen pos from baseline if present
                            BlockPos chosen = null;
                            try {
                                for (Map.Entry<BlockPos, Block> entry : baselineBlocks.entrySet()) {
                                    BlockPos p = entry.getKey();
                                    Block prev = entry.getValue();
                                    if (p == null) continue;
                                    var st = world.getBlockState(p);
                                    Block now = st.getBlock();
                                    if (!st.isAir() && !now.equals(prev) && isPositionInDetectZone(p, lastKnownPos)) {
                                        chosen = p;
                                        break;
                                    }
                                }
                            } catch (Throwable ignored) {}
                            if (chosen == null) chosen = lastKnownPos;

                            did = shortResolveNow(chosen);
                        } catch (Throwable t) {
                            LOGGER.warn("WatchingEntityBlockDelayedEvent: immediate resolve attempt threw: {}", t.toString());
                        }

                        if (did) {
                            manuallyResolved = true;
                            duration = 0;
                            LOGGER.info("WatchingEntityBlockDelayedEvent: immediate resolve succeeded -> event consumed (pos={})", lastKnownPos);
                            return;
                        } else {
                            LOGGER.info("WatchingEntityBlockDelayedEvent: immediate resolve not performed -> scheduling standard deferred resolve (pos={})", lastKnownPos);
                            scheduleResolveNextTickAndForceTick();
                            return;
                        }
                    } else {
                        // resolve уже запланирован — не спамим логами
                        LOGGER.debug("WatchingEntityBlockDelayedEvent: detected NEW block near {} but resolve already scheduled -> skip logging", lastKnownPos);
                        return;
                    }
                }

                // check for near-zero vy and non-air below -> schedule resolve
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

                super.tick(serverSide);
                return;
            }

            // CASE B: entity was removed -> grace checks (log once)
            if (!enteredGrace) {
                LOGGER.info("WatchingEntityBlockDelayedEvent: watched removed -> entering grace checks at lastKnownPos={}", lastKnownPos);
                enteredGrace = true;
            } else {
                LOGGER.debug("WatchingEntityBlockDelayedEvent: in grace checks at lastKnownPos={}", lastKnownPos);
            }

            if (checkForNewBlockAround(lastKnownPos)) {
                LOGGER.info("WatchingEntityBlockDelayedEvent: new block detected during grace -> try immediate resolve (pos={})", lastKnownPos);
                boolean did = false;
                try {
                    BlockPos chosen = null;
                    try {
                        for (Map.Entry<BlockPos, Block> entry : baselineBlocks.entrySet()) {
                            BlockPos p = entry.getKey();
                            Block prev = entry.getValue();
                            if (p == null) continue;
                            var st = world.getBlockState(p);
                            Block now = st.getBlock();
                            if (!st.isAir() && !now.equals(prev) && isPositionInDetectZone(p, lastKnownPos)) {
                                chosen = p;
                                break;
                            }
                        }
                    } catch (Throwable ignored) {}
                    if (chosen == null) chosen = lastKnownPos;

                    did = shortResolveNow(chosen);
                } catch (Throwable t) {
                    LOGGER.warn("WatchingEntityBlockDelayedEvent: immediate resolve attempt threw: {}", t.toString());
                }

                if (did) {
                    manuallyResolved = true;
                    duration = 0;
                    LOGGER.info("WatchingEntityBlockDelayedEvent: immediate resolve succeeded -> event consumed (pos={})", lastKnownPos);
                    return;
                } else {
                    LOGGER.info("WatchingEntityBlockDelayedEvent: immediate resolve not performed during grace -> scheduling standard deferred resolve (pos={})", lastKnownPos);
                    scheduleResolveNextTickAndForceTick();
                    return;
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
            resolvedScheduled = true;
            // откладываем на RESOLVE_DELAY_TICKS (обычно 1) — затем resolveSpell() выполнит отложенный RESUME_DELAY_TICKS
            this.duration = Math.max(1, RESOLVE_DELAY_TICKS);
            LOGGER.info("WatchingEntityBlockDelayedEvent: resolve scheduled (duration set to {}). Will reach resolveSpell() in {} ticks.", this.duration, RESOLVE_DELAY_TICKS);
        }
        // НЕ вызывать super.tick(true) — пусть событие уменьшится естественным образом в следующих тиках.
    }

    /**
     * Новая, более строгая проверка.
     */
    private boolean checkForNewBlockAround(BlockPos center) {
        if (center == null || world == null) return false;
        for (int dx = -R; dx <= R; dx++) {
            for (int dy = -R; dy <= R; dy++) {
                for (int dz = -R; dz <= R; dz++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    BlockState state = world.getBlockState(p);
                    Block now = state.getBlock();
                    Block baseline = baselineBlocks.get(p);

                    boolean isWithinDetectZone = isPositionInDetectZone(p, center);

                    if (baseline == null) {
                        if (!state.isAir()) {
                            if (isWithinDetectZone) {
                                if (resolvedScheduled) return true;
                                if (lastDetectedPos != null && lastDetectedPos.equals(p) && tickCounter - lastDetectTick < 5) return true;
                                lastDetectedPos = p;
                                lastDetectTick = tickCounter;
                                LOGGER.debug("checkForNewBlockAround: new block at {} (no baseline) now={} -> DETECT (within detect zone)", p, now);
                                return true;
                            } else {
                                baselineBlocks.put(p, now);
                                LOGGER.debug("checkForNewBlockAround: new block at {} (no baseline) now={} -> IGNORED (outside detect zone)", p, now);
                                continue;
                            }
                        } else {
                            baselineBlocks.put(p, now);
                            continue;
                        }
                    }

                    if (!now.equals(baseline) && !state.isAir()) {
                        if (isWithinDetectZone) {
                            if (resolvedScheduled) return true;
                            if (lastDetectedPos != null && lastDetectedPos.equals(p) && tickCounter - lastDetectTick < 5) return true;
                            lastDetectedPos = p;
                            lastDetectTick = tickCounter;
                            LOGGER.debug("checkForNewBlockAround: new block at {} now={} baseline={} -> DETECT (within detect zone)", p, now, baseline);
                            return true;
                        } else {
                            LOGGER.debug("checkForNewBlockAround: new block at {} now={} baseline={} -> IGNORED (outside detect zone)", p, now, baseline);
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
                BlockPos usePos = null;

                try {
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

                if (usePos == null && lastKnownPos != null) {
                    BlockPos best = null;
                    int searchRad = 2;
                    for (int dx = -searchRad; dx <= searchRad; dx++) {
                        for (int dz = -searchRad; dz <= searchRad; dz++) {
                            BlockPos colTop = lastKnownPos.offset(dx, 0, dz);
                            BlockPos found = findFirstNonAirBelow(colTop.above(6), 12);
                            if (found != null) {
                                if (best == null || found.getY() > best.getY()) {
                                    best = found;
                                } else if (expectedBlock != null && world.getBlockState(found).getBlock().equals(expectedBlock)) {
                                    best = found;
                                }
                            }
                        }
                    }
                    if (best != null) {
                        usePos = best;
                        LOGGER.info("resolveSpell (defer): neighborhood-scan chose {}", usePos);
                    } else {
                        BlockPos ground = findFirstNonAirBelow(lastKnownPos, Math.max(V_DETECT, 6));
                        usePos = ground != null ? ground : lastKnownPos;
                        LOGGER.info("resolveSpell (defer): fallback to ground/lastKnownPos {}", usePos);
                    }
                }

                deferredUsePos = usePos;
                resumePending = true;
                this.duration = Math.max(1, RESUME_DELAY_TICKS);
                LOGGER.info("resolveSpell: deferring actual resolver.resume(world) by {} ticks (deferredUsePos={})", RESUME_DELAY_TICKS, deferredUsePos);
                return;
            }

            // Если resumePending == true -> это второй вход, выполняем фактический replace + resume
            manuallyResolved = true;
        }

        if (world == null) {
            LOGGER.warn("resolveSpell: world == null -> skipping resume");
            return;
        }

        BlockPos usePos = deferredUsePos != null ? deferredUsePos : (lastKnownPos != null ? lastKnownPos : BlockPos.ZERO);
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
                try { if (guard != null) guard.set(true); } catch (Throwable ignored) {}
            } else {
                LOGGER.info("resolveSpell: skip resume because guard already set.");
            }
        } catch (Throwable t) {
            LOGGER.error("resolveSpell: resolver.resume(world) threw exception:", t);
        } finally {
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
