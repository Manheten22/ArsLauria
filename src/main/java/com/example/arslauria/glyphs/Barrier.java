package com.example.arslauria.glyphs;

import com.example.arslauria.effects.BarrierEffect;
import com.example.arslauria.setup.ModEffects;
import com.hollingsworth.arsnouveau.api.spell.AbstractEffect;
import com.hollingsworth.arsnouveau.api.spell.SpellContext;
import com.hollingsworth.arsnouveau.api.spell.SpellResolver;
import com.hollingsworth.arsnouveau.api.spell.SpellStats;
import com.hollingsworth.arsnouveau.api.spell.SpellTier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;

import javax.annotation.Nonnull;
import java.util.Set;

import static com.example.arslauria.Lauria.prefix;

public class Barrier extends AbstractEffect {

    public static final Barrier INSTANCE =
            new Barrier(prefix("glyph_barrier"), "Barrier");

    private static final int BASE_DURATION = 10 * 20; // 10 секунд

    private Barrier(ResourceLocation tag, String description) {
        super(tag, description);
    }

    @Override
    public int getDefaultManaCost() {
        return 80;
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
            // Self-каст: только если вы регистрируете глиф как SpellType.SELF
            // и хотите применять к себе, иначе выходим.
            // Для TRACE-глифа можно отменять эффект при блоках:
            return;
        }

        // 2. Не даём перекрывать существующий барьер
        if (target.hasEffect(ModEffects.BARRIER.get())) {
            return;
        }

        // 3. Вычисляем пулы HP
        int amp     = (int) stats.getAmpMultiplier();
        int magicHP = 100 + amp * 20;
        int physHP  =  30 + amp *  5;

        // 4. Создаём и вешаем эффект
        MobEffectInstance inst = new MobEffectInstance(
                ModEffects.BARRIER.get(),
                BASE_DURATION,
                0,     // уровень эффекта (unused)
                false, // ambient
                true   // видимые частицы
        );
        target.addEffect(inst);

        // 5. Инициализируем резервы в BarrierEffect
        BarrierEffect.createBarrierData(target, magicHP, physHP);
    }


    @Nonnull
    @Override
    public Set<com.hollingsworth.arsnouveau.api.spell.AbstractAugment> getCompatibleAugments() {
        return augmentSetOf(
                com.hollingsworth.arsnouveau.common.spell.augment.AugmentAmplify.INSTANCE
        );
    }

    @Override
    public SpellTier defaultTier() {
        return SpellTier.ONE;
    }
}
