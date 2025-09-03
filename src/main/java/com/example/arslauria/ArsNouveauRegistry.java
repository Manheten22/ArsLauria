package com.example.arslauria;

//import com.example.arslauria.glyphs.Barrier;
import com.example.arslauria.glyphs.augment_glyphs.AugmentExtendTimeHalf;
import com.example.arslauria.glyphs.augment_glyphs.AugmentExtendTimeLesser;
import com.example.arslauria.glyphs.augment_glyphs.AugmentExtendTimeMinor;
import com.example.arslauria.glyphs.effect_glyphs.EffectBarrier;
import com.example.arslauria.glyphs.effect_glyphs.EffectBlast;
import com.example.arslauria.glyphs.effect_glyphs.EffectImpact;
import com.example.arslauria.glyphs.effect_glyphs.TestEffect;
import com.hollingsworth.arsnouveau.api.registry.GlyphRegistry;
import com.hollingsworth.arsnouveau.api.spell.AbstractSpellPart;
import com.hollingsworth.arsnouveau.common.spell.augment.AugmentExtendTime;

import java.util.ArrayList;
import java.util.List;

public class ArsNouveauRegistry {

    public static List<AbstractSpellPart> registeredSpells = new ArrayList<>(); //this will come handy for datagen

    public static void registerGlyphs() {
        register(TestEffect.INSTANCE);
        register(EffectBlast.INSTANCE);
        register(EffectBarrier.INSTANCE);
        register(EffectImpact.INSTANCE);

        //Augment
        register(AugmentExtendTimeMinor.INSTANCE);
        register(AugmentExtendTimeLesser.INSTANCE);
        register(AugmentExtendTimeHalf.INSTANCE);
    }

    public static void addAugments() {
        for(AbstractSpellPart part : GlyphRegistry.getSpellpartMap().values()){

            if(part.compatibleAugments.contains(AugmentExtendTime.INSTANCE)&&!part.compatibleAugments.contains(AugmentExtendTimeMinor.INSTANCE)){
                part.compatibleAugments.add(AugmentExtendTimeMinor.INSTANCE);
            }

            if(part.compatibleAugments.contains(AugmentExtendTime.INSTANCE)&&!part.compatibleAugments.contains(AugmentExtendTimeLesser.INSTANCE)){
                part.compatibleAugments.add(AugmentExtendTimeLesser.INSTANCE);
            }

            if(part.compatibleAugments.contains(AugmentExtendTime.INSTANCE)&&!part.compatibleAugments.contains(AugmentExtendTimeHalf.INSTANCE)){
                part.compatibleAugments.add(AugmentExtendTimeHalf.INSTANCE);
            }


        }
    }


    public static void registerSounds(){
    }
    public static void register(AbstractSpellPart spellPart){
        GlyphRegistry.registerSpell(spellPart);
        registeredSpells.add(spellPart);
    }

    public static void registerGlyphsForDatagen() {
        registeredSpells.clear();
        registeredSpells.add(TestEffect.INSTANCE);
        registeredSpells.add(EffectBlast.INSTANCE);
        registeredSpells.add(EffectBarrier.INSTANCE);
        registeredSpells.add(EffectImpact.INSTANCE);
        registeredSpells.add(AugmentExtendTimeMinor.INSTANCE);
        registeredSpells.add(AugmentExtendTimeLesser.INSTANCE);
        registeredSpells.add(AugmentExtendTimeHalf.INSTANCE);
    }
}
