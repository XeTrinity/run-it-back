package dev.runitback.core;

import dev.runitback.RunItBack;
import dev.runitback.config.JsonFiles;
import dev.runitback.data.RunData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Loads and saves {@link RunData}. The JSON is built on the server thread (so it is a consistent
 * snapshot) and written on a background thread.
 */
public final class DataStore {
	private final Path file;
	private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "RunItBack data writer");
		thread.setDaemon(true);
		return thread;
	});

	public DataStore(Path dataDir) {
		this.file = dataDir.resolve("data.json");
	}

	public RunData load() {
		try {
			RunData data = JsonFiles.read(file, RunData.class);
			if (data == null) data = new RunData();
			data.normalize();
			return data;
		} catch (IOException e) {
			// Never throw away history: keep the broken file and start fresh beside it.
			Path backup = file.resolveSibling("data.broken-" + System.currentTimeMillis() + ".json");
			RunItBack.LOG.error("Could not read {}; moving it to {} and starting with empty run data", file, backup, e);
			try {
				Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException moveError) {
				RunItBack.LOG.error("Could not back up {}", file, moveError);
			}
			RunData data = new RunData();
			data.normalize();
			return data;
		}
	}

	public void saveAsync(RunData data) {
		String json = JsonFiles.GSON.toJson(data);
		writer.execute(() -> write(json));
	}

	/** Saves and waits, for shutdown and resets. */
	public void saveNow(RunData data) {
		String json = JsonFiles.GSON.toJson(data);
		try {
			writer.submit(() -> write(json)).get(10, TimeUnit.SECONDS);
		} catch (Exception e) {
			RunItBack.LOG.error("Timed out saving run data", e);
		}
	}

	private void write(String json) {
		try {
			JsonFiles.writeString(file, json);
		} catch (IOException e) {
			RunItBack.LOG.error("Could not save run data to {}", file, e);
		}
	}
}
