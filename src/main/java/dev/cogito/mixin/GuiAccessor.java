package dev.cogito.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The line shown above the hotbar. Mods tell the player things there, such as why an action was refused; nothing public reads it. */
@Mixin(Gui.class)
public interface GuiAccessor {
    @Accessor("overlayMessageString")
    Component cogito$overlayMessage();

    @Accessor("overlayMessageTime")
    int cogito$overlayMessageTime();
}
