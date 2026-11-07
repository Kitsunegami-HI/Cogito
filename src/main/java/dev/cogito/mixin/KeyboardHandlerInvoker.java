package dev.cogito.mixin;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The private GLFW callback targets, so virtual events take the same path as real ones. */
@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerInvoker {
    @Invoker("keyPress")
    void cogito$keyPress(long handle, int action, KeyEvent event);

    @Invoker("charTyped")
    void cogito$charTyped(long handle, CharacterEvent event);
}
