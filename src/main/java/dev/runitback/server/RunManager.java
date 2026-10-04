package dev.runitback.server;

import dev.runitback.RunItBack;
import dev.runitback.config.ConfigLoader;
import dev.runitback.config.RunConfig;
import dev.runitback.core.DataStore;
import dev.runitback.core.RunTracker;
import dev.runitback.core.TimeFormat;
import dev.runitback.data.BossKill;
import dev.runitback.data.DeathRecord;
import dev.runitback.data.RunData;
import dev.runitback.data.RunPlayer;
import dev.runitback.data.RunRecord;
import dev.runitback.data.RunStatus;
import dev.runitback.data.SplitRecord;
import dev.runitback.reset.ResetBoot;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Connects the {@link RunTracker} to a running server: feeds it game events and announces what
 * happens. One instance per server lifetime.
 */
public final class RunManager implements RunTracker.Listener {
	private static final int SAVE_INTERVAL_TICKS = 20 * 30;
	/** How far a player must walk from where they joined before FIRST_MOVE starts the timer. */
	private static final double START_MOVE_DISTANCE = 1.0;

	private static RunManager instance;

	final MinecraftServer server;
	private final DataStore store;
	private final RunTracker tracker;
	private final RunDisplay display;
	final ResetService reset;
	/** Dimensions other than the overworld, for the "dims" sidebar section. */
	final List<Dimension> dimensions;
	private final Map<UUID, Vec3> joinPositions = new HashMap<>();
	private final Map<UUID, Component> deathMessages = new HashMap<>();
	private int ticks;
	/** Server tick at which an automatic reset fires, or -1. */
	private int autoResetTick = -1;

	private RunManager(MinecraftServer server) {
		this.server = server;
		this.store = new DataStore(RunItBack.dataDir());
		RunData data = store.load();
		this.tracker = new RunTracker(data, ConfigLoader.loadOrDefault(RunItBack.configFile()), System::currentTimeMillis);
		this.tracker.setListener(this);
		this.display = new RunDisplay(server);
		this.reset = new ResetService(this);
		this.dimensions = Dimension.all(server);
	}

	/** The manager for the running server, or null while no server is running. */
	public static RunManager get() {
		return instance;
	}

	static void serverStarted(MinecraftServer server) {
		instance = new RunManager(server);
		instance.attachToWorld();
	}

	static void serverStopping(MinecraftServer server) {
		if (instance == null) return;
		instance.display.remove();
		instance.store.saveNow(instance.tracker.data());
		instance = null;
	}

	private void attachToWorld() {
		String seed = Long.toString(server.getWorldGenSettings().options().seed());
		Integer tagged = WorldTag.read(server);
		RunRecord before = tracker.current();
		RunRecord run = tracker.ensureRun(seed, tagged);
		if (run != before || tagged == null) {
			try {
				WorldTag.write(server, run.number);
			} catch (IOException e) {
				RunItBack.LOG.error("Could not tag the world with its run number; a restart will start a new run", e);
			}
		}
		ResetBoot.clearPendingSeed();
		warnAboutUnknownIds();
		RunItBack.LOG.info("Tracking run #{} ({}) on seed {}", run.number, run.status, seed);
		save();
	}

	/** A typo in the config would otherwise silently make a split or boss impossible. */
	private void warnAboutUnknownIds() {
		for (RunConfig.Split split : config().splits) {
			Identifier id = Identifier.tryParse(split.advancement);
			if (id == null || server.getAdvancements().get(id) == null) {
				RunItBack.LOG.warn("Split '{}' uses unknown advancement '{}'; it can never be reached", split.label, split.advancement);
			}
		}
		for (RunConfig.Boss boss : config().bosses) {
			Identifier id = Identifier.tryParse(boss.entity);
			if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
				RunItBack.LOG.warn("Boss '{}' uses unknown entity '{}'; it can never be killed", boss.label, boss.entity);
			}
		}
	}

	public RunTracker tracker() {
		return tracker;
	}

	public RunConfig config() {
		return tracker.config();
	}

	/** Re-reads the config file. Throws with the parse error so the command can show it. */
	public void reloadConfig() throws IOException {
		tracker.setConfig(ConfigLoader.load(RunItBack.configFile()));
		warnAboutUnknownIds();
		display.remove();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			display.playerJoined(player, config());
		}
		refreshDisplay();
	}

	/** Re-sends the boss bar and every player's sidebar (only lines that changed go out). */
	void refreshDisplay() {
		display.update(tracker.current(), config(), tracker.data(), dimensions);
	}

	void save() {
		tracker.consumeDirty();
		store.saveAsync(tracker.data());
	}

	void saveNow() {
		tracker.consumeDirty();
		store.saveNow(tracker.data());
	}

	// ---- Game events ------------------------------------------------------------------------

	void tick() {
		ticks++;
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		tracker.tick(System.nanoTime(), players.size());

		RunRecord run = tracker.current();
		if (run != null && run.status == RunStatus.WAITING && config().timerStart == RunConfig.TimerStart.FIRST_MOVE) {
			for (ServerPlayer player : players) {
				Vec3 joinedAt = joinPositions.get(player.getUUID());
				if (joinedAt != null && !player.isSpectator()
					&& horizontalDistance(joinedAt, player.position()) > START_MOVE_DISTANCE) {
					tracker.playerMoved();
					break;
				}
			}
		}

		reset.tick();
		if (autoResetTick >= 0 && ticks >= autoResetTick) {
			autoResetTick = -1;
			reset.begin(null, null);
		}
		if (ticks % 20 == 0) {
			refreshDisplay();
		}
		if (ticks % SAVE_INTERVAL_TICKS == 0 && run != null && (run.status == RunStatus.RUNNING || tracker.consumeDirty())) {
			save();
		}
	}

	private static double horizontalDistance(Vec3 a, Vec3 b) {
		double dx = a.x - b.x;
		double dz = a.z - b.z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	void playerJoined(ServerPlayer player) {
		joinPositions.put(player.getUUID(), player.position());
		tracker.playerJoined(player.getStringUUID(), player.getPlainTextName());
		display.playerJoined(player, config());
		refreshDisplay();
		RunRecord run = tracker.current();
		if (run != null) {
			player.sendSystemMessage(statusLine(run));
		}
		save();
	}

	void playerLeft(ServerPlayer player) {
		joinPositions.remove(player.getUUID());
		display.playerLeft(player);
	}

	/** Called at the start of {@code ServerPlayer.die}, while the combat log still explains the death. */
	public void captureDeathMessage(ServerPlayer player, Component message) {
		deathMessages.put(player.getUUID(), message);
	}

	void entityDied(LivingEntity entity, DamageSource source) {
		if (entity instanceof ServerPlayer player) {
			playerDied(player, source);
			return;
		}
		ServerPlayer killer = creditedPlayer(entity, source);
		String type = EntityType.getKey(entity.getType()).toString();
		if (tracker.findBoss(type) != null) {
			tracker.bossKilled(type, killer == null ? null : killer.getStringUUID(), killer == null ? null : killer.getPlainTextName());
		} else if (killer != null && entity instanceof Enemy) {
			tracker.mobKilled(killer.getStringUUID(), killer.getPlainTextName());
		}
	}

	private static ServerPlayer creditedPlayer(LivingEntity entity, DamageSource source) {
		if (source.getEntity() instanceof ServerPlayer player) return player;
		if (entity.getKillCredit() instanceof ServerPlayer player) return player;
		return null;
	}

	private void playerDied(ServerPlayer player, DamageSource source) {
		Component message = deathMessages.remove(player.getUUID());
		if (message == null) message = source.getLocalizedDeathMessage(player);
		Entity killer = source.getEntity();
		String cause = source.typeHolder().unwrapKey().map(key -> key.identifier().toString()).orElse("unknown");
		tracker.playerDied(new RunTracker.DeathInput(
			player.getStringUUID(),
			player.getPlainTextName(),
			message.getString(),
			cause,
			killer == null || killer == player ? null : EntityType.getKey(killer.getType()).toString(),
			player.level().dimension().identifier().toString(),
			player.getBlockX(),
			player.getBlockY(),
			player.getBlockZ()
		));
	}

	void playerChangedDimension(ServerPlayer player, ServerLevel destination) {
		if (destination.dimension() == Level.OVERWORLD) return;
		String id = destination.dimension().identifier().toString();
		tracker.dimensionEntered(player.getStringUUID(), player.getPlainTextName(), id, Dimension.label(id));
	}

	public void playerDamaged(ServerPlayer player, float amount) {
		tracker.playerDamaged(player.getStringUUID(), player.getPlainTextName(), amount);
	}

	void playerRespawned(ServerPlayer player, boolean alive) {
		if (alive || server.isHardcore() || !config().spectatorOnDeath || config().endRunOn == RunConfig.EndRunOn.NEVER) return;
		RunRecord run = tracker.current();
		RunPlayer state = run == null ? null : run.players.get(player.getStringUUID());
		if (state != null && state.dead) {
			player.setGameMode(GameType.SPECTATOR);
		}
	}

	public void advancementCompleted(ServerPlayer player, String advancementId) {
		tracker.advancementEarned(player.getStringUUID(), player.getPlainTextName(), advancementId);
	}

	// ---- Announcements ----------------------------------------------------------------------

	@Override
	public void runStarted(RunRecord run) {
		broadcast(Text.line("Run #" + run.number + " has started. Good luck - don't die!", ChatFormatting.GREEN));
		playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0F, 1.2F);
		save();
	}

	@Override
	public void playerDied(RunRecord run, DeathRecord death) {
		MutableComponent line = Text.prefix()
			.append(Text.of("☠ " + death.message, ChatFormatting.RED))
			.append(Text.of(" at " + TimeFormat.clock(death.timeMs), ChatFormatting.GRAY));
		broadcast(line);

		ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(death.uuid));
		if (player != null && config().display.deathCoordinates) {
			player.sendSystemMessage(Text.line(
				"You died at " + death.x + " " + death.y + " " + death.z + " in " + Text.shortId(death.dimension) + ".", ChatFormatting.GRAY
			));
		}
		if (!death.fatal) {
			title(Text.of("☠ " + Text.name(death.name) + " died", ChatFormatting.RED), Text.of(death.message, ChatFormatting.GRAY));
			playSound(SoundEvents.LIGHTNING_BOLT_THUNDER, 1.0F, 1.0F);
		}
		save();
	}

	@Override
	public void splitReached(RunRecord run, SplitRecord split, Long previousBest) {
		if (config().display.splitMessages) {
			MutableComponent line = Text.prefix()
				.append(Text.of("✔ " + split.label, ChatFormatting.GREEN, ChatFormatting.BOLD))
				.append(Text.of(" " + TimeFormat.clock(split.timeMs), ChatFormatting.WHITE));
			if (previousBest != null) {
				boolean faster = split.timeMs < previousBest;
				line.append(Text.of(" (" + TimeFormat.delta(split.timeMs, previousBest) + ")", faster ? ChatFormatting.GREEN : ChatFormatting.RED));
			}
			line.append(Text.of(" by " + Text.name(split.name), ChatFormatting.GRAY));
			broadcast(line);
			playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.6F, 0.8F);
		}
		save();
	}

	@Override
	public void bossKilled(RunRecord run, BossKill kill) {
		broadcast(Text.prefix()
			.append(Text.of("⚔ " + kill.label + " defeated", ChatFormatting.GOLD, ChatFormatting.BOLD))
			.append(Text.of(" at " + TimeFormat.clock(kill.timeMs) + (kill.name != null ? " - final blow by " + kill.name : ""), ChatFormatting.YELLOW)));
		save();
	}

	@Override
	public void runEnded(RunRecord run) {
		if (run.status == RunStatus.WON) {
			boolean personalBest = run.elapsedMs == tracker.data().bestWinMs;
			title(
				Text.of("RUN COMPLETE!", ChatFormatting.GOLD, ChatFormatting.BOLD),
				Text.of("Run #" + run.number + " in " + TimeFormat.clock(run.elapsedMs) + (personalBest ? " - new best!" : ""), ChatFormatting.YELLOW)
			);
			playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.0F);
		} else {
			title(Text.of("RUN FAILED", ChatFormatting.DARK_RED, ChatFormatting.BOLD), Text.of(run.endReason == null ? "" : run.endReason, ChatFormatting.GRAY));
			playSound(SoundEvents.WITHER_SPAWN, 1.0F, 1.0F);
			if (config().sharedDeath) {
				killEveryoneElse(run);
			}
		}
		for (Component line : summary(run)) {
			broadcast(line);
		}
		int autoReset = config().reset.autoResetSeconds;
		if (autoReset > 0 && reset.supported()) {
			autoResetTick = ticks + autoReset * 20;
			broadcast(Text.line("A new world will be generated in " + autoReset + " seconds.", ChatFormatting.YELLOW));
		}
		saveNow();
	}

	private void killEveryoneElse(RunRecord run) {
		for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
			if (player.isAlive() && !player.isSpectator() && !player.getStringUUID().equals(run.endedByUuid)) {
				player.kill(player.level());
			}
		}
	}

	List<Component> summary(RunRecord run) {
		List<Component> lines = new ArrayList<>();
		String outcome = switch (run.status) {
			case WON -> "was won";
			case FAILED -> "failed";
			case ABANDONED -> "was abandoned";
			default -> "is in progress";
		};
		lines.add(Text.line("Run #" + run.number + " " + outcome + " after " + TimeFormat.clock(run.elapsedMs) + ".",
			run.status == RunStatus.WON ? ChatFormatting.GOLD : ChatFormatting.WHITE));
		lines.add(Text.line("Deaths " + run.deaths.size()
			+ " · Dims " + run.dimensions.size() + "/" + dimensions.size()
			+ (config().splits.isEmpty() ? "" : " · Splits " + run.splits.size() + "/" + config().splits.size())
			+ " · Bosses " + run.bosses.size() + "/" + config().bosses.size(), ChatFormatting.GRAY));

		RunPlayer tank = null;
		RunPlayer slayer = null;
		for (RunPlayer player : run.players.values()) {
			if (tank == null || player.damageTaken > tank.damageTaken) tank = player;
			if (slayer == null || player.mobKills > slayer.mobKills) slayer = player;
		}
		if (tank != null && tank.damageTaken > 0) {
			lines.add(Text.line("Most damage taken: " + tank.name + " (" + Math.round(tank.damageTaken / 2) + " hearts)", ChatFormatting.GRAY));
		}
		if (slayer != null && slayer.mobKills > 0) {
			lines.add(Text.line("Most hostile mobs killed: " + slayer.name + " (" + slayer.mobKills + ")", ChatFormatting.GRAY));
		}
		if (run.isOver() && reset.supported()) {
			lines.add(Text.prefix()
				.append(Text.button("New world", "/run reset", "Ops only: generate a fresh world", ChatFormatting.GREEN))
				.append(" ")
				.append(Text.button("Death log", "/run deaths", "Show every death this run", ChatFormatting.RED))
				.append(" ")
				.append(Text.button("History", "/run history", "Show past runs", ChatFormatting.AQUA)));
		}
		return lines;
	}

	MutableComponent statusLine(RunRecord run) {
		MutableComponent line = Text.prefix().append(Text.of("Run #" + run.number + " ", ChatFormatting.WHITE));
		return switch (run.status) {
			case WAITING -> line.append(Text.of(switch (config().timerStart) {
				case FIRST_MOVE -> "starts when someone moves.";
				case FIRST_JOIN -> "is about to start.";
				case COMMAND -> "starts with /run start.";
			}, ChatFormatting.YELLOW));
			case RUNNING -> line.append(Text.of(TimeFormat.clock(run.elapsedMs) + " - " + run.deaths.size() + " deaths so far.", ChatFormatting.GREEN));
			case WON -> line.append(Text.of("was won in " + TimeFormat.clock(run.elapsedMs) + "!", ChatFormatting.GOLD));
			case FAILED, ABANDONED -> line.append(Text.of("is over. Waiting for an op to start a new world.", ChatFormatting.RED));
		};
	}

	// ---- Output helpers ---------------------------------------------------------------------

	void broadcast(Component message) {
		server.getPlayerList().broadcastSystemMessage(message, false);
	}

	void title(Component title, Component subtitle) {
		if (!config().display.titles) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
			player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
			player.connection.send(new ClientboundSetTitleTextPacket(title));
		}
	}

	void playSound(SoundEvent sound, float volume, float pitch) {
		if (!config().display.sounds) return;
		var holder = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.connection.send(new ClientboundSoundPacket(
				holder, SoundSource.MASTER, player.getX(), player.getY(), player.getZ(), volume, pitch, player.getRandom().nextLong()
			));
		}
	}
}
