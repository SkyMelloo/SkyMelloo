package com.melloo.skymelloo.client.gui;

import com.melloo.mellooessentials.client.gui.HudLayoutEditorScreen;
import com.melloo.skymelloo.client.config.SkyMellooConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

import java.util.ArrayList;
import java.util.List;

// SkyMelloo's own HUD elements for MellooEssentials' layout editor - supplied via
// HudLayoutEditorScreen#setExtraElementsProvider, since the editor itself lives in that mod.
public final class SkyMellooHudElements {
	private SkyMellooHudElements() {
	}

	public static List<HudLayoutEditorScreen.Draggable> build(int screenWidth, int screenHeight) {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		Font font = Minecraft.getInstance().font;
		List<HudLayoutEditorScreen.Draggable> elements = new ArrayList<>();

		// Width includes the " +999"-style bonus suffix shown right after a combo point is gained.
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

		// Assumes a representative 3-member party for sizing purposes.
		boolean partyFull = "FULL".equalsIgnoreCase(config.partyHudMode);
		int partyPreviewMembers = 3;
		// Sized for "combined line + 1 puzzle line" as a representative during-run case; real count is unbounded.
		int partyRowHeight = 14 + (partyFull ? 3 * 10 : 2 * 10);
		int partyHeight = 16 + partyPreviewMembers * partyRowHeight + 12; // +12 for the pre-run bottleneck line
		int partyWidth = partyFull ? 260 : 190;
		elements.add(new HudLayoutEditorScreen.Draggable(
				"Party AP Bar",
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

		// Width bounded by the longest representative line; real line count is structurally unbounded
		// (secret rows, teammate sync lines, puzzle outcomes), so height is sized for a representative run.
		int scoreWidth = Math.max(
				Math.max(font.width("Dungeon Score: 285 (S+)"), font.width("Skill 100  Explore 100  Speed 100  Bonus 5")),
				font.width("✖ SomeLongUsername16 lost Tic Tac Toe! Yikes!")
		) + 8;
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

		// 8 lines minimum (run-active, wither doors, blood/boss entered+cleared, Hypixel mode/map), more with 2+ wither doors.
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
		// Mana Debug draws a header + one line per raw actionbar segment below the bars; 4 is representative.
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
