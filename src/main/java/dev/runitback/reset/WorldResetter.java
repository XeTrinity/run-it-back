package dev.runitback.reset;

import dev.runitback.config.JsonFiles;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import org.slf4j.Logger;

/**
 * Replaces the world folder on boot when a {@link PendingReset} marker exists. Runs from the
 * pre-launch entrypoint, so it must not touch any Minecraft class.
 */
public final class WorldResetter {
	public static final String OLD_WORLDS_DIR = "old-worlds";
	static final String STAGING_DIR = "reset-staging";
	static final String TRASH_DIR = "trash";

	/** What happened, so the caller can apply the seed and log it. */
	public record Result(Path worldDir, String seed, Path archivedTo) {
	}

	private final Path dataDir;
	private final Logger log;

	/** @param dataDir the mod's own folder ({@code runitback/}), which holds the marker */
	public WorldResetter(Path dataDir, Logger log) {
		this.dataDir = dataDir.toAbsolutePath().normalize();
		this.log = log;
	}

	public Path markerFile() {
		return dataDir.resolve(PendingReset.FILE_NAME);
	}

	public void writeMarker(PendingReset reset) throws IOException {
		JsonFiles.write(markerFile(), reset);
	}

	/**
	 * Performs the pending reset, if any. Returns null when there is nothing to do.
	 *
	 * @throws IOException if the world could not be replaced; the marker is kept so the next boot retries
	 */
	public Result runPending() throws IOException {
		PendingReset reset = JsonFiles.read(markerFile(), PendingReset.class);
		if (reset == null) return null;
		if (reset.worldDir == null || reset.worldDir.isBlank()) {
			throw new IOException("Reset marker " + markerFile() + " has no worldDir");
		}

		Path worldDir = Path.of(reset.worldDir).toAbsolutePath().normalize();
		checkSafeToDelete(worldDir);

		Path archivedTo = null;
		if (Files.exists(worldDir)) {
			Path staging = dataDir.resolve(STAGING_DIR);
			deleteRecursively(staging);
			Files.createDirectories(staging);
			List<String> preserved = new ArrayList<>();
			for (String name : reset.preserve == null ? List.<String>of() : reset.preserve) {
				Path entry = worldDir.resolve(name).normalize();
				if (!entry.getParent().equals(worldDir) || !Files.exists(entry)) continue;
				Files.move(entry, staging.resolve(entry.getFileName()));
				preserved.add(entry.getFileName().toString());
			}

			archivedTo = removeWorld(worldDir, reset);

			Files.createDirectories(worldDir);
			for (String name : preserved) {
				Files.move(staging.resolve(name), worldDir.resolve(name));
			}
			deleteRecursively(staging);
		}

		String seed = reset.seed == null || reset.seed.isBlank()
			? Long.toString(ThreadLocalRandom.current().nextLong())
			: reset.seed.trim();
		Files.delete(markerFile());
		pruneOldWorlds(reset.keepOldWorlds);
		emptyTrashInBackground();
		return new Result(worldDir, seed, archivedTo);
	}

	/** Refuses anything that does not look like a Minecraft world, to avoid deleting the wrong folder. */
	private void checkSafeToDelete(Path worldDir) throws IOException {
		if (worldDir.getParent() == null) {
			throw new IOException("Refusing to reset filesystem root " + worldDir);
		}
		if (dataDir.startsWith(worldDir)) {
			throw new IOException("Refusing to reset " + worldDir + ": it contains the mod's data folder");
		}
		if (Files.exists(worldDir) && !Files.exists(worldDir.resolve("level.dat"))) {
			try (Stream<Path> entries = Files.list(worldDir)) {
				if (entries.findAny().isPresent()) {
					throw new IOException("Refusing to reset " + worldDir + ": no level.dat, so it does not look like a world");
				}
			}
		}
	}

	/** Moves the world out of the way (fast), either into the archive or into the trash. */
	private Path removeWorld(Path worldDir, PendingReset reset) throws IOException {
		Path target;
		if (reset.keepOldWorlds > 0) {
			target = unique(dataDir.resolve(OLD_WORLDS_DIR).resolve("run-" + reset.runNumber));
		} else {
			target = unique(dataDir.resolve(TRASH_DIR).resolve(worldDir.getFileName() + "-" + System.currentTimeMillis()));
		}
		Files.createDirectories(target.getParent());
		try {
			Files.move(worldDir, target);
		} catch (DirectoryNotEmptyException e) {
			// Different filesystem: a directory move would need a copy. Delete in place instead.
			log.warn("Could not move {} next to the mod data folder; deleting it in place", worldDir);
			deleteRecursively(worldDir);
			return null;
		}
		return reset.keepOldWorlds > 0 ? target : null;
	}

	private static Path unique(Path path) {
		Path candidate = path;
		for (int i = 2; Files.exists(candidate); i++) {
			candidate = path.resolveSibling(path.getFileName() + "-" + i);
		}
		return candidate;
	}

	private void pruneOldWorlds(int keep) {
		Path dir = dataDir.resolve(OLD_WORLDS_DIR);
		if (!Files.isDirectory(dir)) return;
		try (Stream<Path> entries = Files.list(dir)) {
			List<Path> worlds = entries.filter(Files::isDirectory)
				.sorted(Comparator.comparingLong(WorldResetter::lastModified).reversed())
				.toList();
			for (Path old : worlds.subList(Math.min(keep, worlds.size()), worlds.size())) {
				deleteRecursively(old);
			}
		} catch (IOException e) {
			log.warn("Could not prune old worlds in {}", dir, e);
		}
	}

	private static long lastModified(Path path) {
		try {
			return Files.getLastModifiedTime(path).toMillis();
		} catch (IOException e) {
			return 0;
		}
	}

	/** Deleting a big world can take a while; the server does not need to wait for it. */
	private void emptyTrashInBackground() {
		Path trash = dataDir.resolve(TRASH_DIR);
		if (!Files.isDirectory(trash)) return;
		Thread thread = new Thread(() -> {
			try {
				deleteRecursively(trash);
			} catch (IOException e) {
				log.warn("Could not fully delete old world in {}", trash, e);
			}
		}, "RunItBack world cleanup");
		thread.setDaemon(true);
		thread.start();
	}

	static void deleteRecursively(Path root) throws IOException {
		if (!Files.exists(root)) return;
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				if (exc != null) throw exc;
				Files.delete(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}
}
