package com.melloo.skymelloo.client.gui;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.IntSupplier;

/**
 * Lightweight HUD layout editor (opened via J, like Lunar Client's own HUD editor) - drag
 * SkyMelloo's on-screen HUD elements to reposition them. Draws a placeholder box for each element
 * (sized to match its real render) rather than the real HudElement, so an element that's currently
 * hidden (e.g. the fishing combo HUD with no active chain right now) can still be repositioned.
 */
public class HudLayoutEditorScreen extends Screen {
	private final class Draggable {
		final String label;
		final IntSupplier getX;
		final IntSupplier getY;
		final BiConsumer<Integer, Integer> setPos;
		final int width;
		final int height;

		Draggable(String label, IntSupplier getX, IntSupplier getY, BiConsumer<Integer, Integer> setPos, int width, int height) {
			this.label = label;
			this.getX = getX;
			this.getY = getY;
			this.setPos = setPos;
			this.width = width;
			this.height = height;
		}
	}

	private static final int SNAP_THRESHOLD = 6;
	private static final int CORNER_TICK_LENGTH = 10;
	private static final int CORNER_TICK_COLOR = 0xFFFFAA00;

	private List<Draggable> elements;
	private Draggable dragging;
	private int dragOffsetX, dragOffsetY;
	private Integer snapLineX;
	private Integer snapLineY;
	/** Corners (TOP_LEFT etc.) where the dragged box is currently equidistant from its two nearby screen edges - drawn as a small accent bracket instead of a full guide line, since it's about two PERPENDICULAR margins matching rather than an alignment target. */
	private final List<Corner> equalMarginCorners = new ArrayList<>();

	private enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

	public HudLayoutEditorScreen() {
		super(Component.literal("SkyMelloo HUD Layout"));
	}

	@Override
	protected void init() {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		com.melloo.mellooessentials.client.config.EssentialsConfig essentialsConfig =
				com.melloo.mellooessentials.client.config.EssentialsConfig.get();
		elements = new ArrayList<>();
		// Fixed 2-line layout now (headline + one combined detail line, see
		// ConnectionStatusHud#extractRenderState's own doc comment) - always exactly 26px tall
		// regardless of connection state. This is essentials' ConnectionStatusHud - the single
		// connection-status HUD for both mods now (this mod's own former copy, WhitelistStatusHud,
		// was a near-duplicate and got removed).
		int statusWidth = Math.max(
				this.font.width("Connected ★"),
				this.font.width("sky.melloo.me · 1h 05m 30s · 999ms")
		) + 20;
		elements.add(new Draggable(
				"Connection Status",
				() -> essentialsConfig.hudConnectionStatusX >= 0 ? essentialsConfig.hudConnectionStatusX : 6,
				() -> essentialsConfig.hudConnectionStatusY >= 0 ? essentialsConfig.hudConnectionStatusY : 6,
				(x, y) -> {
					essentialsConfig.hudConnectionStatusX = x;
					essentialsConfig.hudConnectionStatusY = y;
				},
				statusWidth, 26
		));
		// Real box is 16px tall (see FishingScoreHud) and its width includes a " +999"-style bonus
		// suffix appended after the main text whenever a combo point was just gained - missing from
		// the sample here used to undersize the box during exactly the moment it's most likely visible.
		int fishWidth = this.font.width("Fishing Combo x9  999 pts +999") + 12;
		elements.add(new Draggable(
				"Fishing Combo",
				this::fishingX, this::fishingY,
				(x, y) -> {
					config.hudFishingScoreX = x;
					config.hudFishingScoreY = y;
				},
				fishWidth, 16
		));
		// Party/Score placeholder sizes used to be stale fixed numbers well below what those HUDs
		// actually render now (Full mode's 2-line-per-member layout + bottleneck line, the score
		// HUD's rooms-cleared line + puzzle-outcome block row) - recomputed here to track their real
		// current layout instead. Still an approximation (member count/text length vary at runtime),
		// assuming a representative 3-member party for sizing purposes.
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
			elements.add(new Draggable(
					"Party MP Bar",
					() -> config.hudPartyMpBarX, () -> config.hudPartyMpBarY,
					(x, y) -> {
						config.hudPartyMpBarX = x;
						config.hudPartyMpBarY = y;
					},
					140, 12 + 10
			));
		elements.add(new Draggable(
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
				Math.max(this.font.width("Dungeon Score: 285 (S+)"), this.font.width("Skill 100  Explore 100  Speed 100  Bonus 5")),
				this.font.width("✖ SomeLongUsername16 lost Tic Tac Toe! Yikes!")
		) + 8;
		// Real line count is structurally unbounded (secret rows, teammate sync lines, and every
		// puzzle outcome hit this run all add lines on top of the fixed title/time/S+/possible/
		// penalties/breakdown/rooms/room-secrets-header lines) - 11 only covered the bare minimum
		// "one of everything" case. Sized for a more representative run instead.
		int scoreHeight = 4 + 15 * 10 + 2;
		elements.add(new Draggable(
				"Dungeon Score",
				() -> config.hudScoreX, () -> config.hudScoreY,
				(x, y) -> {
					config.hudScoreX = x;
					config.hudScoreY = y;
				},
				scoreWidth, scoreHeight
		));

		// Real lines: run-active + wither-door(s) + blood-entered + blood-cleared + boss-entered +
		// boss-cleared + Hypixel mode + Hypixel map = 8 minimum, more with 2+ wither doors - the old
		// 6-line/short-sample estimate undercounted both the line count and the widest realistic line.
		// Sample text matches DungeonDebugHud's current (reworded) longest line, not the old wording.
		int debugWidth = this.font.width("✖ Wither door 2 not opened yet (key obtained)") + 8;
		int debugHeight = 4 + 9 * 10 + 2;
		elements.add(new Draggable(
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
		elements.add(new Draggable(
				"Health/Mana Bars",
				() -> config.hudHealthManaX, () -> config.hudHealthManaY,
				(x, y) -> {
					config.hudHealthManaX = x;
					config.hudHealthManaY = y;
				},
				healthManaWidth, healthManaHeight
		));

		// Built from the exact same lines the real HUD renders right now (see PlayerInfoHud#buildLines)
		// instead of guessing from hardcoded sample text - two fixed samples ("Server: mc.hypixel.net",
		// a specific ping reading) were routinely WIDER than what actually renders for a given player's
		// real FPS/ping/server/area, which meant centering the (too-wide) editor box left the real,
		// narrower HUD off-center the moment the editor closed. This element has no "hidden right now"
		// state worth preserving a placeholder for (unlike Fishing Combo/Score/Debug), so using its
		// real live size here is always safe. This is essentials' PlayerInfoHud - the single Player
		// Info HUD for both mods now (this mod's own former copy was a byte-for-byte duplicate and
		// got removed; essentials has no positioning UI of its own, so this editor is the only way to
		// move it, same cross-mod pattern already used for the Connection Status HUD above).
		List<String> playerInfoLines = com.melloo.mellooessentials.client.gui.PlayerInfoHud.buildLines(Minecraft.getInstance());
		int playerInfoWidth = 8;
		for (String line : playerInfoLines) {
			playerInfoWidth = Math.max(playerInfoWidth, this.font.width(line) + 8);
		}
		int playerInfoHeight = 4 + Math.max(1, playerInfoLines.size()) * 10 + 2;
		elements.add(new Draggable(
				"Player Info",
				() -> essentialsConfig.hudPlayerInfoX >= 0 ? essentialsConfig.hudPlayerInfoX : 6,
				() -> essentialsConfig.hudPlayerInfoY >= 0 ? essentialsConfig.hudPlayerInfoY : 6,
				(x, y) -> {
					essentialsConfig.hudPlayerInfoX = x;
					essentialsConfig.hudPlayerInfoY = y;
				},
				playerInfoWidth, playerInfoHeight
		));
	}

	private int fishingX() {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (config.hudFishingScoreX >= 0) {
			return config.hudFishingScoreX;
		}
		int width = this.font.width("Fishing Combo x9  999 pts");
		return this.width / 2 - width / 2;
	}

	private int fishingY() {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		return config.hudFishingScoreY >= 0 ? config.hudFishingScoreY : this.height - 58;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return true;
	}

	@Override
	public void onClose() {
		SkyMellooConfig.HANDLER.save();
		com.melloo.mellooessentials.client.config.EssentialsConfig.save();
		super.onClose();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int mx = (int) event.x();
		int my = (int) event.y();
		for (Draggable d : elements) {
			int x = d.getX.getAsInt();
			int y = d.getY.getAsInt();
			if (mx >= x - 4 && mx <= x + d.width - 4 && my >= y - 3 && my <= y + d.height - 3) {
				dragging = d;
				dragOffsetX = mx - x;
				dragOffsetY = my - y;
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (dragging == null) {
			return super.mouseDragged(event, dragX, dragY);
		}
		int rawX = (int) event.x() - dragOffsetX;
		int rawY = (int) event.y() - dragOffsetY;
		// Clamped first so you can never drag a HUD element fully off-screen.
		int clampedX = Math.max(0, Math.min(rawX, this.width - dragging.width));
		int clampedY = Math.max(0, Math.min(rawY, this.height - dragging.height));

		// Three kinds of snap target per axis - the box's LOW edge (left/top), HIGH edge
		// (right/bottom), or center can each align to a target; whichever of the three is closest
		// within threshold wins. Not just centers anymore: an element can now snap its actual edge
		// flush against the screen edge or another element's edge, not only line up by middle.
		List<Integer> lowX = new ArrayList<>(List.of(0));
		List<Integer> highX = new ArrayList<>(List.of(this.width));
		List<Integer> centerX = new ArrayList<>(List.of(this.width / 2));
		List<Integer> lowY = new ArrayList<>(List.of(0));
		List<Integer> highY = new ArrayList<>(List.of(this.height));
		List<Integer> centerY = new ArrayList<>(List.of(this.height / 2));
		for (Draggable other : elements) {
			if (other == dragging) {
				continue;
			}
			int ox = other.getX.getAsInt();
			int oy = other.getY.getAsInt();
			lowX.add(ox);
			highX.add(ox + other.width);
			centerX.add(ox + other.width / 2);
			lowY.add(oy);
			highY.add(oy + other.height);
			centerY.add(oy + other.height / 2);
		}

		int[] snappedX = snapAxis(clampedX, dragging.width, lowX, highX, centerX);
		int[] snappedY = snapAxis(clampedY, dragging.height, lowY, highY, centerY);
		snapLineX = snappedX[1] >= 0 ? snappedX[1] : null;
		snapLineY = snappedY[1] >= 0 ? snappedY[1] : null;

		int newX = snappedX[0];
		int newY = snappedY[0];
		dragging.setPos.accept(newX, newY);

		equalMarginCorners.clear();
		int marginLeft = newX;
		int marginRight = this.width - (newX + dragging.width);
		int marginTop = newY;
		int marginBottom = this.height - (newY + dragging.height);
		if (Math.abs(marginLeft - marginTop) <= SNAP_THRESHOLD) {
			equalMarginCorners.add(Corner.TOP_LEFT);
		}
		if (Math.abs(marginRight - marginTop) <= SNAP_THRESHOLD) {
			equalMarginCorners.add(Corner.TOP_RIGHT);
		}
		if (Math.abs(marginLeft - marginBottom) <= SNAP_THRESHOLD) {
			equalMarginCorners.add(Corner.BOTTOM_LEFT);
		}
		if (Math.abs(marginRight - marginBottom) <= SNAP_THRESHOLD) {
			equalMarginCorners.add(Corner.BOTTOM_RIGHT);
		}
		return true;
	}

	/**
	 * @return {new low-coordinate (x or y) for this axis, guide line position or -1 if nothing snapped}
	 * Checks the box's low edge, high edge, and center against their respective target lists and
	 * takes whichever single alignment (across all three) is closest within {@link #SNAP_THRESHOLD}.
	 */
	private static int[] snapAxis(int rawLow, int size, List<Integer> lowTargets, List<Integer> highTargets, List<Integer> centerTargets) {
		int bestLow = rawLow;
		int bestDist = SNAP_THRESHOLD + 1;
		int guideLine = -1;

		for (int target : lowTargets) {
			int dist = Math.abs(rawLow - target);
			if (dist <= SNAP_THRESHOLD && dist < bestDist) {
				bestDist = dist;
				bestLow = target;
				guideLine = target;
			}
		}
		int rawHigh = rawLow + size;
		for (int target : highTargets) {
			int dist = Math.abs(rawHigh - target);
			if (dist <= SNAP_THRESHOLD && dist < bestDist) {
				bestDist = dist;
				bestLow = target - size;
				guideLine = target;
			}
		}
		int rawCenter = rawLow + size / 2;
		for (int target : centerTargets) {
			int dist = Math.abs(rawCenter - target);
			if (dist <= SNAP_THRESHOLD && dist < bestDist) {
				bestDist = dist;
				bestLow = target - size / 2;
				guideLine = target;
			}
		}
		return new int[] { bestLow, guideLine };
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging != null) {
			dragging = null;
			snapLineX = null;
			snapLineY = null;
			equalMarginCorners.clear();
			SkyMellooConfig.HANDLER.save();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
		gg.centeredText(this.font, "SkyMelloo HUD Layout — drag to move, Esc to close", this.width / 2, 10, 0xFFFFFFFF);
		if (snapLineX != null) {
			gg.fill(snapLineX, 0, snapLineX + 1, this.height, 0xAAFF6EC7);
		}
		if (snapLineY != null) {
			gg.fill(0, snapLineY, this.width, snapLineY + 1, 0xAAFF6EC7);
		}
		if (dragging != null) {
			int dx = dragging.getX.getAsInt();
			int dy = dragging.getY.getAsInt();
			for (Corner corner : equalMarginCorners) {
				drawCornerTick(gg, corner, dx, dy, dragging.width, dragging.height);
			}
		}
		for (Draggable d : elements) {
			int x = d.getX.getAsInt();
			int y = d.getY.getAsInt();
			boolean active = d == dragging;
			gg.fill(x - 4, y - 3, x + d.width - 4, y + d.height - 3, active ? 0xAAFF6EC7 : 0x6633CC66);
			gg.outline(x - 4, y - 3, d.width, d.height, 0xFFFFFFFF);
			gg.centeredText(this.font, d.label, x + (d.width - 8) / 2, y + (d.height - 8) / 2 - 3, 0xFFFFFFFF);
		}
		super.extractRenderState(gg, mouseX, mouseY, partialTick);
	}

	/** A short L-shaped bracket at one corner of the dragged box, indicating its margins to the two nearby screen edges are currently equal - a different kind of alignment than the full-line edge/center guides above, since it's about two PERPENDICULAR gaps matching rather than a shared position. */
	private void drawCornerTick(GuiGraphicsExtractor gg, Corner corner, int x, int y, int width, int height) {
		int left = x, right = x + width, top = y, bottom = y + height;
		switch (corner) {
			case TOP_LEFT -> {
				gg.fill(left, top, left + CORNER_TICK_LENGTH, top + 1, CORNER_TICK_COLOR);
				gg.fill(left, top, left + 1, top + CORNER_TICK_LENGTH, CORNER_TICK_COLOR);
			}
			case TOP_RIGHT -> {
				gg.fill(right - CORNER_TICK_LENGTH, top, right, top + 1, CORNER_TICK_COLOR);
				gg.fill(right - 1, top, right, top + CORNER_TICK_LENGTH, CORNER_TICK_COLOR);
			}
			case BOTTOM_LEFT -> {
				gg.fill(left, bottom - 1, left + CORNER_TICK_LENGTH, bottom, CORNER_TICK_COLOR);
				gg.fill(left, bottom - CORNER_TICK_LENGTH, left + 1, bottom, CORNER_TICK_COLOR);
			}
			case BOTTOM_RIGHT -> {
				gg.fill(right - CORNER_TICK_LENGTH, bottom - 1, right, bottom, CORNER_TICK_COLOR);
				gg.fill(right - 1, bottom - CORNER_TICK_LENGTH, right, bottom, CORNER_TICK_COLOR);
			}
		}
	}
}
