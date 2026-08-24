package com.melloo.skymelloo.client.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/** Parses Hypixel's raw item text, which still uses legacy section-sign codes, into a styled component. */
public final class LegacyText {
	private LegacyText() {
	}

	public static Component parse(String raw) {
		MutableComponent result = Component.empty();
		if (raw == null || raw.isEmpty()) {
			return result;
		}
		// Item text never inherits the parent's italic default, so start from an explicit non-italic style.
		Style style = Style.EMPTY.withItalic(false);
		StringBuilder buffer = new StringBuilder();
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c != '§' || i + 1 >= raw.length()) {
				buffer.append(c);
				continue;
			}
			if (buffer.length() > 0) {
				result.append(Component.literal(buffer.toString()).setStyle(style));
				buffer.setLength(0);
			}
			char code = Character.toLowerCase(raw.charAt(++i));
			ChatFormatting format = ChatFormatting.getByCode(code);
			if (format == null) {
				continue;
			}
			style = switch (format) {
				case RESET -> Style.EMPTY.withItalic(false);
				case BOLD -> style.withBold(true);
				case ITALIC -> style.withItalic(true);
				case UNDERLINE -> style.withUnderlined(true);
				case STRIKETHROUGH -> style.withStrikethrough(true);
				case OBFUSCATED -> style.withObfuscated(true);
				// A colour code also clears any decoration, exactly as it does in vanilla chat.
				default -> Style.EMPTY.withItalic(false).withColor(format);
			};
		}
		if (buffer.length() > 0) {
			result.append(Component.literal(buffer.toString()).setStyle(style));
		}
		return result;
	}
}
