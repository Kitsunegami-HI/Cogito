package dev.cogito.mixin;

import dev.cogito.control.Control;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The points every slash command leaves the client, so the setting holds however the command was
 * produced: the chat function, the AI typing into the chat box with the screen tool, or a clicked text link.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
    private void cogito$sendCommand(String command, CallbackInfo ci) {
        if (Control.blocksCommand(command)) {
            ci.cancel();
        }
    }

    /** Clicked text and other unsigned commands; reporting it handled keeps the game from logging a refusal. */
    @Inject(method = "sendUnsignedCommand", at = @At("HEAD"), cancellable = true)
    private void cogito$sendUnsignedCommand(String command, CallbackInfoReturnable<Boolean> cir) {
        if (Control.blocksCommand(command)) {
            cir.setReturnValue(true);
        }
    }
}
