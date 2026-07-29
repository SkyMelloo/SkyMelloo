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

/**
 * Hypixel overwrites the vanilla actionbar every tick with its own Health/Defense/Mana readout
 * (see {@link com.melloo.skymelloo.client.fishing.FishingScoreHud}'s doc comment, which already
 * noted this while explaining why it avoids that same actionbar) - real, current mana is text
 * right there, not anything derivable from {@code experienceProgress} (that read the player's
 * actual vanilla XP bar, which Hypixel does NOT repurpose for mana here - a wrong assumption).
 * <p>
 * THREE separate bugs found and fixed here, each confirmed against real evidence from a
 * live report rather than guessed:
 * <ol>
 *     <li>Wrong hook entirely - this used to rely on {@code ActionBarTextMixin}, a custom
 *     {@code @Inject} directly into {@code ClientPacketListener.setActionBarText}. That method name
 *     is genuinely correct (confirmed via javap), but the injection apparently never fired in
 *     practice - plausibly a conflict with one of the many OTHER installed mods that also hook
 *     actionbar text (SkyHanni/Skyblocker both have their own - confirmed by decompiling Skyblocker's
 *     {@code de.hysky.skyblocker.skyblock.StatusBarTracker}). It doesn't touch that packet method at
 *     all - it registers a plain {@link ClientReceiveMessageEvents#ALLOW_GAME} listener, the same
 *     official Fabric API event {@link DungeonRunTracker} already uses elsewhere in this mod for chat
 *     (the {@code overlay} boolean tells the two apart) - safe for many mods to observe at once,
 *     unlike a raw packet-level mixin. Switched to the same approach here - confirmed fixed by a
 *     follow-up report showing "last packet Xms ago" instead of "no packet seen yet".</li>
 *     <li>Wrong matching logic, take 1 - this used to walk the {@link Component}'s per-run STYLE,
 *     picking out whichever "current/max" run was colored aqua. The follow-up report's own "Mana
 *     Debug" output showed the WHOLE actionbar arriving as a single "[none]"-colored segment - the
 *     visible per-stat colors are baked into a custom resource pack's own font glyph bitmaps, not
 *     real Style/TextColor at the Component level at all, so no per-run color signal exists to read
 *     here in the first place.</li>
 *     <li>Wrong matching logic, take 2 - briefly required the literal word "Mana" after the numbers
 *     (matching Skyblocker's own regex), but the same custom resource pack replaces stat NAMES with
 *     icon glyphs entirely, removing the word "Mana" from the text altogether - confirmed directly
 *     from that same debug output ("542/542✎" with no word anywhere near it). Matches positionally
 *     instead now: Hypixel's SkyBlock actionbar has shown Health, then Defense (a bare number with no
 *     slash), then Mana in that fixed order since the game's own inception - "eigentlich doch nur 3
 *     und 4 zahl also 3. zahl mana aktuell und 4. zahl max mana" - so mana is simply the SECOND
 *     "current/max" fraction found in the flattened text, regardless of color or wording. Every
 *     (color, text) segment actually seen is still kept (see {@link #getLastSegments}) for "Mana
 *     Debug", purely as a diagnostic now.</li>
 * </ol>
 */
public final class ActionBarTracker {
	// Any "current/max" fraction, comma-thousands allowed (SkyBlock mana pools can exceed 999) -
	// deliberately NOT anchored to a specific word or color, see the class doc comment on why both
	// of those turned out not to be reliable signals for this user's actual resource pack setup.
	private static final Pattern FRACTION_PATTERN = Pattern.compile("([\\d,]+)\\s*/\\s*([\\d,]+)");

	public record Segment(String colorHex, String text) {
	}

	private static Integer currentHealth;
	private static Integer maxHealth;
	private static Integer currentMana;
	private static Integer maxMana;
	private static long lastUpdateMillis;
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
			// Optionally suppresses Hypixel's own plain Health/Defense/Mana readout once the custom
			// bars already show the same info.
			// Only while the custom bars themselves are actually on and in SkyBlock - never touches
			// the actionbar anywhere else (a lobby/minigame actionbar has nothing to do with this).
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

		// Fourth real bug found here, same "trust real evidence, not an assumption" approach as
		// the three in the class doc comment above: matching used to run over component.getString()
		// instead of the segment text just captured by visit() right above. Confirmed as the actual
		// root cause of a real "62901/2526" health-bar bug from a live report - getString() and visit()
		// can genuinely diverge for Hypixel's actionbar component structure, and whatever extra text
		// getString() was including corrupted specifically the FIRST fraction match (health) with a
		// stray leading digit, while the debug view (built from these same segments) showed the correct
		// "2,901/2,526" the whole time. Matching over the exact same text the debug view already proved
		// correct guarantees both can never again disagree about what the actionbar actually said.
		StringBuilder flattened = new StringBuilder();
		for (Segment seg : segments) {
			flattened.append(seg.text());
		}

		// Every "cur/max" fraction in the text, in left-to-right order - Defense has no slash at all
		// (just a bare number) so it never counts as one of these, meaning position 0 is Health and
		// position 1 is Mana without needing to explicitly skip Defense at all.
		List<int[]> fractions = new ArrayList<>();
		Matcher m = FRACTION_PATTERN.matcher(flattened);
		while (m.find()) {
			try {
				fractions.add(new int[]{Integer.parseInt(m.group(1).replace(",", "")), Integer.parseInt(m.group(2).replace(",", ""))});
			} catch (NumberFormatException ignored) {
				// Malformed number in this particular match - just skip it, not fatal to the rest.
			}
		}
		// Health is position 0, same reasoning as the mana fix: vanilla's own health attribute doesn't necessarily match
		// Hypixel's real SkyBlock HP number 1:1 (the screenshot evidence for the mana fix already
		// showed "current" exceeding "max" here, e.g. absorption/overheal), so both bars now read from
		// the same source instead of mixing a vanilla attribute (health) with actionbar text (mana).
		if (fractions.size() >= 1) {
			currentHealth = fractions.get(0)[0];
			maxHealth = fractions.get(0)[1];
		}
		if (fractions.size() >= 2) {
			currentMana = fractions.get(1)[0];
			maxMana = fractions.get(1)[1];
			lastUpdateMillis = System.currentTimeMillis();
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

	/** {@code null} until the first actionbar mana readout has actually been seen this session. */
	public static Float getManaFraction() {
		if (currentMana == null || maxMana == null || maxMana <= 0) {
			return null;
		}
		return Math.max(0F, Math.min(1F, currentMana / (float) maxMana));
	}

	public static long getLastUpdateMillis() {
		return lastUpdateMillis;
	}

	/** Every (color, text) run from the last actionbar packet actually received - for "Mana Debug". Empty if none seen yet (which itself is diagnostic: means the event listener never fired at all). */
	public static List<Segment> getLastSegments() {
		return lastSegments;
	}

	public static long getLastPacketMillis() {
		return lastPacketMillis;
	}
}
