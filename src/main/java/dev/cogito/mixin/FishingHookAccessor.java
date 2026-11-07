package dev.cogito.mixin;

import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The server tells the client when a fish bites; nothing public exposes it. */
@Mixin(FishingHook.class)
public interface FishingHookAccessor {
    @Accessor("biting")
    boolean cogito$biting();
}
