package dev.cogito.compat;

/**
 * The kinds of container slot click, by the names every supported Minecraft version uses for them
 * (26.1 calls the type ContainerInput, earlier versions ClickType). {@link Mc#click} sends one.
 */
public enum Click {
    PICKUP,
    QUICK_MOVE,
    SWAP,
    CLONE,
    THROW,
    QUICK_CRAFT,
    PICKUP_ALL
}
