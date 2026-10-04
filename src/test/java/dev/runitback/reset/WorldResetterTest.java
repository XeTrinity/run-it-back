package dev.runitback.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class WorldResetterTest {
	@TempDir
	Path server;

	private WorldResetter resetter() {
		return new WorldResetter(server.resolve("runitback"), LoggerFactory.getLogger("test"));
	}

	private Path makeWorld() throws IOException {
		Path world = server.resolve("world");
		Files.createDirectories(world.resolve("region"));
		Files.writeString(world.resolve("level.dat"), "level");
		Files.writeString(world.resolve("region/r.0.0.mca"), "chunks");
		Files.createDirectories(world.resolve("datapacks/pack"));
		Files.writeString(world.resolve("datapacks/pack/pack.mcmeta"), "{}");
		return world;
	}

	private PendingReset marker(Path world) {
		PendingReset reset = new PendingReset();
		reset.worldDir = world.toString();
		reset.runNumber = 7;
		reset.preserve = List.of("datapacks");
		return reset;
	}

	@Test
	void nothingToDoWithoutMarker() throws IOException {
		assertNull(resetter().runPending());
	}

	@Test
	void replacesWorldKeepingDatapacks() throws IOException {
		Path world = makeWorld();
		WorldResetter resetter = resetter();
		resetter.writeMarker(marker(world));

		WorldResetter.Result result = resetter.runPending();
		assertNotNull(result);
		assertFalse(Files.exists(world.resolve("level.dat")));
		assertFalse(Files.exists(world.resolve("region")));
		assertEquals("{}", Files.readString(world.resolve("datapacks/pack/pack.mcmeta")));
		assertFalse(Files.exists(resetter.markerFile()), "marker is consumed");
		Long.parseLong(result.seed());
		assertNull(result.archivedTo());
	}

	@Test
	void fixedSeedIsPassedThrough() throws IOException {
		Path world = makeWorld();
		PendingReset reset = marker(world);
		reset.seed = " my seed ";
		resetter().writeMarker(reset);
		assertEquals("my seed", resetter().runPending().seed());
	}

	@Test
	void archivesOldWorldsAndPrunes() throws IOException {
		WorldResetter resetter = resetter();
		for (int run = 1; run <= 3; run++) {
			Path world = makeWorld();
			PendingReset reset = marker(world);
			reset.runNumber = run;
			reset.keepOldWorlds = 2;
			resetter.writeMarker(reset);
			WorldResetter.Result result = resetter.runPending();
			assertTrue(Files.exists(result.archivedTo().resolve("level.dat")));
			assertEquals("{}", Files.readString(result.archivedTo().resolve("datapacks/pack/pack.mcmeta")),
				"archived worlds retain their own datapacks");
			Files.setLastModifiedTime(result.archivedTo(), java.nio.file.attribute.FileTime.fromMillis(run * 100_000L));
		}
		try (var entries = Files.list(server.resolve("runitback").resolve(WorldResetter.OLD_WORLDS_DIR))) {
			List<String> names = entries.map(p -> p.getFileName().toString()).sorted().toList();
			assertEquals(2, names.size());
		}
	}

	@Test
	void refusesFolderThatIsNotAWorld() throws IOException {
		Path notWorld = server.resolve("important");
		Files.createDirectories(notWorld);
		Files.writeString(notWorld.resolve("file.txt"), "keep me");
		WorldResetter resetter = resetter();
		resetter.writeMarker(marker(notWorld));
		assertThrows(IOException.class, resetter::runPending);
		assertTrue(Files.exists(notWorld.resolve("file.txt")));
		assertTrue(Files.exists(resetter.markerFile()), "marker kept so the admin sees the problem");
	}

	@Test
	void refusesFolderContainingModData() throws IOException {
		WorldResetter resetter = resetter();
		resetter.writeMarker(marker(server));
		assertThrows(IOException.class, resetter::runPending);
	}

	@Test
	void missingWorldStillConsumesMarker() throws IOException {
		WorldResetter resetter = resetter();
		resetter.writeMarker(marker(server.resolve("world")));
		assertNotNull(resetter.runPending());
		assertFalse(Files.exists(resetter.markerFile()));
	}

	@Test
	void retryKeepsDatapacksStagedByAnEarlierFailedReset() throws IOException {
		Path world = makeWorld();
		WorldResetter resetter = resetter();
		resetter.writeMarker(marker(world));
		Path staging = server.resolve("runitback/reset-staging");
		Files.createDirectories(staging);
		Files.move(world.resolve("datapacks"), staging.resolve("datapacks"));
		Path obstruction = server.resolve("runitback/trash");
		Files.writeString(obstruction, "blocks the world move");
		assertThrows(IOException.class, resetter::runPending);
		assertTrue(Files.exists(staging.resolve("datapacks/pack/pack.mcmeta")));
		Files.delete(obstruction);

		resetter.runPending();
		assertEquals("{}", Files.readString(world.resolve("datapacks/pack/pack.mcmeta")));
		assertFalse(Files.exists(resetter.markerFile()));
	}

	@Test
	void interruptedRestoreResumesWithoutMovingTheWorldAgain() throws IOException {
		Path world = makeWorld();
		WorldResetter resetter = resetter();
		PendingReset reset = marker(world);
		reset.keepOldWorlds = 1;
		Path archive = server.resolve("runitback/old-worlds/run-7");
		reset.movedWorldDir = archive.toString();
		resetter.writeMarker(reset);
		Files.createDirectories(archive.getParent());
		Files.move(world, archive);
		Path staging = server.resolve("runitback/reset-staging");
		Files.createDirectories(staging);
		Files.move(archive.resolve("datapacks"), staging.resolve("datapacks"));
		// A partially restored world has no level.dat. It must not trip the wrong-folder guard.
		Files.createDirectories(world.resolve("datapacks/pack"));
		Files.writeString(world.resolve("datapacks/pack/pack.mcmeta"), "partial copy");

		WorldResetter.Result result = resetter.runPending();
		assertEquals(archive, result.archivedTo());
		assertEquals("{}", Files.readString(world.resolve("datapacks/pack/pack.mcmeta")));
		assertTrue(Files.exists(archive.resolve("region/r.0.0.mca")));
		assertFalse(Files.exists(world.resolve("level.dat")));
		assertFalse(Files.exists(staging));
		assertFalse(Files.exists(resetter.markerFile()));
	}

	@Test
	void failedRestoreKeepsStagingAndMarkerForRetry() throws IOException {
		Path world = makeWorld();
		WorldResetter resetter = resetter();
		PendingReset reset = marker(world);
		reset.keepOldWorlds = 1;
		Path archive = server.resolve("runitback/old-worlds/run-7");
		reset.movedWorldDir = archive.toString();
		resetter.writeMarker(reset);
		Files.createDirectories(archive.getParent());
		Files.move(world, archive);
		Path staging = server.resolve("runitback/reset-staging");
		Files.createDirectories(staging);
		Files.move(archive.resolve("datapacks"), staging.resolve("datapacks"));
		Files.createDirectory(world);
		Files.writeString(world.resolve("datapacks"), "blocks restoration");

		assertThrows(IOException.class, resetter::runPending);
		assertTrue(Files.exists(staging.resolve("datapacks/pack/pack.mcmeta")));
		assertTrue(Files.exists(resetter.markerFile()));
		Files.delete(world.resolve("datapacks"));
		resetter.runPending();
		assertEquals("{}", Files.readString(world.resolve("datapacks/pack/pack.mcmeta")));
	}

	@Test
	void crossFilesystemArchiveFailsWithoutDeletingWorld() throws IOException {
		Path otherFilesystem = Path.of("/dev/shm");
		assumeTrue(Files.isDirectory(otherFilesystem) && Files.isWritable(otherFilesystem));
		assumeTrue(!Files.getFileStore(server).equals(Files.getFileStore(otherFilesystem)));
		Path world = Files.createTempDirectory(otherFilesystem, "runitback-archive-test-");
		try {
			Files.writeString(world.resolve("level.dat"), "level");
			Files.createDirectories(world.resolve("region"));
			Files.writeString(world.resolve("region/r.0.0.mca"), "chunks");
			Files.createDirectories(world.resolve("datapacks"));
			Files.writeString(world.resolve("datapacks/pack.mcmeta"), "pack");
			PendingReset reset = marker(world);
			reset.keepOldWorlds = 1;
			WorldResetter resetter = resetter();
			resetter.writeMarker(reset);

			assertThrows(IOException.class, resetter::runPending);
			assertEquals("chunks", Files.readString(world.resolve("region/r.0.0.mca")));
			assertEquals("pack", Files.readString(world.resolve("datapacks/pack.mcmeta")));
			assertTrue(Files.exists(resetter.markerFile()));
			assertThrows(IOException.class, resetter::runPending, "retry must also retain the world");
			assertTrue(Files.exists(world.resolve("level.dat")));
		} finally {
			WorldResetter.deleteRecursively(world);
		}
	}
}
