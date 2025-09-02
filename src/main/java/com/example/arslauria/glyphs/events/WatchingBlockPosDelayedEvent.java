package com.example.arslauria.glyphs.events;

import com.hollingsworth.arsnouveau.api.event.DelayedSpellEvent;
import com.hollingsworth.arsnouveau.api.spell.SpellResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WatchingBlockPosDelayedEvent — ждёт появления блока в заданном радиусе вокруг целевой позиции.
 * Поведение:
 *  - первая задержка RESOLVE_DELAY_TICKS между детектом и первым вызовом resolveSpell()
 *  - внутри resolveSpell() откладываем фактический resolver.resume(world) ещё на RESUME_DELAY_TICKS
 *  - поддерживает общий AtomicBoolean guard для координации с другими ворчерами
 */
public class WatchingBlockPosDelayedEvent extends DelayedSpellEvent {
    private static final Logger LOGGER = LogManager.getLogger("arslauria-impact");

    private final BlockPos center;
    private final int radius;
    private final int vBelow;
    private final int vAbove;
    private final Block expectedBlock; // may be null

    // delays
    private static final int RESOLVE_DELAY_TICKS = 0; // тики до первого вызова resolveSpell (оставьте 0 если вы вызываете scheduleResolveNextTickAndForceTick со своим duration)
    private static final int RESUME_DELAY_TICKS = 10; // 10 тиков перед фактическим resolver.resume(world)

    // guard для координации с другими похожими событиями
    private final AtomicBoolean guard; // may be null

    // fields for deferred resume
    private boolean resumePending = false;
    private BlockPos deferredUsePos = null;
    private boolean manuallyResolved = false;
    private boolean resolvedScheduled = false;

    public WatchingBlockPosDelayedEvent(BlockPos center, int radius, int vBelow, int vAbove, Block expectedBlock,
                                        HitResult initialHit, Level world, SpellResolver resolver, int timeoutTicks,
                                        AtomicBoolean guard) {
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
        this.center = center;
        this.radius = radius;
        this.vBelow = vBelow;
        this.vAbove = vAbove;
        this.expectedBlock = expectedBlock;
        this.guard = guard;

        LOGGER.info("WatchingBlockPosDelayedEvent created for pos {} radius={} vBelow={} vAbove={} expected={} timeoutTicks={}",
                center, radius, vBelow, vAbove, expectedBlock, timeoutTicks);
    }

    // backward compatible constructor
    public WatchingBlockPosDelayedEvent(BlockPos center, int radius, int vBelow, int vAbove, Block expectedBlock,
                                        HitResult initialHit, Level world, SpellResolver resolver, int timeoutTicks) {
        this(center, radius, vBelow, vAbove, expectedBlock, initialHit, world, resolver, timeoutTicks, null);
    }

    @Override
    public void tick(boolean serverSide) {
        if (manuallyResolved || world == null) return;

        if (!serverSide) {
            super.tick(serverSide);
            return;
        }

        try {
            // Обычная проверка: ищем блок в радиусе center
            BlockPos found = scanForBlockNearCenter();
            if (found != null) {
                LOGGER.info("WatchingBlockPosDelayedEvent: block detected near {} -> {} (expected={})", center, found, expectedBlock);
                scheduleResolveWithDelay(found);
                return;
            }
        } catch (Throwable t) {
            LOGGER.warn("WatchingBlockPosDelayedEvent tick exception: {}", t.toString());
        }

        super.tick(serverSide);
    }

    private BlockPos scanForBlockNearCenter() {
        if (center == null || world == null) return null;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -vBelow; dy <= vAbove; dy++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    BlockState st = world.getBlockState(p);
                    if (!st.isAir()) {
                        if (expectedBlock == null || st.getBlock().equals(expectedBlock)) {
                            return p;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Планируем resolve: НЕ вызываем resume немедленно, а откладываем на RESOLVE_DELAY_TICKS (если нужен)
     * и затем — внутри resolveSpell — ещё RESUME_DELAY_TICKS перед фактическим resume.
     */
    private void scheduleResolveWithDelay(BlockPos usePos) {
        synchronized (this) {
            if (resolvedScheduled || manuallyResolved) {
                LOGGER.debug("scheduleResolveWithDelay: already scheduled or manuallyResolved -> skip");
                return;
            }
            // поставим первую задержку (если хотите использовать её) — иначе duration уже может быть установлено извне
            this.duration = Math.max(1, RESOLVE_DELAY_TICKS);
            resolvedScheduled = true;
            // Сохраним позицию (будет выяснена точнее в resolveSpell)
            deferredUsePos = usePos;
            LOGGER.info("WatchingBlockPosDelayedEvent: resolve scheduled (duration set to {}). Will call resolveSpell() soon. deferredUsePos={}", this.duration, usePos);
        }
        // не вызываем super.tick(true) — позволяем естественному таймеру дойти
    }

    @Override
    public void resolveSpell() {
        LOGGER.info("WatchingBlockPosDelayedEvent.resolveSpell called (resolvedScheduled={}, resumePending={}, manuallyResolved={}) for center={}",
                resolvedScheduled, resumePending, manuallyResolved, center);

        synchronized (this) {
            if (manuallyResolved) {
                LOGGER.debug("resolveSpell: manuallyResolved=true -> skipping");
                return;
            }

            // если ещё не отложили фактическое resume — делаем это (откладываем на RESUME_DELAY_TICKS)
            if (!resumePending) {
                // было уже найдено deferredUsePos при scheduleResolveWithDelay; если нет — пытаемся найти сейчас
                if (deferredUsePos == null) {
                    deferredUsePos = scanForBlockNearCenter();
                    if (deferredUsePos == null) {
                        deferredUsePos = center != null ? center : BlockPos.ZERO;
                    }
                }
                // планируем вторую задержку перед фактическим резюмом
                resumePending = true;
                this.duration = Math.max(1, RESUME_DELAY_TICKS);
                LOGGER.info("WatchingBlockPosDelayedEvent.resolveSpell: deferring actual resolver.resume(world) by {} ticks (deferredUsePos={})", RESUME_DELAY_TICKS, deferredUsePos);
                return;
            }

            // второе попадание (после RESUME_DELAY_TICKS) -> пробуем выполнить резюме
            manuallyResolved = true;
        }

        // фактическая замена hitResult и вызов resume
        if (world == null) {
            LOGGER.warn("resolveSpell: world == null -> skipping resume");
            return;
        }

        BlockPos usePos = deferredUsePos != null ? deferredUsePos : (center != null ? center : BlockPos.ZERO);
        boolean replaced = false;

        try {
            // заменяем hitResult если нужно
            if (resolver.hitResult == null || resolver.hitResult instanceof net.minecraft.world.phys.EntityHitResult) {
                BlockHitResult bhr = new BlockHitResult(Vec3.atCenterOf(usePos), net.minecraft.core.Direction.UP, usePos, false);
                Object old = null;
                try { old = resolver.hitResult; } catch (Throwable ignored) {}
                resolver.hitResult = bhr;
                replaced = true;
                LOGGER.info("WatchingBlockPosDelayedEvent: replaced resolver.hitResult with BlockHitResult at {} (old={})", usePos, old);
            } else {
                LOGGER.info("WatchingBlockPosDelayedEvent: resolver.hitResult present and valid -> leaving as-is: {}", resolver.hitResult);
            }
        } catch (Throwable t) {
            LOGGER.warn("WatchingBlockPosDelayedEvent: error while replacing hitResult: {}", t.toString());
        }

        // Теперь выполняем resume, но только если guard позволяет или guard == null
        try {
            LOGGER.info("WatchingBlockPosDelayedEvent: calling resolver.resume(world) (usePos={}, replacedHit={})", usePos, replaced);
            if (guard == null || guard.compareAndSet(false, true)) {
                resolver.resume(world);
                LOGGER.info("WatchingBlockPosDelayedEvent: resolver.resume(world) completed.");
            } else {
                LOGGER.info("WatchingBlockPosDelayedEvent: guard already set -> skipping resume (someone else will resume).");
            }
        } catch (Throwable t) {
            LOGGER.error("WatchingBlockPosDelayedEvent: resolver.resume(world) threw exception:", t);
        } finally {
            // очистка
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
