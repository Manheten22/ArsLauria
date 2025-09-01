package com.example.arslauria.mixin;

import com.hollingsworth.arsnouveau.client.gui.book.InfinityGuiSpellBook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InfinityGuiSpellBook.class) // не указывайте remap = false — пусть ремапит
public abstract class InfinityGuiSpellBookMixin {

    @Shadow
    private int spellWindowOffset;

    @Shadow
    protected abstract void updateWindowOffset(int offset);

    @Inject(method = "init", at = @At("HEAD"))
    private void onInit(CallbackInfo ci) {
        this.spellWindowOffset = 0;
        this.updateWindowOffset(0);
    }
}
