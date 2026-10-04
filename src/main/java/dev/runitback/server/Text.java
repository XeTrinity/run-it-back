package dev.runitback.server;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * Chat building helpers. Text is plain English rather than translation keys because players use
 * vanilla clients, which do not have this mod's language files.
 */
final class Text {
	private Text() {
	}

	static MutableComponent prefix() {
		return Component.literal("[").withStyle(ChatFormatting.DARK_GRAY)
			.append(Component.literal("HC").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD))
			.append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY));
	}

	static MutableComponent line(String text, ChatFormatting... style) {
		return prefix().append(Component.literal(text).withStyle(style));
	}

	static MutableComponent of(String text, ChatFormatting... style) {
		return Component.literal(text).withStyle(style);
	}

	static MutableComponent button(String label, String command, String hover, ChatFormatting color) {
		return Component.literal("[" + label + "]").withStyle(style -> style
			.withColor(color)
			.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	static MutableComponent suggest(String label, String command, String hover, ChatFormatting color) {
		return Component.literal("[" + label + "]").withStyle(style -> style
			.withColor(color)
			.withClickEvent(new ClickEvent.SuggestCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	/** "Dave" or "someone" for unknown names. */
	static String name(String name) {
		return name == null || name.isBlank() ? "someone" : name;
	}

	/** {@code minecraft:the_nether} becomes {@code the_nether}; modded ids keep their namespace. */
	static String shortId(String id) {
		if (id == null) return "?";
		return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
	}
}
