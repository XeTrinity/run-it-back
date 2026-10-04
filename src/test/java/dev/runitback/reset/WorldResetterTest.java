package dev.runitback.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
