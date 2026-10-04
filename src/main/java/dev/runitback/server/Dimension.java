package dev.runitback.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

/** A dimension other than the overworld, with a readable name for chat and the sidebar. */
record Dimension(String id, String label) {
	/** Every loaded dimension except the overworld: Nether and End first, then modded ones by id. */
	static List<Dimension> all(MinecraftServer server) {
		List<Dimension> dimensions = new ArrayList<>();
		for (ResourceKey<Level> key : server.levelKeys()) {
			if (key == Level.OVERWORLD) continue;
			String id = key.identifier().toString();
			dimensions.add(new Dimension(id, label(id)));
		}
		dimensions.sort(Comparator.comparingInt((Dimension d) -> order(d.id())).thenComparing(Dimension::id));
		return dimensions;
	}

	private static int order(String id) {
		return switch (id) {
			case "minecraft:the_nether" -> 0;
			case "minecraft:the_end" -> 1;
			default -> 2;
		};
	}

	static String label(String id) {
		return switch (id) {
			case "minecraft:the_nether" -> "Nether";
			case "minecraft:the_end" -> "The End";
			case "minecraft:overworld" -> "Overworld";
			default -> {
				String path = id.substring(id.indexOf(':') + 1).replace('_', ' ').replace('/', ' ');
				StringBuilder out = new StringBuilder();
				for (String word : path.split(" ")) {
					if (word.isEmpty()) continue;
					if (!out.isEmpty()) out.append(' ');
					out.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
				}
				yield out.toString();
			}
		};
	}
}
