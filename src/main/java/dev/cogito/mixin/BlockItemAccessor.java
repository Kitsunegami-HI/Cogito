package dev.cogito.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** What a click with this item would put down. Each item decides this itself: a torch is a wall torch on a side, for one. */
@Mixin(BlockItem.class)
public interface BlockItemAccessor {
    @Invoker("getPlacementState")
    BlockState cogito$placementState(BlockPlaceContext context);
}
