package com.example.arslauria;

//import com.example.arslauria.glyphs.Barrier;
import com.example.arslauria.glyphs.Barrier;
import com.example.arslauria.glyphs.Blast;
import com.example.arslauria.glyphs.TestEffect;
import com.example.arslauria.registry.ModRegistry;
import com.hollingsworth.arsnouveau.api.registry.GlyphRegistry;
import com.hollingsworth.arsnouveau.api.registry.SpellSoundRegistry;
import com.hollingsworth.arsnouveau.api.sound.SpellSound;
import com.hollingsworth.arsnouveau.api.spell.AbstractSpellPart;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class ArsNouveauRegistry {

    public static List<AbstractSpellPart> registeredSpells = new ArrayList<>(); //this will come handy for datagen

    public static void registerGlyphs() {
        register(TestEffect.INSTANCE);
        register(Blast.INSTANCE);
        register(Barrier.INSTANCE);

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
        registeredSpells.add(Blast.INSTANCE);
        registeredSpells.add(Barrier.INSTANCE);
    }
}
