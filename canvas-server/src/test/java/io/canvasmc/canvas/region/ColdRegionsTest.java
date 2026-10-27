package io.canvasmc.canvas.region;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cold regions against a real region file storage in a temporary folder: a missing file goes through the fetcher for
 * reads and writes, a failed fetch fails the open without creating a file, and a held file stays closed until it is
 * released, or until the hold timeout runs out.
 */
@Tag("Region")
class ColdRegionsTest {

    private static final String WORLD = "islands";
    private static final ChunkPos CHUNK = new ChunkPos(40, -3);
    private static final int REGION_X = 1;
    private static final int REGION_Z = -1;

    @TempDir
    Path temp;

    private Path folder;
    private Path archive;
    private RegionFileStorage storage;

    @BeforeEach
    void setUp() throws IOException {
        this.folder = this.temp.resolve("poi");
        this.archive = Files.createDirectories(this.temp.resolve("archive"));
        this.storage = this.newStorage();
    }

    @AfterEach
    void tearDown() throws IOException {
        ColdRegions.installFetcher(null);
        ColdRegions.releaseAll(WORLD, REGION_X, REGION_Z);
        ColdRegions.holdTimeoutNanos = TimeUnit.SECONDS.toNanos(30L);
        this.storage.close();
    }

    @Test
    void withoutFetcherAMissingRegionReadsEmptyAndWritesCreateIt() throws IOException {
        assertFalse(ColdRegions.isEngaged());
        assertNull(this.storage.read(CHUNK));

        this.storage.write(CHUNK, tag("vanilla"));
        assertTrue(Files.isRegularFile(this.regionFile()));
        assertEquals("vanilla", this.storage.read(CHUNK).getStringOr("value", ""));
    }

    @Test
    void anArchivedRegionIsFetchedBeforeItIsRead() throws IOException {
        this.archiveRegionWith("archived");

        final List<Path> asked = new CopyOnWriteArrayList<>();
        ColdRegions.installFetcher((world, file) -> {
            asked.add(file);
            assertEquals(WORLD, world);
            return this.restore(file);
        });

        assertEquals("archived", this.storage.read(CHUNK).getStringOr("value", ""));
        assertEquals(List.of(this.regionFile()), asked);
    }

    @Test
    void anArchivedRegionIsFetchedBeforeItIsWritten() throws IOException {
        this.archiveRegionWith("archived");
        ColdRegions.installFetcher((world, file) -> this.restore(file));

        this.storage.write(new ChunkPos(CHUNK.x() + 1, CHUNK.z()), tag("neighbour"));
        assertEquals("archived", this.storage.read(CHUNK).getStringOr("value", ""));
    }

    @Test
    void aRegionThatIsNotArchivedBehavesAsVanilla() throws IOException {
        final List<Path> asked = new CopyOnWriteArrayList<>();
        ColdRegions.installFetcher((world, file) -> {
            asked.add(file);
            return false;
        });

        assertNull(this.storage.read(CHUNK));
        this.storage.write(CHUNK, tag("fresh"));

        assertTrue(Files.isRegularFile(this.regionFile()));
        assertEquals(List.of(this.regionFile(), this.regionFile()), asked);
    }

    @Test
    void aFailedFetchFailsTheOpenAndCreatesNothing() {
        ColdRegions.installFetcher((world, file) -> {
            throw new UncheckedIOException(new IOException("bucket unreachable"));
        });

        assertThrows(IOException.class, () -> this.storage.read(CHUNK));
        assertThrows(IOException.class, () -> this.storage.write(CHUNK, tag("blank")));
        assertFalse(Files.exists(this.regionFile()));
    }

    @Test
    void aHeldRegionStaysClosedUntilReleased() throws Exception {
        this.storage.write(CHUNK, tag("held"));
        ColdRegions.installFetcher((world, file) -> this.restore(file));

        final Path held = ColdRegions.holdStorage(this.storage, WORLD, MoonriseRegionFileIO.RegionFileType.CHUNK_DATA, REGION_X, REGION_Z);
        assertEquals(this.regionFile(), held);
        assertNull(this.storage.moonrise$getRegionFileIfLoaded(CHUNK.x(), CHUNK.z()));
        assertTrue(ColdRegions.isHeld(held));

        final CompletableFuture<String> read = CompletableFuture.supplyAsync(() -> {
            try {
                return this.storage.read(CHUNK).getStringOr("value", "");
            } catch (final IOException exception) {
                throw new UncheckedIOException(exception);
            }
        });

        assertThrows(TimeoutException.class, () -> read.get(300L, TimeUnit.MILLISECONDS));

        // archive it while held, the waiting read fetches it back after the release
        Files.move(held, this.archive.resolve(held.getFileName()));
        ColdRegions.release(WORLD, REGION_X, REGION_Z);

        assertEquals("held", read.get(10L, TimeUnit.SECONDS));
        assertFalse(ColdRegions.isHeld(held));
    }

    @Test
    void aHoldThatIsNotReleasedInTimeFailsTheOpen() throws Exception {
        this.storage.write(CHUNK, tag("held"));
        ColdRegions.holdTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(200L);
        ColdRegions.holdStorage(this.storage, WORLD, MoonriseRegionFileIO.RegionFileType.CHUNK_DATA, REGION_X, REGION_Z);

        assertThrows(IOException.class, () -> this.storage.write(CHUNK, tag("late")));
        assertThrows(IllegalStateException.class, () -> ColdRegions.holdStorage(this.storage, WORLD, MoonriseRegionFileIO.RegionFileType.CHUNK_DATA, REGION_X, REGION_Z));
    }

    @Test
    void releaseAllLeavesNoHoldBehind() throws Exception {
        this.storage.write(CHUNK, tag("held"));
        ColdRegions.holdStorage(this.storage, WORLD, MoonriseRegionFileIO.RegionFileType.CHUNK_DATA, REGION_X, REGION_Z);
        assertTrue(ColdRegions.isEngaged());

        ColdRegions.releaseAll(WORLD, REGION_X, REGION_Z);
        assertFalse(ColdRegions.isEngaged());
        assertEquals("held", this.storage.read(CHUNK).getStringOr("value", ""));
    }

    private RegionFileStorage newStorage() {
        final RegionStorageInfo info = new RegionStorageInfo(WORLD, ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace("overworld")), "poi");
        final RegionFileStorage ret = new RegionFileStorage(info, this.folder, true);
        ret.canvas$setColdWorld(WORLD);
        return ret;
    }

    private Path regionFile() {
        return this.folder.resolve("r." + REGION_X + "." + REGION_Z + ".mca").toAbsolutePath().normalize();
    }

    private void archiveRegionWith(final String value) throws IOException {
        this.storage.write(CHUNK, tag(value));
        this.storage.close();
        Files.move(this.regionFile(), this.archive.resolve(this.regionFile().getFileName()));
        this.storage = this.newStorage();
    }

    private boolean restore(final Path file) {
        final Path archived = this.archive.resolve(file.getFileName());
        if (!Files.exists(archived)) {
            return false;
        }

        try {
            Files.move(archived, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }

        return true;
    }

    private static CompoundTag tag(final String value) {
        final CompoundTag ret = new CompoundTag();
        ret.putString("value", value);
        return ret;
    }
}
