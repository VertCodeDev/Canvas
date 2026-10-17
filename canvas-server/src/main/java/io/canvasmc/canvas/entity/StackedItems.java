package io.canvasmc.canvas.entity;

import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftItem;
import org.bukkit.entity.Item;

/**
 * The server's side of {@link ItemStacks}, and the label an item entity with a reserve carries.
 */
public final class StackedItems implements ItemStacks.Access {

    private StackedItems() {
    }

    /**
     * Installs the API. Called once while the server starts.
     */
    public static void install() {
        ItemStacks.install(new StackedItems());
    }

    /**
     * @param level
     *     a world
     *
     * @return whether its dropped items stack past their maximum stack size
     */
    public static boolean enabled(final ServerLevel level) {
        return level.canvasConfig().entities.itemStacking.enabled;
    }

    /**
     * The name shown above an item entity that carries a reserve: the item's name and how many there are.
     *
     * @param stack
     *     the entity's stack
     * @param amount
     *     its stack and reserve together
     *
     * @return the label
     */
    public static Component label(final ItemStack stack, final long amount) {
        return Component.empty()
            .append(stack.getHoverName().copy().withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" \u00D7" + String.format(Locale.ROOT, "%,d", amount)).withStyle(ChatFormatting.GRAY));
    }

    @Override
    public boolean isEnabled(final World world) {
        return enabled(((CraftWorld) world).getHandle());
    }

    @Override
    public long getAmount(final Item item) {
        return ((CraftItem) item).getHandle().getStackedAmount();
    }

    @Override
    public void setAmount(final Item item, final long amount) {
        final ItemEntity handle = ((CraftItem) item).getHandle();
        handle.setStackedAmount(amount);
    }
}
