package io.canvasmc.canvas.entity;

import java.util.function.BiPredicate;
import java.util.function.ToIntFunction;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Plugin access to the per-world cap on how many other players each player is shown, configured with
 * {@code players.max-visible} in the Canvas world configuration.
 * <p>
 * The cap is applied by the entity tracker, so a player that is capped out for a viewer is not tracked by that viewer
 * and is not part of {@link org.bukkit.entity.Entity#getTrackedBy()}. It never shows a player the viewer could not be
 * sent otherwise, like a player hidden with {@link Player#hidePlayer(org.bukkit.plugin.Plugin, Player)}.
 */
public final class PlayerVisibilityCap {

    /**
     * A viewer with this permission is not capped.
     */
    public static final String BYPASS_PERMISSION = "canvas.players.max-visible.bypass";

    /**
     * A player with this permission is always shown and does not count towards the cap of any viewer.
     */
    public static final String EXEMPT_PERMISSION = "canvas.players.max-visible.exempt";

    private static volatile @Nullable BiPredicate<Player, Player> pairExemption;
    private static volatile @Nullable ToIntFunction<Player> viewerLimit;

    private PlayerVisibilityCap() {
    }

    /**
     * Sets the predicate that decides which pairs of players belong together, like team mates or trade partners. A
     * target the predicate accepts for a viewer is shown to that viewer even when the target is not among the nearest
     * players the cap allows.
     * <p>
     * The predicate is called on tick region threads, by several of them at once, each time the players shown to a
     * viewer are chosen again, which is about once a second per capped viewer. It must be fast and thread-safe, and it
     * may only read the two players it is given, both of which are owned by the calling region. A predicate that throws
     * is logged once and treated as not accepting the pair.
     * <p>
     * There is one predicate for the whole server. Set it back to {@code null} when the plugin that set it is disabled.
     *
     * @param alwaysShown
     *     tests a viewer and a target, in that order, or {@code null} to remove the current predicate
     */
    public static void setPairExemption(final @Nullable BiPredicate<Player, Player> alwaysShown) {
        pairExemption = alwaysShown;
    }

    /**
     * Gets the predicate that decides which pairs of players belong together
     *
     * @return the current predicate testing a viewer and a target, or {@code null} if none is set
     *
     * @see #setPairExemption(BiPredicate)
     */
    public static @Nullable BiPredicate<Player, Player> getPairExemption() {
        return pairExemption;
    }

    /**
     * Sets the function that gives single viewers a limit of their own, like a player that switched other players off
     * in a hub. It answers how many other players the viewer is shown at most, {@code 0} for none, or a negative number
     * for a viewer without a limit of their own.
     * <p>
     * A limit of their own applies in every world, also in worlds without {@code players.max-visible}, and it is not
     * lifted by {@link #BYPASS_PERMISSION} since it is the choice of the viewer. Where the world has a cap too, the
     * lower of the two applies. Players accepted by the pair exemption and players with {@link #EXEMPT_PERMISSION} are
     * still shown.
     * <p>
     * The function is called on tick region threads, by several of them at once, about once a second for every player
     * on the server. It must be fast and thread-safe, and it may only read the player it is given, which is owned by
     * the calling region. A function that throws is logged once and treated as giving no limit.
     * <p>
     * There is one function for the whole server. Set it back to {@code null} when the plugin that set it is disabled.
     *
     * @param limit
     *     gives the limit of a viewer, or {@code null} to remove the current function
     */
    public static void setViewerLimit(final @Nullable ToIntFunction<Player> limit) {
        viewerLimit = limit;
    }

    /**
     * Gets the function that gives single viewers a limit of their own
     *
     * @return the current function, or {@code null} if none is set
     *
     * @see #setViewerLimit(ToIntFunction)
     */
    public static @Nullable ToIntFunction<Player> getViewerLimit() {
        return viewerLimit;
    }
}
