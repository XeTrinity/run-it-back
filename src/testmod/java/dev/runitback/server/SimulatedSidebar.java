package dev.runitback.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.world.scores.DisplaySlot;

/** Rebuilds what a vanilla client's sidebar shows from the scoreboard packets it received. */
final class SimulatedSidebar {
	private record Score(int value, Component display, Component right) {
	}

	private final Map<String, Component> objectives = new HashMap<>();
	private final Map<String, Map<String, Score>> scores = new HashMap<>();
	private String sidebar;

	void apply(Packet<?> packet) {
		switch (packet) {
			case ClientboundSetObjectivePacket p -> {
				if (p.getMethod() == ClientboundSetObjectivePacket.METHOD_REMOVE) {
					objectives.remove(p.getObjectiveName());
					scores.remove(p.getObjectiveName());
					if (p.getObjectiveName().equals(sidebar)) sidebar = null;
				} else {
					objectives.put(p.getObjectiveName(), p.getDisplayName());
				}
			}
			case ClientboundSetDisplayObjectivePacket p -> {
				if (p.getSlot() == DisplaySlot.SIDEBAR) sidebar = p.getObjectiveName();
			}
			case ClientboundSetScorePacket p ->
				scores.computeIfAbsent(p.objectiveName(), k -> new LinkedHashMap<>()).put(p.owner(), new Score(p.score(), p.display().orElse(null),
					p.numberFormat().orElse(null) instanceof net.minecraft.network.chat.numbers.FixedFormat f ? f.value() : null));
			case ClientboundResetScorePacket p -> {
				if (p.objectiveName() == null) {
					scores.values().forEach(m -> m.remove(p.owner()));
				} else if (scores.containsKey(p.objectiveName())) {
					scores.get(p.objectiveName()).remove(p.owner());
				}
			}
			default -> {
			}
		}
	}

	/** Title first, then lines in the order the client draws them; empty if no sidebar. */
	List<String> render() {
		List<String> out = new ArrayList<>();
		if (sidebar == null || !objectives.containsKey(sidebar)) return out;
		out.add("== " + objectives.get(sidebar).getString() + " ==");
		scores.getOrDefault(sidebar, Map.of()).entrySet().stream()
			// Same filter as the client: holders starting with '#' are hidden.
			.filter(e -> !e.getKey().startsWith("#"))
			.sorted((a, b) -> Integer.compare(b.getValue().value(), a.getValue().value()))
			.limit(15)
			.forEach(e -> {
				Score score = e.getValue();
				String left = score.display() != null ? score.display().getString() : e.getKey();
				out.add(String.format("%-18s %s", left, score.right() != null ? score.right().getString() : ""));
			});
		return out;
	}
}
