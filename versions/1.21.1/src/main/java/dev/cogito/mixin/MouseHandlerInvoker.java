package dev.cogito.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The private GLFW callback targets, so virtual events take the same path as real ones. */
@Mixin(MouseHandler.class)
public interface MouseHandlerInvoker {
    @Invoker("onPress")
    void cogito$onPress(long handle, int button, int action, int modifiers);

    @Invoker("onMove")
    void cogito$onMove(long handle, double x, double y);

    @Invoker("onScroll")
    void cogito$onScroll(long handle, double xoffset, double yoffset);
}
