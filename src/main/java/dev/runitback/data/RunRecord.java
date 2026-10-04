package dev.runitback.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One attempt: a single world from creation until it is won, failed or reset. */
public final class RunRecord {
	public int number;
	public String seed;
	/** Wall-clock times, epoch millis. */
	public long createdAt;
	public Long startedAt;
	public Long endedAt;
	/** Run time: counts only while the run is in progress and someone is online. */
	public long elapsedMs;
	public RunStatus status = RunStatus.WAITING;
	/** Human-readable reason the run ended, e.g. the fatal death message. */
	public String endReason;
	public String endedByUuid;
	public String endedByName;
	public List<DeathRecord> deaths = new ArrayList<>();
	/** Splits in the order they were reached, keyed by advancement id. */
	public Map<String, SplitRecord> splits = new LinkedHashMap<>();
	/** First time anyone entered each dimension, keyed by dimension id. The overworld is never listed. */
	public Map<String, SplitRecord> dimensions = new LinkedHashMap<>();
	/** Bosses killed this run, keyed by entity type id. */
	public Map<String, BossKill> bosses = new LinkedHashMap<>();
	/** Per-player stats for this run, keyed by UUID. */
	public Map<String, RunPlayer> players = new LinkedHashMap<>();

	public void normalize() {
		if (status == null) status = RunStatus.WAITING;
		if (deaths == null) deaths = new ArrayList<>();
		if (splits == null) splits = new LinkedHashMap<>();
		if (dimensions == null) dimensions = new LinkedHashMap<>();
		if (bosses == null) bosses = new LinkedHashMap<>();
		if (players == null) players = new LinkedHashMap<>();
	}

	public RunPlayer player(String uuid, String name) {
		RunPlayer player = players.computeIfAbsent(uuid, k -> new RunPlayer());
		if (name != null) player.name = name;
		return player;
	}

	public boolean isOver() {
		return status.isOver();
	}
}
