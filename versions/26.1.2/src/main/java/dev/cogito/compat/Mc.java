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
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.stream.Stream;

/**
 * Minecraft 26.1: the game calls whose names or shapes differ between the supported versions.
 * Every version has a class of this name with the same methods; the shared code calls only these.
 * Ids come back as the version's own id type, so callers keep them in {@code var} or pass them straight on.
 */
public final class Mc {
    public static final String DEFAULT_NAMESPACE = Identifier.DEFAULT_NAMESPACE;

    private Mc() {}

    /** An id from text, "minecraft:" when it names no namespace; a bad_argument error when it is not an id. */
    public static Identifier id(String raw) {
        Identifier parsed = Identifier.tryParse(raw.contains(":") ? raw : "minecraft:" + raw);
        if (parsed == null) {
            throw new ApiError("bad_argument", "'" + raw + "' is not a valid id");
        }
        return parsed;
    }

    /** An id from text, or null when the text is not one. */
    public static Identifier tryId(String text) {
        return Identifier.tryParse(text);
    }

    public static Identifier vanillaId(String path) {
        return Identifier.withDefaultNamespace(path);
    }

    /** A registry key's id as text, such as "minecraft:overworld". */
    public static String key(ResourceKey<?> key) {
        return key.identifier().toString();
    }

    public static String dimension(Level level) {
        return key(level.dimension());
    }

    public static <T> String keyOf(Holder<T> holder) {
        return holder.unwrapKey().map(Mc::key).orElse("");
    }

    public static <T> Stream<Holder.Reference<T>> holders(Registry<T> registry) {
        return registry.listElements();
    }

    /** A registry entry by id, when the registry has one. */
    public static <T> java.util.Optional<Holder.Reference<T>> holder(Registry<T> registry, String raw) {
        return registry.get(id(raw));
    }

    public static HolderLookup.RegistryLookup<Block> blockLookup() {
        return BuiltInRegistries.BLOCK;
    }

    public static int selectedSlot(Inventory inventory) {
        return inventory.getSelectedSlot();
    }

    /** The lowest and highest block layer the world can hold, both inclusive. */
    public static int minY(Level level) {
        return level.getMinY();
    }

    public static int maxY(Level level) {
        return level.getMaxY();
    }

    /** The overworld's time of day in ticks, counting days (24000 a day). */
    public static long dayTime(Level level) {
        return level.getOverworldClockTime();
    }

    public static String profileName(GameProfile profile) {
        return profile.name();
    }

    public static UUID profileId(GameProfile profile) {
        return profile.id();
    }

    public static Stream<TagKey<Item>> tags(ItemStack stack) {
        return stack.typeHolder().tags();
    }

    public static Stream<TagKey<Block>> tags(BlockState state) {
        return state.typeHolder().tags();
    }

    public static boolean solidRender(BlockState state, BlockGetter level, BlockPos pos) {
        return state.isSolidRender();
    }

    /** How much of an entity an explosion at a point can see, 0 to 1, as the game works it out for damage. */
    public static float seenPercent(Vec3 center, Entity entity) {
        return ServerExplosion.getSeenPercent(center, entity);
    }

    /** How long one of this stack's items burns in a furnace, in ticks; 0 when it is no fuel. */
    public static int burnTicks(ItemStack stack) {
        return Minecraft.getInstance().level.fuelValues().burnDuration(stack);
    }

    /** The slot an item goes to when worn, or null when it cannot be worn. */
    public static EquipmentSlot equipSlot(ItemStack stack) {
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        return equippable == null ? null : equippable.slot();
    }

    /** Sends a container slot click through the game's own handling, as a click on that slot would. */
    public static void click(int containerId, int slot, int button, Click click, Player player) {
        Minecraft.getInstance().gameMode.handleContainerInput(containerId, slot, button, ContainerInput.valueOf(click.name()), player);
    }

    /** True while a book and quill shows its signing page (title and Sign and Close). */
    public static boolean bookSigning(net.minecraft.client.gui.screens.Screen screen) {
        return screen instanceof net.minecraft.client.gui.screens.inventory.BookSignScreen;
    }

    // NBT reads that give an empty tag or 0 when the value is missing or of another type.

    public static net.minecraft.nbt.ListTag list(net.minecraft.nbt.CompoundTag tag, String key) {
        return tag.getListOrEmpty(key);
    }

    public static net.minecraft.nbt.ListTag list(net.minecraft.nbt.ListTag list, int index) {
        return list.getListOrEmpty(index);
    }

    public static net.minecraft.nbt.CompoundTag compound(net.minecraft.nbt.ListTag list, int index) {
        return list.getCompoundOrEmpty(index);
    }

    public static int intAt(net.minecraft.nbt.ListTag list, int index) {
        return list.getIntOr(index, 0);
    }

    public static int intOf(net.minecraft.nbt.CompoundTag tag, String key) {
        return tag.getIntOr(key, 0);
    }

    /** A key binding under the controls screen's Miscellaneous heading. */
    public static KeyMapping keyMapping(String name, int key) {
        return new KeyMapping(name, InputConstants.Type.KEYSYM, key, KeyMapping.Category.MISC);
    }
}
