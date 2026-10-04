package dev.runitback;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Constants and paths. Must not reference Minecraft classes: the pre-launch reset uses it before
 * Minecraft is loaded.
 */
public final class RunItBack {
	public static final String MOD_ID = "runitback";
	public static final Logger LOG = LoggerFactory.getLogger("Run It Back");

	private RunItBack() {
	}

	/** {@code runitback/} in the server folder: run data, the reset marker and old worlds. */
	public static Path dataDir() {
		return FabricLoader.getInstance().getGameDir().resolve(MOD_ID);
	}

	public static Path configFile() {
		return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json");
	}

	/** The mod's id before it was renamed to Run It Back. */
	public static final String OLD_MOD_ID = "hardcoreruns";

	/**
	 * Moves the data folder and config file over from the old "Hardcore Runs" name, so a server
	 * keeps its history, settings and any pending reset. Never overwrites existing new files.
	 */
	public static void migrateOldNames() {
		moveIfOnlyOld(FabricLoader.getInstance().getGameDir().resolve(OLD_MOD_ID), dataDir());
		moveIfOnlyOld(FabricLoader.getInstance().getConfigDir().resolve(OLD_MOD_ID + ".json"), configFile());
	}

	private static void moveIfOnlyOld(Path old, Path current) {
		if (!Files.exists(old) || Files.exists(current)) return;
		try {
			Files.move(old, current);
			LOG.info("Moved {} to {} (mod renamed to Run It Back)", old, current);
		} catch (IOException e) {
			LOG.error("Could not move {} to {}; move it by hand to keep your run history", old, current, e);
		}
	}
}
