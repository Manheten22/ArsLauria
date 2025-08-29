package com.example.arslauria.setup;

import com.example.arslauria.effects.BarrierEffect;
import com.example.arslauria.Lauria;
import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEffects {
    // Создаём DeferredRegister для MobEffect
    public static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, Lauria.MOD_ID);

    // Регистрируем наш BarrierEffect под именем "barrier"
    public static final RegistryObject<MobEffect> BARRIER =
            EFFECTS.register("barrier", BarrierEffect::new);

    /** Вызывается из конструктора Lauria для регистрации всех эффектов */
    public static void register(IEventBus modEventBus) {
        EFFECTS.register(modEventBus);
    }
}
