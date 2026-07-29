package com.melloo.skymelloo.client.highlight;

import com.melloo.skymelloo.client.SkyMellooClient;
import com.melloo.skymelloo.client.util.ChatUtil;
import com.melloo.skymelloo.client.util.HypixelDetector;
import com.melloo.skymelloo.client.util.SkyblockDetector;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.UUID;

/**
 * Tracks the one player currently targeted by {@code /sm search}, so {@link HighlightManager} can
 * highlight them green while browsing a Hypixel lobby - the party/staff/friend highlight (see
 * HighlightManager#classifyPlayer) is deliberately SkyBlock-only, so there was otherwise no way to pick a
 * specific person out of a crowded lobby at all. Deliberately lobby-only, not usable in SkyBlock -
 * party/friend/staff highlighting already covers that case there.
 * <p>
 * Cleared automatically on leaving Hypixel entirely or entering SkyBlock (see {@link #tick}), not
 * just on disconnect - a leftover search target from the last lobby would be meaningless (and
 * misleading) anywhere else.
 */
public final class LobbySearchManager {
	private static volatile UUID searchedUuid = null;
	private static volatile String searchedName = null;

	private LobbySearchManager() {
	}

	public static void tick(Minecraft client) {
		if (searchedUuid == null) {
			return;
		}
		if (!HypixelDetector.isHypixel(client) || SkyblockDetector.isInSkyblock()) {
			clear();
		}
	}

	/** Whether the local player is somewhere this feature actually applies - connected to Hypixel, but not in SkyBlock (which has its own party/staff/friend highlighting instead). */
	public static boolean isLobby(Minecraft client) {
		return HypixelDetector.isHypixel(client) && !SkyblockDetector.isInSkyblock();
	}

	/**
	 * Resolves the typed name against the current tab list - the true source of truth for who's
	 * actually visible in this lobby right now, same list the command's own autocomplete offers (see
	 * {@link SkyMellooClient#suggestOnlinePlayers}). Sends its own chat feedback either way.
	 */
	public static void search(Minecraft client, String name) {
		if (client.player == null) {
			return;
		}
		if (!isLobby(client)) {
			client.player.sendSystemMessage(ChatUtil.prefixed("§cThis only works in a Hypixel lobby, not in SkyBlock."));
			return;
		}
		if (client.getConnection() == null) {
			return;
		}
		PlayerInfo match = client.getConnection().getOnlinePlayers().stream()
				.filter(info -> info.getProfile().name().equalsIgnoreCase(name))
				.findFirst()
				.orElse(null);
		if (match == null) {
			client.player.sendSystemMessage(ChatUtil.prefixed("§cNo player named \"" + name + "\" found in this lobby."));
			return;
		}
		searchedUuid = match.getProfile().id();
		searchedName = match.getProfile().name();
		client.player.sendSystemMessage(ChatUtil.prefixed("§aNow highlighting §f" + searchedName + "§a - look for the green glow."));
	}

	public static void clear() {
		if (searchedUuid == null) {
			return;
		}
		searchedUuid = null;
		searchedName = null;
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) {
			client.player.sendSystemMessage(ChatUtil.prefixed("§7Player search cleared."));
		}
	}

	public static boolean isSearchedPlayer(UUID uuid) {
		return searchedUuid != null && searchedUuid.equals(uuid);
	}

	// ---- command tree ----

	public static LiteralArgumentBuilder<FabricClientCommandSource> buildSearchCommand() {
		return ClientCommands.literal("search")
				.then(ClientCommands.literal("clear").executes(ctx -> {
					clear();
					return 1;
				}))
				.then(ClientCommands.argument("name", StringArgumentType.word())
						.suggests(SkyMellooClient::suggestOnlinePlayers)
						.executes(ctx -> {
							search(Minecraft.getInstance(), StringArgumentType.getString(ctx, "name"));
							return 1;
						}));
	}
}
