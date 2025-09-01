package com.example.arslauria.glyphs.events;

import com.hollingsworth.arsnouveau.api.event.DelayedSpellEvent;
import com.hollingsworth.arsnouveau.api.spell.SpellResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Более агрессивный DelayedSpellEvent, который наблюдает за BlockPos + соседями.
 * Логирует каждую значимую проверку и таймаут.
 */
public class WatchingBlockPosDelayedEvent extends DelayedSpellEvent {
    private static final Logger LOGGER = LogManager.getLogger("arslauria-impact");

    private final BlockPos pos;
    private boolean manuallyResolved = false;
    private int tickCounter = 0;

    public WatchingBlockPosDelayedEvent(BlockPos pos, Level world, SpellResolver resolver, int timeoutTicks) {
        super(timeoutTicks, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false), world, resolver);
        this.pos = pos;
        LOGGER.info("WatchingBlockPosDelayedEvent created for pos {} with timeoutTicks={}", pos, timeoutTicks);
    }

    @Override
    public void tick(boolean serverSide) {
        // минимальная защита
        if (manuallyResolved || world == null) return;

        // уменьшаем частоту логирования — лог каждые 5 тиков, но проверяем каждый тик
        tickCounter++;

        if (serverSide) {
            try {
                // Проверяем центральную позицию и ближайшие соседи (радиус 1)
                BlockPos[] toCheck = new BlockPos[] {
                        pos,
                        pos.above(),
                        pos.below(),
                        pos.north(),
                        pos.south(),
                        pos.east(),
                        pos.west(),
                        pos.north().east(),
                        pos.north().west(),
                        pos.south().east(),
                        pos.south().west()
                };

                boolean found = false;
                String foundInfo = null;
                for (BlockPos p : toCheck) {
                    var state = world.getBlockState(p);
                    if (!state.isAir()) {
                        found = true;
                        foundInfo = p + " -> " + state.getBlock();
                        break;
                    }
                }

                if (found) {
                    LOGGER.info("WatchingBlockPosDelayedEvent: block detected near {}: {} -> resume", pos, foundInfo);
                    resolver.resume(world);
                    manuallyResolved = true;
                    duration = 0;
                    return;
                }

                // Логируем прогресс каждые 5 тиков для отладки (чтобы не спамить)
                if (tickCounter % 5 == 0) {
                    LOGGER.debug("WatchingBlockPosDelayedEvent tick: no block at/around {} (ticks left={})", pos, duration);
                }

                // Если дошли до super.tick, duration уменьшится; super.resolveSpell() сработает при истечении.
            } catch (Throwable t) {
                LOGGER.warn("WatchingBlockPosDelayedEvent tick exception: {}", t.toString());
            }
        }

        super.tick(serverSide);
    }

    @Override
    public void resolveSpell() {
        LOGGER.info("WatchingBlockPosDelayedEvent.resolveSpell called (manuallyResolved={}) for pos {}", manuallyResolved, pos);
        if (manuallyResolved) return;
        super.resolveSpell();
    }

    @Override
    public boolean isExpired() {
        return manuallyResolved || super.isExpired();
    }
}
