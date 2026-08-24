package com.melloo.skymelloo.client.highlight;

import com.melloo.skymelloo.client.SkyMellooClient;
import com.melloo.skymelloo.client.util.ChatUtil;
import com.melloo.mellooessentials.client.util.HypixelDetector;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * Tracks the one player currently targeted by {@code /sm search}, so {@link HighlightManager} can
 * highlight them green anywhere on Hypixel, lobby or SkyBlock - party/staff/friend highlighting
 * (see MellooEssentials' own highlight.HighlightManager) needs an actual Hypixel party/team/friend
 * relationship, so there's otherwise no way to pick an arbitrary specific person out of a crowd.
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
		if (!HypixelDetector.isHypixel(client)) {
			clear();
		}
	}

	/** Whether the local player is somewhere this feature actually applies - connected to Hypixel at all. */
	public static boolean isLobby(Minecraft client) {
		return HypixelDetector.isHypixel(client);
	}

	/**
	 * Resolves the typed name against the current tab list - the true source of truth for who's
	 * actually visible right now, same list the command's own autocomplete offers (see
	 * {@link SkyMellooClient#suggestOnlinePlayers}). Sends its own chat feedback either way.
	 */
	public static void search(Minecraft client, String name) {
		if (client.player == null) {
			return;
		}
		if (!isLobby(client)) {
			client.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.chat.lobby_search.wrong_location")));
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
			client.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.chat.lobby_search.not_found", name)));
			return;
		}
		searchedUuid = match.getProfile().id();
		searchedName = match.getProfile().name();
		client.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.chat.lobby_search.now_highlighting", searchedName)));
	}

	public static void clear() {
		if (searchedUuid == null) {
			return;
		}
		searchedUuid = null;
		searchedName = null;
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) {
			client.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.chat.lobby_search.cleared")));
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
