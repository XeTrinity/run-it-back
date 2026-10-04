package dev.runitback.server;

import dev.runitback.RunItBack;
import dev.runitback.config.RunConfig;
import dev.runitback.core.TimeFormat;
import dev.runitback.data.BossKill;
import dev.runitback.data.RunData;
import dev.runitback.data.RunPlayer;
import dev.runitback.data.RunRecord;
import dev.runitback.data.SidebarPrefs;
import dev.runitback.data.SidebarSection;
import dev.runitback.data.SplitRecord;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.chat.numbers.FixedFormat;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/**
 * Per-player sidebar (with the run time in its title) and an optional boss bar. Both work on unmodded clients.
 *
 * <p>The sidebar is sent to each player as packets instead of living on the server scoreboard, so
 * every player can pick their own layout and nothing is saved into the world.
 */
final class RunDisplay {
	private static final String OBJECTIVE = "runitback";
	/** The client shows at most 15 sidebar lines. */
	private static final int MAX_LINES = 15;

	/** What one client currently shows, so only changed lines are re-sent. */
	private static final class ClientSidebar {
		boolean shown;
		Component title;
		final List<Line> lines = new ArrayList<>();
	}

	private final MinecraftServer server;
	/** Never registered with the server: objectives built on it exist only in packets. */
	private final Scoreboard detachedScoreboard = new Scoreboard();
	private final Map<UUID, ClientSidebar> clients = new HashMap<>();
	private final ServerBossEvent bossbar = new ServerBossEvent(
		UUID.randomUUID(), Component.empty(), BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS
	);
	private Component lastBossbarName;

	RunDisplay(MinecraftServer server) {
		this.server = server;
		// Version 1.0.0 kept its sidebar on the world scoreboard; clear it out of old worlds.
		ServerScoreboard scoreboard = server.getScoreboard();
		for (String name : new String[] {OBJECTIVE, RunItBack.OLD_MOD_ID}) {
			Objective legacy = scoreboard.getObjective(name);
			if (legacy != null) scoreboard.removeObjective(legacy);
		}
	}

	void playerJoined(ServerPlayer player, RunConfig config) {
		if (config.display.bossbar) bossbar.addPlayer(player);
		// A (re)connecting client starts with no sidebar.
		clients.remove(player.getUUID());
	}

	void playerLeft(ServerPlayer player) {
		bossbar.removePlayer(player);
		clients.remove(player.getUUID());
	}

	void update(RunRecord run, RunConfig config, RunData data, List<Dimension> dimensions) {
		updateBossbar(run, config, dimensions);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			updateSidebar(player, run, config, data, dimensions);
		}
	}

	/** Hides every sidebar and the boss bar, e.g. on shutdown or config reload. */
	void remove() {
		bossbar.removeAllPlayers();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ClientSidebar client = clients.get(player.getUUID());
			if (client != null && client.shown) hide(player);
		}
		clients.clear();
	}

	// ---- Boss bar ---------------------------------------------------------------------------

	private void updateBossbar(RunRecord run, RunConfig config, List<Dimension> dimensions) {
		if (!config.display.bossbar || run == null) {
			bossbar.removeAllPlayers();
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			bossbar.addPlayer(player);
		}

		MutableComponent name = Component.literal("Run #" + run.number + "  ").withStyle(ChatFormatting.WHITE)
			.append(Component.literal(TimeFormat.clock(run.elapsedMs)).withStyle(ChatFormatting.YELLOW));
		BossEvent.BossBarColor color;
		switch (run.status) {
			case WAITING -> {
				name.append(Component.literal("  waiting to start").withStyle(ChatFormatting.GRAY));
				color = BossEvent.BossBarColor.YELLOW;
			}
			case WON -> {
				name.append(Component.literal("  WON").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
				color = BossEvent.BossBarColor.PURPLE;
			}
			case FAILED, ABANDONED -> {
				name.append(Component.literal("  FAILED").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
				color = BossEvent.BossBarColor.RED;
			}
			default -> color = BossEvent.BossBarColor.GREEN;
		}
		if (!name.equals(lastBossbarName)) {
			bossbar.setName(name);
			lastBossbarName = name;
		}
		bossbar.setColor(color);

		int total = config.splits.size() + config.bosses.size() + dimensions.size();
		int done = 0;
		for (RunConfig.Split split : config.splits) {
			if (run.splits.containsKey(split.advancement)) done++;
		}
		for (RunConfig.Boss boss : config.bosses) {
			if (run.bosses.containsKey(boss.entity)) done++;
		}
		for (Dimension dimension : dimensions) {
			if (run.dimensions.containsKey(dimension.id())) done++;
		}
		bossbar.setProgress(total == 0 ? 1.0F : (float) done / total);
	}

	// ---- Sidebar ----------------------------------------------------------------------------

	private void updateSidebar(ServerPlayer player, RunRecord run, RunConfig config, RunData data, List<Dimension> dimensions) {
		ClientSidebar client = clients.computeIfAbsent(player.getUUID(), k -> new ClientSidebar());
		SidebarPrefs prefs = data.sidebars.get(player.getStringUUID());
		boolean wanted = run != null && (prefs != null ? prefs.shown(config.display.sidebar) : config.display.sidebar);
		if (!wanted) {
			if (client.shown) {
				hide(player);
				client.shown = false;
			}
			return;
		}

		EnumSet<SidebarSection> sections = prefs != null
			? prefs.sections(config.display.sidebarPreset)
			: config.display.sidebarPreset.sections();
		Component title = title(run);
		List<Line> lines = buildLines(run, config, sections, dimensions);

		if (!client.shown) {
			Objective objective = objective(title);
			player.connection.send(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD));
			player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective));
			client.shown = true;
			client.title = title;
			client.lines.clear();
		} else if (!title.equals(client.title)) {
			player.connection.send(new ClientboundSetObjectivePacket(objective(title), ClientboundSetObjectivePacket.METHOD_CHANGE));
			client.title = title;
		}

		for (int i = 0; i < Math.max(lines.size(), client.lines.size()); i++) {
			// Owner names starting with '#' are hidden by the client, and '.' can't appear in a
			// player name, so these never collide with a real player's score.
			String owner = "hcr." + i;
			if (i >= lines.size()) {
				player.connection.send(new ClientboundResetScorePacket(owner, OBJECTIVE));
			} else if (i >= client.lines.size() || !Objects.equals(client.lines.get(i), lines.get(i))) {
				// The sidebar sorts by score, highest first.
				Line line = lines.get(i);
				// The right-hand column normally shows the score number; FixedFormat puts our text there instead.
				Optional<NumberFormat> right = line.right() == null ? Optional.empty() : Optional.of(new FixedFormat(line.right()));
				player.connection.send(new ClientboundSetScorePacket(owner, OBJECTIVE, MAX_LINES - i, Optional.of(line.left()), right));
			}
		}
		client.lines.clear();
		client.lines.addAll(lines);
	}

	private Objective objective(Component title) {
		return new Objective(detachedScoreboard, OBJECTIVE, ObjectiveCriteria.DUMMY, title, ObjectiveCriteria.RenderType.INTEGER, false, BlankFormat.INSTANCE);
	}

	private void hide(ServerPlayer player) {
		player.connection.send(new ClientboundSetObjectivePacket(objective(Component.empty()), ClientboundSetObjectivePacket.METHOD_REMOVE));
		// Give back a sidebar the server itself shows (e.g. from a datapack).
		Objective serverSidebar = server.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
		if (serverSidebar != null) {
			player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, serverSidebar));
		}
	}

	/** A sidebar line: text on the left, and an optional right-aligned value (time or stats). */
	record Line(Component left, Component right) {
		static Line of(Component left) {
			return new Line(left, null);
		}
	}

	static List<Line> buildLines(RunRecord run, RunConfig config, EnumSet<SidebarSection> sections, List<Dimension> dimensions) {
		List<Line> lines = new ArrayList<>();
		if (sections.contains(SidebarSection.DEATHS) || sections.contains(SidebarSection.STATS)) {
			addPlayers(lines, run, sections.contains(SidebarSection.DEATHS), sections.contains(SidebarSection.STATS));
		}
		if (sections.contains(SidebarSection.DIMS) && !dimensions.isEmpty()) {
			lines.add(header("Dimensions"));
			for (Dimension dimension : dimensions) {
				lines.add(timed(dimension.label(), run.dimensions.get(dimension.id()), ChatFormatting.LIGHT_PURPLE, ChatFormatting.GRAY));
			}
		}
		if (sections.contains(SidebarSection.BOSSES) && !config.bosses.isEmpty()) {
			lines.add(header("Bosses"));
			for (RunConfig.Boss boss : config.bosses) {
				BossKill kill = run.bosses.get(boss.entity);
				lines.add(kill != null
					? new Line(Component.literal(boss.label).withStyle(ChatFormatting.GOLD), time(kill.timeMs))
					: new Line(Component.literal(boss.label).withStyle(ChatFormatting.GRAY), notYet()));
			}
		}
		if (sections.contains(SidebarSection.SPLITS) && !config.splits.isEmpty()) {
			lines.add(header("Splits"));
			for (RunConfig.Split split : config.splits) {
				lines.add(timed(split.label, run.splits.get(split.advancement), ChatFormatting.GREEN, ChatFormatting.GRAY));
			}
		}
		if (lines.isEmpty()) {
			lines.add(Line.of(Component.literal("/run sidebar max").withStyle(ChatFormatting.GRAY)));
		}
		if (lines.size() > MAX_LINES) {
			int hidden = lines.size() - (MAX_LINES - 1);
			lines = new ArrayList<>(lines.subList(0, MAX_LINES - 1));
			lines.add(Line.of(Component.literal("+" + hidden + " more (/run splits)").withStyle(ChatFormatting.DARK_GRAY)));
		}
		return lines;
	}

	/** "HC Run #4  6:11": the run time lives in the title, updated every second. */
	static Component title(RunRecord run) {
		return Component.empty()
			.append(Component.literal("HC Run #" + run.number).withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD))
			.append(Component.literal("  " + TimeFormat.clock(run.elapsedMs)).withStyle(ChatFormatting.YELLOW));
	}

	private static Line header(String text) {
		return Line.of(Component.literal(text).withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
	}

	private static Line timed(String label, SplitRecord reached, ChatFormatting doneColor, ChatFormatting pendingColor) {
		return reached != null
			? new Line(Component.literal(label).withStyle(doneColor), time(reached.timeMs))
			: new Line(Component.literal(label).withStyle(pendingColor), notYet());
	}

	private static Component time(long ms) {
		return Component.literal(TimeFormat.clock(ms)).withStyle(ChatFormatting.YELLOW);
	}

	private static Component notYet() {
		return Component.literal("-").withStyle(ChatFormatting.GRAY);
	}

	/** One line per player: name on the left, deaths and/or stats on the right. */
	private static void addPlayers(List<Line> lines, RunRecord run, boolean deaths, boolean stats) {
		if (run.players.isEmpty()) {
			lines.add(Line.of(Component.literal("No players yet").withStyle(ChatFormatting.GRAY)));
			return;
		}
		for (RunPlayer player : run.players.values()) {
			Component name = Component.literal(Text.name(player.name)).withStyle(player.dead
				? new ChatFormatting[] {ChatFormatting.DARK_GRAY, ChatFormatting.STRIKETHROUGH}
				: new ChatFormatting[] {ChatFormatting.WHITE});
			MutableComponent right = Component.empty();
			if (deaths) {
				right.append(Component.literal("\u2620" + player.deaths).withStyle(player.deaths > 0 ? ChatFormatting.RED : ChatFormatting.GREEN));
			}
			if (stats) {
				if (deaths) right.append(" ");
				right.append(Component.literal("\u2764" + Math.round(player.damageTaken / 2)).withStyle(ChatFormatting.GOLD));
				right.append(Component.literal(" \u2694" + player.mobKills).withStyle(ChatFormatting.AQUA));
			}
			lines.add(new Line(name, right));
		}
	}
}
