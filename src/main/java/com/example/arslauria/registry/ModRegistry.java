package com.example.arslauria.registry;

import com.example.arslauria.item.ExampleCosmetic;
import com.hollingsworth.arsnouveau.api.sound.SpellSound;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import static com.example.arslauria.Lauria.MOD_ID;

public class ModRegistry {

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID);
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, MOD_ID);


    public static void registerRegistries(IEventBus bus){
        BLOCKS.register(bus);
        ITEMS.register(bus);
        SOUNDS.register(bus);
    }

    public static final RegistryObject<Item> EXAMPLE;

    //this is an example of how to register a sound. You also need to add the sound to the sound.json file, referencing your ogg files, and a texture for the button under textures/sounds.
    //this example will use one of the existing sounds randomly
    public static RegistryObject<SoundEvent> EXAMPLE_FAMILY = SOUNDS.register("example_sound", () -> makeSound("example_sound"));
    public static SpellSound EXAMPLE_SPELL_SOUND;


    static {
        EXAMPLE = ITEMS.register("star_hat", () -> new ExampleCosmetic(new Item.Properties()));
    }

    static SoundEvent makeSound(String name) {
        return SoundEvent.createVariableRangeEvent(new ResourceLocation(MOD_ID, name));
    }
}
