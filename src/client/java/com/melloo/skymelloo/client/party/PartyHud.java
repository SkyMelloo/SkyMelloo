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

/**
 * Shows your current Hypixel party - each member's name, face and Magical Power (see
 * {@link PartyHudManager}) - only while you're actually in one. "Full" mode's per-member sub-line
 * shows different info depending on whether a dungeon run is active ({@link DungeonRunTracker#isRunActive()}):
 * DURING a run, a ready-up indicator, live HP%, death count, current room type, and a "near boss
 * portal" marker (room/portal resolved via {@link DungeonRoomTracker}/
 * {@link DungeonRunTracker#isNearBossPortal}, not just for the local player); BEFORE one starts, HP/
 * ready/room aren't meaningful yet, so each member's highest-completed Catacombs floor is shown
 * instead (from sky.melloo.me, see {@link PartyHudManager}), plus one extra line at the very bottom
 * of the box showing the whole party's floor "bottleneck" (the lowest of everyone's highest-completed
 * floor). Position is configurable via the HUD layout editor (default J).
 * <p>
 * Face icons are cached per-UUID ({@link #skinCache}) once successfully resolved from the local tab
 * list, and kept showing the last-known skin if a member's {@link PlayerInfo} briefly isn't
 * available (e.g. right after a reconnect) instead of the icon flickering away and back.
 */
public final class PartyHud implements HudElement {
	private static final int ROW_HEIGHT = 14;
	private static final int SUB_ROW_HEIGHT = 10;
	private static final int FACE_SIZE = 12;
	private static final float SKIN_TEX_SIZE = 64f;
	private static final Map<UUID, Identifier> skinCache = new HashMap<>();

	public static final PartyHud INSTANCE = new PartyHud();

	private PartyHud() {
	}

	private static String mpText(int mp) {
		return mp >= 0 ? mp + " MP" : "-- MP";
	}

	private static final int MP_NORMAL_COLOR = 0xFF55FFFF;
	private static final int MP_LOW_COLOR_YELLOW = 0xFFFFFF55;
	private static final int MP_LOW_COLOR_RED = 0xFFFF5555;
	// A member needs this fraction of your own MP or less before their color maxes out at pure red -
	// e.g. 0.5 means "half your MP or less is as bad as it gets", not literally zero.
	private static final double MP_DEFICIT_FOR_FULL_RED = 0.5;

	/** Default cyan unless {@code memberMp} is meaningfully LOWER than your own {@code localMp} - then a yellow-to-red gradient scaled by how big the gap is, not a flat "low MP" threshold. */
	private static int mpColor(int memberMp, int localMp) {
		if (memberMp < 0 || localMp <= 0 || memberMp >= localMp) {
			return MP_NORMAL_COLOR;
		}
		double deficit = (localMp - memberMp) / (double) localMp;
		double t = Math.min(1.0, deficit / MP_DEFICIT_FOR_FULL_RED);
		return lerpArgb(MP_LOW_COLOR_YELLOW, MP_LOW_COLOR_RED, t);
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
		// SkyBlock's Dungeons only - not the main hub, other islands, or any other Hypixel gamemode.
		// Covers both the dungeon hub (pre-run floor/MP readiness, see the class doc comment) and an
		// actual run - HypixelLocationTracker#isLikelyInDungeon is a best-effort substring match on
		// Hypixel's own (undocumented) location data, not a hardcoded room/floor check, so it already
		// covers "somewhere in the Catacombs area" rather than just "mid-run".
		if (!com.melloo.skymelloo.client.util.SkyblockDetector.isInSkyblock() || !com.melloo.skymelloo.client.social.HypixelLocationTracker.isLikelyInDungeon()) {
			return;
		}
		Map<java.util.UUID, PartyHudManager.MemberInfo> members = PartyHudManager.getMembers();
		if (members.isEmpty()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		int x = config.hudPartyX;
		int y = config.hudPartyY;
		// Your own MP, if known - used to color OTHER members' MP relative to yours (see mpColor).
		int localMp = -1;
		if (client.player != null) {
			PartyHudManager.MemberInfo self = members.get(client.player.getUUID());
			if (self != null) {
				localMp = self.magicalPower();
			}
		}
		// Full mode renders a BIGGER face icon, so textX needs the wider size too.
		int rowFaceSize = full ? FACE_SIZE + SUB_ROW_HEIGHT : FACE_SIZE;
		int textX = x + rowFaceSize + 4;

		boolean runActive = DungeonRunTracker.isRunActive();
		// Not in a run right now - HP/ready/room aren't meaningful yet, so show a party-wide
		// bottleneck line instead: the group can only reliably handle whatever the WEAKEST member's
		// highest-completed floor is, per sky.melloo.me stats (see PartyHudManager).
		String bottomLine = null;
		if (!runActive) {
			int minCompleted = members.values().stream().mapToInt(PartyHudManager.MemberInfo::highestFloor).filter(f -> f >= 0).min().orElse(-1);
			int minQualifying = members.values().stream().mapToInt(PartyHudManager.MemberInfo::qualifyingFloor).filter(f -> f >= 0).min().orElse(-1);
			int minReadiness = members.values().stream().mapToInt(PartyHudManager.MemberInfo::readinessScore).filter(f -> f >= 0).min().orElse(-1);
			if (minCompleted >= 0 || minQualifying >= 0 || minReadiness >= 0) {
				// "Weakest" called out explicitly in the label - every value here is a MINIMUM across
				// the party, not an average or the local player's own stat, which wasn't obvious before.
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

		// Pre-run sub-info is always 3 stacked lines (Done/Qualifies/Readiness); during a run each
		// member can have a DIFFERENT number of extra lines now (the shared ready+HP+deaths+room line,
		// plus one line per puzzle THEY specifically solved/failed, appended via
		// PlayerHudManager/subInfoLines) - so heights/widths are computed per-member below rather than
		// from one shared count, or a member with puzzle history would just clip into the row below.
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
			int rowWidth = rowFaceSize + 4 + client.font.width(nameWidthText) + client.font.width(mpText(member.magicalPower())) + 24;
			width = Math.max(width, rowWidth);
		}
		if (full) {
			// Sub-info lines can be wider than the name+MP line above them on their own - widen the
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

			// Only members currently in our own tab list have a bound skin texture available live -
			// cache it per-UUID once seen so the icon doesn't flicker away and back for cross-instance
			// party members or brief tab-list gaps (see PartyHudManager, skinCache).
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

			// A small marker after the name for party members also detected running SkyMelloo (via
			// sky.melloo.me presence, same detection the highlight system already uses for its own mod-user color) -
			// embedded §-code, honored regardless of the base color passed to gg.text below.
			String nameText = member.username() + (ModPresenceManager.isModUser(entry.getKey()) ? " §b◆" : "");
			gg.text(client.font, nameText, textX, rowY, 0xFFFFFFFF);
			String mp = mpText(member.magicalPower());
			gg.text(client.font, mp, x + width - 4 - client.font.width(mp), rowY, mpColor(member.magicalPower(), localMp));

			java.util.List<String> subLines = subLinesByMember.getOrDefault(entry.getKey(), java.util.List.of());
			if (full) {
				// Full mode's extra info hangs BELOW the name/MP line (not widening it sideways) - one
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
			// Pre-run: HP/ready/room don't exist yet - show what's actually useful before queueing:
			// highest floor actually completed before, AND highest floor they're currently level-
			// eligible for (Catacombs/Combat Skill requirements) - two different questions, see
			// PartyHudManager.MemberInfo. Stacked one per line rather than crammed side by side, since
			// that read as one confusing run-on line at a glance.
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
		// One extra line per puzzle THIS member specifically solved/failed - the row visually "folds
		// open" underneath whenever they've actually been involved in a puzzle, and stays exactly as
		// before for anyone who hasn't. Matched by username since that's all a PuzzleResult records.
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

		// Room/portal info is only knowable for members whose entity is currently tracked by the
		// client (same limitation as HP/face icon above) - DungeonRoomTracker's map-reading anchors
		// are shared for the whole run, so once resolved this works for ANY tracked member's
		// position, not just the local player's own.
		AbstractClientPlayer player = findPlayer(uuid, client);
		if (player != null) {
			DungeonRoomTracker.RoomType type = DungeonRoomTracker.getRoomTypeAt(client, player.getX(), player.getZ());
			if (type != null) {
				sub.append("  §7").append(roomLabel(type));
				// The specific catalogued room name (e.g. "Round Room") only via Skyblocker's own room
				// database if it's also installed - our own map-color detection only knows the coarse
				// RoomType, never the exact room. Only meaningful for the LOCAL player's own row, since
				// Skyblocker (like our DungeonRoomTracker) only ever knows the local player's current room.
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

	/** Only meaningful for members currently rendered as a visible entity nearby (same limitation as the face icon) - empty otherwise, since vanilla only syncs entity health for entities the client can actually see. */
	private static String hpText(UUID uuid, Minecraft client) {
		AbstractClientPlayer player = findPlayer(uuid, client);
		if (player == null || player.getMaxHealth() <= 0) {
			return "";
		}
		int percent = Math.round(player.getHealth() / player.getMaxHealth() * 100f);
		return percent + "%";
	}

	/** A §-formatting-code prefix (not an int color) since the whole sub-info line renders as one {@code gg.text} call with inline color codes. */
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
