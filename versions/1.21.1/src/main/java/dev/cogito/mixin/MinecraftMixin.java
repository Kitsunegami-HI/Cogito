package dev.cogito.mixin;

import dev.cogito.control.Control;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The AI keeps playing while the window is in the background. On this version the pause on lost focus
 * asks isWindowActive too, so this one hook covers both.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "isWindowActive", at = @At("HEAD"), cancellable = true)
    private void cogito$isWindowActive(CallbackInfoReturnable<Boolean> cir) {
        if (Control.isAi()) {
            cir.setReturnValue(true);
        }
    }
}
