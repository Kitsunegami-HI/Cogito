package dev.cogito.mixin;

import dev.cogito.control.Control;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The takeover key is read here, ahead of every screen, so no modded GUI can swallow it.
 * All other real keys are dropped while the AI drives.
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void cogito$keyPress(long handle, int action, KeyEvent event, CallbackInfo ci) {
        if (Control.onRealKey(action, event) || Control.blocksRealInput()) {
            ci.cancel();
        }
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void cogito$charTyped(long handle, CharacterEvent event, CallbackInfo ci) {
        if (Control.blocksRealInput()) {
            ci.cancel();
        }
    }
}
