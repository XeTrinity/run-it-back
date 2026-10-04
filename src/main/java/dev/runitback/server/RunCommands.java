package dev.runitback.server;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.runitback.RunItBack;
import dev.runitback.config.RunConfig;
import dev.runitback.core.RunTracker;
import dev.runitback.core.TimeFormat;
import dev.runitback.data.BossKill;
import dev.runitback.data.DeathRecord;
import dev.runitback.data.PlayerStats;
import dev.runitback.data.RunData;
import dev.runitback.data.RunRecord;
import dev.runitback.data.RunStatus;
import dev.runitback.data.SidebarPreset;
import dev.runitback.data.SidebarPrefs;
import dev.runitback.data.SidebarSection;
import dev.runitback.data.SplitRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/** The {@code /run} command tree. Read-only subcommands are open to everyone; the rest need op. */
final class RunCommands {
	private static final int HISTORY_PAGE = 8;
	private static final int CONFIRM_TICKS = 20 * 30;

	/** Commands waiting for a second identical use to confirm, keyed by "who|command". */
	private static final Map<String, Integer> pendingConfirmations = new HashMap<>();

	private RunCommands() {
	}

	static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("run")
			.executes(safe(c -> status(c.getSource())))
			.then(Commands.literal("status").executes(safe(c -> status(c.getSource()))))
			.then(Commands.literal("deaths")
				.executes(safe(c -> deaths(c.getSource(), null)))
				.then(Commands.argument("run", IntegerArgumentType.integer(1)).executes(safe(c -> deaths(c.getSource(), IntegerArgumentType.getInteger(c, "run"))))))
			.then(Commands.literal("splits").executes(safe(c -> splits(c.getSource()))))
			.then(Commands.literal("history")
				.executes(safe(c -> history(c.getSource(), 1)))
				.then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(safe(c -> history(c.getSource(), IntegerArgumentType.getInteger(c, "page"))))))
			.then(Commands.literal("stats")
				.executes(safe(c -> stats(c.getSource(), c.getSource().getPlayer() == null ? null : c.getSource().getPlayer().getPlainTextName())))
				.then(Commands.argument("player", StringArgumentType.word())
					.suggests((c, b) -> {
						RunManager m = RunManager.get();
						if (m != null) m.tracker().data().players.values().forEach(p -> b.suggest(p.name));
						return b.buildFuture();
					})
					.executes(safe(c -> stats(c.getSource(), StringArgumentType.getString(c, "player"))))))
			.then(Commands.literal("leaderboard").executes(safe(c -> leaderboard(c.getSource()))))
			.then(Commands.literal("start")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(safe(c -> start(c.getSource()))))
			.then(Commands.literal("end")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("win").executes(safe(c -> end(c, RunStatus.WON))))
				.then(Commands.literal("fail").executes(safe(c -> end(c, RunStatus.FAILED)))))
			.then(Commands.literal("reset")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(safe(c -> reset(c, null, "/run reset", false)))
				.then(Commands.literal("confirm").executes(safe(c -> reset(c, null, "/run reset", true))))
				.then(Commands.literal("same")
					.executes(safe(c -> reset(c, currentSeed(), "/run reset same", false)))
					.then(Commands.literal("confirm").executes(safe(c -> reset(c, currentSeed(), "/run reset same", true)))))
				.then(Commands.literal("seed")
					.then(Commands.argument("seed", StringArgumentType.greedyString())
						.executes(safe(c -> {
							// The seed takes the rest of the line (text seeds can have spaces), so "confirm" is a suffix.
							String seed = StringArgumentType.getString(c, "seed").trim();
							boolean confirmed = seed.endsWith(" confirm");
							if (confirmed) seed = seed.substring(0, seed.length() - " confirm".length()).trim();
							return reset(c, seed, "/run reset seed " + seed, confirmed);
						})))))
			.then(sidebarCommand())
			.then(Commands.literal("reload")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(safe(c -> reload(c.getSource()))))
		);
	}

	/**
	 * {@code /run sidebar}: toggle, {@code show}/{@code hide}, a preset ({@code min}/{@code normal}/{@code max}),
	 * a section name to toggle that section, or {@code default} to follow the server config again.
	 */
	private static LiteralArgumentBuilder<CommandSourceStack> sidebarCommand() {
		LiteralArgumentBuilder<CommandSourceStack> sidebar = Commands.literal("sidebar")
			.executes(safe(c -> sidebar(c.getSource(), prefs -> {
				boolean show = !prefs.shown(RunManager.get().config().display.sidebar);
				prefs.shown = show;
				return show ? "Sidebar shown." : "Sidebar hidden. /run sidebar brings it back.";
			})))
			.then(Commands.literal("show").executes(safe(c -> sidebar(c.getSource(), prefs -> {
				prefs.shown = true;
				return "Sidebar shown.";
			}))))
			.then(Commands.literal("hide").executes(safe(c -> sidebar(c.getSource(), prefs -> {
				prefs.shown = false;
				return "Sidebar hidden. /run sidebar brings it back.";
			}))))
			.then(Commands.literal("default").executes(safe(c -> sidebar(c.getSource(), prefs -> {
				prefs.shown = null;
				prefs.sections = null;
				return "Sidebar follows the server default again.";
			}))));
		for (SidebarPreset preset : SidebarPreset.values()) {
			String name = preset.name().toLowerCase(Locale.ROOT);
			sidebar.then(Commands.literal(name).executes(safe(c -> sidebar(c.getSource(), prefs -> {
				prefs.applyPreset(preset);
				return "Sidebar preset: " + name + " (" + describe(preset.sections()) + ").";
			}))));
		}
		for (SidebarSection section : SidebarSection.values()) {
			sidebar.then(Commands.literal(section.id()).executes(safe(c -> sidebar(c.getSource(), prefs -> {
				boolean on = prefs.toggle(section, RunManager.get().config().display.sidebarPreset);
				return "Sidebar " + section.id() + " " + (on ? "on" : "off") + ". Showing: "
					+ describe(prefs.sections(RunManager.get().config().display.sidebarPreset)) + ".";
			}))));
		}
		return sidebar;
	}

	private static String describe(Set<SidebarSection> sections) {
		return sections.isEmpty() ? "nothing" : sections.stream().map(SidebarSection::id).collect(Collectors.joining(", "));
	}

	private static int sidebar(CommandSourceStack source, Function<SidebarPrefs, String> change) {
		RunManager m = manager(source);
		if (m == null) return 0;
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("Only players have a sidebar. Server-wide defaults are in config/runitback.json."));
			return 0;
		}
		RunData data = m.tracker().data();
		SidebarPrefs prefs = data.sidebar(player.getStringUUID());
		String message = change.apply(prefs);
		if (prefs.isDefault()) data.sidebars.remove(player.getStringUUID());
		m.refreshDisplay();
		m.save();
		send(source, Text.line(message, ChatFormatting.GRAY));
		return 1;
	}

	/**
	 * Vanilla only logs command exceptions in a dev environment. Log them for real so server
	 * admins have a stack trace for bug reports.
	 */
	private static Command<CommandSourceStack> safe(Command<CommandSourceStack> command) {
		return c -> {
			try {
				return command.run(c);
			} catch (RuntimeException e) {
				RunItBack.LOG.error("'/{}' failed", c.getInput(), e);
				throw e;
			}
		};
	}

	private static RunManager manager(CommandSourceStack source) {
		RunManager manager = RunManager.get();
		if (manager == null) {
			source.sendFailure(Component.literal("Run It Back is not active yet."));
		}
		return manager;
	}

	private static void send(CommandSourceStack source, Component line) {
		source.sendSuccess(() -> line, false);
	}

	private static int status(CommandSourceStack source) {
		RunManager m = manager(source);
		if (m == null) return 0;
		RunRecord run = m.tracker().current();
		if (run == null) {
			send(source, Text.line("No run yet.", ChatFormatting.GRAY));
			return 0;
		}
		send(source, m.statusLine(run));
		if (run.isOver()) {
			m.summary(run).forEach(line -> send(source, line));
		} else {
			send(source, Text.line("Deaths " + run.deaths.size()
				+ " · Dims " + run.dimensions.size() + "/" + m.dimensions.size()
				+ (m.config().splits.isEmpty() ? "" : " · Splits " + run.splits.size() + "/" + m.config().splits.size())
				+ " · Bosses " + run.bosses.size() + "/" + m.config().bosses.size()
				+ " · Seed " + run.seed, ChatFormatting.GRAY));
			send(source, Text.prefix()
				.append(Text.button("Splits", "/run splits", "Show split times", ChatFormatting.GREEN))
				.append(" ")
				.append(Text.button("Deaths", "/run deaths", "Show the death log", ChatFormatting.RED))
				.append(" ")
				.append(Text.button("Leaderboard", "/run leaderboard", "Lifetime rankings", ChatFormatting.AQUA)));
		}
		return 1;
	}

	private static int deaths(CommandSourceStack source, Integer number) {
		RunManager m = manager(source);
		if (m == null) return 0;
		RunRecord run = number == null ? m.tracker().current() : m.tracker().data().findRun(number);
		if (run == null) {
			source.sendFailure(Component.literal("No such run."));
			return 0;
		}
		if (run.deaths.isEmpty()) {
			send(source, Text.line("Nobody has died in run #" + run.number + ". Yet.", ChatFormatting.GREEN));
			return 1;
		}
		send(source, Text.line("Deaths in run #" + run.number + ":", ChatFormatting.WHITE));
		for (DeathRecord death : run.deaths) {
			MutableComponent line = Text.of("  " + TimeFormat.clock(death.timeMs) + " ", ChatFormatting.GRAY)
				.append(Text.of(death.message, death.fatal ? ChatFormatting.RED : ChatFormatting.WHITE))
				.append(Text.of(" (" + death.x + " " + death.y + " " + death.z + " " + Text.shortId(death.dimension) + ")", ChatFormatting.DARK_GRAY));
			send(source, line);
		}
		return run.deaths.size();
	}

	private static int splits(CommandSourceStack source) {
		RunManager m = manager(source);
		if (m == null) return 0;
		RunRecord run = m.tracker().current();
		if (run == null) return 0;
		RunData data = m.tracker().data();
		send(source, Text.line("Times for run #" + run.number + " (best in brackets):", ChatFormatting.WHITE));
		for (Dimension dimension : m.dimensions) {
			SplitRecord entered = run.dimensions.get(dimension.id());
			Long best = data.bestSplits.get(RunTracker.dimensionBestKey(dimension.id()));
			MutableComponent line = entered != null
				? Text.of("  ✔ " + dimension.label() + " " + TimeFormat.clock(entered.timeMs), ChatFormatting.LIGHT_PURPLE)
					.append(Text.of(" by " + Text.name(entered.name), ChatFormatting.GRAY))
				: Text.of("  • " + dimension.label(), ChatFormatting.GRAY);
			if (best != null) line.append(Text.of(" [" + TimeFormat.clock(best) + "]", ChatFormatting.DARK_AQUA));
			send(source, line);
		}
		for (RunConfig.Boss boss : m.config().bosses) {
			BossKill kill = run.bosses.get(boss.entity);
			send(source, kill != null
				? Text.of("  ☠ " + boss.label + " " + TimeFormat.clock(kill.timeMs) + (kill.name != null ? " by " + kill.name : ""), ChatFormatting.GOLD)
				: Text.of("  " + (boss.required ? "⚔ " : "• ") + boss.label + (boss.required ? " (required)" : ""), ChatFormatting.GRAY));
		}
		for (RunConfig.Split split : m.config().splits) {
			SplitRecord reached = run.splits.get(split.advancement);
			Long best = data.bestSplits.get(split.advancement);
			MutableComponent line = reached != null
				? Text.of("  ✔ " + split.label + " " + TimeFormat.clock(reached.timeMs), ChatFormatting.GREEN)
					.append(Text.of(" by " + Text.name(reached.name), ChatFormatting.GRAY))
				: Text.of("  • " + split.label, ChatFormatting.GRAY);
			if (best != null) line.append(Text.of(" [" + TimeFormat.clock(best) + "]", ChatFormatting.DARK_AQUA));
			send(source, line);
		}
		if (data.bestWinMs != null) {
			send(source, Text.of("  Best winning time: " + TimeFormat.clock(data.bestWinMs), ChatFormatting.LIGHT_PURPLE));
		}
		return 1;
	}

	private static int history(CommandSourceStack source, int page) {
		RunManager m = manager(source);
		if (m == null) return 0;
		List<RunRecord> runs = new ArrayList<>(m.tracker().data().history);
		RunRecord current = m.tracker().current();
		if (current != null) runs.add(current);
		runs.sort(Comparator.comparingInt((RunRecord r) -> r.number).reversed());
		int pages = Math.max(1, (runs.size() + HISTORY_PAGE - 1) / HISTORY_PAGE);
		page = Math.min(page, pages);

		long won = runs.stream().filter(r -> r.status == RunStatus.WON).count();
		send(source, Text.line(runs.size() + " runs, " + won + " won. Page " + page + "/" + pages + ":", ChatFormatting.WHITE));
		for (RunRecord run : runs.subList((page - 1) * HISTORY_PAGE, Math.min(runs.size(), page * HISTORY_PAGE))) {
			ChatFormatting color = switch (run.status) {
				case WON -> ChatFormatting.GOLD;
				case FAILED -> ChatFormatting.RED;
				case ABANDONED -> ChatFormatting.DARK_GRAY;
				default -> ChatFormatting.GREEN;
			};
			String detail = switch (run.status) {
				case FAILED -> run.endReason == null ? "failed" : run.endReason;
				case WON -> "won";
				case ABANDONED -> "reset";
				case RUNNING -> "in progress";
				case WAITING -> "not started";
			};
			MutableComponent line = Text.of("  #" + run.number + " ", ChatFormatting.WHITE)
				.append(Text.of(TimeFormat.clock(run.elapsedMs) + " ", color))
				.append(Text.of(detail, ChatFormatting.GRAY))
				.withStyle(style -> style
					.withClickEvent(new ClickEvent.RunCommand("/run deaths " + run.number))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal(
						"Seed " + run.seed + "\nDeaths " + run.deaths.size() + ", dims " + run.dimensions.size() + ", bosses " + run.bosses.size()
							+ "\nClick for the death log"))));
			send(source, line);
		}
		if (page < pages) {
			send(source, Text.prefix().append(Text.button("Next page", "/run history " + (page + 1), "Older runs", ChatFormatting.AQUA)));
		}
		return runs.size();
	}

	private static int stats(CommandSourceStack source, String name) {
		RunManager m = manager(source);
		if (m == null) return 0;
		if (name == null) {
			source.sendFailure(Component.literal("Usage: /run stats <player>"));
			return 0;
		}
		PlayerStats stats = m.tracker().data().players.values().stream()
			.filter(p -> name.equalsIgnoreCase(p.name)).findFirst().orElse(null);
		if (stats == null) {
			source.sendFailure(Component.literal("No stats for " + name + "."));
			return 0;
		}
		send(source, Text.line("Lifetime stats for " + stats.name + ":", ChatFormatting.WHITE));
		send(source, Text.of("  Runs played " + stats.runsPlayed + " · won " + stats.runsWon + " · ended by them " + stats.runsEnded, ChatFormatting.GRAY));
		send(source, Text.of("  Deaths " + stats.deaths + " · boss kills " + stats.bossKills + " · hostile kills " + stats.mobKills, ChatFormatting.GRAY));
		send(source, Text.of("  Damage taken " + Math.round(stats.damageTaken / 2) + " hearts", ChatFormatting.GRAY));
		return 1;
	}

	private static int leaderboard(CommandSourceStack source) {
		RunManager m = manager(source);
		if (m == null) return 0;
		List<PlayerStats> players = new ArrayList<>(m.tracker().data().players.values());
		if (players.isEmpty()) {
			send(source, Text.line("No players yet.", ChatFormatting.GRAY));
			return 0;
		}
		send(source, Text.line("Leaderboard", ChatFormatting.WHITE, ChatFormatting.BOLD));
		board(source, players, "Runs ended (oops)", p -> p.runsEnded, ChatFormatting.RED);
		board(source, players, "Deaths", p -> p.deaths, ChatFormatting.RED);
		board(source, players, "Boss kills", p -> p.bossKills, ChatFormatting.GOLD);
		board(source, players, "Hostile mobs killed", p -> p.mobKills, ChatFormatting.GREEN);
		board(source, players, "Damage taken (hearts)", p -> Math.round(p.damageTaken / 2), ChatFormatting.YELLOW);
		return players.size();
	}

	private static void board(CommandSourceStack source, List<PlayerStats> players, String title, ToDoubleFunction<PlayerStats> value, ChatFormatting color) {
		List<PlayerStats> sorted = players.stream().sorted(Comparator.comparingDouble(value).reversed()).limit(3).toList();
		if (value.applyAsDouble(sorted.getFirst()) <= 0) return;
		MutableComponent line = Text.of("  " + title + ": ", ChatFormatting.GRAY);
		for (int i = 0; i < sorted.size(); i++) {
			PlayerStats p = sorted.get(i);
			if (value.applyAsDouble(p) <= 0) break;
			if (i > 0) line.append(Text.of(", ", ChatFormatting.DARK_GRAY));
			line.append(Text.of(p.name + " " + Math.round(value.applyAsDouble(p)), color));
		}
		send(source, line);
	}

	private static int start(CommandSourceStack source) {
		RunManager m = manager(source);
		if (m == null) return 0;
		if (!m.tracker().start()) {
			source.sendFailure(Component.literal("The run has already started or is over."));
			return 0;
		}
		return 1;
	}

	private static int end(CommandContext<CommandSourceStack> c, RunStatus status) {
		RunManager m = manager(c.getSource());
		if (m == null) return 0;
		String by = c.getSource().getTextName();
		if (!m.tracker().end(status, (status == RunStatus.WON ? "Declared won" : "Declared failed") + " by " + by, null, by)) {
			c.getSource().sendFailure(Component.literal("There is no run in progress."));
			return 0;
		}
		return 1;
	}

	private static String currentSeed() {
		RunManager m = RunManager.get();
		RunRecord run = m == null ? null : m.tracker().current();
		return run == null ? null : run.seed;
	}

	/**
	 * @param command the command without "confirm", used for the [Confirm] button
	 * @param confirmed true when "confirm" was typed, which skips the in-progress check
	 */
	private static int reset(CommandContext<CommandSourceStack> c, String seed, String command, boolean confirmed) {
		CommandSourceStack source = c.getSource();
		RunManager m = manager(source);
		if (m == null) return 0;
		if (!m.reset.supported()) {
			source.sendFailure(Component.literal("World reset only works on a dedicated server."));
			return 0;
		}
		if (m.reset.inProgress()) {
			source.sendFailure(Component.literal("A reset is already in progress."));
			return 0;
		}
		RunRecord run = m.tracker().current();
		if (!confirmed && run != null && run.status == RunStatus.RUNNING) {
			String key = source.getTextName() + "|" + command;
			int now = m.server.getTickCount();
			Integer expires = pendingConfirmations.remove(key);
			if (expires == null || now > expires) {
				pendingConfirmations.put(key, now + CONFIRM_TICKS);
				send(source, Text.line("Run #" + run.number + " is still going (" + TimeFormat.clock(run.elapsedMs) + "). Reset anyway? ", ChatFormatting.YELLOW)
					.append(Text.button("Confirm", command + " confirm", "Throw away this world and start a new run", ChatFormatting.RED)));
				return 0;
			}
		}
		pendingConfirmations.clear();
		m.reset.begin(seed, source.getTextName());
		return 1;
	}

	private static int reload(CommandSourceStack source) {
		RunManager m = manager(source);
		if (m == null) return 0;
		try {
			m.reloadConfig();
		} catch (Exception e) {
			source.sendFailure(Component.literal("Config not reloaded: " + e.getMessage()));
			return 0;
		}
		send(source, Text.line("Config reloaded.", ChatFormatting.GREEN));
		return 1;
	}
}
