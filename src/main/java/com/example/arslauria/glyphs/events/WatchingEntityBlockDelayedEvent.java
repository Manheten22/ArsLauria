package com.example.arslauria.glyphs.events;

import com.hollingsworth.arsnouveau.api.event.DelayedSpellEvent;
import com.hollingsworth.arsnouveau.api.spell.SpellResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * WatchingEntityBlockDelayedEvent — ждёт появления блока под/вокруг watched entity.
 * При детекции нового блока ставит duration = 1 и немедленно вызывает super.tick(true),
 * чтобы движок снизил duration и вызвал resolveSpell() в том же тике.
 *
 * Это устраняет ситуацию, когда tick() возвращает до super.tick(...) и события застревают.
 */
public class WatchingEntityBlockDelayedEvent extends DelayedSpellEvent {
    private static final Logger LOGGER = LogManager.getLogger("arslauria-impact");

    private final Entity watched;
    private boolean resolvedScheduled = false;
    private boolean manuallyResolved = false;
    private int tickCounter = 0;

    private final Map<BlockPos, Block> baselineBlocks = new HashMap<>();
    private static final int R = 1; // radius (3x3x3)
    private BlockPos lastKnownPos = null;

    private static final int GRACE_TICKS = 3;
    private boolean removalDetected = false;
    private int removalTickCounter = 0;

    public WatchingEntityBlockDelayedEvent(Entity watched, HitResult initialHit, Level world, SpellResolver resolver, int timeoutTicks) {
        super(timeoutTicks,
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
                if (checkForNewBlockAround(lastKnownPos)) {
                    LOGGER.info("WatchingEntityBlockDelayedEvent: detected NEW block near entity at {} -> schedule resolve next tick", lastKnownPos);
                    scheduleResolveNextTickAndForceTick();
                    return;
                }

                var vel = watched.getDeltaMovement();
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
                            lastKnownPos, watched != null ? watched.getDeltaMovement().y : Double.NaN, duration);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("WatchingEntityBlockDelayedEvent tick exception: {}", t.toString());
        }

        super.tick(serverSide);
    }

    /**
     * Устанавливает duration=1 и немедленно вызывает super.tick(true) (в серверном потоке),
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

        // Немедленный вызов super.tick(true) — выполняется в том же потоке, т.к. мы уже на серверном потоке.
        try {
            super.tick(true); // это уменьшит duration и вызовет resolveSpell() если duration <= 0
        } catch (Throwable t) {
            LOGGER.warn("scheduleResolveNextTickAndForceTick: super.tick(true) threw: {}", t.toString());
        }
    }

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
            manuallyResolved = true;
        }
        super.resolveSpell(); // this will call resolver.resume(world)
    }

    @Override
    public boolean isExpired() {
        return manuallyResolved || super.isExpired();
    }
}
