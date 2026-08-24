package com.melloo.skymelloo.client.gui;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.UUID;

/**
 * Draws a player's skin as a flat front-facing figure, head to feet, with the overlay layers on top.
 * The skin is looked up once per screen through Minecraft's own skin manager and cached here.
 */
public final class PlayerSkinPanel {
	/** Unscaled figure size - 16 wide (arms either side of an 8-wide body) by 32 tall. */
	public static final int WIDTH = 16;
	public static final int HEIGHT = 32;

	private final String username;
	private Identifier texture;
	private boolean slim;
	private boolean requested;

	public PlayerSkinPanel(String username) {
		this.username = username;
	}

	/** Starts the lookup once the real uuid is known - a name alone can't be resolved offline. */
	public void request(String uuid) {
		if (requested || uuid == null || uuid.isBlank()) {
			return;
		}
		requested = true;
		UUID parsed = parseUuid(uuid);
		if (parsed == null) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		client.getSkinManager().get(new GameProfile(parsed, username)).thenAccept(skin ->
				client.execute(() -> skin.ifPresent(this::apply)));
	}

	private void apply(PlayerSkin skin) {
		texture = skin.body().texturePath();
		slim = skin.model() == PlayerModelType.SLIM;
	}

	public boolean isReady() {
		return texture != null;
	}

	public void render(GuiGraphicsExtractor gg, int x, int y, int scale) {
		if (texture == null) {
			return;
		}
		int armWidth = slim ? 3 : 4;
		int bodyX = x + 4 * scale;
		int armY = y + 8 * scale;
		// Base layer, then the hat/jacket/sleeve overlays drawn over it in the same positions.
		part(gg, bodyX, y, 8, 8, 8, 8, scale);
		part(gg, bodyX, armY, 20, 20, 8, 12, scale);
		part(gg, x + (4 - armWidth) * scale, armY, 44, 20, armWidth, 12, scale);
		part(gg, x + 12 * scale, armY, 36, 52, armWidth, 12, scale);
		part(gg, bodyX, y + 20 * scale, 4, 20, 4, 12, scale);
		part(gg, x + 8 * scale, y + 20 * scale, 20, 52, 4, 12, scale);

		part(gg, bodyX, y, 40, 8, 8, 8, scale);
		part(gg, bodyX, armY, 20, 36, 8, 12, scale);
		part(gg, x + (4 - armWidth) * scale, armY, 44, 36, armWidth, 12, scale);
		part(gg, x + 12 * scale, armY, 52, 52, armWidth, 12, scale);
		part(gg, bodyX, y + 20 * scale, 4, 36, 4, 12, scale);
		part(gg, x + 8 * scale, y + 20 * scale, 4, 52, 4, 12, scale);
	}

	private void part(GuiGraphicsExtractor gg, int x, int y, int u, int v, int w, int h, int scale) {
		gg.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, w * scale, h * scale, w, h, 64, 64);
	}

	private static UUID parseUuid(String raw) {
		try {
			return raw.contains("-") ? UUID.fromString(raw)
					: UUID.fromString(raw.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
