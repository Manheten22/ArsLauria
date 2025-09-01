package com.example.arslauria.glyphs.events;

import com.hollingsworth.arsnouveau.api.event.DelayedSpellEvent;
import com.hollingsworth.arsnouveau.api.spell.SpellResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Подкласс DelayedSpellEvent: ожидает удаления наблюдаемой сущности (watched.isRemoved()).
 * Если сущность удалена или под ней появился блок — вызывает resolver.resume(world) и помечает себя истёкшим.
 *
 * Используется потому, что SpellContext.delay(...) принимает DelayedSpellEvent.
 */
public class WatchingDelayedSpellEvent extends DelayedSpellEvent {
    private static final Logger LOGGER = LogManager.getLogger("arslauria-impact");

    private final Entity watched;
    private boolean manuallyResolved = false;

    /**
     * @param watched      сущность, за которой наблюдаем (например FallingBlockEntity)
     * @param result       HitResult — тот же, что используется для визуализации/позиции
     * @param world        мир
     * @param resolver     SpellResolver
     * @param timeoutTicks обычный таймаут (в тиках) — если watched не исчезнет, сработает стандартный таймер
     */
    public WatchingDelayedSpellEvent(Entity watched, HitResult result, Level world, SpellResolver resolver, int timeoutTicks) {
        super(timeoutTicks, result, world, resolver);
        this.watched = watched;
    }

    @Override
    public void tick(boolean serverSide) {
        if (manuallyResolved || world == null) return;

        if (serverSide) {
            try {
                // 0) Простая логика для отладки
                if (watched == null) {
                    LOGGER.info("WatchingDelayedSpellEvent: watched == null -> resume");
                    resolver.resume(world);
                    manuallyResolved = true;
                    duration = 0;
                    return;
                }

                // 1) Если сущность удалена по движку — резолвим
                if (watched.isRemoved()) {
                    LOGGER.info("WatchingDelayedSpellEvent: watched.isRemoved() == true -> resume for {}", watched.getType());
                    resolver.resume(world);
                    manuallyResolved = true;
                    duration = 0;
                    return;
                }

                // 2) Проверка блока непосредственно под сущностью:
                //    часто FallingBlockEntity превращается в блок, но сам entity может оставаться.
                BlockPos pos = watched.blockPosition();
                var stateAtPos = world.getBlockState(pos);
                if (!stateAtPos.isAir()) {
                    LOGGER.info("WatchingDelayedSpellEvent: non-air block at entity pos {} -> resume (block: {})", pos, stateAtPos.getBlock());
                    resolver.resume(world);
                    manuallyResolved = true;
                    duration = 0;
                    return;
                }

                // 3) Проверка блока чуть ниже (под ногами) — в некоторых случаях блок появляется на pos.below()
                BlockPos below = pos.below();
                var stateBelow = world.getBlockState(below);
                if (!stateBelow.isAir()) {
                    LOGGER.info("WatchingDelayedSpellEvent: non-air block under entity at {} -> resume (block: {})", below, stateBelow.getBlock());
                    resolver.resume(world);
                    manuallyResolved = true;
                    duration = 0;
                    return;
                }

                // 4) Эвристика по скорости: если вертикальная скорость близка к нулю и сущность движется мало — вероятно, приземлился.
                Vec3 vel = watched.getDeltaMovement();
                if (vel != null) {
                    double vy = vel.y;
                    if (Math.abs(vy) < 0.02) { // порог можно настроить
                        LOGGER.debug("WatchingDelayedSpellEvent: low vertical velocity vy={} at pos {} -> resume", vy, pos);
                        resolver.resume(world);
                        manuallyResolved = true;
                        duration = 0;
                        return;
                    }
                }
            } catch (Throwable t) {
                // Не даём ошибке ломать тик: логируем и продолжаем дефолтное поведение
                LOGGER.warn("WatchingDelayedSpellEvent tick exception: {}", t.toString());
            }
        }

        // Вызов стандартной логики DelayedSpellEvent (уменьшение duration и т.д.)
        super.tick(serverSide);
    }

    @Override
    public void resolveSpell() {
        LOGGER.info("WatchingDelayedSpellEvent.resolveSpell called (manuallyResolved={}) for watched={}", manuallyResolved, watched);
        if (manuallyResolved) return;
        super.resolveSpell();
    }


    @Override
    public boolean isExpired() {
        return manuallyResolved || super.isExpired();
    }
}
