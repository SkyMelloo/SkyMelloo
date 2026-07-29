package com.melloo.skymelloo.client.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.regex.Pattern;

/** Consistent chat message formatting for all SkyMelloo output. */
public final class ChatUtil {
	private static final int GRADIENT_START = 0xFF6EC7; // pink
	private static final int GRADIENT_END = 0xD946B8; // deeper pink/magenta - was light blue, matching the website's now-all-pink brand gradient instead of fading toward blue
	private static final Pattern FORMAT_CODE = Pattern.compile("§[0-9A-FK-ORa-fk-or]");

	private ChatUtil() {
	}

	public static MutableComponent prefixed(String message) {
		return prefixed(Component.literal(message));
	}

	/**
	 * Strips §-color codes and prepends a plain "[SkyMelloo] " prefix (no color at all) - for text
	 * actually sent through {@code /pc} as a command string. Confirmed directly from a real screenshot:
	 * § codes typed into a command argument don't survive to the other party members' screens at all -
	 * Hypixel strips the § itself but leaves its format-code LETTER behind as literal text (e.g.
	 * "§b[SkyMelloo]§r" arrived as literal "b[SkyMelloo]r"), which reads as garbled nonsense, worse
	 * than no color at all.
	 */
	public static String partyPrefixed(String message) {
		return "[SkyMelloo] " + FORMAT_CODE.matcher(message).replaceAll("");
	}

	/** Like {@link #prefixed(String)}, but for a message that needs rich formatting (click/hover events, mixed colors) rather than a plain string. */
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

	/**
	 * Consistent, colorized "fetch failed" line for every stats-lookup call site - a raw exception's
	 * getMessage() (e.g. "java.net.http.HttpTimeoutException: request timed out") read like a bare
	 * stack trace line, all one flat color, nothing like the rest of this mod's chat output. Unwraps
	 * CompletableFuture's CompletionException/ExecutionException wrapping to get at the real cause,
	 * and gives timeouts specifically a plain-language message instead of the exception class name -
	 * by the time this shows at all, the automatic 1-retry in SkyMellooApiClient already failed too.
	 */
	public static String errorMessage(String name, Throwable error) {
		return "§c✖ §7Fehler bei §e" + name + "§7: §c" + friendlyError(error);
	}

	private static String friendlyError(Throwable error) {
		Throwable cause = error;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		if (cause instanceof java.net.http.HttpTimeoutException) {
			return "Zeitüberschreitung - Server antwortet nicht";
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
