package dev.cogito.mixin;

import dev.cogito.control.Control;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The single point every slash command leaves the client, so the setting holds however the command was
 * produced: the chat function, or the AI typing into the chat box with the screen tool.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
    private void cogito$sendCommand(String command, CallbackInfo ci) {
        if (Control.blocksCommand(command)) {
            ci.cancel();
        }
    }

    @Inject(method = "sendUnattendedCommand", at = @At("HEAD"), cancellable = true)
    private void cogito$sendUnattendedCommand(String command, Screen screenAfterCommand, CallbackInfo ci) {
        if (Control.blocksCommand(command)) {
            ci.cancel();
        }
    }
}
