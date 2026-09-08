package com.melloo.skymelloo.client.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.regex.Pattern;

// Consistent chat message formatting for all SkyMelloo output.
public final class ChatUtil {
	private static final int GRADIENT_START = 0xFF6EC7; // pink
	private static final int GRADIENT_END = 0xD946B8; // deeper pink/magenta, matches the website's brand gradient
	private static final Pattern FORMAT_CODE = Pattern.compile("§[0-9A-FK-ORa-fk-or]");

	private ChatUtil() {
	}

	public static MutableComponent prefixed(String message) {
		return prefixed(Component.literal(message));
	}

	// Strips §-color codes and uses a plain "[SkyMelloo] " prefix, for text sent through /pc as a
	// command string - Hypixel strips the § but leaves the format-code letter as literal garbage text.
	public static String partyPrefixed(String message) {
		return "[SkyMelloo] " + FORMAT_CODE.matcher(message).replaceAll("");
	}

	public static MutableComponent prefixed(Component message) {
		MutableComponent result = Component.literal("§b[").append(gradientText("SkyMelloo")).append(Component.literal("§b]§r "));
		result.append(message);
		return result;
	}

	private static MutableComponent gradientText(String text) {
		MutableComponent result = Component.empty();
		int len = text.length();
		for (int i = 0; i < len; i++) {
			float t = len <= 1 ? 0F : (float) i / (len - 1);
			int rgb = lerpColor(GRADIENT_START, GRADIENT_END, t);
			result.append(Component.literal(String.valueOf(text.charAt(i))).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb))));
		}
		return result;
	}

	public static String errorMessage(String name, Throwable error) {
		return Component.translatable("skymelloo.chat.error.error_in", name, friendlyError(error)).getString();
	}

	// Unwraps CompletionException/ExecutionException to the real cause, since its own getMessage()
	// is just cause.toString().
	public static String friendlyError(Throwable error) {
		Throwable cause = error;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		if (cause instanceof java.net.http.HttpTimeoutException) {
			return Component.translatable("skymelloo.chat.error.timed_out").getString();
		}
		String msg = cause.getMessage();
		return msg != null && !msg.isBlank() ? msg : cause.getClass().getSimpleName();
	}

	private static int lerpColor(int from, int to, float t) {
		int r1 = (from >> 16) & 0xFF, g1 = (from >> 8) & 0xFF, b1 = from & 0xFF;
		int r2 = (to >> 16) & 0xFF, g2 = (to >> 8) & 0xFF, b2 = to & 0xFF;
		int r = Math.round(r1 + (r2 - r1) * t);
		int g = Math.round(g1 + (g2 - g1) * t);
		int b = Math.round(b1 + (b2 - b1) * t);
		return (r << 16) | (g << 8) | b;
	}
}
