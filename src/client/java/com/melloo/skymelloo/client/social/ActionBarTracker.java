package com.melloo.skymelloo.client.social;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Reads Hypixel's Health/Defense/Mana actionbar. Matches "cur/max" fractions by position (Health,
// then Mana), not color or the word "Mana" - a custom resource pack can replace either with glyphs.
public final class ActionBarTracker {
	// Any "current/max" fraction, comma-thousands allowed (mana pools can exceed 999).
	private static final Pattern FRACTION_PATTERN = Pattern.compile("([\\d,]+)\\s*/\\s*([\\d,]+)");

	public record Segment(String colorHex, String text) {
	}

	private static Integer currentHealth;
	private static Integer maxHealth;
	private static Integer currentMana;
	private static Integer maxMana;
	private static volatile List<Segment> lastSegments = List.of();
	private static volatile long lastPacketMillis = 0;
	private static boolean initialized = false;

	private ActionBarTracker() {
	}

	public static void init() {
		if (initialized) {
			return;
		}
		initialized = true;
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			if (!overlay) {
				return true;
			}
			onActionBarText(message);
			// Suppresses Hypixel's own readout once the custom bars show the same info - only in SkyBlock.
			com.melloo.skymelloo.client.config.SkyMellooConfig config = com.melloo.skymelloo.client.config.SkyMellooConfig.HANDLER.instance();
			if (config.healthManaBarsEnabled && config.hideNativeStatusActionBarEnabled
					&& com.melloo.skymelloo.client.util.SkyblockDetector.isInSkyblock()) {
				return false;
			}
			return true;
		});
	}

	public static void onActionBarText(Component component) {
		if (component == null) {
			return;
		}
		lastPacketMillis = System.currentTimeMillis();
		List<Segment> segments = new ArrayList<>();
		component.visit((Style style, String text) -> {
			if (!text.isEmpty()) {
				TextColor color = style.getColor();
				segments.add(new Segment(color != null ? color.serialize() : "none", text));
			}
			return Optional.empty();
		}, Style.EMPTY);
		lastSegments = segments;

		// Matches over these same segments, not component.getString() - the two can diverge and corrupt the match.
		StringBuilder flattened = new StringBuilder();
		for (Segment seg : segments) {
			flattened.append(seg.text());
		}

		// Left-to-right order; Defense has no slash so it's never one of these - position 0 is Health, 1 is Mana.
		List<int[]> fractions = new ArrayList<>();
		Matcher m = FRACTION_PATTERN.matcher(flattened);
		while (m.find()) {
			try {
				fractions.add(new int[]{Integer.parseInt(m.group(1).replace(",", "")), Integer.parseInt(m.group(2).replace(",", ""))});
			} catch (NumberFormatException ignored) {
				// Malformed number in this particular match - just skip it, not fatal to the rest.
			}
		}
		// Health reads from here too, not the vanilla health attribute - that doesn't match real SkyBlock HP 1:1.
		if (fractions.size() >= 1) {
			currentHealth = fractions.get(0)[0];
			maxHealth = fractions.get(0)[1];
		}
		if (fractions.size() >= 2) {
			currentMana = fractions.get(1)[0];
			maxMana = fractions.get(1)[1];
		}
	}

	public static Integer getCurrentHealth() {
		return currentHealth;
	}

	public static Integer getMaxHealth() {
		return maxHealth;
	}

	public static Integer getCurrentMana() {
		return currentMana;
	}

	public static Integer getMaxMana() {
		return maxMana;
	}

	// null until the first actionbar mana readout has been seen this session.
	public static Float getManaFraction() {
		if (currentMana == null || maxMana == null || maxMana <= 0) {
			return null;
		}
		return Math.max(0F, Math.min(1F, currentMana / (float) maxMana));
	}

	// For "Mana Debug" - empty means the event listener never fired at all.
	public static List<Segment> getLastSegments() {
		return lastSegments;
	}

	public static long getLastPacketMillis() {
		return lastPacketMillis;
	}
}
