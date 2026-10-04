package dev.runitback.config;

import dev.runitback.RunItBack;
import java.io.IOException;
import java.nio.file.Path;

public final class ConfigLoader {
	private ConfigLoader() {
	}

	/**
	 * Reads the config, writing defaults for anything missing so admins can see every option.
	 * Throws if the file exists but is not valid JSON, so a typo is reported instead of silently
	 * replaced by defaults.
	 */
	public static RunConfig load(Path file) throws IOException {
		RunConfig config = JsonFiles.read(file, RunConfig.class);
		if (config == null) config = new RunConfig();
		config.normalize();
		JsonFiles.write(file, config);
		return config;
	}

	/** Like {@link #load} but falls back to defaults on error; used at startup. */
	public static RunConfig loadOrDefault(Path file) {
		try {
			return load(file);
		} catch (IOException e) {
			RunItBack.LOG.error("Could not load {}; using defaults until it is fixed and /run reload is used", file, e);
			RunConfig config = new RunConfig();
			config.normalize();
			return config;
		}
	}
}
