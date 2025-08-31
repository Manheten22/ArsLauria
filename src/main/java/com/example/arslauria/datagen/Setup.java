package com.example.arslauria.datagen;

import com.example.arslauria.Lauria;
import net.minecraft.data.DataGenerator;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Lauria.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Setup {

    @SubscribeEvent
    public static void gatherData(GatherDataEvent event) {
        DataGenerator gen = event.getGenerator();

        // SERVER data (recipes, loot, tags и т.д.)
        if (event.includeServer()) {
            gen.addProvider(true, new ArsProviders.GlyphProvider(gen));
            gen.addProvider(true, new ArsProviders.EnchantingAppProvider(gen));
            gen.addProvider(true, new ArsProviders.ImbuementProvider(gen)); // добавил — у тебя был провайдер, не регистрирован
        }

        // CLIENT data (assets, локали, patchouli страницы и т.п.)
        if (event.includeClient()) {
            gen.addProvider(true, new ArsProviders.PatchouliProvider(gen)); // должен идти под includeClient()
        }
    }
}

