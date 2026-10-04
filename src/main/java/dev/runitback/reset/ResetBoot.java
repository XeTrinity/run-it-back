package dev.runitback.reset;

import dev.runitback.RunItBack;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Runs before Minecraft is loaded. If the last session asked for a world reset, the old world is
 * moved away here, so the server boots straight into a freshly generated one.
 */
public final class ResetBoot implements PreLaunchEntrypoint {
	/**
	 * Seed for the world about to be generated, consumed by {@code DedicatedServerPropertiesMixin}.
	 * Null when no reset happened this boot.
	 */
	private static volatile String pendingSeed;

	public static String pendingSeed() {
		return pendingSeed;
	}

	public static void clearPendingSeed() {
		pendingSeed = null;
	}

	@Override
	public void onPreLaunch() {
		if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) return;
		RunItBack.migrateOldNames();
		WorldResetter resetter = new WorldResetter(RunItBack.dataDir(), RunItBack.LOG);
		try {
			WorldResetter.Result result = resetter.runPending();
			if (result == null) return;
			pendingSeed = result.seed();
			RunItBack.LOG.info("Reset world {} - generating a new one with seed {}", result.worldDir(), result.seed());
			if (result.archivedTo() != null) {
				RunItBack.LOG.info("Old world archived to {}", result.archivedTo());
			}
		} catch (Exception e) {
			// Booting into the old world would silently continue a run that was meant to be over.
			throw new IllegalStateException("Run It Back could not reset the world. Fix the problem below, or delete "
				+ resetter.markerFile() + " to cancel the reset.", e);
		}
	}
}
