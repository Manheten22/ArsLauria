package com.example.arslauria.glyphs.effect_glyphs;

import com.hollingsworth.arsnouveau.api.spell.*;
import com.hollingsworth.arsnouveau.common.spell.augment.AugmentAmplify;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;

import javax.annotation.Nonnull;

import java.util.Set;

import static com.example.arslauria.Lauria.prefix;

public class TestEffect extends AbstractEffect {

    public static TestEffect INSTANCE = new TestEffect(prefix("glyph_test"), "Test");

    public TestEffect(ResourceLocation tag, String description) {
        super(tag, description);
    }

    @Override
    public int getDefaultManaCost() {
        return 0;
    }

    @Override
    public void onResolve(HitResult rayTraceResult, Level world, @Nonnull LivingEntity shooter, SpellStats spellStats, SpellContext spellContext, SpellResolver resolver) {
        super.onResolve(rayTraceResult, world, shooter, spellStats, spellContext, resolver);

        System.out.println("Hello from my resolve!");
    }


    @Nonnull
    @Override
    public Set<AbstractAugment> getCompatibleAugments() {
        return augmentSetOf(AugmentAmplify.INSTANCE);
    }

    public SpellTier defaultTier() {
        return SpellTier.THREE;
    }
}
