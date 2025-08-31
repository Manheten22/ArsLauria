package com.example.arslauria.glyphs;

import com.hollingsworth.arsnouveau.api.spell.*;
import com.hollingsworth.arsnouveau.api.util.ANExplosion;
import com.hollingsworth.arsnouveau.common.spell.augment.AugmentAOE;
import com.hollingsworth.arsnouveau.common.spell.augment.AugmentAmplify;
import com.hollingsworth.arsnouveau.common.spell.augment.AugmentDampen;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nonnull;
import java.util.Set;

import static com.example.arslauria.Lauria.prefix;

public class Blast extends AbstractEffect implements IDamageEffect {

    public static Blast INSTANCE = new Blast(prefix("glyph_blast"), "Blast");

    public Blast(ResourceLocation tag, String description) {
        super(tag, description);
    }

    @Override
    public int getDefaultManaCost() {
        return 200;
    }

    @Override
    public void onResolve(HitResult rayTraceResult, Level world, @Nonnull LivingEntity shooter,
                          SpellStats spellStats, SpellContext spellContext, SpellResolver resolver) {
        super.onResolve(rayTraceResult, world, shooter, spellStats, spellContext, resolver);

        if (world.isClientSide) return;

        Vec3 pos = rayTraceResult.getLocation();

        // Получаем количество AugmentDampen
        int dampenCount = spellStats.getBuffCount(AugmentDampen.INSTANCE);

        // Рассчитываем радиус с учетом Dampen (уменьшаем на 0.7 за каждый уровень Dampen)
        float explosionRadius = Math.max(0.5f, 3.0f +
                (float)spellStats.getAmpMultiplier() * 0.5f +
                (float)spellStats.getAoeMultiplier() * 2.0f -
                dampenCount * 0.7f);

        // Рассчитываем урон с учетом Dampen (уменьшаем на 1.5 за каждый уровень Dampen)
        float baseDamage = Math.max(0.5f, 5.0f - dampenCount * 1.5f);
        float ampDamageScalar = Math.max(0.5f, 2.0f - dampenCount * 0.5f);

        // Создаем кастомный взрыв без повреждения блоков
        createCustomExplosion(world, shooter, pos.x(), pos.y(), pos.z(), explosionRadius,
                spellStats.getAmpMultiplier(), baseDamage, ampDamageScalar);

        // Создаем взрывные партиклы
        if (world instanceof ServerLevel serverLevel) {
            createExplosionParticles(serverLevel, (float)pos.x(), (float)pos.y(), (float)pos.z(), explosionRadius);
        }

        // Воспроизводим звук взрыва
        world.playSound(null, pos.x(), pos.y(), pos.z(),
                SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS,
                4.0F, (1.0F + (world.random.nextFloat() - world.random.nextFloat()) * 0.2F) * 0.7F);
    }

    private void createCustomExplosion(Level world, LivingEntity shooter, double x, double y, double z,
                                       float radius, double amp, float baseDmg, float ampScalar) {
        ANExplosion explosion = new ANExplosion(world, shooter,
                buildDamageSource(world, shooter),
                null, x, y, z, radius, false,
                Explosion.BlockInteraction.KEEP, amp);

        // Устанавливаем базовый урон и множитель усиления с учетом Dampen
        explosion.baseDamage = baseDmg;
        explosion.ampDamageScalar = ampScalar;

        // Вызываем взрыв
        explosion.explode();
        explosion.finalizeExplosion(false);
    }

    private void createExplosionParticles(ServerLevel world, float x, float y, float z, float radius) {
        // Создаем основные взрывные партиклы
        world.sendParticles(ParticleTypes.EXPLOSION, x, y, z, 1, 0, 0, 0, 0);

        // Создаем дополнительные партиклы дыма
        int smokeParticles = (int)(30 * radius);
        for (int i = 0; i < smokeParticles; i++) {
            double offsetX = (world.random.nextDouble() - 0.5) * radius * 0.5;
            double offsetY = (world.random.nextDouble() - 0.5) * radius * 0.5;
            double offsetZ = (world.random.nextDouble() - 0.5) * radius * 0.5;

            world.sendParticles(ParticleTypes.POOF,
                    x + offsetX, y + offsetY, z + offsetZ,
                    1, 0, 0, 0, 0.1f);
        }

        // Создаем партиклы вспышки
        int flashParticles = (int)(20 * radius);
        for (int i = 0; i < flashParticles; i++) {
            double offsetX = (world.random.nextDouble() - 0.5) * radius;
            double offsetY = (world.random.nextDouble() - 0.5) * radius;
            double offsetZ = (world.random.nextDouble() - 0.5) * radius;

            world.sendParticles(ParticleTypes.FLASH,
                    x + offsetX, y + offsetY, z + offsetZ,
                    1, 0, 0, 0, 0);
        }
    }

    @Nonnull
    @Override
    public Set<AbstractAugment> getCompatibleAugments() {
        // Добавляем AugmentDampen в список совместимых аугментов
        return augmentSetOf(AugmentAmplify.INSTANCE, AugmentAOE.INSTANCE, AugmentDampen.INSTANCE);
    }

    @Override
    public SpellTier defaultTier() {
        return SpellTier.TWO;
    }
}