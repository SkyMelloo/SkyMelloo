package com.melloo.skymelloo.client.gui;

import com.melloo.mellooessentials.client.gui.HudLayoutEditorScreen;
import com.melloo.skymelloo.client.config.SkyMellooConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

import java.util.ArrayList;
import java.util.List;

/**
 * SkyMelloo's own HUD elements for MellooEssentials' HUD layout editor (key J - the editor itself
 * now lives entirely in MellooEssentials, this mod only supplies its own elements' Draggables via
 * {@link HudLayoutEditorScreen#setExtraElementsProvider}, registered once in
 * SkyMellooClient#onInitializeClient). Moved out of the old (now-deleted) SkyMellooClient-owned
 * HudLayoutEditorScreen's init() - same content, just relocated and taking the target screen's own
 * width/height as parameters instead of reading {@code this.width}/{@code this.height} directly,
 * since this no longer runs inside a Screen subclass.
 */
public final class SkyMellooHudElements {
	private SkyMellooHudElements() {
	}

	public static List<HudLayoutEditorScreen.Draggable> build(int screenWidth, int screenHeight) {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		Font font = Minecraft.getInstance().font;
		List<HudLayoutEditorScreen.Draggable> elements = new ArrayList<>();

		// Real box is 16px tall (see FishingScoreHud); width includes the " +999"-style bonus suffix
		// shown right after a combo point is gained, its most-visible moment.
		int fishWidth = font.width("Fishing Combo x9  999 pts +999") + 12;
		elements.add(new HudLayoutEditorScreen.Draggable(
				"Fishing Combo",
				() -> fishingX(config, font, screenWidth),
				() -> fishingY(config, screenHeight),
				(x, y) -> {
					config.hudFishingScoreX = x;
					config.hudFishingScoreY = y;
				},
				fishWidth, 16
		));

		// Recomputed to track the real current layout (member count/text length vary at runtime) -
		// assumes a representative 3-member party for sizing purposes.
		boolean partyFull = "FULL".equalsIgnoreCase(config.partyHudMode);
		int partyPreviewMembers = 3;
		// Full mode's pre-run sub-info is 3 STACKED lines (Done/Qualifies/Readiness); DURING a run
		// it's a single combined line PLUS one extra line per puzzle that member solved/failed (see
		// PartyHud#subInfoLines, partyHudShowPuzzleHistory) - sized for "the combined line + 1 puzzle
		// line" as a representative during-run case, since the real count is unbounded.
		int partyRowHeight = 14 + (partyFull ? 3 * 10 : 2 * 10);
		int partyHeight = 16 + partyPreviewMembers * partyRowHeight + 12; // +12 for the pre-run party-bottleneck line
		// Widened from 220/160 - a 16-char username plus the mod-user marker and MP text routinely
		// exceeds the old compact width, and the during-run combined sub-line (readiness + deaths +
		// room + portal/AFK markers) routinely exceeds the old full width.
		int partyWidth = partyFull ? 260 : 190;
		// PartyMpBarHud - a fixed-width horizontal strip regardless of member count (heads are
		// positioned proportionally along it, not stacked), so its preview size is just the range
		// label height + the bar itself.
		elements.add(new HudLayoutEditorScreen.Draggable(
				"Party MP Bar",
				() -> config.hudPartyMpBarX, () -> config.hudPartyMpBarY,
				(x, y) -> {
					config.hudPartyMpBarX = x;
					config.hudPartyMpBarY = y;
				},
				140, 12 + 10
		));
		elements.add(new HudLayoutEditorScreen.Draggable(
				"Party",
				() -> config.hudPartyX, () -> config.hudPartyY,
				(x, y) -> {
					config.hudPartyX = x;
					config.hudPartyY = y;
				},
				partyWidth, partyHeight
		));

		// DungeonScoreHud is now a plain vertical line stack (see its own doc comment for why) - width
		// is bounded by the longest representative line, height by a representative line count
		// (title/possible/penalties/breakdown/rooms + one room-secrets line + one secret + one puzzle line each).
		int scoreWidth = Math.max(
				Math.max(font.width("Dungeon Score: 285 (S+)"), font.width("Skill 100  Explore 100  Speed 100  Bonus 5")),
				font.width("✖ SomeLongUsername16 lost Tic Tac Toe! Yikes!")
		) + 8;
		// Real line count is structurally unbounded (secret rows, teammate sync lines, and every
		// puzzle outcome hit this run add lines on top of the fixed header lines) - sized for a
		// representative run, not just the bare minimum.
		int scoreHeight = 4 + 15 * 10 + 2;
		elements.add(new HudLayoutEditorScreen.Draggable(
				"Dungeon Score",
				() -> config.hudScoreX, () -> config.hudScoreY,
				(x, y) -> {
					config.hudScoreX = x;
					config.hudScoreY = y;
				},
				scoreWidth, scoreHeight
		));

		// Real lines: run-active + wither-door(s) + blood-entered + blood-cleared + boss-entered +
		// boss-cleared + Hypixel mode + Hypixel map = 8 minimum, more with 2+ wither doors.
		int debugWidth = font.width("✖ Wither door 2 not opened yet (key obtained)") + 8;
		int debugHeight = 4 + 9 * 10 + 2;
		elements.add(new HudLayoutEditorScreen.Draggable(
				"Dungeon Debug",
				() -> config.hudDebugX, () -> config.hudDebugY,
				(x, y) -> {
					config.hudDebugX = x;
					config.hudDebugY = y;
				},
				debugWidth, debugHeight
		));

		int healthManaWidth = config.healthManaBarsSideBySide ? (120 + 24 + 120 + 8) : (120 + 8);
		int healthManaHeight = config.healthManaBarsSideBySide ? 8 + 3 : (8 + 4 + 8 + 3); // side-by-side: one row; stacked: two 8px bars + gap
		// Mana Debug (see HealthManaBarsHud#renderManaDebug) draws a header line + one line per raw
		// actionbar (color,text) segment BELOW the bars - previously not accounted for at all, so the
		// placeholder box was far too short whenever that toggle was actually on. 4 segments is a
		// representative real actionbar (health/defense/mana/effect readouts).
		if (config.manaDebugEnabled) {
			healthManaHeight += 10 + 4 * 10;
		}
		elements.add(new HudLayoutEditorScreen.Draggable(
				"Health/Mana Bars",
				() -> config.hudHealthManaX, () -> config.hudHealthManaY,
				(x, y) -> {
					config.hudHealthManaX = x;
					config.hudHealthManaY = y;
				},
				healthManaWidth, healthManaHeight
		));

		return elements;
	}

	private static int fishingX(SkyMellooConfig config, Font font, int screenWidth) {
		if (config.hudFishingScoreX >= 0) {
			return config.hudFishingScoreX;
		}
		int width = font.width("Fishing Combo x9  999 pts");
		return screenWidth / 2 - width / 2;
	}

	private static int fishingY(SkyMellooConfig config, int screenHeight) {
		return config.hudFishingScoreY >= 0 ? config.hudFishingScoreY : screenHeight - 58;
	}
}
