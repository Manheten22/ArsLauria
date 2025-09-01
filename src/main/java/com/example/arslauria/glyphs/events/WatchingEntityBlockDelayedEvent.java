package com.example.arslauria.glyphs.events;

import com.hollingsworth.arsnouveau.api.event.DelayedSpellEvent;
import com.hollingsworth.arsnouveau.api.spell.SpellResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * DelayedSpellEvent, наблюдающий одновременно за сущностью и за блоками вокруг её текущей позиции.
 * Резолвит спелл, когда:
 *  - watched == null || watched.isRemoved()
 *  - или когда в текущей позиции сущности (или соседях) появился непустой блок
 *  - или при достижении таймаута (дефолтный behavior через super)
 */
public class WatchingEntityBlockDelayedEvent extends DelayedSpellEvent {
    private static final Logger LOGGER = LogManager.getLogger("arslauria-impact");

    private final Entity watched;
    private boolean manuallyResolved = false;
    private int tickCounter = 0;

    /**
     * @param watched     сущность, за которой наблюдаем (например FallingBlockEntity или enchanted_mage_block)
     * @param initialHit  HitResult для конструктора DelayedSpellEvent (можно передать rayTraceResult)
     * @param world       мир
     * @param resolver    SpellResolver
     * @param timeoutTicks таймаут в тиках
     */
    public WatchingEntityBlockDelayedEvent(Entity watched, HitResult initialHit, Level world, SpellResolver resolver, int timeoutTicks) {
        super(timeoutTicks, initialHit, world, resolver);
        this.watched = watched;
        LOGGER.info("WatchingEntityBlockDelayedEvent created for entity {} at initialPos {} with timeoutTicks={}",
                watched != null ? watched.getType() : "null", watched != null ? watched.blockPosition() : "null", timeoutTicks);
    }

    @Override
    public void tick(boolean serverSide) {
        if (manuallyResolved || world == null) return;
        tickCounter++;

        if (serverSide) {
            try {
                // 1) Если сущность удалена — резолвим немедленно
                if (watched == null || watched.isRemoved()) {
                    LOGGER.info("WatchingEntityBlockDelayedEvent: watched is null/removed -> resume");
                    resolver.resume(world);
                    manuallyResolved = true;
                    duration = 0;
                    return;
                }

                // 2) Берём текущую позицию сущности (динамически) и проверяем блоки вокруг неё
                BlockPos current = watched.blockPosition();
                BlockPos[] toCheck = new BlockPos[] {
                        current,
                        current.above(),
                        current.below(),
                        current.north(),
                        current.south(),
                        current.east(),
                        current.west(),
                        current.north().east(),
                        current.north().west(),
                        current.south().east(),
                        current.south().west()
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
                    LOGGER.info("WatchingEntityBlockDelayedEvent: block detected near entity at {}: {} -> resume", current, foundInfo);
                    resolver.resume(world);
                    manuallyResolved = true;
                    duration = 0;
                    return;
                }

                // 3) Эвристика: если вертикальная скорость мала (почти ноль), считаем, что приземлился
                Vec3 vel = watched.getDeltaMovement();
                if (vel != null) {
                    double vy = vel.y;
                    if (Math.abs(vy) < 0.02) {
                        // проверим блок чуть ниже как дополнительную эвристику
                        BlockPos below = current.below();
                        var belowState = world.getBlockState(below);
                        if (!belowState.isAir()) {
                            LOGGER.info("WatchingEntityBlockDelayedEvent: low vy={} and non-air below at {} -> resume", vy, below);
                            resolver.resume(world);
                            manuallyResolved = true;
                            duration = 0;
                            return;
                        }
                    }
                }

                // 4) Логирование для отладки (каждые 5 тиков)
                if (tickCounter % 5 == 0) {
                    LOGGER.debug("WatchingEntityBlockDelayedEvent tick: entity at {} (vy={}), no block near (duration={})",
                            current, vel != null ? vel.y : Double.NaN, duration);
                }
            } catch (Throwable t) {
                LOGGER.warn("WatchingEntityBlockDelayedEvent tick exception: {}", t.toString());
            }
        }

        // базовая логика уменьшения duration / таймаут
        super.tick(serverSide);
    }

    @Override
    public void resolveSpell() {
        LOGGER.info("WatchingEntityBlockDelayedEvent.resolveSpell called (manuallyResolved={}) for watched={}", manuallyResolved, watched != null ? watched.getType() : "null");
        if (manuallyResolved) return;
        super.resolveSpell();
    }

    @Override
    public boolean isExpired() {
        return manuallyResolved || super.isExpired();
    }
}
