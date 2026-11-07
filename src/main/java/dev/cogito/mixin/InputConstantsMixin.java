package dev.cogito.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.cogito.control.Control;
import dev.cogito.control.VirtualInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While the AI drives, polled key state comes from the virtual keyboard, and the operating system's
 * cursor is left alone so the person can keep using their mouse outside the game window.
 */
@Mixin(InputConstants.class)
public abstract class InputConstantsMixin {
    @Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
    private static void cogito$isKeyDown(Window window, int key, CallbackInfoReturnable<Boolean> cir) {
        if (Control.isAi()) {
            cir.setReturnValue(VirtualInput.isKeyDown(key));
        }
    }

    @Inject(method = "grabOrReleaseMouse", at = @At("HEAD"), cancellable = true)
    private static void cogito$grab(Window window, int mode, double x, double y, CallbackInfo ci) {
        if (Control.isAi()) {
            ci.cancel();
        }
    }
}
