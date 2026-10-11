package io.canvasmc.canvas.world;

import io.canvasmc.canvas.GlobalConfiguration;
import io.canvasmc.canvas.entity.PlayerVisibilityCap;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.ToIntFunction;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.jspecify.annotations.Nullable;

/**
 * The other players one viewer is allowed to be sent in a world with {@code players.max-visible} set.
 * <p>
 * This is owned by the viewer and only touched by the region that owns the viewer. It holds entity ids and no players,
 * so a listed player that leaves is not kept alive, and since entity ids are never reused their id just stops matching
 * until the next recompute drops it.
 */
public final class VisiblePlayers {

    /**
     * How often, in ticks of the viewer, the players shown to a viewer are chosen again.
     */
    public static final int RECOMPUTE_INTERVAL_TICKS = 20;

    /**
     * A player the viewer is already being sent is ranked as if they were this fraction of their real distance away,
     * so 25% closer than they are. Without this, two players at about the same distance at the edge of the cap would
     * swap places on every recompute, removing and spawning each other for the viewer over and over. A newcomer only
     * takes the place of a shown player by being clearly closer.
     */
    public static final double SHOWN_DISTANCE_FACTOR = 0.75D;

    private static final Comparator<Candidate> NEAREST_FIRST = Comparator.comparingDouble(Candidate::distanceSquared);

    // the last pair exemption predicate that threw, so each one is only logged once
    private static volatile @Nullable Object failedPairExemption;
    // the same for the viewer limit function
    private static volatile @Nullable Object failedViewerLimit;

    static {
        // both are only for players they are given to explicitly, operators included
        register(PlayerVisibilityCap.BYPASS_PERMISSION, "Shows every player in worlds with players.max-visible set");
        register(PlayerVisibilityCap.EXEMPT_PERMISSION, "Is shown to every player in worlds with players.max-visible set");
    }

    private IntOpenHashSet shown = new IntOpenHashSet();
    // how many of the shown players count towards the cap
    private int counted;
    private boolean unlimited;
    private int computedTick;
    private int computedMax;
    // what the viewer is capped at, the lower of the world cap and their own limit
    private int limit;

    private VisiblePlayers() {
    }

    private static void register(final String name, final String description) {
        try {
            Bukkit.getPluginManager().addPermission(new Permission(name, description, PermissionDefault.FALSE));
        } catch (final IllegalArgumentException ignored) {
            // a plugin already defined it, so its default is the one that applies
        }
    }

    /**
     * Checks if the cap lets the viewer be sent the target. This must only be called by the region that owns the
     * viewer, for a target the viewer would be sent without the cap.
     *
     * @param viewer
     *     the player being sent entities
     * @param target
     *     the other player
     * @param max
     *     the value of {@code players.max-visible} in their world, which is not above 0 when only a viewer limit
     *     function made the tracker ask
     *
     * @return {@code false} if the target is capped out for the viewer
     */
    public static boolean allows(final ServerPlayer viewer, final ServerPlayer target, final int max) {
        VisiblePlayers visible = viewer.canvas$visiblePlayers;

        if (visible == null) {
            // first use in this world, compute right away instead of showing nobody or everybody
            visible = viewer.canvas$visiblePlayers = new VisiblePlayers();
            visible.recompute(viewer, max);
        } else {
            final int age = viewer.tickCount - visible.computedTick;

            if (age >= RECOMPUTE_INTERVAL_TICKS || age < 0 || visible.computedMax != max) {
                visible.recompute(viewer, max);
            }
        }

        if (visible.unlimited || visible.shown.contains(target.getId())) {
            return true;
        }

        // below the cap there is nothing to rank, so a player that comes into range is shown at once like without the
        // cap. This is also what shows the players around a viewer that just joined, whose chunks were not sent yet
        // when the first recompute ran
        if (visible.counted < visible.limit && !isCarried(target)) {
            visible.shown.add(target.getId());
            visible.counted++;
            return true;
        }

        return false;
    }

    // a player riding another player is only sent rotations, so it is shown when its carrier is and not on its own
    private static boolean isCarried(final ServerPlayer target) {
        return target.isPassenger() && target.getRootVehicle() instanceof ServerPlayer;
    }

    private void recompute(final ServerPlayer viewer, final int max) {
        // set first, so a tracker update caused from in here doesn't start a second recompute
        this.computedTick = viewer.tickCount;
        this.computedMax = max;

        final RegionizedWorldData worldData = TickRegionScheduler.getCurrentRegionizedWorldData();

        // without a region ticking the world of the viewer there is no list of players we own, so don't cap until there is one
        final int own = ownLimit(viewer);
        final boolean capped = max > 0 && !viewer.getBukkitEntity().hasPermission(PlayerVisibilityCap.BYPASS_PERMISSION);

        // a limit of their own is the choice of the viewer, so the bypass permission only lifts the cap of the world
        if (own >= 0) {
            this.limit = capped ? Math.min(max, own) : own;
        } else {
            this.limit = capped ? max : -1;
        }

        this.unlimited = this.limit < 0 || worldData == null || worldData.world != viewer.level();

        if (this.unlimited) {
            this.shown = new IntOpenHashSet();
            this.counted = 0;
            return;
        }

        final IntOpenHashSet next = new IntOpenHashSet();
        final List<Candidate> ranked = new ArrayList<>();
        final List<ServerPlayer> carried = new ArrayList<>();
        final Entity viewerRoot = viewer.getRootVehicle();

        // only the players of the current region, players owned by other regions cannot be read or tracked from here
        for (final ServerPlayer target : worldData.getLocalPlayers()) {
            if (target == viewer) {
                continue;
            }

            final ChunkMap.TrackedEntity tracker = target.moonrise$getTrackedEntity();

            // out of range, hidden, spectating and vanished players are not sent anyway, so they don't take up a place
            if (tracker == null || !tracker.canvas$updatePlayer(viewer, true)) {
                continue;
            }

            final Entity targetRoot = target.getRootVehicle();

            if (targetRoot == viewerRoot) {
                // riding the viewer, carrying the viewer or in the same vehicle as the viewer
                next.add(target.getId());
                continue;
            }

            if (isCarried(target)) {
                carried.add(target);
                continue;
            }

            if (target.getBukkitEntity().hasPermission(PlayerVisibilityCap.EXEMPT_PERMISSION)) {
                next.add(target.getId());
                continue;
            }

            double distanceSquared = viewer.distanceToSqr(target);

            if (tracker.seenBy.contains(viewer.connection)) {
                distanceSquared *= SHOWN_DISTANCE_FACTOR * SHOWN_DISTANCE_FACTOR;
            }

            ranked.add(new Candidate(target, distanceSquared));
        }

        if (ranked.size() > this.limit) {
            ranked.sort(NEAREST_FIRST);
        }

        final BiPredicate<Player, Player> pairExemption = PlayerVisibilityCap.getPairExemption();

        for (int i = 0, len = ranked.size(); i < len; ++i) {
            final ServerPlayer target = ranked.get(i).player();

            if (i < this.limit || isPairExempt(pairExemption, viewer, target)) {
                next.add(target.getId());
            }
        }

        for (final ServerPlayer target : carried) {
            if (next.contains(target.getRootVehicle().getId())) {
                next.add(target.getId());
            }
        }

        this.shown = next;
        this.counted = Math.min(ranked.size(), this.limit);
    }

    // the limit a plugin gives this viewer alone, negative for none
    private static int ownLimit(final ServerPlayer viewer) {
        final ToIntFunction<Player> viewerLimit = PlayerVisibilityCap.getViewerLimit();

        if (viewerLimit == null) {
            return -1;
        }

        try {
            return viewerLimit.applyAsInt(viewer.getBukkitEntity());
        } catch (final Throwable thrown) {
            // plugin code must not be able to break tracking
            if (failedViewerLimit != viewerLimit) {
                failedViewerLimit = viewerLimit;
                GlobalConfiguration.LOGGER.error("The viewer limit of the max visible players cap threw, treating its viewers as having no limit of their own. This is only logged once", thrown);
            }

            return -1;
        }
    }

    private static boolean isPairExempt(final @Nullable BiPredicate<Player, Player> pairExemption, final ServerPlayer viewer, final ServerPlayer target) {
        if (pairExemption == null) {
            return false;
        }

        try {
            return pairExemption.test(viewer.getBukkitEntity(), target.getBukkitEntity());
        } catch (final Throwable thrown) {
            // plugin code must not be able to break tracking
            if (failedPairExemption != pairExemption) {
                failedPairExemption = pairExemption;
                GlobalConfiguration.LOGGER.error("The pair exemption of the max visible players cap threw, treating its pairs as not exempt. This is only logged once", thrown);
            }

            return false;
        }
    }

    private record Candidate(ServerPlayer player, double distanceSquared) {
    }
}
