package com.example.arslauria.glyphs.augment_glyphs;

import com.example.arslauria.Lauria;
import com.hollingsworth.arsnouveau.api.spell.AbstractAugment;
import com.hollingsworth.arsnouveau.api.spell.AbstractSpellPart;
import com.hollingsworth.arsnouveau.api.spell.SpellStats;
import com.hollingsworth.arsnouveau.api.spell.SpellTier;
import com.hollingsworth.arsnouveau.common.spell.augment.AugmentExtendTime;
import net.minecraft.resources.ResourceLocation;


public class AugmentExtendTimeHalf extends AbstractAugment {

    public static final AugmentExtendTimeHalf INSTANCE = new AugmentExtendTimeHalf(new ResourceLocation(Lauria.MOD_ID, "glyph_extendtime_half"), "Extend Time Half");

    public AugmentExtendTimeHalf(ResourceLocation tag, String description) {
        super(tag, description);
    }

    @Override
    public int getDefaultManaCost() {
        return (int) (AugmentExtendTime.INSTANCE.getDefaultManaCost() * 1.5);
    }

    @Override
    public String getBookDescription() {
        return "0.5 sec";
    }

    @Override
    public SpellTier defaultTier() {
        return SpellTier.ONE;
    }

    @Override
    public SpellStats.Builder applyModifiers(SpellStats.Builder builder, AbstractSpellPart spellPart) {
        builder.addDurationModifier(0.5);
        return super.applyModifiers(builder, spellPart);
    }

}
