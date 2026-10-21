package io.github.jonesjughead.devcopy;

import java.util.Optional;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.scores.TeamColor;

public enum TextFormat {
	/** Texte brut, sans couleur. */
	PLAIN,
	/** Classic § codes (and §x§r§r§g§g§b§b for hex colors). */
	LEGACY,
	/** MiniMessage flavor, handy to paste into a Paper/Adventure config. */
	MINIMESSAGE,
	/** Raw JSON component, as the server sends it. */
	JSON;

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	public String format(Component c) {
		return switch (this) {
			case PLAIN -> c.getString();
			case LEGACY -> toLegacy(c);
			case MINIMESSAGE -> toMiniMessage(c);
			case JSON -> toJson(c);
		};
	}

	private static String toJson(Component c) {
		var level = Minecraft.getInstance().level;
		var ops = level != null ? level.registryAccess().createSerializationContext(JsonOps.INSTANCE) : JsonOps.INSTANCE;
		return ComponentSerialization.CODEC.encodeStart(ops, c)
				.result()
				.map(GSON::toJson)
				.orElse("\"<serialization error>\"");
	}

	private static String toLegacy(Component c) {
		StringBuilder sb = new StringBuilder();
		c.visit((style, text) -> {
			if (!text.isEmpty()) {
				sb.append("§r").append(legacyCodes(style)).append(text);
			}
			return Optional.empty();
		}, Style.EMPTY);
		// A leading §r is useless
		return sb.length() >= 2 && sb.charAt(0) == '§' && sb.charAt(1) == 'r' ? sb.substring(2) : sb.toString();
	}

	private static String legacyCodes(Style style) {
		StringBuilder sb = new StringBuilder();
		TextColor color = style.getColor();
		if (color != null) {
			TeamColor named = namedColor(color);
			if (named != null) {
				// toString() of a ChatFormatting = "§" + code
				sb.append(ChatFormatting.valueOf(named.name()));
			} else {
				String hex = String.format("%06x", color.getValue() & 0xFFFFFF);
				sb.append("§x");
				for (char ch : hex.toCharArray()) sb.append('§').append(ch);
			}
		}
		if (style.isBold()) sb.append("§l");
		if (style.isItalic()) sb.append("§o");
		if (style.isUnderlined()) sb.append("§n");
		if (style.isStrikethrough()) sb.append("§m");
		if (style.isObfuscated()) sb.append("§k");
		return sb.toString();
	}

	private static String toMiniMessage(Component c) {
		StringBuilder sb = new StringBuilder();
		c.visit((style, text) -> {
			if (text.isEmpty()) return Optional.empty();
			StringBuilder open = new StringBuilder();
			StringBuilder close = new StringBuilder();
			TextColor color = style.getColor();
			if (color != null) {
				TeamColor named = namedColor(color);
				String tag = named != null ? named.getSerializedName() : String.format("#%06x", color.getValue() & 0xFFFFFF);
				open.append('<').append(tag).append('>');
				close.insert(0, "</" + tag + ">");
			}
			if (style.isBold()) { open.append("<b>"); close.insert(0, "</b>"); }
			if (style.isItalic()) { open.append("<i>"); close.insert(0, "</i>"); }
			if (style.isUnderlined()) { open.append("<u>"); close.insert(0, "</u>"); }
			if (style.isStrikethrough()) { open.append("<st>"); close.insert(0, "</st>"); }
			if (style.isObfuscated()) { open.append("<obf>"); close.insert(0, "</obf>"); }
			sb.append(open).append(text.replace("<", "\\<")).append(close);
			return Optional.empty();
		}, Style.EMPTY);
		return sb.toString();
	}

	private static TeamColor namedColor(TextColor color) {
		for (TeamColor c : TeamColor.values()) {
			if (c.rgb() == color.getValue()) {
				return c;
			}
		}
		return null;
	}
}
