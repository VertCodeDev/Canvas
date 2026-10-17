package io.canvasmc.canvas.entity;

import org.bukkit.World;
import org.bukkit.entity.Item;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * Plugin access to item stacking, configured with {@code entities.item-stacking} in the Canvas world configuration.
 * <p>
 * With stacking on, identical dropped items merge into one item entity whatever their maximum stack size, boats and
 * tools included. The items that do not fit in the entity's stack are carried as a reserve: the stack players, hoppers
 * and plugins see stays a normal stack, and it is filled again from the reserve whenever some of it is taken. The
 * amount of an item entity is that stack and the reserve together, and is shown above it while there is a reserve.
 * <p>
 * Adding to an item that lies there with {@link #setAmount(Item, long)} is far cheaper than dropping a new one that
 * merges into it: no entity is spawned, and the label is sent at most once per {@code label-refresh-ticks}.
 * <p>
 * Every method must be called on the region that owns the item.
 */
public final class ItemStacks {

    private static volatile @Nullable Access access;

    private ItemStacks() {
    }

    /**
     * Gets whether item stacking is on in a world.
     *
     * @param world
     *     the world
     *
     * @return {@code true} if dropped items of that world stack past their maximum stack size
     */
    public static boolean isEnabled(final World world) {
        final Access current = access;
        return current != null && current.isEnabled(world);
    }

    /**
     * Gets how many items an item entity carries: its stack and its reserve.
     *
     * @param item
     *     the item entity
     *
     * @return the amount, which is the stack's own amount when it carries no reserve
     */
    public static long getAmount(final Item item) {
        final Access current = access;
        return current == null ? item.getItemStack().getAmount() : current.getAmount(item);
    }

    /**
     * Sets how many items an item entity carries. What does not fit in its stack becomes its reserve, also in a world
     * where stacking is off, where it is still handed out on pickup but no longer merged.
     *
     * @param item
     *     the item entity
     * @param amount
     *     the amount, at least one
     *
     * @throws IllegalArgumentException
     *     if the amount is below one
     */
    public static void setAmount(final Item item, final long amount) {
        if (amount < 1L) {
            throw new IllegalArgumentException("An item entity carries at least one item, not " + amount);
        }

        final Access current = access;
        if (current == null) {
            item.setItemStack(item.getItemStack().asQuantity((int) Math.min(amount, item.getItemStack().getMaxStackSize())));
            return;
        }

        current.setAmount(item, amount);
    }

    /**
     * Installs the server's side of this API.
     *
     * @param implementation
     *     the implementation
     */
    @ApiStatus.Internal
    public static void install(final Access implementation) {
        access = implementation;
    }

    /**
     * The server's side of this API.
     */
    @ApiStatus.Internal
    public interface Access {

        /**
         * @param world
         *     the world
         *
         * @return whether stacking is on there
         */
        boolean isEnabled(World world);

        /**
         * @param item
         *     the item entity
         *
         * @return its stack and reserve together
         */
        long getAmount(Item item);

        /**
         * @param item
         *     the item entity
         * @param amount
         *     the amount, at least one
         */
        void setAmount(Item item, long amount);
    }
}
