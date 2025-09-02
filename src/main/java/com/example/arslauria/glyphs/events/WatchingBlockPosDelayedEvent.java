package com.example.arslauria.glyphs.events;

import com.hollingsworth.arsnouveau.api.event.DelayedSpellEvent;
import com.hollingsworth.arsnouveau.api.spell.SpellResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WatchingBlockPosDelayedEvent с фильтрацией найденных блоков по expectedBlock.
 * Теперь: при обнаружении guard == true событие сразу истекает (manuallyResolved = true).
 */
public class WatchingBlockPosDelayedEvent extends DelayedSpellEvent {
    private static final Logger LOGGER = LogManager.getLogger("arslauria-impact");

    private final BlockPos pos;
    private final int radius; // радиус в XZ
    private final int verticalBelow;
    private final int verticalAbove;
    private final AtomicBoolean guard; // может быть null
    private final Block expectedBlock; // может быть null — если не передали ожидаемый блок

    private boolean manuallyResolved = false;
    private int tickCounter = 0;

    public WatchingBlockPosDelayedEvent(BlockPos pos, Level world, SpellResolver resolver, int timeoutTicks,
                                        int radius, int verticalBelow, int verticalAbove, AtomicBoolean guard, Block expectedBlock) {
        super(timeoutTicks, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false), world, resolver);
        this.pos = pos;
        this.radius = Math.max(0, radius);
        this.verticalBelow = Math.max(0, verticalBelow);
        this.verticalAbove = Math.max(0, verticalAbove);
        this.guard = guard;
        this.expectedBlock = expectedBlock;
        LOGGER.info("WatchingBlockPosDelayedEvent created for pos {} with timeoutTicks={} radius={} vBelow={} vAbove={} expectedBlock={}",
                pos, timeoutTicks, this.radius, this.verticalBelow, this.verticalAbove,
                expectedBlock != null ? expectedBlock.toString() : "null");
    }

    // обратная совместимость
    public WatchingBlockPosDelayedEvent(BlockPos pos, Level world, SpellResolver resolver, int timeoutTicks) {
        this(pos, world, resolver, timeoutTicks, 1, 1, 1, new AtomicBoolean(false), null);
    }

    @Override
    public void tick(boolean serverSide) {
        if (manuallyResolved || world == null) return;

        // --- НОВАЯ РАННЯЯ ПРОВЕРКА: если guard уже выставлен где-то, просто истекаем
        if (guard != null && guard.get()) {
            LOGGER.debug("WatchingBlockPosDelayedEvent: guard already set -> expiring event for pos {}", pos);
            manuallyResolved = true;
            duration = 0;
            return;
        }
        // ---------------------------------------------------------------

        tickCounter++;

        if (serverSide) {
            try {
                boolean found = false;
                BlockPos foundPos = null;
                Block foundBlock = null;
                String foundInfo = null;

                for (int dx = -radius; dx <= radius && !found; dx++) {
                    for (int dz = -radius; dz <= radius && !found; dz++) {
                        for (int dy = -verticalBelow; dy <= verticalAbove && !found; dy++) {
                            BlockPos p = pos.offset(dx, dy, dz);
                            var state = world.getBlockState(p);
                            if (!state.isAir()) {
                                Block now = state.getBlock();

                                if (expectedBlock != null) {
                                    if (now.equals(expectedBlock)) {
                                        found = true;
                                        foundPos = p;
                                        foundBlock = now;
                                        foundInfo = p + " -> " + now;
                                    } else {
                                        continue;
                                    }
                                } else {
                                    if (now.defaultBlockState().is(BlockTags.LEAVES) || now.defaultBlockState().is(BlockTags.FLOWERS)) {
                                        continue;
                                    }
                                    found = true;
                                    foundPos = p;
                                    foundBlock = now;
                                    foundInfo = p + " -> " + now;
                                }
                            }
                        }
                    }
                }

                if (found && foundPos != null) {
                    LOGGER.info("WatchingBlockPosDelayedEvent: block detected near {} (radius={}) : {} -> attempt resolve (expected={})",
                            pos, radius, foundInfo, expectedBlock != null ? expectedBlock.toString() : "null");
                    attemptResolve(foundPos, foundBlock, foundInfo);
                    return;
                }

                if (tickCounter % 5 == 0) {
                    LOGGER.debug("WatchingBlockPosDelayedEvent tick: no block at/around {} (radius={}) (ticks left={})", pos, radius, duration);
                }

            } catch (Throwable t) {
                LOGGER.warn("WatchingBlockPosDelayedEvent tick exception: {}", t.toString());
            }
        }

        super.tick(serverSide);
    }

    private void attemptResolve(BlockPos foundPos, Block foundBlock, String info) {
        try {
            if (expectedBlock != null) {
                var stateAt = world.getBlockState(foundPos);
                if (stateAt.isAir() || !stateAt.getBlock().equals(expectedBlock)) {
                    LOGGER.info("WatchingBlockPosDelayedEvent: expected block mismatch at {} (expected={} now={}) — пропускаем",
                            foundPos, expectedBlock, stateAt.getBlock());
                    return;
                }
            }

            Object oldHit = null;
            try { oldHit = resolver.hitResult; } catch (Throwable ignored) {}

            var bhr = new BlockHitResult(Vec3.atCenterOf(foundPos), Direction.UP, foundPos, false);
            resolver.hitResult = bhr;
            LOGGER.info("WatchingBlockPosDelayedEvent: replaced resolver.hitResult with BlockHitResult at {} (old={})", foundPos, oldHit);

            if (guard == null || guard.compareAndSet(false, true)) {
                LOGGER.info("WatchingBlockPosDelayedEvent: performing resolver.resume(world) (info={})", info);
                resolver.resume(world);
            } else {
                LOGGER.info("WatchingBlockPosDelayedEvent: skip resume because guard already set (info={})", info);
            }
        } catch (Throwable t) {
            LOGGER.warn("WatchingBlockPosDelayedEvent: resolver.resume or hitResult set threw: {}", t.toString());
        } finally {
            manuallyResolved = true;
            duration = 0;
        }
    }

    @Override
    public void resolveSpell() {
        LOGGER.info("WatchingBlockPosDelayedEvent.resolveSpell called (manuallyResolved={}) for pos {}", manuallyResolved, pos);
        if (manuallyResolved) return;
        attemptResolve(pos, world.getBlockState(pos).getBlock(), "timeout-resolve");
        super.resolveSpell();
    }

    @Override
    public boolean isExpired() {
        return manuallyResolved || super.isExpired();
    }
}
