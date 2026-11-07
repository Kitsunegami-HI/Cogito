package dev.cogito.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where a container window sits, so the cursor can be moved over its slots. */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccessor {
    @Accessor("leftPos")
    int cogito$leftPos();

    @Accessor("topPos")
    int cogito$topPos();
}
