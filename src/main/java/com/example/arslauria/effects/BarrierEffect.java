package com.example.arslauria.effects;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.MobEffectEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Эффект «Barrier»:
 * – поглощает урон,
 * – удаляет данные при смерти или снятии эффекта,
 * – хранит носителей в DATA для рендера сферы.
 */
public class BarrierEffect extends MobEffect {
    private static final Map<LivingEntity, BarrierData> DATA = new WeakHashMap<>();

    public BarrierEffect() {
        super(MobEffectCategory.BENEFICIAL, 0x7F00FF);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public void applyEffectTick(LivingEntity entity, int amplifier) {
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
        if (!target.hasEffect(this)) return;

        BarrierData data = DATA.get(target);
        if (data == null) return;

        DamageSource src    = event.getSource();
        float incoming       = event.getAmount();
        float absorbed;

        boolean isMelee = src.getDirectEntity() instanceof LivingEntity
                && !(src.getDirectEntity() instanceof Projectile);

        if (isMelee) {
            absorbed      = Math.min(incoming, data.physHP);
            data.physHP  -= absorbed;
        } else {
            absorbed       = Math.min(incoming, data.magicHP);
            data.magicHP  -= absorbed;
        }

        event.setAmount(incoming - absorbed);

        if (!data.broken && ((isMelee && data.physHP <= 0) || (!isMelee && data.magicHP <= 0))) {
            target.level().playSound(
                    null,
                    target.getX(), target.getY(), target.getZ(),
                    SoundEvents.GLASS_BREAK,
                    SoundSource.BLOCKS,
                    1.0F,
                    1.0F
            );
            data.broken = true;
        }
    }

    @SubscribeEvent
    public void onEntityDeath(LivingDeathEvent event) {
        DATA.remove(event.getEntity());
    }

    @SubscribeEvent
    public void onEffectRemoved(MobEffectEvent.Remove event) {
        if (event.getEffectInstance().getEffect() == this) {
            DATA.remove(event.getEntity());
        }
    }

    /** Вызывается из глифа для установки пулов HP */
    public static void createBarrierData(LivingEntity entity, int magicHP, int physHP) {
        DATA.put(entity, new BarrierData(magicHP, physHP));
    }

    /** Для рендерера: все сущности с активным барьером */
    public static Set<LivingEntity> getEntities() {
        return DATA.keySet();
    }

    private static class BarrierData {
        int magicHP, physHP;
        boolean broken = false;
        BarrierData(int magicHP, int physHP) {
            this.magicHP = magicHP;
            this.physHP  = physHP;
        }
    }
}