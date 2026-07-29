package com.melloo.skymelloo.client.gui;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.social.ActionBarTracker;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;

/**
 * A sleek replacement bar display for health (green, gold where absorption/"golden hearts" adds
 * extra) and mana (light blue), independently toggleable and either stacked or side-by-side. Health
 * reads real vanilla data ({@link LocalPlayer#getHealth()} etc. - no scraping needed, always exactly
 * accurate). Mana has no real vanilla stat of its own to read directly - {@code experienceProgress}
 * (the vanilla XP bar fill) was a wrong assumption, not actually what Hypixel uses here. The real
 * mana number is plain text Hypixel prints into the actionbar every tick (see
 * {@link ActionBarTracker}), so this reads that instead.
 */
public final class HealthManaBarsHud implements HudElement {
	public static final HealthManaBarsHud INSTANCE = new HealthManaBarsHud();

	// Public - /sm debug hm-bar reports fill state against these same constants, so the chat output and
	// the actual on-screen bar can never disagree about what "120px wide" means.
	public static final int BAR_WIDTH = 120;
	public static final int BAR_HEIGHT = 8;
	private static final int BAR_GAP = 4;
	// How fast the white "recently lost" trailing segment catches down to the real value - fraction
	// of the bar drained per tick, tuned so a big hit takes roughly half a second to fully catch up.
	// Also reused for the mirrored "gain" fill-in below (same speed both directions, "animiert
	// aufgefüllt wie wenn es weggeht" - 2026-07-27).
	private static final float DAMAGE_TRAIL_DECAY_PER_TICK = 0.03F;
	// The gained-but-not-yet-filled-in gap - "erst direkt mit rot angezeigt wird und dann animiert
	// aufgefüllt" (2026-07-27): a heal/mana-regen shows the FULL new amount instantly in this color,
	// while the real green/blue fill only animates up to meet it.
	private static final int GAIN_HIGHLIGHT_COLOR = 0xFFFF5555;

	private float displayedHealthFraction = 1F;
	// Mirror of displayedHealthFraction for GAINS instead of losses - snaps down immediately with the
	// real value on a loss, but lags BEHIND (catching up from below) on a gain, so the newly-added
	// chunk stays visible as the gain-highlight colour until this animates up to meet it.
	private float risingHealthFraction = 1F;
	private boolean initializedTrail = false;
	// Same "flash white, then catch down a second later" trail the health bar already had - "die
	// manabar auch animiert wenn was verliert erst weiß färben und sekunde später dann so animiert
	// runtergehen lassen wie bei hp" (2026-07-26).
	private float displayedManaFraction = 1F;
	private float risingManaFraction = 1F;
	private boolean initializedManaTrail = false;

	private HealthManaBarsHud() {
	}

	/**
	 * Everything the health bar's fill is derived from, in one place - shared by the actual renderer
	 * ({@link #renderHealthBar}) and {@code /sm debug hm-bar} so the chat output can never drift from
	 * what's really drawn on screen. {@code fromActionBar} is false only in the brief window before the
	 * first actionbar packet has arrived this session, when this falls back to the vanilla health
	 * attribute - see the class doc comment on why the actionbar reading is preferred once available.
	 */
	public record HealthBarState(float health, float maxHealth, float absorption, float healthFraction,
								  float absorptionFraction, int healthPx, int absorptionPx, boolean fromActionBar) {
	}

	public static HealthBarState computeHealthBarState(LocalPlayer player) {
		Integer abHealth = ActionBarTracker.getCurrentHealth();
		Integer abMaxHealth = ActionBarTracker.getMaxHealth();
		float maxHealth;
		float health;
		boolean fromActionBar;
		if (abHealth != null && abMaxHealth != null && abMaxHealth > 0) {
			maxHealth = abMaxHealth;
			health = abHealth;
			fromActionBar = true;
		} else {
			maxHealth = Math.max(1F, player.getMaxHealth());
			health = Math.max(0F, player.getHealth());
			fromActionBar = false;
		}
		float absorption = Math.max(0F, health - maxHealth);
		float baseHealth = Math.min(health, maxHealth);
		float total = maxHealth + absorption;
		float healthFraction = baseHealth / total;
		float absorptionFraction = absorption / total;
		int healthPx = Math.round(BAR_WIDTH * healthFraction);
		int absorptionPx = Math.round(BAR_WIDTH * absorptionFraction);
		return new HealthBarState(health, maxHealth, absorption, healthFraction, absorptionFraction, healthPx, absorptionPx, fromActionBar);
	}

	/** Same sharing purpose as {@link HealthBarState} - {@code fraction}/{@code manaPx} are {@code null}/0 until the first actionbar mana readout has arrived this session. */
	public record ManaBarState(Integer current, Integer max, Float fraction, int manaPx) {
	}

	public static ManaBarState computeManaBarState() {
		Float manaFraction = ActionBarTracker.getManaFraction();
		int manaPx = manaFraction != null ? Math.round(BAR_WIDTH * manaFraction) : 0;
		return new ManaBarState(ActionBarTracker.getCurrentMana(), ActionBarTracker.getMaxMana(), manaFraction, manaPx);
	}

	/** The health bar's current animated "just lost" white-trail fill (0-1) - only meaningful once {@link #isHealthTrailInitialized()} is true. */
	public float getDisplayedHealthFraction() {
		return displayedHealthFraction;
	}

	public boolean isHealthTrailInitialized() {
		return initializedTrail;
	}

	/** The mana bar's equivalent of {@link #getDisplayedHealthFraction()}. */
	public float getDisplayedManaFraction() {
		return displayedManaFraction;
	}

	public boolean isManaTrailInitialized() {
		return initializedManaTrail;
	}

	/** The health bar's current animated "catching up to a gain" fill (0-1, mirror of {@link #getDisplayedHealthFraction()}) - only meaningful once {@link #isHealthTrailInitialized()} is true. */
	public float getRisingHealthFraction() {
		return risingHealthFraction;
	}

	/** The mana bar's equivalent of {@link #getRisingHealthFraction()}. */
	public float getRisingManaFraction() {
		return risingManaFraction;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor gg, DeltaTracker deltaTracker) {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (!config.healthManaBarsEnabled || (!config.healthBarEnabled && !config.manaBarEnabled)) {
			initializedTrail = false;
			initializedManaTrail = false;
			return;
		}
		// SkyBlock-only - these bars used to show on ANY Hypixel gamemode (lobby, other minigames),
		// not just SkyBlock, since nothing here ever checked further than "connected to Hypixel".
		if (!com.melloo.skymelloo.client.util.SkyblockDetector.isInSkyblock()) {
			initializedTrail = false;
			initializedManaTrail = false;
			return;
		}
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null) {
			initializedTrail = false;
			initializedManaTrail = false;
			return;
		}

		int x = config.hudHealthManaX;
		int y = config.hudHealthManaY;

		if (config.healthBarEnabled) {
			renderHealthBar(gg, client, player, x, y);
		}
		if (config.manaBarEnabled) {
			int manaX = config.healthBarEnabled && config.healthManaBarsSideBySide ? x + BAR_WIDTH + 24 : x;
			int manaY = config.healthBarEnabled && !config.healthManaBarsSideBySide ? y + BAR_HEIGHT + BAR_GAP : y;
			renderManaBar(gg, client, player, manaX, manaY);
		}
	}

	private void renderHealthBar(GuiGraphicsExtractor gg, Minecraft client, LocalPlayer player, int x, int y) {
		// Reads real SkyBlock HP from the same actionbar text the mana bar already uses, not vanilla's
		// own health attribute
		// (2026-07-26): vanilla health doesn't necessarily match Hypixel's real HP number 1:1 (e.g.
		// absorption/overheal can push "current" past "max", same as the mana pattern already handles).
		// Falls back to the vanilla attribute only if no actionbar reading has arrived yet this session.
		HealthBarState state = computeHealthBarState(player);
		float health = state.health();
		float maxHealth = state.maxHealth();
		float healthFraction = state.healthFraction();

		if (!initializedTrail) {
			displayedHealthFraction = healthFraction;
			risingHealthFraction = healthFraction;
			initializedTrail = true;
		} else {
			// Loss trail: the real fill snaps down immediately (drawn below), this lags behind on the
			// way down, fading out.
			displayedHealthFraction = displayedHealthFraction > healthFraction
					? Math.max(healthFraction, displayedHealthFraction - DAMAGE_TRAIL_DECAY_PER_TICK)
					: healthFraction;
			// Gain fill-in: the real value is shown instantly (below, as the highlight-coloured block),
			// this is the green fill catching UP to it - snaps immediately on a drop, no animation there.
			risingHealthFraction = risingHealthFraction < healthFraction
					? Math.min(healthFraction, risingHealthFraction + DAMAGE_TRAIL_DECAY_PER_TICK)
					: healthFraction;
		}

		// Background, white "just lost" trail, gain-highlight block (full new amount, instantly), then
		// the real current fill (green, gold where absorption extends past normal max) drawn on top -
		// the loss-trail only ever peeks out past the real edge (never underneath it), and the
		// gain-highlight only ever peeks out ahead of the still-catching-up green fill. healthPx is the
		// FULL health/total fraction on its own (NOT minus absorption - that was the bug: it made the
		// green segment absorption-worth too short, leaving a visible gap between the green and gold
		// segments instead of them sitting flush).
		gg.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, 0x99101018);
		int trailPx = Math.round(BAR_WIDTH * displayedHealthFraction);
		if (trailPx > 0) {
			gg.fill(x, y, x + trailPx, y + BAR_HEIGHT, 0xFFFFFFFF);
		}
		int healthPx = state.healthPx();
		int absorptionPx = state.absorptionPx();
		if (healthPx > 0) {
			gg.fill(x, y, x + healthPx, y + BAR_HEIGHT, GAIN_HIGHLIGHT_COLOR);
		}
		int risingHealthPx = Math.round(BAR_WIDTH * risingHealthFraction);
		if (risingHealthPx > 0) {
			gg.fill(x, y, x + risingHealthPx, y + BAR_HEIGHT, 0xFF55DD55);
		}
		if (absorptionPx > 0) {
			gg.fill(x + healthPx, y, x + healthPx + absorptionPx, y + BAR_HEIGHT, 0xFFFFD700);
		}
		gg.outline(x, y, BAR_WIDTH, BAR_HEIGHT, 0xFF000000);
		// Numeric current/max HP label added alongside the bar (2026-07-26) - not counting absorption
		// (that's already visually distinct as the gold segment above).
		String healthText = Math.round(health) + "/" + Math.round(maxHealth);
		int healthTextX = x + BAR_WIDTH / 2 - client.font.width(healthText) / 2;
		gg.text(client.font, healthText, healthTextX, y, 0xFFFFFFFF);
	}

	private void renderManaBar(GuiGraphicsExtractor gg, Minecraft client, LocalPlayer player, int x, int y) {
		Float manaFraction = ActionBarTracker.getManaFraction();
		float safeManaFraction = manaFraction != null ? manaFraction : displayedManaFraction;
		// Same white "just lost" trail the health bar has - "die manabar auch animiert wenn was
		// verliert erst weiß färben und sekunde später dann so animiert runtergehen lassen wie bei
		// hp" (2026-07-26). Same mirrored gain fill-in as the health bar too (2026-07-27).
		if (!initializedManaTrail) {
			displayedManaFraction = safeManaFraction;
			risingManaFraction = safeManaFraction;
			initializedManaTrail = true;
		} else {
			displayedManaFraction = displayedManaFraction > safeManaFraction
					? Math.max(safeManaFraction, displayedManaFraction - DAMAGE_TRAIL_DECAY_PER_TICK)
					: safeManaFraction;
			risingManaFraction = risingManaFraction < safeManaFraction
					? Math.min(safeManaFraction, risingManaFraction + DAMAGE_TRAIL_DECAY_PER_TICK)
					: safeManaFraction;
		}

		gg.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, 0x99101018);
		int manaTrailPx = Math.round(BAR_WIDTH * displayedManaFraction);
		if (manaTrailPx > 0) {
			gg.fill(x, y, x + manaTrailPx, y + BAR_HEIGHT, 0xFFFFFFFF);
		}
		if (manaFraction != null) {
			int manaPx = Math.round(BAR_WIDTH * manaFraction);
			if (manaPx > 0) {
				gg.fill(x, y, x + manaPx, y + BAR_HEIGHT, GAIN_HIGHLIGHT_COLOR);
			}
			int risingManaPx = Math.round(BAR_WIDTH * risingManaFraction);
			if (risingManaPx > 0) {
				gg.fill(x, y, x + risingManaPx, y + BAR_HEIGHT, 0xFF55CCFF);
			}
		}
		gg.outline(x, y, BAR_WIDTH, BAR_HEIGHT, 0xFF000000);
		Integer current = ActionBarTracker.getCurrentMana();
		Integer max = ActionBarTracker.getMaxMana();
		if (current != null && max != null) {
			String text = current + "/" + max;
			int textX = x + BAR_WIDTH / 2 - client.font.width(text) / 2;
			gg.text(client.font, text, textX, y, 0xFFFFFFFF);
		}
		if (SkyMellooConfig.HANDLER.instance().manaDebugEnabled) {
			renderManaDebug(gg, client, x, y);
		}
	}

	/** Every (color, text) run actually seen in the last actionbar packet - toggled via "Mana Debug" in the settings screen. */
	private void renderManaDebug(GuiGraphicsExtractor gg, Minecraft client, int x, int y) {
		int lineY = y + BAR_HEIGHT + 2;
		long sincePacket = System.currentTimeMillis() - ActionBarTracker.getLastPacketMillis();
		String header = ActionBarTracker.getLastPacketMillis() == 0
				? "no actionbar packet seen yet"
				: "last packet " + sincePacket + "ms ago, " + ActionBarTracker.getLastSegments().size() + " segment(s):";
		gg.text(client.font, header, x, lineY, 0xFFFFAA00);
		lineY += 10;
		for (ActionBarTracker.Segment seg : ActionBarTracker.getLastSegments()) {
			gg.text(client.font, "[" + seg.colorHex() + "] \"" + seg.text() + "\"", x, lineY, 0xFFCCCCCC);
			lineY += 10;
		}
	}
}
