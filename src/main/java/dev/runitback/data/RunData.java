package dev.runitback.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the mod remembers, stored as {@code runitback/data.json} next to the server jar.
 * It lives outside the world folder so it survives world resets.
 */
public final class RunData {
	public static final int SCHEMA_VERSION = 1;

	public int schemaVersion = SCHEMA_VERSION;
	public int nextRunNumber = 1;
	public RunRecord current;
	/** Finished runs, oldest first. */
	public List<RunRecord> history = new ArrayList<>();
	/** Lifetime stats keyed by player UUID. */
	public Map<String, PlayerStats> players = new LinkedHashMap<>();
	/** Fastest time ever reached for each split, keyed by advancement id. */
	public Map<String, Long> bestSplits = new HashMap<>();
	/** Fastest winning run time. */
	public Long bestWinMs;
	/** Sidebar choices keyed by player UUID. Players who never changed theirs have no entry. */
	public Map<String, SidebarPrefs> sidebars = new HashMap<>();

	public void normalize() {
		if (history == null) history = new ArrayList<>();
		if (players == null) players = new LinkedHashMap<>();
		if (bestSplits == null) bestSplits = new HashMap<>();
		if (sidebars == null) sidebars = new HashMap<>();
		// Sections removed in later versions load as null.
		sidebars.values().forEach(prefs -> {
			if (prefs != null && prefs.sections != null) prefs.sections.removeIf(java.util.Objects::isNull);
		});
		sidebars.values().removeIf(java.util.Objects::isNull);
		if (nextRunNumber < 1) nextRunNumber = 1;
		if (current != null) current.normalize();
		for (RunRecord run : history) {
			run.normalize();
		}
	}

	public PlayerStats stats(String uuid, String name) {
		PlayerStats stats = players.computeIfAbsent(uuid, k -> new PlayerStats());
		if (name != null) stats.name = name;
		return stats;
	}

	public SidebarPrefs sidebar(String uuid) {
		return sidebars.computeIfAbsent(uuid, k -> new SidebarPrefs());
	}

	public RunRecord findRun(int number) {
		if (current != null && current.number == number) return current;
		for (RunRecord run : history) {
			if (run.number == number) return run;
		}
		return null;
	}
}
