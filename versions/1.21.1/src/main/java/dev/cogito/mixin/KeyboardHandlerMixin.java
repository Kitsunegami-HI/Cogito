package dev.cogito.mixin;

import dev.cogito.control.Control;
import net.minecraft.client.KeyboardHandler;
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
    private void cogito$keyPress(long handle, int key, int scancode, int action, int modifiers, CallbackInfo ci) {
        if (Control.onRealKey(action, key) || Control.blocksRealInput()) {
            ci.cancel();
        }
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void cogito$charTyped(long handle, int codepoint, int modifiers, CallbackInfo ci) {
        if (Control.blocksRealInput()) {
            ci.cancel();
        }
    }
}
