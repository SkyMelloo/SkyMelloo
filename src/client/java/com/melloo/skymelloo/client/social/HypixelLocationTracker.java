package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.util.DebugLog;
import net.hypixel.modapi.HypixelModAPI;
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket;

import java.util.Locale;
import java.util.Objects;

/**
 * Whether the local player is actually inside a Hypixel SkyBlock dungeon right now, per Hypixel's
 * OWN official Mod API location event (already a dependency - see PartyTracker, which uses the same
 * library for party info) - a real server-confirmed signal, rather than inferring it purely from
 * chat text/sidebar parsing, which has been the root cause of most of this project's dungeon-tracking
 * bugs this session (spurious resets, a run getting stuck "active" forever, boss-fight false
 * readings - all traceable back to chat/sidebar being an unofficial, sometimes-inconsistent source).
 * <p>
 * Hypixel's exact mode/map string values for Catacombs aren't documented anywhere public - this
 * still logs the raw fields (see the debug HUD) so a wrong guess can be confirmed/fixed from a real
 * run. {@link #isLikelyInDungeon()} (currently just a "dungeon" substring match on mode/map) now
 * DOES gate real behavior ({@link com.melloo.skymelloo.client.party.PartyHud}'s "only show in
 * Dungeons" restriction, 2026-07-25) - if that turns out unreliable in practice, check the debug
 * HUD's raw mode/map values first before assuming the gating logic itself is wrong.
 */
public final class HypixelLocationTracker {
	private static boolean initialized = false;
	private static volatile String lastMode = null;
	private static volatile String lastMap = null;

	private HypixelLocationTracker() {
	}

	public static void init() {
		if (initialized) {
			return;
		}
		initialized = true;
		HypixelModAPI.getInstance().subscribeToEventPacket(ClientboundLocationPacket.class);
		HypixelModAPI.getInstance().createHandler(ClientboundLocationPacket.class, packet -> {
			String mode = packet.getMode().orElse(null);
			String map = packet.getMap().orElse(null);
			if (Objects.equals(mode, lastMode) && Objects.equals(map, lastMap)) {
				return;
			}
			lastMode = mode;
			lastMap = map;
			DebugLog.log(DebugLog.Category.DUNGEON, "Hypixel location changed - server=" + packet.getServerName() + ", mode=" + mode + ", map=" + map);
		});
	}

	public static String getMode() {
		return lastMode;
	}

	public static String getMap() {
		return lastMap;
	}

	/** Best-effort only - see the class doc comment. Confirm the real mode/map values from the debug log before relying on this for anything that actually gates behavior. */
	public static boolean isLikelyInDungeon() {
		return containsDungeon(lastMode) || containsDungeon(lastMap);
	}

	private static boolean containsDungeon(String value) {
		return value != null && value.toLowerCase(Locale.ROOT).contains("dungeon");
	}
}
