package com.melloo.skymelloo.client.party;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.social.DungeonRoomTracker;
import com.melloo.skymelloo.client.social.DungeonRunTracker;
import com.melloo.skymelloo.client.social.ModPresenceManager;
import com.melloo.skymelloo.client.social.SkyblockerBridge;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// Shows the current party (name/face/AP); Full mode adds live HP/ready/room during a run, or
// highest-completed floor before one starts. Face icons are cached per-UUID to avoid flicker.
public final class PartyHud implements HudElement {
	private static final int ROW_HEIGHT = 14;
	private static final int SUB_ROW_HEIGHT = 10;
	private static final int FACE_SIZE = 12;
	private static final float SKIN_TEX_SIZE = 64f;
	private static final Map<UUID, Identifier> skinCache = new HashMap<>();

	public static final PartyHud INSTANCE = new PartyHud();

	private PartyHud() {
	}

	private static String apText(int ap) {
		return ap >= 0 ? ap + " AP" : "-- AP";
	}

	private static final int AP_NORMAL_COLOR = 0xFF55FFFF;
	private static final int AP_LOW_COLOR_YELLOW = 0xFFFFFF55;
	private static final int AP_LOW_COLOR_RED = 0xFFFF5555;
	// A member needs this fraction of your own AP or less before their color maxes out at pure red -
	// e.g. 0.5 means "half your AP or less is as bad as it gets", not literally zero.
	private static final double AP_DEFICIT_FOR_FULL_RED = 0.5;

	// Default cyan unless memberAp is meaningfully lower than localAp, then a yellow-to-red gradient.
	private static int apColor(int memberAp, int localAp) {
		if (memberAp < 0 || localAp <= 0 || memberAp >= localAp) {
			return AP_NORMAL_COLOR;
		}
		double deficit = (localAp - memberAp) / (double) localAp;
		double t = Math.min(1.0, deficit / AP_DEFICIT_FOR_FULL_RED);
		return lerpArgb(AP_LOW_COLOR_YELLOW, AP_LOW_COLOR_RED, t);
	}

	private static int lerpArgb(int from, int to, double t) {
		int r1 = (from >> 16) & 0xFF, g1 = (from >> 8) & 0xFF, b1 = from & 0xFF;
		int r2 = (to >> 16) & 0xFF, g2 = (to >> 8) & 0xFF, b2 = to & 0xFF;
		int r = (int) Math.round(r1 + (r2 - r1) * t);
		int g = (int) Math.round(g1 + (g2 - g1) * t);
		int b = (int) Math.round(b1 + (b2 - b1) * t);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	// Standard 64x64 skin layout: base face at (8,8)-(16,16), hat overlay at (40,8)-(48,16).
	// blit(texture, x1, y1, x2, y2, u0, u1, v0, v1) - corner coordinates, not (x, y, width, height).
	private static void drawFace(GuiGraphicsExtractor gg, Identifier texture, int x, int y, int size) {
		gg.blit(texture, x, y, x + size, y + size, 8f / SKIN_TEX_SIZE, 16f / SKIN_TEX_SIZE, 8f / SKIN_TEX_SIZE, 16f / SKIN_TEX_SIZE);
		gg.blit(texture, x, y, x + size, y + size, 40f / SKIN_TEX_SIZE, 48f / SKIN_TEX_SIZE, 8f / SKIN_TEX_SIZE, 16f / SKIN_TEX_SIZE);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor gg, DeltaTracker deltaTracker) {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		boolean full = "FULL".equalsIgnoreCase(config.partyHudMode);
		if ("OFF".equalsIgnoreCase(config.partyHudMode)) {
			return;
		}
		// Covers both the dungeon hub and an actual run - isLikelyInDungeon is a substring match on
		// Hypixel's own location data, not a hardcoded room/floor check.
		if (!com.melloo.skymelloo.client.util.SkyblockDetector.isInSkyblock() || !com.melloo.mellooessentials.client.social.HypixelLocationTracker.isLikelyInDungeon()) {
			return;
		}
		Map<java.util.UUID, PartyHudManager.MemberInfo> members = PartyHudManager.getMembers();
		if (members.isEmpty()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		int x = config.hudPartyX;
		int y = config.hudPartyY;
		// Your own AP, if known - used to color OTHER members' AP relative to yours (see apColor).
		int localAp = -1;
		if (client.player != null) {
			PartyHudManager.MemberInfo self = members.get(client.player.getUUID());
			if (self != null) {
				localAp = self.accessoryPower();
			}
		}
		// Full mode renders a BIGGER face icon, so textX needs the wider size too.
		int rowFaceSize = full ? FACE_SIZE + SUB_ROW_HEIGHT : FACE_SIZE;
		int textX = x + rowFaceSize + 4;

		boolean runActive = DungeonRunTracker.isRunActive();
		// Not in a run - show a bottleneck line instead: the group is only as strong as its weakest member.
		String bottomLine = null;
		if (!runActive) {
			int minCompleted = members.values().stream().mapToInt(PartyHudManager.MemberInfo::highestFloor).filter(f -> f >= 0).min().orElse(-1);
			int minQualifying = members.values().stream().mapToInt(PartyHudManager.MemberInfo::qualifyingFloor).filter(f -> f >= 0).min().orElse(-1);
			int minReadiness = members.values().stream().mapToInt(PartyHudManager.MemberInfo::readinessScore).filter(f -> f >= 0).min().orElse(-1);
			if (minCompleted >= 0 || minQualifying >= 0 || minReadiness >= 0) {
				// Every value here is the MINIMUM across the party, not an average or the local player's own.
				StringBuilder line = new StringBuilder("Weakest: ");
				boolean first = true;
				if (minCompleted >= 0) {
					line.append("done F").append(minCompleted);
					first = false;
				}
				if (minQualifying >= 0) {
					if (!first) {
						line.append("  ·  ");
					}
					line.append("qualifies F").append(minQualifying);
					first = false;
				}
				if (minReadiness >= 0) {
					if (!first) {
						line.append("  ·  ");
					}
					line.append("readiness ").append(minReadiness).append("/1000");
				}
				bottomLine = line.toString();
			}
		}

		// Each member can have a different number of sub-lines (puzzle history varies), so
		// heights/widths are computed per-member below instead of from one shared count.
		Map<UUID, java.util.List<String>> subLinesByMember = new HashMap<>();
		if (full) {
			for (Map.Entry<UUID, PartyHudManager.MemberInfo> entry : members.entrySet()) {
				subLinesByMember.put(entry.getKey(), subInfoLines(entry.getKey(), entry.getValue(), client));
			}
		}
		String headerText = "Party (" + members.size() + ")";
		// The header text itself wasn't accounted for in the width below - with few/short-named
		// members it could be wider than every row, overflowing past the box's own right edge.
		int width = Math.max(140, client.font.width(headerText) + 8);
		for (Map.Entry<UUID, PartyHudManager.MemberInfo> widthEntry : members.entrySet()) {
			PartyHudManager.MemberInfo member = widthEntry.getValue();
			String nameWidthText = member.username() + (ModPresenceManager.isModUser(widthEntry.getKey()) ? " ◆" : "");
			int rowWidth = rowFaceSize + 4 + client.font.width(nameWidthText) + client.font.width(apText(member.accessoryPower())) + 24;
			width = Math.max(width, rowWidth);
		}
		if (full) {
			// Sub-info lines can be wider than the name+AP line above them on their own - widen the
			// box for that too instead of letting it clip.
			for (java.util.List<String> subLines : subLinesByMember.values()) {
				for (String sub : subLines) {
					width = Math.max(width, rowFaceSize + 4 + client.font.width(sub) + 8);
				}
			}
		}
		if (bottomLine != null) {
			width = Math.max(width, client.font.width(bottomLine) + 8);
		}
		int height = 16 + (bottomLine != null ? 12 : 0);
		for (UUID uuid : members.keySet()) {
			height += ROW_HEIGHT + subLinesByMember.getOrDefault(uuid, java.util.List.of()).size() * SUB_ROW_HEIGHT;
		}

		gg.fill(x - 4, y - 3, x + width, y + height, 0x99101018);
		gg.text(client.font, headerText, x, y, 0xFFFF6EC7);

		int rowY = y + 12;
		for (Map.Entry<UUID, PartyHudManager.MemberInfo> entry : members.entrySet()) {
			PartyHudManager.MemberInfo member = entry.getValue();

			// Cache per-UUID once seen so cross-instance members or brief tab-list gaps don't flicker.
			PlayerInfo info = client.getConnection() != null ? client.getConnection().getPlayerInfo(entry.getKey()) : null;
			Identifier skinTexture;
			if (info != null && info.getSkin() != null) {
				skinTexture = info.getSkin().body().texturePath();
				skinCache.put(entry.getKey(), skinTexture);
			} else {
				skinTexture = skinCache.get(entry.getKey());
			}
			if (skinTexture != null) {
				drawFace(gg, skinTexture, x, rowY - 2, rowFaceSize);
			}

			// Embedded §-code marker for other detected SkyMelloo users, honored regardless of the base color below.
			String nameText = member.username() + (ModPresenceManager.isModUser(entry.getKey()) ? " §b◆" : "");
			gg.text(client.font, nameText, textX, rowY, 0xFFFFFFFF);
			String ap = apText(member.accessoryPower());
			gg.text(client.font, ap, x + width - 4 - client.font.width(ap), rowY, apColor(member.accessoryPower(), localAp));

			java.util.List<String> subLines = subLinesByMember.getOrDefault(entry.getKey(), java.util.List.of());
			if (full) {
				// Full mode's extra info hangs BELOW the name/AP line (not widening it sideways) - one
				// or more of its own lines per member, stacked, rather than crammed onto one line.
				int subY = rowY + ROW_HEIGHT - 2;
				for (String sub : subLines) {
					gg.text(client.font, sub, textX, subY, 0xFFFFFFFF);
					subY += SUB_ROW_HEIGHT;
				}
			}

			rowY += ROW_HEIGHT + subLines.size() * SUB_ROW_HEIGHT;
		}

		if (bottomLine != null) {
			gg.text(client.font, bottomLine, x, rowY, 0xFFAAAAAA);
		}
	}

	private static java.util.List<String> subInfoLines(UUID uuid, PartyHudManager.MemberInfo member, Minecraft client) {
		if (!DungeonRunTracker.isRunActive()) {
			// Pre-run: highest floor completed and highest floor currently eligible for - see MemberInfo.
			java.util.List<String> lines = new java.util.ArrayList<>(3);
			lines.add("§7Done " + (member.highestFloor() < 0 ? "?" : "F" + member.highestFloor()));
			lines.add("§7Qualifies " + (member.qualifyingFloor() < 0 ? "?" : "F" + member.qualifyingFloor()));
			if (member.readinessScore() >= 0) {
				lines.add("§7Readiness " + member.readinessScore() + "/1000");
			}
			return lines;
		}
		java.util.List<String> lines = new java.util.ArrayList<>();
		lines.add(subInfoTextDuringRun(uuid, member, client));
		// One extra line per puzzle this member solved/failed. Matched by username, all PuzzleResult has.
		if (SkyMellooConfig.HANDLER.instance().partyHudShowPuzzleHistory) {
			for (DungeonRunTracker.PuzzleResult puzzle : DungeonRunTracker.getPuzzleOutcomes()) {
				if (puzzle.outcome() == DungeonRunTracker.PuzzleOutcome.PENDING || !puzzle.player().equalsIgnoreCase(member.username())) {
					continue;
				}
				boolean solved = puzzle.outcome() == DungeonRunTracker.PuzzleOutcome.SOLVED;
				lines.add((solved ? "§a✓ " : "§c✖ ") + "§7" + puzzle.detail());
			}
		}
		return lines;
	}

	private static String subInfoTextDuringRun(UUID uuid, PartyHudManager.MemberInfo member, Minecraft client) {
		boolean ready = DungeonRunTracker.isReady(member.username());
		String hp = hpText(uuid, client);
		StringBuilder sub = new StringBuilder(ready ? "§a✓ " : "§7○ ");
		if (!hp.isEmpty()) {
			sub.append(hpColorCode(uuid, client)).append(hp).append("§r  ");
		}
		sub.append("§c").append(deathText(member.username()));

		// Room/portal info needs the member's entity to be currently tracked by the client, same as HP.
		AbstractClientPlayer player = findPlayer(uuid, client);
		if (player != null) {
			DungeonRoomTracker.RoomType type = DungeonRoomTracker.getRoomTypeAt(client, player.getX(), player.getZ());
			if (type != null) {
				sub.append("  §7").append(roomLabel(type));
				// The specific room name is only available via Skyblocker, and only for the local player's row.
				if (client.player != null && uuid.equals(client.player.getUUID())) {
					SkyblockerBridge.RoomSecrets roomSecrets = SkyblockerBridge.getCurrentRoomSecrets();
					if (roomSecrets != null && roomSecrets.roomName() != null) {
						sub.append(" (").append(roomSecrets.roomName()).append(")");
					}
				}
			}
			if (DungeonRunTracker.isNearBossPortal(player.position())) {
				sub.append(" §d[Portal]");
			}
		}
		if (DungeonRunTracker.isAfk(member.username())) {
			sub.append(" §e[AFK]");
		}
		return sub.toString();
	}

	private static String roomLabel(DungeonRoomTracker.RoomType type) {
		return switch (type) {
			case ENTRANCE -> "Entrance";
			case ROOM -> "Room";
			case PUZZLE -> "Puzzle";
			case TRAP -> "Trap";
			case MINIBOSS -> "Miniboss";
			case FAIRY -> "Fairy";
			case BLOOD -> "Blood";
			case UNKNOWN -> "?";
		};
	}

	private static String deathText(String username) {
		return "☠" + DungeonRunTracker.getDeaths(username);
	}

	// Empty unless the member is a currently-visible nearby entity - vanilla only syncs health for those.
	private static String hpText(UUID uuid, Minecraft client) {
		AbstractClientPlayer player = findPlayer(uuid, client);
		if (player == null || player.getMaxHealth() <= 0) {
			return "";
		}
		int percent = Math.round(player.getHealth() / player.getMaxHealth() * 100f);
		return percent + "%";
	}

	// A §-code prefix, not an int color - the sub-info line renders as one gg.text call with inline codes.
	private static String hpColorCode(UUID uuid, Minecraft client) {
		AbstractClientPlayer player = findPlayer(uuid, client);
		if (player == null || player.getMaxHealth() <= 0) {
			return "§7";
		}
		float ratio = player.getHealth() / player.getMaxHealth();
		if (ratio > 0.66f) {
			return "§a";
		}
		if (ratio > 0.33f) {
			return "§e";
		}
		return "§c";
	}

	private static AbstractClientPlayer findPlayer(UUID uuid, Minecraft client) {
		if (client.level == null) {
			return null;
		}
		for (AbstractClientPlayer player : client.level.players()) {
			if (player.getUUID().equals(uuid)) {
				return player;
			}
		}
		return null;
	}
}
