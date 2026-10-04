package dev.runitback.config;

import dev.runitback.data.SidebarPreset;
import java.util.ArrayList;
import java.util.List;

/**
 * User-editable settings, stored as {@code config/runitback.json}. Field names are the JSON keys,
 * so renaming a field is a breaking config change.
 */
public final class RunConfig {
	public static final int CURRENT_VERSION = 3;

	/** Lets {@link #normalize()} upgrade older config files. 0 means a file from before versioning. */
	public int configVersion;

	/** When a run counts as failed. */
	public EndRunOn endRunOn = EndRunOn.FIRST_DEATH;
	/** When the run fails, everyone still alive dies too. */
	public boolean sharedDeath = false;
	/** Put dead players into spectator even if the server is not in hardcore mode. */
	public boolean spectatorOnDeath = true;
	/** What starts the run timer in a fresh world. */
	public TimerStart timerStart = TimerStart.FIRST_MOVE;

	/**
	 * Bosses shown on the sidebar, in this order. Killing every boss marked {@code required} wins
	 * the run.
	 */
	public List<Boss> bosses = defaultBosses();

	/**
	 * Optional extra milestones, triggered the first time any player earns the advancement during a
	 * run, e.g. {@code {"advancement": "minecraft:nether/obtain_blaze_rod", "label": "Blaze Rod"}}.
	 * None by default; entering the Nether and the End is tracked as dimensions.
	 */
	public List<Split> splits = new ArrayList<>();

	private static List<Boss> defaultBosses() {
		return new ArrayList<>(List.of(
			new Boss("minecraft:elder_guardian", "Elder Guardian", false),
			new Boss("minecraft:warden", "Warden", false),
			new Boss("minecraft:wither", "Wither", false),
			new Boss("minecraft:ender_dragon", "Ender Dragon", true)
		));
	}

	public Display display = new Display();
	public Reset reset = new Reset();

	private static final List<String> OLD_DEFAULT_SPLITS = List.of(
		"minecraft:story/smelt_iron", "minecraft:story/mine_diamond", "minecraft:story/enter_the_nether",
		"minecraft:nether/find_bastion", "minecraft:nether/find_fortress", "minecraft:nether/obtain_blaze_rod",
		"minecraft:story/follow_ender_eye", "minecraft:story/enter_the_end"
	);
	private static final List<String> OLD_DEFAULT_BOSSES = List.of(
		"minecraft:ender_dragon", "minecraft:wither", "minecraft:warden", "minecraft:elder_guardian"
	);

	public enum EndRunOn {
		/** Any death ends the run. The classic "one of us dies, we all restart". */
		FIRST_DEATH,
		/** The run ends once every player in it has died. */
		ALL_DEAD,
		/** Deaths are only counted; the run never fails. */
		NEVER
	}

	public enum TimerStart {
		/** The first time a player moves after joining a fresh world. */
		FIRST_MOVE,
		/** As soon as the first player joins. */
		FIRST_JOIN,
		/** Only via {@code /run start}. */
		COMMAND
	}

	public static final class Boss {
		public String entity;
		public String label;
		public boolean required;

		public Boss() {
		}

		public Boss(String entity, String label, boolean required) {
			this.entity = entity;
			this.label = label;
			this.required = required;
		}
	}

	public static final class Split {
		public String advancement;
		public String label;

		public Split() {
		}

		public Split(String advancement, String label) {
			this.advancement = advancement;
			this.label = label;
		}
	}

	public static final class Display {
		/** Boss bar with run number and timer. Off by default: the sidebar title already shows the time. */
		public boolean bossbar = false;
		/** Show the sidebar to players who have not chosen otherwise with /run sidebar. */
		public boolean sidebar = true;
		/** Sidebar preset for players who have not picked their own: MIN, NORMAL or MAX. */
		public SidebarPreset sidebarPreset = SidebarPreset.NORMAL;
		/** Big on-screen title for deaths, run end and win. */
		public boolean titles = true;
		public boolean sounds = true;
		/** Chat message when a split is reached. */
		public boolean splitMessages = true;
		/** Tell a player where they died. */
		public boolean deathCoordinates = true;
	}

	public static final class Reset {
		/** Seed for new worlds. Empty means a random seed every run. */
		public String seed = "";
		/** Seconds of on-screen countdown before players are kicked. */
		public int countdownSeconds = 3;
		/** Reset automatically this many seconds after a run ends. 0 disables it. */
		public int autoResetSeconds = 0;
		/** Old worlds to keep in {@code runitback/old-worlds}. 0 deletes them. */
		public int keepOldWorlds = 0;
		/** Entries inside the world folder carried over to the new world. */
		public List<String> preserve = new ArrayList<>(List.of("datapacks"));
		public String kickMessage = "Run over! A new world is being generated - rejoin in a few seconds.";
		/**
		 * Process exit code after a reset stop. Leave at 0 if your host or start script restarts
		 * after any stop; set to e.g. 1 if it only restarts after a crash.
		 */
		public int exitCode = 0;
	}

	/** Fills in anything missing from a hand-edited or older config file, and upgrades old files. */
	public void normalize() {
		RunConfig defaults = new RunConfig();
		if (configVersion < 2) {
			// Version 1 defaults: item splits and Nether/End splits (now dimensions), a 5 second
			// countdown and a different boss order. Replace them; anything customised is kept.
			if (splits != null && splits.stream().allMatch(s -> s != null && OLD_DEFAULT_SPLITS.contains(s.advancement))) {
				splits = defaults.splits;
			}
			if (reset != null && reset.countdownSeconds == 5) {
				reset.countdownSeconds = defaults.reset.countdownSeconds;
			}
			if (bosses != null && bosses.size() == 4 && bosses.stream().allMatch(b -> b != null && OLD_DEFAULT_BOSSES.contains(b.entity))) {
				// Same four bosses: only change the order, keeping labels and "required" choices.
				List<Boss> reordered = new ArrayList<>();
				for (Boss boss : defaultBosses()) {
					bosses.stream().filter(b -> b.entity.equals(boss.entity)).findFirst().ifPresent(reordered::add);
				}
				bosses = reordered;
			}
		}
		if (configVersion < 3 && display != null) {
			// The run time moved into the sidebar title, so the boss bar is now off by default.
			display.bossbar = false;
		}
		configVersion = CURRENT_VERSION;
		if (endRunOn == null) endRunOn = defaults.endRunOn;
		if (timerStart == null) timerStart = defaults.timerStart;
		if (bosses == null) bosses = defaults.bosses;
		if (splits == null) splits = defaults.splits;
		if (display == null) display = defaults.display;
		if (reset == null) reset = defaults.reset;
		if (display.sidebarPreset == null) display.sidebarPreset = SidebarPreset.NORMAL;
		if (reset.seed == null) reset.seed = "";
		if (reset.preserve == null) reset.preserve = new ArrayList<>();
		if (reset.kickMessage == null) reset.kickMessage = defaults.reset.kickMessage;
		reset.countdownSeconds = Math.max(0, reset.countdownSeconds);
		reset.autoResetSeconds = Math.max(0, reset.autoResetSeconds);
		reset.keepOldWorlds = Math.max(0, reset.keepOldWorlds);
		bosses.removeIf(b -> b == null || b.entity == null || b.entity.isBlank());
		splits.removeIf(s -> s == null || s.advancement == null || s.advancement.isBlank());
		for (Boss boss : bosses) {
			if (boss.label == null || boss.label.isBlank()) boss.label = boss.entity;
		}
		for (Split split : splits) {
			if (split.label == null || split.label.isBlank()) split.label = split.advancement;
		}
	}
}
