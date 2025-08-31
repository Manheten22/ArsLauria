package com.example.arslauria.effects;

import com.example.arslauria.network.BarrierSyncPacket;
import com.example.arslauria.network.NetworkHandler;
import com.example.arslauria.setup.ModEffects;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.MobEffectEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BarrierEffect — поддерживает стэки с демпфингом и синхронизирует отображение на клиентах.
 *
 * Поведение:
 * - стак считается сломанным (ломается звук) только когда оба его HP (magic и phys) дошли до 0 (isEmpty).
 * - барьер полностью удаляется, когда суммарный magic <= 0 || суммарный phys <= 0 || нет стэков.
 * - absorb* НЕ вызывает removeEffect напрямую; onLivingHurt воспроизводит звук(и) ломания стэков и при необходимости удаляет эффект/данные.
 *
 * Сетевые отправки делаются через NetworkHandler. NetworkHandler.init() должен быть вызван в фазе common-setup вашего мода.
 */
public class BarrierEffect extends MobEffect {
    private static final Map<LivingEntity, BarrierData> DATA = new ConcurrentHashMap<>();

    private static final double STACK_WEAKEN = 0.8;

    // диапазон для fallback-рассылки (если нужно) — в блоках
    private static final double SYNC_RANGE = 64.0;
    private static final double SYNC_RANGE_SQ = SYNC_RANGE * SYNC_RANGE;

    public BarrierEffect() {
        super(MobEffectCategory.BENEFICIAL, 0x7F00FF);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public void applyEffectTick(LivingEntity entity, int amplifier) {
        // Клиентские частицы (вызов происходит и на клиенте)
        if (entity.level().isClientSide) {
            double x = entity.getX();
            double y = entity.getY() + entity.getBbHeight() * 0.5;
            double z = entity.getZ();
            entity.level().addParticle(
                    ParticleTypes.ENCHANT,
                    x + (Math.random() - 0.5),
                    y + (Math.random() - 0.5),
                    z + (Math.random() - 0.5),
                    0, 0, 0
            );
        }
    }

    @Override
    public boolean isDurationEffectTick(int duration, int amplifier) {
        return true;
    }

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        LivingEntity target = event.getEntity();
        if (!target.hasEffect(ModEffects.BARRIER.get())) return;

        BarrierData data = DATA.get(target);
        if (data == null) return;

        DamageSource src    = event.getSource();
        float incoming       = event.getAmount();

        boolean isMelee = src.getDirectEntity() instanceof LivingEntity
                && !(src.getDirectEntity() instanceof Projectile);

        BarrierData.AbsorbResult result;
        if (isMelee) {
            result = data.absorbPhys(incoming);
        } else {
            result = data.absorbMagic(incoming);
        }

        float totalAbsorbed = result.absorbed;
        event.setAmount(incoming - totalAbsorbed);

        // Если сломались какие-то стэки — воспроизводим звук для каждого сломанного стакa (на сервере один раз)
        if (result.stacksRemoved > 0 && !target.level().isClientSide) {
            for (int i = 0; i < result.stacksRemoved; i++) {
                target.level().playSound(
                        null,
                        target.getX(), target.getY(), target.getZ(),
                        SoundEvents.GLASS_BREAK,
                        SoundSource.BLOCKS,
                        1.0F,
                        1.0F
                );
            }
        }

        // Проверяем состояние барьера: удаляем эффект и данные только здесь (один раз),
        // когда выполнено условие удаления: суммарный magic <= 0 || суммарный phys <= 0 || нет стэков.
        boolean shouldRemove = false;
        synchronized (data) {
            if (data.isEmpty()) {
                shouldRemove = true;
            } else if (data.getTotalMagic() <= 0 || data.getTotalPhys() <= 0) {
                shouldRemove = true;
            }
        }

        if (shouldRemove) {
            // финальный звук (сервер)
            if (!target.level().isClientSide) {
                target.level().playSound(
                        null,
                        target.getX(), target.getY(), target.getZ(),
                        SoundEvents.GLASS_BREAK,
                        SoundSource.BLOCKS,
                        1.0F,
                        1.0F
                );
            }

            // удаляем эффект и данные (серверная сторона)
            target.removeEffect(ModEffects.BARRIER.get());
            DATA.remove(target);

            // синхронизируем удаление на клиенты
            if (!target.level().isClientSide) {
                sendSyncRemoval(target);
            }

            if (target instanceof ServerPlayer sp) {
                sp.sendSystemMessage(Component.literal("Barrier broken."));
            }
            return;
        } else {
            // Если просто изменились пуулы/стэки — отправляем debug-сообщение о новых значениях серверному игроку
            if (target instanceof ServerPlayer sp) {
                BarrierData d = data;
                sp.sendSystemMessage(Component.literal(String.format(
                        "Barrier changed — magic: %d/%d, phys: %d/%d (stacks: %d)",
                        d.getTotalMagic(), d.getTotalMagicMax(),
                        d.getTotalPhys(), d.getTotalPhysMax(),
                        d.getStacksCount()
                )));
            }
        }
    }

    @SubscribeEvent
    public void onEntityDeath(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (DATA.remove(entity) != null) {
            if (!entity.level().isClientSide) sendSyncRemoval(entity);
        }
    }

    @SubscribeEvent
    public void onEffectRemoved(MobEffectEvent.Remove event) {
        // Защита от случая, когда getEffectInstance() возвращает null
        if (event.getEffectInstance() == null) return;

        if (event.getEffectInstance().getEffect() == ModEffects.BARRIER.get()) {
            LivingEntity entity = (LivingEntity) event.getEntity();
            if (DATA.remove(entity) != null) {
                if (!entity.level().isClientSide) sendSyncRemoval(entity);
            }
        }
    }

    /**
     * Добавляет новый стэк барьера к цели.
     * @param entity цель
     * @param magicHP базовый magic HP
     * @param physHP  базовый phys HP
     * @param alreadyHadEffect true если на сущности *уже был* эффект ДО применения (если false — reset)
     */
    public static void addBarrierStack(LivingEntity entity, int magicHP, int physHP, boolean alreadyHadEffect) {
        if (!alreadyHadEffect) {
            BarrierData data = new BarrierData();
            data.addStack(magicHP, physHP);
            DATA.put(entity, data);
        } else {
            BarrierData data = DATA.computeIfAbsent(entity, k -> new BarrierData());
            data.addStack(magicHP, physHP);
        }

        // синхронизируем на клиентов — добавление / обновление
        if (!entity.level().isClientSide) {
            sendSyncAdd(entity);
        }

        BarrierData current = DATA.get(entity);
        if (entity instanceof ServerPlayer sp && current != null) {
            String msg = String.format("Barrier applied — magic: %d/%d, phys: %d/%d (stacks: %d)",
                    current.getTotalMagic(), current.getTotalMagicMax(),
                    current.getTotalPhys(), current.getTotalPhysMax(),
                    current.getStacksCount());
            sp.sendSystemMessage(Component.literal(msg));
        }
    }

    // backward-compatible overload
    public static void addBarrierStack(LivingEntity entity, int magicHP, int physHP) {
        addBarrierStack(entity, magicHP, physHP, entity.getEffect(ModEffects.BARRIER.get()) != null);
    }

    public static Set<LivingEntity> getEntities() {
        return DATA.keySet();
    }

    /* ======= внутренние классы / логика поглощения ======= */

    private static class BarrierData {
        private final List<StackData> stacks = new ArrayList<>();

        synchronized void addStack(int baseMagic, int basePhys) {
            int existing = stacks.size();
            double multiplier = Math.pow(STACK_WEAKEN, existing);
            int mMax = (int) Math.ceil(baseMagic * multiplier);
            int pMax = (int) Math.ceil(basePhys * multiplier);
            if (mMax <= 0) mMax = 1;
            if (pMax <= 0) pMax = 1;
            stacks.add(new StackData(mMax, pMax));
        }

        /**
         * Результат поглощения: сколько поглощено и сколько стэков удалено (сломано).
         */
        static class AbsorbResult {
            final float absorbed;
            final int stacksRemoved;

            AbsorbResult(float absorbed, int stacksRemoved) {
                this.absorbed = absorbed;
                this.stacksRemoved = stacksRemoved;
            }
        }

        /**
         * Поглощение магического урона.
         * Удаляет стэки, которые полностью опустели (magic<=0 && phys<=0).
         * НЕ удаляет эффект напрямую.
         */
        synchronized AbsorbResult absorbMagic(float amount) {
            float remaining = amount;
            int removed = 0;

            Iterator<StackData> it = stacks.iterator();
            while (it.hasNext() && remaining > 0f) {
                StackData s = it.next();
                float absorbed = Math.min(remaining, s.magicHP);
                s.magicHP -= absorbed;
                remaining -= absorbed;

                // если стак полностью пуст — удаляем его и подсчитываем, что сломался
                if (s.isEmpty()) {
                    it.remove();
                    removed++;
                }
            }

            float totalAbsorbed = amount - remaining;
            return new AbsorbResult(totalAbsorbed, removed);
        }

        synchronized AbsorbResult absorbPhys(float amount) {
            float remaining = amount;
            int removed = 0;

            Iterator<StackData> it = stacks.iterator();
            while (it.hasNext() && remaining > 0f) {
                StackData s = it.next();
                float absorbed = Math.min(remaining, s.physHP);
                s.physHP -= absorbed;
                remaining -= absorbed;

                if (s.isEmpty()) {
                    it.remove();
                    removed++;
                }
            }

            float totalAbsorbed = amount - remaining;
            return new AbsorbResult(totalAbsorbed, removed);
        }

        synchronized int getTotalMagic() {
            return stacks.stream().mapToInt(s -> Math.max(0, s.magicHP)).sum();
        }

        synchronized int getTotalPhys() {
            return stacks.stream().mapToInt(s -> Math.max(0, s.physHP)).sum();
        }

        synchronized int getTotalMagicMax() {
            return stacks.stream().mapToInt(s -> s.magicMax).sum();
        }

        synchronized int getTotalPhysMax() {
            return stacks.stream().mapToInt(s -> s.physMax).sum();
        }

        synchronized boolean isEmpty() {
            return stacks.isEmpty();
        }

        synchronized int getStacksCount() {
            return stacks.size();
        }
    }

    private static class StackData {
        int magicHP;
        int physHP;
        final int magicMax;
        final int physMax;

        StackData(int magicMax, int physMax) {
            this.magicMax = magicMax;
            this.physMax  = physMax;
            this.magicHP  = magicMax;
            this.physHP   = physMax;
        }

        // стак считается сломанным только когда оба HP дошли до нуля
        boolean isEmpty() {
            return magicHP <= 0 && physHP <= 0;
        }
    }

    /* ======= сетевые хелперы для синхронизации с клиентом ======= */

    private static void sendSyncAdd(LivingEntity entity) {
        if (entity.level().isClientSide) return;
        try {
            int duration = 0;
            var inst = entity.getEffect(ModEffects.BARRIER.get());
            if (inst != null) duration = inst.getDuration(); // тики

            if (NetworkHandler.isInitialized()) {
                NetworkHandler.sendToTracking(entity, new com.example.arslauria.network.BarrierSyncPacket(entity.getId(), true, duration));
                return;
            }

            // fallback (если канал не инициализирован) — разослать всем игрокам рядом
            if (entity.level() instanceof ServerLevel serverLevel) {
                for (ServerPlayer p : serverLevel.players()) {
                    if (p.distanceToSqr(entity) <= SYNC_RANGE_SQ) {
                        NetworkHandler.sendToPlayer(p, new com.example.arslauria.network.BarrierSyncPacket(entity.getId(), true, duration));
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void sendSyncRemoval(LivingEntity entity) {
        if (entity.level().isClientSide) return;
        try {
            if (NetworkHandler.isInitialized()) {
                NetworkHandler.sendToTracking(entity, new com.example.arslauria.network.BarrierSyncPacket(entity.getId(), false, 0));
                return;
            }
            if (entity.level() instanceof ServerLevel serverLevel) {
                for (ServerPlayer p : serverLevel.players()) {
                    if (p.distanceToSqr(entity) <= SYNC_RANGE_SQ) {
                        NetworkHandler.sendToPlayer(p, new com.example.arslauria.network.BarrierSyncPacket(entity.getId(), false, 0));
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
