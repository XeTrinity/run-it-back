package dev.runitback.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** JSON read/write shared by config, run data and the reset marker. Writes are atomic. */
public final class JsonFiles {
	public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();

	private JsonFiles() {
	}

	/** Returns null if the file does not exist. */
	public static <T> T read(Path file, Class<T> type) throws IOException {
		if (!Files.exists(file)) {
			return null;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			return GSON.fromJson(reader, type);
		} catch (RuntimeException e) {
			throw new IOException("Invalid JSON in " + file + ": " + e.getMessage(), e);
		}
	}

	public static void write(Path file, Object value) throws IOException {
		writeString(file, GSON.toJson(value));
	}

	public static void writeString(Path file, String json) throws IOException {
		Path dir = file.toAbsolutePath().getParent();
		Files.createDirectories(dir);
		Path tmp = dir.resolve(file.getFileName() + ".tmp");
		try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			writer.write(json);
		}
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
