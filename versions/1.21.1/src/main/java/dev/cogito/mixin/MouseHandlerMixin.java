package dev.cogito.mixin;

import dev.cogito.control.Control;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drops the real mouse while the AI drives; virtual events pass because they set the injecting flag. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void cogito$blockButton(long handle, int button, int action, int modifiers, CallbackInfo ci) {
        if (Control.blocksRealInput()) {
            ci.cancel();
        }
    }

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void cogito$blockMove(long handle, double x, double y, CallbackInfo ci) {
        if (Control.blocksRealInput()) {
            ci.cancel();
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void cogito$blockScroll(long handle, double xoffset, double yoffset, CallbackInfo ci) {
        if (Control.blocksRealInput()) {
            ci.cancel();
        }
    }
}
