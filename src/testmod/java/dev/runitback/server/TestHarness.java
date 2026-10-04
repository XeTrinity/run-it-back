package dev.runitback.server;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.runitback.data.RunRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;

/**
 * Dev-only commands that drive real game code paths with a FakePlayer, since no headless client
 * exists for this Minecraft version. Never shipped in the release jar.
 */
public final class TestHarness implements ModInitializer {
	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, ctx, selection) -> dispatcher.register(Commands.literal("hcrtest")
			.then(Commands.literal("join").then(Commands.argument("name", StringArgumentType.word())
				.executes(c -> join(c.getSource(), StringArgumentType.getString(c, "name")))))
			.then(Commands.literal("advance").then(Commands.argument("name", StringArgumentType.word())
				.then(Commands.argument("advancement", StringArgumentType.greedyString())
					.executes(c -> advance(c.getSource(), StringArgumentType.getString(c, "name"), StringArgumentType.getString(c, "advancement"))))))
			.then(Commands.literal("fall").then(Commands.argument("name", StringArgumentType.word())
				.executes(c -> fall(c.getSource(), StringArgumentType.getString(c, "name")))))
			.then(Commands.literal("sidebar").executes(c -> sidebar(c.getSource())))
			.then(Commands.literal("viewer").then(Commands.argument("name", StringArgumentType.word())
				.executes(c -> viewer(c.getSource(), StringArgumentType.getString(c, "name")))))
			.then(Commands.literal("as").then(Commands.argument("name", StringArgumentType.word())
				.then(Commands.argument("command", StringArgumentType.greedyString())
					.executes(c -> as(c.getSource(), StringArgumentType.getString(c, "name"), StringArgumentType.getString(c, "command"))))))
			.then(Commands.literal("view").then(Commands.argument("name", StringArgumentType.word())
				.executes(c -> view(c.getSource(), StringArgumentType.getString(c, "name")))))
			.then(Commands.literal("dim").then(Commands.argument("name", StringArgumentType.word())
				.then(Commands.argument("dimension", StringArgumentType.greedyString())
					.executes(c -> dim(c.getSource(), StringArgumentType.getString(c, "name"), StringArgumentType.getString(c, "dimension"))))))
			.then(Commands.literal("leaveall").executes(c -> leaveAll(c.getSource())))));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> viewers.values().forEach(v -> server.getPlayerList().getPlayers().remove(v.player)));
	}

	private record Viewer(ServerPlayer player, CapturingListener listener, SimulatedSidebar sidebar) {
	}

	private static final Map<String, Viewer> viewers = new HashMap<>();

	/** A player with a packet-capturing connection, in the player list like a real one. */
	private static int viewer(CommandSourceStack source, String name) {
		ServerLevel level = source.getServer().overworld();
		ServerPlayer player = new ServerPlayer(source.getServer(), level,
			new GameProfile(UUID.nameUUIDFromBytes(("hcrtest:" + name).getBytes()), name), ClientInformation.createDefault());
		player.setPos(0, 100, 0);
		CapturingListener listener = new CapturingListener(player);
		player.connection = listener;
		source.getServer().getPlayerList().getPlayers().add(player);
		viewers.put(name, new Viewer(player, listener, new SimulatedSidebar()));
		RunManager.get().playerJoined(player);
		say(source, name + " connected as a capturing viewer");
		return 1;
	}

	private static int as(CommandSourceStack source, String name, String command) {
		Viewer viewer = viewers.get(name);
		source.getServer().getCommands().performPrefixedCommand(viewer.player().createCommandSourceStack(), command);
		say(source, name + " ran /" + command);
		return 1;
	}

	/** Prints chat the viewer received since last time, then their sidebar as the client would draw it. */
	private static int view(CommandSourceStack source, String name) {
		Viewer viewer = viewers.get(name);
		RunManager.get().refreshDisplay();
		for (var packet : viewer.listener().received) {
			viewer.sidebar().apply(packet);
			if (packet instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket chat && !chat.overlay()) {
				say(source, name + " chat: " + chat.content().getString());
			}
		}
		say(source, name + " received " + viewer.listener().received.size() + " packets");
		viewer.listener().received.clear();
		List<String> lines = viewer.sidebar().render();
		if (lines.isEmpty()) say(source, name + " sidebar: (none)");
		lines.forEach(line -> say(source, name + " | " + line));
		return 1;
	}

	private static int dim(CommandSourceStack source, String name, String dimension) {
		Viewer viewer = viewers.get(name);
		ServerLevel level = source.getServer().getLevel(net.minecraft.resources.ResourceKey.create(
			net.minecraft.core.registries.Registries.DIMENSION, Identifier.parse(dimension)));
		RunManager.get().playerChangedDimension(viewer.player(), level);
		say(source, name + " entered " + dimension);
		return 1;
	}

	private static int leaveAll(CommandSourceStack source) {
		viewers.values().forEach(v -> {
			RunManager.get().playerLeft(v.player());
			source.getServer().getPlayerList().getPlayers().remove(v.player());
		});
		viewers.clear();
		return 1;
	}

	private static FakePlayer fake(CommandSourceStack source, String name) {
		ServerLevel level = source.getServer().overworld();
		UUID uuid = UUID.nameUUIDFromBytes(("hcrtest:" + name).getBytes());
		FakePlayer player = FakePlayer.get(level, new GameProfile(uuid, name));
		player.setPos(0, 100, 0);
		return player;
	}

	private static void say(CommandSourceStack source, String text) {
		source.sendSuccess(() -> Component.literal("[hcrtest] " + text), false);
	}

	private static int join(CommandSourceStack source, String name) {
		RunManager.get().playerJoined(fake(source, name));
		say(source, name + " joined; run players=" + RunManager.get().tracker().current().players.keySet().size());
		return 1;
	}

	private static int advance(CommandSourceStack source, String name, String id) {
		// FakePlayer is blocked from earning advancements by Fabric API, so use a bare ServerPlayer.
		ServerLevel level = source.getServer().overworld();
		ServerPlayer player = new ServerPlayer(source.getServer(), level,
			new GameProfile(UUID.nameUUIDFromBytes(("hcrtest:" + name).getBytes()), name), ClientInformation.createDefault());
		AdvancementHolder holder = source.getServer().getAdvancements().get(Identifier.parse(id));
		if (holder == null) {
			say(source, "no advancement " + id);
			return 0;
		}
		List<String> criteria = new ArrayList<>();
		player.getAdvancements().getOrStartProgress(holder).getRemainingCriteria().forEach(criteria::add);
		for (String criterion : criteria) {
			player.getAdvancements().award(holder, criterion);
		}
		say(source, name + " awarded " + id + "; splits=" + RunManager.get().tracker().current().splits.keySet());
		return 1;
	}

	private static int fall(CommandSourceStack source, String name) {
		FakePlayer player = fake(source, name);
		ServerLevel level = source.getServer().overworld();
		player.fallDistance = 30;
		player.getCombatTracker().recordDamage(level.damageSources().fall(), 30);
		player.setHealth(0);
		player.die(level.damageSources().fall());
		RunRecord run = RunManager.get().tracker().current();
		say(source, "after death: status=" + run.status + " deaths=" + run.deaths.size()
			+ (run.deaths.isEmpty() ? "" : " last='" + run.deaths.getLast().message + "' fatal=" + run.deaths.getLast().fatal));
		return 1;
	}

	private static int sidebar(CommandSourceStack source) {
		var scoreboard = source.getServer().getScoreboard();
		Objective objective = scoreboard.getObjective("runitback");
		if (objective == null) {
			say(source, "no sidebar objective");
			return 0;
		}
		say(source, "sidebar title: " + objective.getDisplayName().getString());
		scoreboard.listPlayerScores(objective).stream()
			.sorted((a, b) -> Integer.compare(b.value(), a.value()))
			.forEach(e -> say(source, "  " + e.value() + " " + (e.display() == null ? e.owner() : e.display().getString())));
		return 1;
	}
}
