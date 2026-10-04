package dev.runitback.core;

import dev.runitback.config.RunConfig;
import dev.runitback.data.BossKill;
import dev.runitback.data.DeathRecord;
import dev.runitback.data.PlayerStats;
import dev.runitback.data.RunData;
import dev.runitback.data.RunPlayer;
import dev.runitback.data.RunRecord;
import dev.runitback.data.RunStatus;
import dev.runitback.data.SplitRecord;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The run state machine. Plain Java with no Minecraft types so the rules can be unit tested;
 * {@code RunManager} feeds it game events and turns the listener callbacks into chat, titles and sounds.
 *
 * <p>Every method must be called from one thread (the server thread).
 */
public final class RunTracker {
	/** A single lag spike or a suspended VM never adds more than this to the run time. */
	static final long MAX_TICK_GAP_NANOS = 2_000_000_000L;

	public interface Listener {
		default void runStarted(RunRecord run) {
		}

		default void playerDied(RunRecord run, DeathRecord death) {
		}

		/** {@code previousBest} is the best time before this split, or null if there was none. */
		default void splitReached(RunRecord run, SplitRecord split, Long previousBest) {
		}

		default void bossKilled(RunRecord run, BossKill kill) {
		}

		/** The run was won or failed (not called for resets). */
		default void runEnded(RunRecord run) {
		}
	}

	/** Facts about a death; the tracker fills in run time and whether it ended the run. */
	public record DeathInput(
		String uuid, String name, String message, String cause, String killer, String dimension, int x, int y, int z
	) {
	}

	private final RunData data;
	private final LongSupplier wallClock;
	private RunConfig config;
	private Listener listener = new Listener() {
	};
	private long lastTickNanos = -1;
	private long carryNanos;
	private boolean dirty;

	public RunTracker(RunData data, RunConfig config, LongSupplier wallClock) {
		this.data = Objects.requireNonNull(data);
		this.config = Objects.requireNonNull(config);
		this.wallClock = wallClock;
	}

	public void setListener(Listener listener) {
		this.listener = Objects.requireNonNull(listener);
	}

	public void setConfig(RunConfig config) {
		this.config = Objects.requireNonNull(config);
	}

	public RunConfig config() {
		return config;
	}

	public RunData data() {
		return data;
	}

	public RunRecord current() {
		return data.current;
	}

	/** True if data changed since the last call. */
	public boolean consumeDirty() {
		boolean was = dirty;
		dirty = false;
		return was;
	}

	/**
	 * Matches the world that just loaded to a run. Each world is tagged with its run number; if the
	 * tag matches the current run (even a finished one) that run continues. Otherwise the world is
	 * new, so the current run is archived and a new one starts.
	 *
	 * @param worldRunNumber the run number stored in the world, or null for an untagged world
	 */
	public RunRecord ensureRun(String seed, Integer worldRunNumber) {
		RunRecord run = data.current;
		if (run != null && worldRunNumber != null && worldRunNumber == run.number) {
			return run;
		}
		if (run != null) {
			if (!run.isOver()) {
				end(RunStatus.ABANDONED, "World was replaced", null, null);
			}
			archiveCurrent();
		}
		RunRecord fresh = new RunRecord();
		fresh.number = data.nextRunNumber++;
		fresh.seed = seed;
		fresh.createdAt = wallClock.getAsLong();
		data.current = fresh;
		dirty = true;
		return fresh;
	}

	/** Moves the current run to history. */
	public void archiveCurrent() {
		RunRecord run = data.current;
		if (run == null) return;
		if (!data.history.contains(run)) {
			data.history.add(run);
		}
		data.current = null;
		dirty = true;
	}

	public void playerJoined(String uuid, String name) {
		RunRecord run = data.current;
		if (run == null) return;
		data.stats(uuid, name);
		if (!run.isOver() && !run.players.containsKey(uuid)) {
			run.player(uuid, name);
			data.stats(uuid, name).runsPlayed++;
			dirty = true;
		}
		if (config.timerStart == RunConfig.TimerStart.FIRST_JOIN) {
			start();
		}
	}

	public void playerMoved() {
		if (config.timerStart == RunConfig.TimerStart.FIRST_MOVE) {
			start();
		}
	}

	/** Starts the timer if the run is waiting. Returns true if it started. */
	public boolean start() {
		RunRecord run = data.current;
		if (run == null || run.status != RunStatus.WAITING) return false;
		run.status = RunStatus.RUNNING;
		run.startedAt = wallClock.getAsLong();
		lastTickNanos = -1;
		dirty = true;
		listener.runStarted(run);
		return true;
	}

	/** Advances the run timer. Time only counts while the run is in progress and someone is online. */
	public void tick(long nowNanos, int playersOnline) {
		RunRecord run = data.current;
		if (run == null || run.status != RunStatus.RUNNING || playersOnline <= 0) {
			lastTickNanos = -1;
			return;
		}
		if (lastTickNanos >= 0) {
			long delta = Math.min(Math.max(0, nowNanos - lastTickNanos), MAX_TICK_GAP_NANOS) + carryNanos;
			run.elapsedMs += delta / 1_000_000L;
			carryNanos = delta % 1_000_000L;
		}
		lastTickNanos = nowNanos;
	}

	public DeathRecord playerDied(DeathInput input) {
		RunRecord run = data.current;
		if (run == null || run.isOver()) return null;
		start();

		RunPlayer player = run.player(input.uuid(), input.name());
		player.deaths++;
		player.dead = true;
		data.stats(input.uuid(), input.name()).deaths++;

		DeathRecord death = new DeathRecord();
		death.uuid = input.uuid();
		death.name = input.name();
		death.message = input.message();
		death.cause = input.cause();
		death.killer = input.killer();
		death.dimension = input.dimension();
		death.x = input.x();
		death.y = input.y();
		death.z = input.z();
		death.timeMs = run.elapsedMs;
		death.epochMs = wallClock.getAsLong();
		death.fatal = isFatal(run);
		run.deaths.add(death);
		dirty = true;

		listener.playerDied(run, death);
		if (death.fatal) {
			data.stats(input.uuid(), input.name()).runsEnded++;
			end(RunStatus.FAILED, input.message(), input.uuid(), input.name());
		}
		return death;
	}

	private boolean isFatal(RunRecord run) {
		return switch (config.endRunOn) {
			case FIRST_DEATH -> true;
			case ALL_DEAD -> run.players.values().stream().allMatch(p -> p.dead);
			case NEVER -> false;
		};
	}

	/** Records a split the first time anyone earns a configured advancement this run. */
	public SplitRecord advancementEarned(String uuid, String name, String advancementId) {
		RunRecord run = data.current;
		if (run == null || run.isOver() || run.splits.containsKey(advancementId)) return null;
		RunConfig.Split configured = null;
		for (RunConfig.Split split : config.splits) {
			if (split.advancement.equals(advancementId)) {
				configured = split;
				break;
			}
		}
		if (configured == null) return null;
		start();

		SplitRecord split = new SplitRecord();
		split.id = advancementId;
		split.label = configured.label;
		split.timeMs = run.elapsedMs;
		split.uuid = uuid;
		split.name = name;
		run.splits.put(advancementId, split);

		Long previousBest = data.bestSplits.get(advancementId);
		if (previousBest == null || split.timeMs < previousBest) {
			data.bestSplits.put(advancementId, split.timeMs);
		}
		dirty = true;
		listener.splitReached(run, split, previousBest);
		return split;
	}

	/** Key under which a dimension's best first-entry time is stored in {@code RunData.bestSplits}. */
	public static String dimensionBestKey(String dimensionId) {
		return "dimension:" + dimensionId;
	}

	/**
	 * Records the first time anyone enters a dimension this run. Announced like a split.
	 *
	 * @param label display name, e.g. "Nether"
	 */
	public SplitRecord dimensionEntered(String uuid, String name, String dimensionId, String label) {
		RunRecord run = data.current;
		if (run == null || run.isOver() || run.dimensions.containsKey(dimensionId)) return null;
		start();

		SplitRecord entry = new SplitRecord();
		entry.id = dimensionId;
		entry.label = label;
		entry.timeMs = run.elapsedMs;
		entry.uuid = uuid;
		entry.name = name;
		run.dimensions.put(dimensionId, entry);

		String bestKey = dimensionBestKey(dimensionId);
		Long previousBest = data.bestSplits.get(bestKey);
		if (previousBest == null || entry.timeMs < previousBest) {
			data.bestSplits.put(bestKey, entry.timeMs);
		}
		dirty = true;
		listener.splitReached(run, entry, previousBest);
		return entry;
	}

	/**
	 * Records a boss kill. Only the first kill of each boss type counts. Wins the run once every
	 * required boss is dead.
	 */
	public BossKill bossKilled(String entityId, String killerUuid, String killerName) {
		RunRecord run = data.current;
		if (run == null || run.isOver() || run.bosses.containsKey(entityId)) return null;
		RunConfig.Boss configured = findBoss(entityId);
		if (configured == null) return null;
		start();

		BossKill kill = new BossKill();
		kill.entity = entityId;
		kill.label = configured.label;
		kill.timeMs = run.elapsedMs;
		kill.uuid = killerUuid;
		kill.name = killerName;
		run.bosses.put(entityId, kill);
		if (killerUuid != null) {
			run.player(killerUuid, killerName).bossKills++;
			data.stats(killerUuid, killerName).bossKills++;
		}
		dirty = true;
		listener.bossKilled(run, kill);

		boolean anyRequired = false;
		boolean allRequiredDead = true;
		for (RunConfig.Boss boss : config.bosses) {
			if (boss.required) {
				anyRequired = true;
				allRequiredDead &= run.bosses.containsKey(boss.entity);
			}
		}
		if (anyRequired && allRequiredDead) {
			end(RunStatus.WON, "All required bosses defeated", killerUuid, killerName);
		}
		return kill;
	}

	public RunConfig.Boss findBoss(String entityId) {
		for (RunConfig.Boss boss : config.bosses) {
			if (boss.entity.equals(entityId)) return boss;
		}
		return null;
	}

	public void playerDamaged(String uuid, String name, float amount) {
		RunRecord run = data.current;
		if (run == null || run.status != RunStatus.RUNNING || amount <= 0) return;
		run.player(uuid, name).damageTaken += amount;
		data.stats(uuid, name).damageTaken += amount;
		dirty = true;
	}

	public void mobKilled(String uuid, String name) {
		RunRecord run = data.current;
		if (run == null || run.status != RunStatus.RUNNING) return;
		run.player(uuid, name).mobKills++;
		data.stats(uuid, name).mobKills++;
		dirty = true;
	}

	/**
	 * Ends the current run. WON and FAILED notify the listener; ABANDONED is silent because the
	 * caller is resetting. Does nothing if the run is already over.
	 */
	public boolean end(RunStatus status, String reason, String byUuid, String byName) {
		RunRecord run = data.current;
		if (run == null || run.isOver() || !status.isOver()) return false;
		run.status = status;
		run.endReason = reason;
		run.endedByUuid = byUuid;
		run.endedByName = byName;
		run.endedAt = wallClock.getAsLong();
		lastTickNanos = -1;
		if (status == RunStatus.WON) {
			for (String uuid : run.players.keySet()) {
				PlayerStats stats = data.players.get(uuid);
				if (stats != null) stats.runsWon++;
			}
			if (data.bestWinMs == null || run.elapsedMs < data.bestWinMs) {
				data.bestWinMs = run.elapsedMs;
			}
		}
		dirty = true;
		if (status != RunStatus.ABANDONED) {
			listener.runEnded(run);
		}
		return true;
	}
}
