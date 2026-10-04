package dev.runitback.server;

import dev.runitback.RunItBack;
import dev.runitback.data.RunRecord;
import dev.runitback.data.RunStatus;
import dev.runitback.reset.PendingReset;
import dev.runitback.reset.WorldResetter;
import java.io.IOException;
import java.util.ArrayList;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.storage.LevelResource;

/**
 * World reset, server side: count down, write the reset marker, kick everyone and stop the server.
 * The actual world swap happens on the next boot in {@link dev.runitback.reset.ResetBoot}, before
 * Minecraft loads, which is the only point where the world can be replaced safely.
 */
final class ResetService {
	private final RunManager manager;
	/** Ticks left in the countdown, or -1 if no reset is in progress. */
	private int countdownTicks = -1;
	private String seed;
	/** Set once the server is stopping for a reset, so the process can exit with the configured code. */
	private static volatile Integer exitCodeAfterStop;

	ResetService(RunManager manager) {
		this.manager = manager;
	}

	/** Only a dedicated server can stop and come back with a new world. */
	boolean supported() {
		return manager.server.isDedicatedServer();
	}

	boolean inProgress() {
		return countdownTicks >= 0;
	}

	/**
	 * Starts the reset countdown.
	 *
	 * @param seed seed for the new world, or null for the configured default
	 * @param requestedBy name shown to players, or null for an automatic reset
	 */
	void begin(String seed, String requestedBy) {
		if (inProgress() || !supported()) return;
		this.seed = seed != null ? seed : manager.config().reset.seed;

		RunRecord run = manager.tracker().current();
		if (run != null && !run.isOver()) {
			manager.tracker().end(RunStatus.ABANDONED, "Reset" + (requestedBy != null ? " by " + requestedBy : ""), null, requestedBy);
		}
		manager.saveNow();

		countdownTicks = manager.config().reset.countdownSeconds * 20;
		manager.broadcast(Text.line(
			(requestedBy != null ? requestedBy + " is generating" : "Generating") + " a new world"
				+ (countdownTicks > 0 ? " in " + manager.config().reset.countdownSeconds + " seconds" : "") + ". You will be disconnected - just rejoin.",
			ChatFormatting.YELLOW
		));
		RunItBack.LOG.info("World reset requested by {}", requestedBy != null ? requestedBy : "auto-reset");
	}

	void tick() {
		if (countdownTicks < 0) return;
		if (countdownTicks > 0 && countdownTicks % 20 == 0) {
			int seconds = countdownTicks / 20;
			manager.title(Text.of(Integer.toString(seconds), ChatFormatting.YELLOW, ChatFormatting.BOLD), Text.of("New world incoming", ChatFormatting.GRAY));
			manager.playSound(SoundEvents.NOTE_BLOCK_HAT.value(), 1.0F, 1.0F);
		}
		if (countdownTicks-- == 0) {
			finish();
		}
	}

	private void finish() {
		countdownTicks = -1;
		RunRecord run = manager.tracker().current();
		PendingReset marker = new PendingReset();
		marker.worldDir = manager.server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString();
		marker.seed = seed == null ? "" : seed;
		marker.runNumber = run == null ? 0 : run.number;
		marker.keepOldWorlds = manager.config().reset.keepOldWorlds;
		marker.preserve = new ArrayList<>(manager.config().reset.preserve);
		marker.requestedAt = System.currentTimeMillis();
		try {
			new WorldResetter(RunItBack.dataDir(), RunItBack.LOG).writeMarker(marker);
		} catch (IOException e) {
			RunItBack.LOG.error("Could not write the reset marker; reset cancelled", e);
			manager.broadcast(Text.line("World reset failed: " + e.getMessage(), ChatFormatting.RED));
			return;
		}
		manager.saveNow();

		RunItBack.LOG.info("Stopping the server to reset the world. It must be restarted (most hosts do this automatically).");
		Component kick = Component.literal(manager.config().reset.kickMessage);
		for (ServerPlayer player : new ArrayList<>(manager.server.getPlayerList().getPlayers())) {
			player.connection.disconnect(kick);
		}
		exitCodeAfterStop = manager.config().reset.exitCode;
		manager.server.halt(false);
	}

	/** Called once the server has fully stopped and saved. */
	static void serverStopped() {
		Integer code = exitCodeAfterStop;
		exitCodeAfterStop = null;
		if (code != null && code != 0) {
			RunItBack.LOG.info("Exiting with code {} so the host restarts the server", code);
			// Not System.exit: vanilla's shutdown hook waits for the server thread, which would deadlock.
			Runtime.getRuntime().halt(code);
		}
	}
}
