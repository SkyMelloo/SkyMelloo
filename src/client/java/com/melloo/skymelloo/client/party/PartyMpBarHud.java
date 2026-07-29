package com.melloo.skymelloo.client.party;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A horizontal Magical Power "spread" bar for the current party - each member's face icon is placed
 * along a fixed-width strip, positioned left-to-right from whoever has the least MP to whoever has
 * the most, with the actual MP range labeled above it. Distinct from {@link PartyHud}'s own vertical
 * name+MP list: this is about seeing the SHAPE of the party's gear gap at a glance (how spread out
 * everyone is) rather than reading exact numbers per member. Needs at least 2 members with known MP
 * to mean anything - a single point has nowhere meaningful to sit on a range.
 */
public final class PartyMpBarHud implements HudElement {
	private static final int BAR_WIDTH = 140;
	private static final int BAR_HEIGHT = 6;
	private static final int FACE_SIZE = 10;
	private static final float SKIN_TEX_SIZE = 64f;
	private static final Map<UUID, Identifier> skinCache = new HashMap<>();

	public static final PartyMpBarHud INSTANCE = new PartyMpBarHud();

	private PartyMpBarHud() {
	}

	// Same corner-face blit as PartyHud's own drawFace - duplicated rather than shared since it's a
	// tiny private static method there, not worth restructuring a working file over.
	private static void drawFace(GuiGraphicsExtractor gg, Identifier texture, int x, int y, int size) {
		gg.blit(texture, x, y, x + size, y + size, 8f / SKIN_TEX_SIZE, 16f / SKIN_TEX_SIZE, 8f / SKIN_TEX_SIZE, 16f / SKIN_TEX_SIZE);
		gg.blit(texture, x, y, x + size, y + size, 40f / SKIN_TEX_SIZE, 48f / SKIN_TEX_SIZE, 8f / SKIN_TEX_SIZE, 16f / SKIN_TEX_SIZE);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor gg, DeltaTracker deltaTracker) {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (!config.partyMpBarEnabled) {
			return;
		}
		Map<UUID, PartyHudManager.MemberInfo> members = PartyHudManager.getMembers();
		List<Map.Entry<UUID, PartyHudManager.MemberInfo>> withMp = members.entrySet().stream()
				.filter(entry -> entry.getValue().magicalPower() >= 0)
				.toList();
		if (withMp.size() < 2) {
			return;
		}

		int minMp = withMp.stream().mapToInt(entry -> entry.getValue().magicalPower()).min().orElse(0);
		int maxMp = withMp.stream().mapToInt(entry -> entry.getValue().magicalPower()).max().orElse(0);

		Minecraft client = Minecraft.getInstance();
		int x = config.hudPartyMpBarX;
		int y = config.hudPartyMpBarY;

		String rangeText = minMp + " - " + maxMp + " MP";
		gg.text(client.font, rangeText, x + BAR_WIDTH / 2 - client.font.width(rangeText) / 2, y, 0xFFFF6EC7);

		int barY = y + 12;
		gg.fill(x - 2, barY - 2, x + BAR_WIDTH + 2, barY + BAR_HEIGHT + 2, 0x99101018);
		gg.fill(x, barY, x + BAR_WIDTH, barY + BAR_HEIGHT, 0xFF303038);

		for (Map.Entry<UUID, PartyHudManager.MemberInfo> entry : withMp) {
			PartyHudManager.MemberInfo member = entry.getValue();
			double t = maxMp == minMp ? 0.5 : (double) (member.magicalPower() - minMp) / (maxMp - minMp);
			int faceX = x + (int) Math.round(t * (BAR_WIDTH - FACE_SIZE));
			int faceY = barY + BAR_HEIGHT / 2 - FACE_SIZE / 2;

			// Same tab-list-first, cache-fallback resolution as PartyHud's own face icons - a party
			// member isn't always in the local tab list (cross-instance parties), so this keeps
			// showing the last-known skin instead of the icon flickering away and back.
			PlayerInfo info = client.getConnection() != null ? client.getConnection().getPlayerInfo(entry.getKey()) : null;
			Identifier skinTexture;
			if (info != null && info.getSkin() != null) {
				skinTexture = info.getSkin().body().texturePath();
				skinCache.put(entry.getKey(), skinTexture);
			} else {
				skinTexture = skinCache.get(entry.getKey());
			}
			if (skinTexture != null) {
				drawFace(gg, skinTexture, faceX, faceY, FACE_SIZE);
			}
		}
	}
}
