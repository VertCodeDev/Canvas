package io.canvasmc.canvas.entity;

import io.canvasmc.canvas.WorldConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.ChunkEntitySlices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ColorGens: caps how many entities of a kind one chunk holds, as {@code entities.chunk-entity-limit}
 * in canvas-worlds.yml says. Checked when an entity is added to a world, on the region that owns
 * its chunk, so a chunk only ever goes over by the one that was just added. When it does, the
 * oldest of that kind there are removed until it is back at the limit.
 */
public final class ChunkEntityLimit {

    private static final Logger LOGGER = LoggerFactory.getLogger("CanvasChunkEntityLimit");

    private enum Kind {
        ITEM,
        ORB,
        OTHER,
        IGNORED
    }

    private ChunkEntityLimit() {
    }

    /**
     * @param level the world the entity was added to
     * @param added the entity just added
     */
    public static void check(final ServerLevel level, final Entity added) {
        final WorldConfig.Entities.ChunkEntityLimit config = level.canvasConfig().entities.chunkEntityLimit;
        if (!config.enabled) {
            return;
        }

        final Kind kind = kind(added, config);
        final int limit = limit(kind, config);
        if (limit <= 0) {
            return;
        }

        final ChunkEntitySlices slices = level.moonrise$getEntityLookup().getChunk(added.chunkPosition().x(), added.chunkPosition().z());
        if (slices == null) {
            return;
        }

        final List<Entity> same = new ArrayList<>();
        for (final Entity entity : slices.getAllEntities()) {
            if (entity != null && !entity.isRemoved() && kind(entity, config) == kind) {
                same.add(entity);
            }
        }

        final int over = same.size() - limit;
        if (over <= 0) {
            return;
        }

        // The ones that have been there the longest go first.
        same.sort(Comparator.comparingInt((Entity entity) -> entity.tickCount).reversed());
        for (int index = 0; index < over; index++) {
            same.get(index).discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DESPAWN);
        }

        if (config.log) {
            LOGGER.info("Cleared {} {} over the limit of {} in chunk {} {} of {}", over, kind.name().toLowerCase(java.util.Locale.ROOT), limit,
                added.chunkPosition().x(), added.chunkPosition().z(), level.getWorld().getName());
        }
    }

    private static int limit(final Kind kind, final WorldConfig.Entities.ChunkEntityLimit config) {
        return switch (kind) {
            case ITEM -> config.maxItems;
            case ORB -> config.maxExperienceOrbs;
            case OTHER -> config.maxOtherEntities;
            case IGNORED -> 0;
        };
    }

    private static Kind kind(final Entity entity, final WorldConfig.Entities.ChunkEntityLimit config) {
        if (entity instanceof Player || config.skipNamed && entity.hasCustomName()) {
            return Kind.IGNORED;
        }

        if (entity instanceof ItemEntity) {
            return Kind.ITEM;
        }

        if (entity instanceof ExperienceOrb) {
            return Kind.ORB;
        }

        // What builds, holograms and NPCs are made of, and what somebody owns or rides, is never touched.
        if (entity instanceof ArmorStand || entity instanceof HangingEntity || entity instanceof Display
            || entity instanceof Interaction || entity instanceof Marker || entity.isVehicle() || entity.isPassenger()
            || entity instanceof OwnableEntity ownable && ownable.getOwnerReference() != null
            || entity instanceof Leashable leashable && leashable.isLeashed()) {
            return Kind.IGNORED;
        }

        return Kind.OTHER;
    }
}
