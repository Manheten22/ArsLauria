package com.example.arslauria.glyphs.augment_glyphs;

import com.example.arslauria.Lauria;
import com.hollingsworth.arsnouveau.api.spell.*;
import com.hollingsworth.arsnouveau.api.spell.AbstractSpellPart;
import com.hollingsworth.arsnouveau.api.spell.SpellStats;
import com.hollingsworth.arsnouveau.common.spell.augment.AugmentExtendTime;
import net.minecraft.resources.ResourceLocation;


public class AugmentExtendTimeMinor extends AbstractAugment {

    public static final AugmentExtendTimeMinor INSTANCE = new AugmentExtendTimeMinor(new ResourceLocation(Lauria.MOD_ID, "glyph_extendtime_minor"), "Extend Time Minor");

    public AugmentExtendTimeMinor(ResourceLocation tag, String description) {
        super(tag, description);
    }

    @Override
    public int getDefaultManaCost() {
        return (int) (AugmentExtendTime.INSTANCE.getDefaultManaCost() * 1.1);
    }

    @Override
    public String getBookDescription() {
        return "0.1 sec";
    }

    @Override
    public SpellTier defaultTier() {
        return SpellTier.ONE;
    }

    @Override
    public SpellStats.Builder applyModifiers(SpellStats.Builder builder, AbstractSpellPart spellPart) {
        builder.addDurationModifier(3);
        return super.applyModifiers(builder, spellPart);
    }

}
