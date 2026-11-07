package dev.cogito.mixin;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.multiplayer.ClientAdvancements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** The game tracks advancement progress here but exposes it only to the advancements screen. */
@Mixin(ClientAdvancements.class)
public interface ClientAdvancementsAccessor {
    @Accessor("progress")
    Map<AdvancementHolder, AdvancementProgress> cogito$progress();
}
