package com.example.arslauria.glyphs;

import com.example.arslauria.effects.BarrierEffect;
import com.example.arslauria.setup.ModEffects;
import com.hollingsworth.arsnouveau.api.spell.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import java.util.Set;

import static com.example.arslauria.Lauria.prefix;

public class Barrier extends AbstractEffect {

    public static final Barrier INSTANCE =
            new Barrier(prefix("glyph_barrier"), "Barrier");

    public static final int BASE_DURATION = 10 * 20; // 10 секунд

    public Barrier(ResourceLocation tag, String description) {
        super(tag, description);
    }


    @Override
    public void onResolve(HitResult trace,
                          Level world,
                          @Nonnull LivingEntity shooter,
                          SpellStats stats,
                          SpellContext ctx,
                          SpellResolver resolver) {
        super.onResolve(trace, world, shooter, stats, ctx, resolver);

        if (world.isClientSide) {
            return;
        }

        // 1. Определяем, на кого вешать барьер
        LivingEntity target;
        if (trace instanceof net.minecraft.world.phys.EntityHitResult eHit
                && eHit.getEntity() instanceof LivingEntity hitEntity) {
            target = hitEntity;         // попали по другой сущности
        } else {
            // Self-каст: если глиф не поддерживает self — просто выход
            return;
        }

// 2. Вычисляем пулы HP
        int amp     = (int) stats.getAmpMultiplier();
        int magicHP = 100 + amp * 20;
        int physHP  =  30 + amp *  5;

// --- СОХРАНЯЕМ, БЫЛ ЛИ ЭФФЕКТ ДО ПРИМЕНЕНИЯ (важно) ---
        boolean hadEffect = target.hasEffect(ModEffects.BARRIER.get());

// 3. Если эффект не висит — повесим его (чтобы был видимый статус),
//    иначе просто продлим / оставим
        if (!hadEffect) {
            MobEffectInstance inst = new MobEffectInstance(
                    ModEffects.BARRIER.get(),
                    BASE_DURATION,
                    0,
                    false,
                    false
            );
            target.addEffect(inst);
        } else {
            MobEffectInstance cur = target.getEffect(ModEffects.BARRIER.get());
            if (cur != null) {
                MobEffectInstance refreshed = new MobEffectInstance(
                        ModEffects.BARRIER.get(),
                        Math.max(cur.getDuration(), BASE_DURATION),
                        cur.getAmplifier(),
                        cur.isAmbient(),
                        cur.isVisible()
                );
                target.addEffect(refreshed);
            }
        }

// 4. Добавляем стэк в BarrierEffect — передаём hadEffect (если эффекта не было, нужно сделать reset)
        BarrierEffect.addBarrierStack(target, magicHP, physHP, hadEffect);

    }

    @Override
    protected int getDefaultManaCost() {
        return 80;
    }
    @Override
    public SpellTier defaultTier() {
        return SpellTier.THREE;
    }

    @Override
    public void buildConfig(ForgeConfigSpec.Builder builder) {
        super.buildConfig(builder);
    }

    @NotNull
    @Override
    public Set<com.hollingsworth.arsnouveau.api.spell.AbstractAugment> getCompatibleAugments() {
        return augmentSetOf(
                com.hollingsworth.arsnouveau.common.spell.augment.AugmentAmplify.INSTANCE
        );
    }

    @Override
    public String getBookDescription() {
        return "Creates a protective barrier capable of absorbing damage (stacks, diminishing returns).";
    }

    @NotNull
    @Override
    public Set<SpellSchool> getSchools() {
        return setOf(SpellSchools.MANIPULATION);
    }
}
