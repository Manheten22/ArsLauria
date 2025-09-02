package com.example.arslauria.glyphs.events;

import com.hollingsworth.arsnouveau.api.event.DelayedSpellEvent;
import com.hollingsworth.arsnouveau.api.spell.SpellResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Подкласс DelayedSpellEvent: ожидает удаления наблюдаемой сущности (watched.isRemoved()).
 * Исправления и доработки:
 *  - не триггерим по блоку, который лежит слишком далеко ниже сущности;
 *  - по скорости триггерим только если vy <= 0 (не движется вверх) и есть непустой блок в ближней области;
 *  - при срабатывании аккуратно подставляем BlockHitResult и вызываем resolver.resume(world) с возможной задержкой.
 */
public class WatchingDelayedSpellEvent extends DelayedSpellEvent {
    private static final Logger LOGGER = LogManager.getLogger("arslauria-impact");

    private final Entity watched;
    private boolean manuallyResolved = false;

    // Настраиваемые константы (можно менять под вашу механику)
    private static final int MAX_BLOCK_SEARCH_DEPTH = 6;      // сколько блоков вниз искать при попытке найти твердую поверхность
    private static final int MAX_VERTICAL_DIFF = 6;          // допустимая вертикальная дистанция между сущностью и найденным блоком
    private static final double LOW_VY_THRESHOLD = 0.02;     // порог малой скорости по Y
    private static final int NEARBY_BLOCK_CHECK_DEPTH = 2;   // при проверке "низкой скорости" требуем блок в пределах этих блоков под сущностью

    // Задержка перед фактическим вызовом resolver.resume(world). Настроить при необходимости.
    private static final int RESUME_DELAY_TICKS = 10; // 10 тиков ~ 0.5s

    // Поля для отложенного резюма
    private boolean pendingResume = false;
    private int pendingResumeTicks = 0;
    private BlockPos pendingUsePos = null;

    public WatchingDelayedSpellEvent(Entity watched, HitResult result, Level world, SpellResolver resolver, int timeoutTicks) {
        super(timeoutTicks, result, world, resolver);
        this.watched = watched;
    }

    @Override
    public void tick(boolean serverSide) {
        if (manuallyResolved || world == null) return;

        // Если запланирован отложенный резюме — считаем тики на серверной стороне
        if (serverSide) {
            if (pendingResume) {
                if (pendingResumeTicks > 0) {
                    pendingResumeTicks--;
                    LOGGER.debug("WatchingDelayedSpellEvent: pendingResume tick, remaining={}", pendingResumeTicks);
                    // Уменьшаем обычный таймаут тоже, чтобы не застрять навсегда.
                    super.tick(serverSide);
                    return;
                }

                // Таймер дошёл до нуля — выполняем резюме
                LOGGER.info("WatchingDelayedSpellEvent: pendingResume timer expired -> performing resume (usePos={})", pendingUsePos);
                performResume(pendingUsePos);
                super.tick(serverSide);
                return;
            }
        }

        if (!serverSide) {
            super.tick(serverSide);
            return;
        }

        try {
            // Базовая позиция, от которой будем искать блоки/оценивать расстояния
            Vec3 entityExactPos = (watched != null) ? watched.position() : null;
            BlockPos entityBlockPos = (watched != null) ? watched.blockPosition()
                    : (result instanceof BlockHitResult ? ((BlockHitResult) result).getBlockPos() : BlockPos.ZERO);

            // Случай: watched == null -> попытаемся резолвить по позиции результата
            if (watched == null) {
                LOGGER.debug("WatchingDelayedSpellEvent: watched == null -> attempt to resume using pos {}", entityBlockPos);
                // найдем первый непустой блок под pos в пределах MAX_BLOCK_SEARCH_DEPTH
                BlockPos usePos = findFirstNonAirBelowOrSelf(entityBlockPos, MAX_BLOCK_SEARCH_DEPTH);
                if (usePos != null && !world.getBlockState(usePos).isAir()) {
                    LOGGER.info("WatchingDelayedSpellEvent: watched==null and found block at {} -> attempt resume", usePos);
                    attemptReplaceHitAndResume(usePos);
                    return;
                }
                // иначе позволяем таймауту продолжать
                super.tick(serverSide);
                return;
            }

            // 1) Если сущность удалена — это основной корректный кейс для резолва.
            if (watched.isRemoved()) {
                LOGGER.info("WatchingDelayedSpellEvent: watched.isRemoved() == true -> checking for nearby solid block to resume (last pos {})", entityBlockPos);

                // Попытаемся найти блок под last pos
                BlockPos usePos = findFirstNonAirBelowOrSelf(entityBlockPos, MAX_BLOCK_SEARCH_DEPTH);

                if (usePos != null && !world.getBlockState(usePos).isAir()) {
                    // дополнительная защита: блок должен быть не слишком далеко по Y от позиции сущности
                    double entityY = (entityExactPos != null) ? entityExactPos.y : entityBlockPos.getY();
                    double verticalDiff = entityY - (usePos.getY() + 0.5); // сравниваем к центру блока
                    if (verticalDiff >= 0 && verticalDiff <= MAX_VERTICAL_DIFF) {
                        LOGGER.info("WatchingDelayedSpellEvent: watched removed and found nearby block at {} (verticalDiff={}) -> resume", usePos, verticalDiff);
                        attemptReplaceHitAndResume(usePos);
                        return;
                    } else {
                        LOGGER.debug("WatchingDelayedSpellEvent: found block at {} but verticalDiff={} > MAX_VERTICAL_DIFF={} -> ignore for now",
                                usePos, verticalDiff, MAX_VERTICAL_DIFF);
                        // не резолвим сразу — позволим таймауту / другому событию обработать
                    }
                } else {
                    LOGGER.debug("WatchingDelayedSpellEvent: watched removed but no nearby block found within {} below {}", MAX_BLOCK_SEARCH_DEPTH, entityBlockPos);
                }

                // Не форсим резолв только по удалению; ждём появления блока или таймаута
                super.tick(serverSide);
                return;
            }

            // 2) Если сущность ещё жива — проверяем блок прямо под ней (в ближайшей области).
            BlockPos at = watched.blockPosition();

            // Ищем ближайший непустой блок в 0..NEARBY_BLOCK_CHECK_DEPTH под сущностью
            BlockPos foundNearby = null;
            for (int d = 0; d <= NEARBY_BLOCK_CHECK_DEPTH; d++) {
                BlockPos p = at.below(d);
                if (!world.getBlockState(p).isAir()) {
                    foundNearby = p;
                    break;
                }
            }
            if (foundNearby != null) {
                // проверка вертикальной близости (на случай, если blockPosition вернул диковатые координаты)
                double entityY = (entityExactPos != null) ? entityExactPos.y : at.getY();
                double verticalDiff = entityY - (foundNearby.getY() + 0.5);
                if (verticalDiff >= 0 && verticalDiff <= MAX_VERTICAL_DIFF) {
                    LOGGER.info("WatchingDelayedSpellEvent: non-air block at/near entity pos {} -> resume (foundNearby={}, verticalDiff={})",
                            at, foundNearby, verticalDiff);
                    attemptReplaceHitAndResume(foundNearby);
                    return;
                } else {
                    LOGGER.debug("WatchingDelayedSpellEvent: foundNearby {} but verticalDiff={} > {} -> ignore",
                            foundNearby, verticalDiff, MAX_VERTICAL_DIFF);
                }
            }

            // 3) Эвристика по скорости: триггер только если vy небольшая и сущность НЕ движется вверх (vy <= 0)
            Vec3 vel = watched.getDeltaMovement();
            if (vel != null) {
                double vy = vel.y;
                if (Math.abs(vy) < LOW_VY_THRESHOLD && vy <= 0.0) {
                    // при низкой скорости дополнительно требуем, чтобы рядом (в NEARBY_BLOCK_CHECK_DEPTH) был блок,
                    // иначе не считаем это приземлением
                    if (foundNearby != null) {
                        LOGGER.info("WatchingDelayedSpellEvent: low vertical velocity vy={} and nearby block {} -> resume", vy, foundNearby);
                        attemptReplaceHitAndResume(foundNearby);
                        return;
                    } else {
                        LOGGER.debug("WatchingDelayedSpellEvent: low vy={} but no nearby block within {} -> skip", vy, NEARBY_BLOCK_CHECK_DEPTH);
                    }
                } else {
                    LOGGER.debug("WatchingDelayedSpellEvent: vy={} not qualifying (threshold={}, require <=0) -> skip", vy, LOW_VY_THRESHOLD);
                }
            }

        } catch (Throwable t) {
            LOGGER.warn("WatchingDelayedSpellEvent tick exception: {}", t.toString());
        }

        // default behaviour: продолжаем ждать / таймаут
        super.tick(serverSide);
    }

    /**
     * Попытка заменить hit и либо сразу вызвать resume, либо запланировать его через RESUME_DELAY_TICKS.
     */
    private void attemptReplaceHitAndResume(BlockPos usePos) {
        if (manuallyResolved || world == null) return;

        if (usePos == null) usePos = BlockPos.ZERO;

        // Если задержка 0 — выполняем немедленно
        if (RESUME_DELAY_TICKS <= 0) {
            performResume(usePos);
            return;
        }

        // Планируем отложенный резюме — гарантируем, что только один запланирован
        synchronized (this) {
            if (manuallyResolved || pendingResume) return;
            pendingResume = true;
            pendingResumeTicks = RESUME_DELAY_TICKS;
            pendingUsePos = usePos;
            // не выставляем manuallyResolved=true прямо сейчас — пометим это в performResume
            LOGGER.info("WatchingDelayedSpellEvent: scheduled resolver.resume in {} ticks (usePos={})", RESUME_DELAY_TICKS, usePos);
        }
    }

    /**
     * Фактическое выполнение резюма: безопасно заменяет hitResult и вызывает resolver.resume(world).
     * Вызывается когда задержка истекла или если запросили немедленное выполнение.
     */
    private void performResume(BlockPos usePos) {
        if (manuallyResolved || world == null) return;

        synchronized (this) {
            if (manuallyResolved) return;
            // помечаем как завершённое, чтобы предотвратить повторные вызовы
            manuallyResolved = true;
            pendingResume = false;
            pendingResumeTicks = 0;
            pendingUsePos = null;
            duration = 0;
        }

        try {
            if (usePos == null) usePos = BlockPos.ZERO;

            try {
                if (resolver.hitResult == null
                        || (resolver.hitResult instanceof EntityHitResult && (((EntityHitResult) resolver.hitResult).getEntity() == null
                        || ((EntityHitResult) resolver.hitResult).getEntity().isRemoved()))) {

                    BlockHitResult bhr = new BlockHitResult(Vec3.atCenterOf(usePos), Direction.UP, usePos, false);
                    Object old = null;
                    try { old = resolver.hitResult; } catch (Throwable ignored) {}
                    resolver.hitResult = bhr;
                    LOGGER.info("WatchingDelayedSpellEvent: replaced resolver.hitResult with BlockHitResult at {} (old={})", usePos, old);
                } else {
                    LOGGER.info("WatchingDelayedSpellEvent: resolver.hitResult present and valid -> leaving as-is: {}", resolver.hitResult);
                }
            } catch (Throwable t) {
                LOGGER.warn("WatchingDelayedSpellEvent: error while inspecting/replacing resolver.hitResult: {}", t.toString());
            }

            try {
                LOGGER.info("WatchingDelayedSpellEvent: calling resolver.resume(world) now (usePos={})", usePos);
                resolver.resume(world);
                LOGGER.info("WatchingDelayedSpellEvent: resolver.resume(world) completed.");
            } catch (Throwable t) {
                LOGGER.warn("WatchingDelayedSpellEvent: resolver.resume(world) threw exception:", t);
            }
        } catch (Throwable ex) {
            LOGGER.warn("WatchingDelayedSpellEvent: performResume outer exception:", ex);
        }
    }

    /**
     * Ищет первый непустой блок начиная с start и вниз до maxDepth (включая start).
     * Возвращает найденную позицию или null (если не найдено).
     */
    private BlockPos findFirstNonAirBelowOrSelf(BlockPos start, int maxDepth) {
        if (start == null || world == null) return null;
        for (int dy = 0; dy <= Math.max(0, maxDepth); dy++) {
            BlockPos p = start.below(dy);
            if (!world.getBlockState(p).isAir()) return p;
        }
        return null;
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
