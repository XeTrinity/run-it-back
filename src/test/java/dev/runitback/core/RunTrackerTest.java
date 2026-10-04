package dev.runitback.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.runitback.config.RunConfig;
import dev.runitback.data.DeathRecord;
import dev.runitback.data.RunData;
import dev.runitback.data.RunRecord;
import dev.runitback.data.RunStatus;
import dev.runitback.data.SplitRecord;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RunTrackerTest {
	private static final long SECOND = 1_000_000_000L;

	private RunData data;
	private RunConfig config;
	private RunTracker tracker;
	private final List<String> events = new ArrayList<>();

	@BeforeEach
	void setUp() {
		data = new RunData();
		config = new RunConfig();
		config.normalize();
		config.splits.add(new RunConfig.Split("minecraft:nether/find_fortress", "Fortress"));
		tracker = new RunTracker(data, config, () -> 1000L);
		tracker.setListener(new RunTracker.Listener() {
			@Override
			public void runStarted(RunRecord run) {
				events.add("started");
			}

			@Override
			public void playerDied(RunRecord run, DeathRecord death) {
				events.add("died " + death.name + (death.fatal ? " fatal" : ""));
			}

			@Override
			public void splitReached(RunRecord run, SplitRecord split, Long previousBest) {
				events.add("split " + split.label + " best=" + previousBest);
			}

			@Override
			public void runEnded(RunRecord run) {
				events.add("ended " + run.status);
			}
		});
	}

	private static RunTracker.DeathInput death(String uuid, String name) {
		return new RunTracker.DeathInput(uuid, name, name + " fell", "minecraft:fall", null, "minecraft:overworld", 1, 2, 3);
	}

	@Test
	void sameTaggedWorldKeepsRunEvenWhenOver() {
		RunRecord first = tracker.ensureRun("1", null);
		assertEquals(1, first.number);
		tracker.playerJoined("a", "Alice");
		tracker.start();
		tracker.playerDied(death("a", "Alice"));
		assertEquals(RunStatus.FAILED, first.status);

		// Server restarted before resetting: still the failed run, not a new one.
		assertSame(first, tracker.ensureRun("1", 1));
		assertTrue(data.history.isEmpty());
	}

	@Test
	void newWorldArchivesAndAbandonsUnfinishedRun() {
		RunRecord first = tracker.ensureRun("1", null);
		tracker.start();
		RunRecord second = tracker.ensureRun("1", null);
		assertNotSame(first, second);
		assertEquals(2, second.number);
		assertEquals(RunStatus.ABANDONED, first.status);
		assertEquals(List.of(first), data.history);
	}

	@Test
	void timerCountsOnlyWhileRunningAndPlayersOnline() {
		tracker.ensureRun("1", null);
		tracker.tick(0, 1);
		tracker.tick(5 * SECOND, 1);
		assertEquals(0, tracker.current().elapsedMs, "waiting runs do not count");

		tracker.start();
		tracker.tick(10 * SECOND, 1);
		tracker.tick(11 * SECOND, 1);
		tracker.tick(12 * SECOND, 0);
		tracker.tick(20 * SECOND, 0);
		tracker.tick(21 * SECOND, 1);
		tracker.tick(22 * SECOND, 1);
		assertEquals(2000, tracker.current().elapsedMs, "empty-server time is skipped");
	}

	@Test
	void lagSpikeIsCapped() {
		tracker.ensureRun("1", null);
		tracker.start();
		tracker.tick(0, 1);
		tracker.tick(60 * SECOND, 1);
		assertEquals(RunTracker.MAX_TICK_GAP_NANOS / 1_000_000L, tracker.current().elapsedMs);
	}

	@Test
	void firstDeathFailsRunAndCountsAgainstPlayer() {
		tracker.ensureRun("1", null);
		tracker.playerJoined("a", "Alice");
		tracker.playerJoined("b", "Bob");
		tracker.start();
		DeathRecord death = tracker.playerDied(death("b", "Bob"));
		assertTrue(death.fatal);
		assertEquals(RunStatus.FAILED, tracker.current().status);
		assertEquals("b", tracker.current().endedByUuid);
		assertEquals(1, data.players.get("b").runsEnded);
		assertEquals(1, data.players.get("b").deaths);

		// Deaths after the run is over (e.g. shared death) are not recorded.
		assertNull(tracker.playerDied(death("a", "Alice")));
		assertEquals(0, data.players.get("a").deaths);
		assertEquals(List.of("started", "died Bob fatal", "ended FAILED"), events);
	}

	@Test
	void allDeadModeFailsOnlyWhenEveryoneIsDead() {
		config.endRunOn = RunConfig.EndRunOn.ALL_DEAD;
		tracker.ensureRun("1", null);
		tracker.playerJoined("a", "Alice");
		tracker.playerJoined("b", "Bob");
		tracker.start();
		assertFalse(tracker.playerDied(death("a", "Alice")).fatal);
		assertEquals(RunStatus.RUNNING, tracker.current().status);
		assertTrue(tracker.playerDied(death("b", "Bob")).fatal);
		assertEquals(RunStatus.FAILED, tracker.current().status);
	}

	@Test
	void neverModeOnlyCounts() {
		config.endRunOn = RunConfig.EndRunOn.NEVER;
		tracker.ensureRun("1", null);
		tracker.playerJoined("a", "Alice");
		tracker.start();
		tracker.playerDied(death("a", "Alice"));
		tracker.playerDied(death("a", "Alice"));
		assertEquals(RunStatus.RUNNING, tracker.current().status);
		assertEquals(2, tracker.current().deaths.size());
	}

	@Test
	void splitsRecordOnceAndTrackBests() {
		tracker.ensureRun("1", null);
		tracker.start();
		tracker.current().elapsedMs = 60_000;
		assertNotNull(tracker.advancementEarned("a", "Alice", "minecraft:nether/find_fortress"));
		assertNull(tracker.advancementEarned("b", "Bob", "minecraft:nether/find_fortress"), "only the first player counts");
		assertNull(tracker.advancementEarned("a", "Alice", "minecraft:story/root"), "not a configured split");
		assertEquals(60_000L, data.bestSplits.get("minecraft:nether/find_fortress"));

		tracker.ensureRun("2", null);
		tracker.start();
		tracker.current().elapsedMs = 30_000;
		tracker.advancementEarned("a", "Alice", "minecraft:nether/find_fortress");
		assertEquals(30_000L, data.bestSplits.get("minecraft:nether/find_fortress"));
		assertTrue(events.contains("split Fortress best=60000"));
	}

	@Test
	void dimensionFirstsRecordOnceWithBests() {
		tracker.ensureRun("1", null);
		tracker.start();
		tracker.current().elapsedMs = 90_000;
		assertNotNull(tracker.dimensionEntered("a", "Alice", "minecraft:the_nether", "Nether"));
		assertNull(tracker.dimensionEntered("b", "Bob", "minecraft:the_nether", "Nether"), "only the first entry counts");
		assertEquals("Alice", tracker.current().dimensions.get("minecraft:the_nether").name);
		assertEquals(90_000L, data.bestSplits.get(RunTracker.dimensionBestKey("minecraft:the_nether")));
		assertTrue(events.contains("split Nether best=null"));
	}

	@Test
	void killingRequiredBossesWinsRun() {
		tracker.ensureRun("1", null);
		tracker.playerJoined("a", "Alice");
		tracker.start();
		tracker.current().elapsedMs = 5_000;
		tracker.bossKilled("minecraft:wither", "a", "Alice");
		assertEquals(RunStatus.RUNNING, tracker.current().status, "wither is optional by default");
		tracker.bossKilled("minecraft:ender_dragon", "a", "Alice");
		assertEquals(RunStatus.WON, tracker.current().status);
		assertEquals(5_000L, data.bestWinMs);
		assertEquals(1, data.players.get("a").runsWon);
		assertEquals(2, data.players.get("a").bossKills);
	}

	@Test
	void untrackedEntityIsNotABoss() {
		tracker.ensureRun("1", null);
		assertNull(tracker.bossKilled("minecraft:zombie", "a", "Alice"));
	}

	@Test
	void activityStartsWaitingRun() {
		config.timerStart = RunConfig.TimerStart.COMMAND;
		tracker.ensureRun("1", null);
		tracker.playerJoined("a", "Alice");
		tracker.playerMoved();
		assertEquals(RunStatus.WAITING, tracker.current().status);
		tracker.advancementEarned("a", "Alice", "minecraft:nether/find_fortress");
		assertEquals(RunStatus.RUNNING, tracker.current().status);
	}

	@Test
	void joiningCountsRunsPlayedOnce() {
		tracker.ensureRun("1", null);
		tracker.playerJoined("a", "Alice");
		tracker.playerJoined("a", "Alice");
		assertEquals(1, data.players.get("a").runsPlayed);
	}

	@Test
	void abandonDoesNotNotify() {
		tracker.ensureRun("1", null);
		tracker.start();
		assertTrue(tracker.end(RunStatus.ABANDONED, "Reset", null, "op"));
		assertFalse(events.contains("ended ABANDONED"));
		assertFalse(tracker.end(RunStatus.FAILED, "x", null, null), "already over");
	}
}
