package dev.cogito.compat;

import dev.cogito.bot.ApiError;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.stream.Stream;

/**
 * Minecraft 1.21.1: the game calls whose names or shapes differ between the supported versions.
 * Every version has a class of this name with the same methods; the shared code calls only these.
 * Ids come back as the version's own id type, so callers keep them in {@code var} or pass them straight on.
 */
public final class Mc {
    public static final String DEFAULT_NAMESPACE = ResourceLocation.DEFAULT_NAMESPACE;

    private Mc() {}

    /** An id from text, "minecraft:" when it names no namespace; a bad_argument error when it is not an id. */
    public static ResourceLocation id(String raw) {
        ResourceLocation parsed = ResourceLocation.tryParse(raw.contains(":") ? raw : "minecraft:" + raw);
        if (parsed == null) {
            throw new ApiError("bad_argument", "'" + raw + "' is not a valid id");
        }
        return parsed;
    }

    /** An id from text, or null when the text is not one. */
    public static ResourceLocation tryId(String text) {
        return ResourceLocation.tryParse(text);
    }

    public static ResourceLocation vanillaId(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }

    /** A registry key's id as text, such as "minecraft:overworld". */
    public static String key(ResourceKey<?> key) {
        return key.location().toString();
    }

    public static String dimension(Level level) {
        return key(level.dimension());
    }

    public static <T> String keyOf(Holder<T> holder) {
        return holder.unwrapKey().map(Mc::key).orElse("");
    }

    public static <T> Stream<Holder.Reference<T>> holders(Registry<T> registry) {
        return registry.holders();
    }

    /** A registry entry by id, when the registry has one. */
    public static <T> java.util.Optional<Holder.Reference<T>> holder(Registry<T> registry, String raw) {
        return registry.getHolder(id(raw));
    }

    public static HolderLookup.RegistryLookup<Block> blockLookup() {
        return BuiltInRegistries.BLOCK.asLookup();
    }

    public static int selectedSlot(Inventory inventory) {
        return inventory.selected;
    }

    /** The lowest and highest block layer the world can hold, both inclusive. */
    public static int minY(Level level) {
        return level.getMinBuildHeight();
    }

    public static int maxY(Level level) {
        return level.getMaxBuildHeight() - 1;
    }

    /** The overworld's time of day in ticks, counting days (24000 a day). */
    public static long dayTime(Level level) {
        return level.getDayTime();
    }

    public static String profileName(GameProfile profile) {
        return profile.getName();
    }

    public static UUID profileId(GameProfile profile) {
        return profile.getId();
    }

    public static Stream<TagKey<Item>> tags(ItemStack stack) {
        return stack.getTags();
    }

    public static Stream<TagKey<Block>> tags(BlockState state) {
        return state.getTags();
    }

    public static boolean solidRender(BlockState state, BlockGetter level, BlockPos pos) {
        return state.isSolidRender(level, pos);
    }

    /** How much of an entity an explosion at a point can see, 0 to 1, as the game works it out for damage. */
    public static float seenPercent(Vec3 center, Entity entity) {
        return Explosion.getSeenPercent(center, entity);
    }

    /** How long one of this stack's items burns in a furnace, in ticks; 0 when it is no fuel. */
    public static int burnTicks(ItemStack stack) {
        return stack.isEmpty() ? 0 : stack.getBurnTime(RecipeType.SMELTING);
    }

    /** The slot an item goes to when worn, or null when it cannot be worn. */
    public static EquipmentSlot equipSlot(ItemStack stack) {
        Equipable equipable = Equipable.get(stack);
        return equipable == null ? null : equipable.getEquipmentSlot();
    }

    /** Sends a container slot click through the game's own handling, as a click on that slot would. */
    public static void click(int containerId, int slot, int button, Click click, Player player) {
        Minecraft.getInstance().gameMode.handleInventoryMouseClick(containerId, slot, button, ClickType.valueOf(click.name()), player);
    }

    /** True while a book and quill shows its signing page (title and Sign and Close), which here is a mode of the edit screen. */
    public static boolean bookSigning(net.minecraft.client.gui.screens.Screen screen) {
        if (!(screen instanceof net.minecraft.client.gui.screens.inventory.BookEditScreen)) {
            return false;
        }
        net.minecraft.network.chat.Component finalize = net.minecraft.network.chat.Component.translatable("book.finalizeButton");
        for (var child : screen.children()) {
            if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget && widget.visible && widget.getMessage().equals(finalize)) {
                return true;
            }
        }
        return false;
    }

    // NBT reads that give an empty tag or 0 when the value is missing or of another type.

    public static net.minecraft.nbt.ListTag list(net.minecraft.nbt.CompoundTag tag, String key) {
        return tag.get(key) instanceof net.minecraft.nbt.ListTag list ? list : new net.minecraft.nbt.ListTag();
    }

    public static net.minecraft.nbt.ListTag list(net.minecraft.nbt.ListTag list, int index) {
        return list.getList(index);
    }

    public static net.minecraft.nbt.CompoundTag compound(net.minecraft.nbt.ListTag list, int index) {
        return list.getCompound(index);
    }

    public static int intAt(net.minecraft.nbt.ListTag list, int index) {
        return list.getInt(index);
    }

    public static int intOf(net.minecraft.nbt.CompoundTag tag, String key) {
        return tag.getInt(key);
    }

    /** A key binding under the controls screen's Miscellaneous heading. */
    public static KeyMapping keyMapping(String name, int key) {
        return new KeyMapping(name, InputConstants.Type.KEYSYM, key, "key.categories.misc");
    }
}
