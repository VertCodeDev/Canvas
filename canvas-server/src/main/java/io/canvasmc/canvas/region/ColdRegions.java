package io.canvasmc.canvas.region;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemLevel;
import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiPredicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.jspecify.annotations.Nullable;

/**
 * Lets a plugin move whole region files off disk and bring them back on demand, without the server ever creating a
 * blank region over an archived one or holding a file open while the plugin moves it.
 * <p>
 * Two pieces work together. The fetcher is asked for every region file (chunk, entities and poi) the server is about
 * to open while it is missing from disk, so an archived region comes back before anything reads or writes it. A hold
 * closes one region file and keeps it closed until it is released, so the plugin can copy and delete it safely.
 * <p>
 * Nothing here costs anything while no fetcher is installed and no region is held: the region file cache only reads
 * one volatile flag when it misses.
 * <p>
 * ColorGens only: this lives in our fork for cold islands and is never part of a CanvasMC pull request.
 */
public final class ColdRegions {

    private static final long DEFAULT_HOLD_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30L);
    private static final int MAX_FETCHES_PER_OPEN = 3;
    private static final MoonriseRegionFileIO.RegionFileType[] CHUNK_ONLY = {MoonriseRegionFileIO.RegionFileType.CHUNK_DATA};
    private static final MoonriseRegionFileIO.RegionFileType[] ALL_TYPES = MoonriseRegionFileIO.RegionFileType.values();

    private static final Object STATE_LOCK = new Object();
    private static final Map<Path, Hold> HOLDS = new ConcurrentHashMap<>();
    private static final Map<Path, CompletableFuture<Boolean>> FETCHES = new ConcurrentHashMap<>();

    private static volatile @Nullable BiPredicate<String, Path> fetcher;
    private static volatile boolean engaged;
    private static int pendingHolds;
    static volatile long holdTimeoutNanos = DEFAULT_HOLD_TIMEOUT_NANOS;

    private ColdRegions() {
    }

    /**
     * Install the fetcher, or null to remove it. Called with (worldName, regionFile) on the chunk IO thread when a
     * region file is about to be opened (for read OR write) and does not exist on disk. The fetcher returns true when
     * the file now exists (it downloaded it), false when the region is not archived (the server then behaves exactly
     * as vanilla, creating the file as needed), and throws (any RuntimeException, e.g. UncheckedIOException) when the
     * region IS archived but could not be fetched: then the open fails with an IOException so the chunk load or save
     * fails loudly and nothing creates a new file over the archived region. The fetcher is responsible for its own
     * timeout.
     * <p>
     * The region file passed in is absolute and normalised, for example {@code /srv/gens/islands/entities/r.3.-2.mca}.
     *
     * @param newFetcher
     *     the fetcher, or null to go back to vanilla behaviour
     */
    public static void setFetcher(final @Nullable BiPredicate<String, Path> newFetcher) {
        installFetcher(newFetcher);

        // regions remembered as missing before this fetcher could answer for them may be archived
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }

        for (final ServerLevel level : server.getAllLevels()) {
            for (final MoonriseRegionFileIO.RegionFileType type : ALL_TYPES) {
                MoonriseRegionFileIO.getControllerFor(level, type).getCache().canvas$forgetAllMissing();
            }
        }
    }

    static void installFetcher(final @Nullable BiPredicate<String, Path> newFetcher) {
        synchronized (STATE_LOCK) {
            fetcher = newFetcher;
            refreshEngaged();
        }
    }

    /**
     * Flush and close the region file for region (regionX, regionZ) (region coords = chunk >> 5) of the world's main
     * chunk storage (the "region" folder, not entities or poi), and keep it closed: until {@link #release} any attempt
     * to open that file blocks for up to 30 seconds and then fails with an IOException. Completes with the region file
     * path once it is flushed, closed and held. Completes exceptionally if a chunk in that region is currently loaded
     * or has chunk IO in flight (retry later), if the region is already held, or if the world is unknown. Safe to call
     * from any thread.
     *
     * @param worldName
     *     the Bukkit world name
     * @param regionX
     *     the region x, chunk x >> 5
     * @param regionZ
     *     the region z, chunk z >> 5
     * @return the absolute path of the held region file
     */
    public static CompletableFuture<Path> hold(final String worldName, final int regionX, final int regionZ) {
        return holdTypes(worldName, regionX, regionZ, CHUNK_ONLY).thenApply(paths -> paths.get(0));
    }

    /**
     * Release a hold taken by {@link #hold}; waiting opens continue, and go through the fetcher if the file is now
     * gone. Does nothing when the region is not held.
     *
     * @param worldName
     *     the Bukkit world name
     * @param regionX
     *     the region x
     * @param regionZ
     *     the region z
     */
    public static void release(final String worldName, final int regionX, final int regionZ) {
        releaseTypes(worldName, regionX, regionZ, CHUNK_ONLY);
    }

    /**
     * The same as {@link #hold}, for all three storages of the region: the chunk ("region"), "entities" and "poi"
     * files. Either all three are held or, when one fails, none is.
     *
     * @param worldName
     *     the Bukkit world name
     * @param regionX
     *     the region x
     * @param regionZ
     *     the region z
     * @return the absolute paths of the held files, in the order region, poi, entities
     */
    public static CompletableFuture<List<Path>> holdAll(final String worldName, final int regionX, final int regionZ) {
        return holdTypes(worldName, regionX, regionZ, ALL_TYPES);
    }

    /**
     * Release every hold of the region, in all three storages.
     *
     * @param worldName
     *     the Bukkit world name
     * @param regionX
     *     the region x
     * @param regionZ
     *     the region z
     */
    public static void releaseAll(final String worldName, final int regionX, final int regionZ) {
        releaseTypes(worldName, regionX, regionZ, ALL_TYPES);
    }

    /**
     * Whether a fetcher is installed or a region is held or being held. The region file cache reads this, under its
     * own lock, on every miss: when it is false the open is plain vanilla.
     *
     * @return whether opens have to go through {@link Open}
     */
    public static boolean isEngaged() {
        return engaged;
    }

    /**
     * Whether the region file is held right now.
     *
     * @param regionFile
     *     the region file, relative or absolute
     * @return whether opening it would wait for a release
     */
    public static boolean isHeld(final Path regionFile) {
        return HOLDS.containsKey(regionFile.toAbsolutePath().normalize());
    }

    /**
     * Bind the three storages of a freshly constructed level to its Bukkit world name, so the fetcher can be told
     * which world a missing file belongs to. Storages that are never bound (the offline world upgrader) never consult
     * the fetcher.
     *
     * @param level
     *     the level whose storages to bind
     * @param worldName
     *     the Bukkit world name of the level
     */
    public static void bind(final ServerLevel level, final String worldName) {
        for (final MoonriseRegionFileIO.RegionFileType type : ALL_TYPES) {
            MoonriseRegionFileIO.getControllerFor(level, type).getCache().canvas$setColdWorld(worldName);
        }
    }

    private static CompletableFuture<List<Path>> holdTypes(
        final String worldName, final int regionX, final int regionZ, final MoonriseRegionFileIO.RegionFileType[] types
    ) {
        final ServerLevel level = findLevel(worldName);
        if (level == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown world " + worldName));
        }

        if (isRegionLoaded(level, regionX, regionZ)) {
            return CompletableFuture.failedFuture(loadedException(worldName, regionX, regionZ));
        }

        final List<CompletableFuture<Path>> parts = new ArrayList<>(types.length);
        for (final MoonriseRegionFileIO.RegionFileType type : types) {
            parts.add(scheduleHold(level, worldName, type, regionX, regionZ));
        }

        return CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new)).handle((ignored, failure) -> {
            if (failure == null) {
                final List<Path> paths = new ArrayList<>(parts.size());
                for (final CompletableFuture<Path> part : parts) {
                    paths.add(part.join());
                }

                return paths;
            }

            // all or nothing: let go of the storages that did get held
            for (int i = 0; i < parts.size(); ++i) {
                if (!parts.get(i).isCompletedExceptionally()) {
                    releaseTypes(worldName, regionX, regionZ, new MoonriseRegionFileIO.RegionFileType[] {types[i]});
                }
            }

            throw failure instanceof CompletionException completion ? completion : new CompletionException(failure);
        });
    }

    private static CompletableFuture<Path> scheduleHold(
        final ServerLevel level, final String worldName, final MoonriseRegionFileIO.RegionFileType type, final int regionX, final int regionZ
    ) {
        final MoonriseRegionFileIO.RegionDataController controller = MoonriseRegionFileIO.getControllerFor(level, type);
        final CompletableFuture<Path> ret = new CompletableFuture<>();

        // engage before the hold exists, so a miss that takes the storage lock after the hold sees it
        synchronized (STATE_LOCK) {
            ++pendingHolds;
            refreshEngaged();
        }

        // run in the region's IO queue: no chunk IO task of this region runs while the file is closed
        controller.createRegionIoTask(regionX << 5, regionZ << 5, () -> {
            try {
                if (controller.canvas$hasTasksInRegion(regionX, regionZ) || isRegionLoaded(level, regionX, regionZ)) {
                    throw loadedException(worldName, regionX, regionZ);
                }

                ret.complete(holdStorage(controller.getCache(), worldName, type, regionX, regionZ));
            } catch (final Throwable throwable) {
                ret.completeExceptionally(throwable);
            } finally {
                synchronized (STATE_LOCK) {
                    --pendingHolds;
                    refreshEngaged();
                }
            }
        }, Priority.HIGH).queue();

        return ret;
    }

    /**
     * Hold one region file of one storage: register the hold and close the cached file under the storage lock, the
     * lock every open of that storage takes. The caller makes sure no chunk IO of the region runs at the same time.
     */
    static Path holdStorage(
        final RegionFileStorage storage, final String worldName, final MoonriseRegionFileIO.RegionFileType type,
        final int regionX, final int regionZ
    ) throws IOException {
        final Path path = storage.canvas$regionPath(regionX, regionZ);
        final Hold hold = new Hold(worldName, type, regionX, regionZ, storage, path);

        synchronized (STATE_LOCK) {
            engaged = true;
        }

        synchronized (storage) {
            if (HOLDS.putIfAbsent(path, hold) != null) {
                throw new IllegalStateException("Region file " + path + " is already held");
            }

            try {
                storage.canvas$closeRegionFile(regionX, regionZ);
            } catch (final IOException | RuntimeException exception) {
                HOLDS.remove(path, hold);
                hold.released.complete(null);
                throw exception;
            } finally {
                synchronized (STATE_LOCK) {
                    refreshEngaged();
                }
            }
        }

        return path;
    }

    private static void releaseTypes(
        final String worldName, final int regionX, final int regionZ, final MoonriseRegionFileIO.RegionFileType[] types
    ) {
        for (final Hold hold : HOLDS.values()) {
            if (!hold.matches(worldName, regionX, regionZ, types) || !HOLDS.remove(hold.path, hold)) {
                continue;
            }

            hold.storage.canvas$forgetMissing(regionX, regionZ);
            hold.released.complete(null);
        }

        synchronized (STATE_LOCK) {
            refreshEngaged();
        }
    }

    private static void refreshEngaged() {
        engaged = fetcher != null || pendingHolds > 0 || !HOLDS.isEmpty();
    }

    private static @Nullable ServerLevel findLevel(final String worldName) {
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return null;
        }

        for (final ServerLevel level : server.getAllLevels()) {
            if (level.bukkitName.equals(worldName)) {
                return level;
            }
        }

        return null;
    }

    private static boolean isRegionLoaded(final ServerLevel level, final int regionX, final int regionZ) {
        final ChunkSystemServerLevel chunkSystem = (ChunkSystemServerLevel) level;
        final int minX = regionX << 5;
        final int minZ = regionZ << 5;

        for (int chunkZ = minZ; chunkZ < minZ + 32; ++chunkZ) {
            for (int chunkX = minX; chunkX < minX + 32; ++chunkX) {
                if (chunkSystem.moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(CoordinateUtils.getChunkKey(chunkX, chunkZ)) != null) {
                    return true;
                }

                if (((ChunkSystemLevel) level).moonrise$getEntityLookup().getChunk(chunkX, chunkZ) != null) {
                    return true;
                }
            }
        }

        return false;
    }

    private static IllegalStateException loadedException(final String worldName, final int regionX, final int regionZ) {
        return new IllegalStateException("Region " + regionX + "," + regionZ + " of world " + worldName + " has a loaded chunk or chunk IO in flight");
    }

    /**
     * Wait until the hold is released, for at most the given deadline.
     */
    private static void awaitRelease(final Hold hold, final long deadline) throws IOException {
        final long remaining = deadline - System.nanoTime();
        try {
            if (remaining <= 0L) {
                throw new TimeoutException();
            }

            hold.released.get(remaining, TimeUnit.NANOSECONDS);
        } catch (final TimeoutException exception) {
            throw new IOException("Region file " + hold.path + " is held and was not released in time", exception);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for region file " + hold.path + " to be released", exception);
        } catch (final ExecutionException exception) {
            throw new IOException("Waiting for region file " + hold.path + " failed", exception.getCause());
        }
    }

    /**
     * Ask the fetcher for one missing file. Concurrent opens of the same file share one call.
     *
     * @return whether the file is on disk now
     */
    private static boolean fetch(final BiPredicate<String, Path> fetcher, final String worldName, final Path path) throws IOException {
        final CompletableFuture<Boolean> mine = new CompletableFuture<>();
        final CompletableFuture<Boolean> running = FETCHES.putIfAbsent(path, mine);
        if (running != null) {
            return awaitFetch(running, path);
        }

        try {
            final boolean fetched = fetcher.test(worldName, path);
            mine.complete(fetched);
        } catch (final Throwable throwable) {
            mine.completeExceptionally(throwable);
        } finally {
            FETCHES.remove(path, mine);
        }

        return awaitFetch(mine, path);
    }

    private static boolean awaitFetch(final CompletableFuture<Boolean> fetch, final Path path) throws IOException {
        final boolean fetched;
        try {
            fetched = fetch.get();
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching archived region file " + path, exception);
        } catch (final ExecutionException exception) {
            final Throwable cause = exception.getCause();
            if (cause instanceof Error error) {
                throw error;
            }

            throw new IOException("Region file " + path + " is archived and could not be fetched", cause instanceof UncheckedIOException unchecked ? unchecked.getCause() : cause);
        }

        if (fetched && !Files.isRegularFile(path)) {
            throw new IOException("The fetcher reported region file " + path + " as fetched, but it is not on disk");
        }

        return fetched;
    }

    /**
     * One open of a region file that missed the cache while {@link #isEngaged()} was true. The storage asks
     * {@link #mustWait} under its lock right before it would open, create or skip the file; when that returns true
     * the storage leaves its lock, calls {@link #resolve} and tries again.
     */
    public static final class Open {

        private final @Nullable String worldName;
        private final long deadline = System.nanoTime() + holdTimeoutNanos;
        private @Nullable Hold hold;
        private @Nullable BiPredicate<String, Path> pendingFetcher;
        private @Nullable Path pendingPath;
        private boolean notArchived;
        private int fetches;

        public Open(final @Nullable String worldName) {
            this.worldName = worldName;
        }

        /**
         * Called under the storage lock. Returns true when the file is held, or when it is missing and the fetcher
         * has not been asked yet in this open.
         *
         * @param regionFile
         *     the region file the storage is about to open
         * @return whether the storage has to leave its lock and call {@link #resolve}
         */
        public boolean mustWait(final Path regionFile) {
            final Path path = regionFile.toAbsolutePath().normalize();
            final Hold held = HOLDS.get(path);
            if (held != null) {
                this.hold = held;
                return true;
            }

            final BiPredicate<String, Path> current = fetcher;
            if (current == null || this.worldName == null || this.notArchived || Files.isRegularFile(path)) {
                return false;
            }

            this.pendingFetcher = current;
            this.pendingPath = path;
            return true;
        }

        /**
         * Called outside the storage lock: wait for the hold or ask the fetcher, whichever {@link #mustWait} found.
         *
         * @throws IOException
         *     when the hold was not released in time, or the region is archived and could not be fetched
         */
        public void resolve() throws IOException {
            final Hold held = this.hold;
            if (held != null) {
                this.hold = null;
                awaitRelease(held, this.deadline);

                // the plugin may have archived the file during the hold
                this.notArchived = false;
                return;
            }

            final BiPredicate<String, Path> current = this.pendingFetcher;
            final Path path = this.pendingPath;
            this.pendingFetcher = null;
            this.pendingPath = null;
            if (current == null || path == null || this.worldName == null) {
                return;
            }

            if (++this.fetches > MAX_FETCHES_PER_OPEN) {
                throw new IOException("Region file " + path + " kept disappearing after being fetched");
            }

            this.notArchived = !fetch(current, this.worldName, path);
        }
    }

    private static final class Hold {

        private final String worldName;
        private final MoonriseRegionFileIO.RegionFileType type;
        private final int regionX;
        private final int regionZ;
        private final RegionFileStorage storage;
        private final Path path;
        private final CompletableFuture<Void> released = new CompletableFuture<>();

        private Hold(
            final String worldName, final MoonriseRegionFileIO.RegionFileType type, final int regionX, final int regionZ,
            final RegionFileStorage storage, final Path path
        ) {
            this.worldName = worldName;
            this.type = type;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.storage = storage;
            this.path = path;
        }

        private boolean matches(final String worldName, final int regionX, final int regionZ, final MoonriseRegionFileIO.RegionFileType[] types) {
            if (this.regionX != regionX || this.regionZ != regionZ || !this.worldName.equals(worldName)) {
                return false;
            }

            for (final MoonriseRegionFileIO.RegionFileType candidate : types) {
                if (candidate == this.type) {
                    return true;
                }
            }

            return false;
        }
    }
}
