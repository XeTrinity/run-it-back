package dev.runitback.server;

import com.google.gson.JsonObject;
import dev.runitback.config.JsonFiles;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * A small file inside the world folder recording which run it belongs to. This is how a restart
 * of the same world is told apart from a brand new world, even with the same seed.
 */
final class WorldTag {
	private static final String FILE_NAME = "runitback-run.json";
	private static final String OLD_FILE_NAME = "hardcoreruns-run.json";

	private WorldTag() {
	}

	private static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(FILE_NAME);
	}

	static Integer read(MinecraftServer server) {
		try {
			JsonObject json = JsonFiles.read(file(server), JsonObject.class);
			if (json == null) {
				// Worlds tagged before the rename to Run It Back.
				json = JsonFiles.read(server.getWorldPath(LevelResource.ROOT).resolve(OLD_FILE_NAME), JsonObject.class);
			}
			return json != null && json.has("run") ? json.get("run").getAsInt() : null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	static void write(MinecraftServer server, int runNumber) throws IOException {
		JsonObject json = new JsonObject();
		json.addProperty("run", runNumber);
		JsonFiles.write(file(server), json);
	}
}
