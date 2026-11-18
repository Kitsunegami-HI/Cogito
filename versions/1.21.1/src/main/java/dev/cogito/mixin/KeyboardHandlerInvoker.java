package dev.cogito.mixin;

import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The GLFW callback targets, so virtual events take the same path as real ones. */
@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerInvoker {
    @Invoker("keyPress")
    void cogito$keyPress(long handle, int key, int scancode, int action, int modifiers);

    @Invoker("charTyped")
    void cogito$charTyped(long handle, int codepoint, int modifiers);
}
