package com.melloo.skymelloo.client.api;

import com.melloo.skymelloo.client.util.ChatUtil;
import com.melloo.skymelloo.client.util.DebugLog;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Proves to sky.melloo.me that a request genuinely comes from a live, logged-in Minecraft client
 * for a specific account - not just a client claiming so via a header, which anyone could fake
 * with curl. Rides on the exact same joinServer/hasJoined handshake vanilla servers use for
 * online-mode auth: we call Mojang's own sessionserver "join" with a one-time serverId the
 * backend hands us, then the backend asks Mojang's "hasJoined" whether that really happened.
 * Only someone holding a real, currently-valid Microsoft/Mojang access token for that account can
 * make that true.
 *
 * Unlike the old bearer-token design, what the backend gets after that confirmation is NOT a
 * reusable secret - it's the public half of an Ed25519 keypair generated fresh in memory this
 * launch (never written to disk, never sent anywhere but its public half). Every subsequent
 * request is individually signed with the matching private key (see {@link ModIdentity#sign}),
 * so a captured request can never be replayed later - each signature is only valid once, for one
 * exact request, within a short time window (2026-07-26, replacing the previous bearer-token
 * design after the user chose the full ephemeral-keypair + per-request-signing rewrite).
 */
public final class ModAuthManager {
	// Refresh a little before actual expiry so an in-flight request never races a just-expired session.
	private static final long REFRESH_MARGIN_MS = 5 * 60 * 1000;

	private static volatile CompletableFuture<ModIdentity> identityFuture = null;
	private static volatile long identityExpiresAt = 0;
	// Generated once per game launch, held ONLY in memory, never serialized to disk or sent anywhere
	// but its public half - there is no at-rest secret here for anything to steal, unlike a
	// persisted token/keyfile would be, so nothing needs OS-level key storage to protect it.
	private static volatile KeyPair ephemeralKeyPair = null;
	// Shown at most once per game session - if the underlying Minecraft/Mojang access token itself
	// has gone stale (as opposed to just our own session expiring, which re-proves itself
	// transparently), NOTHING account-linked (Friends, Cloud Sync, party stats, /skymelloo view, ...)
	// can work again until the game restarts and the launcher hands out a fresh one - previously this
	// just failed silently forever (only visible with Debug Messages on), leaving no indication of
	// what broke or how to fix it.
	private static volatile boolean sessionExpiredPromptShown = false;

	private ModAuthManager() {
	}

	/**
	 * A live, authenticated identity: the account this launch proved ownership of, the private key
	 * only this JVM instance ever holds, and this launch's one-time clock-offset correction (see
	 * {@link #authenticate}) so every signed timestamp reflects the SERVER's clock, not whatever the
	 * player's PC clock happens to read.
	 */
	public record ModIdentity(String uuid, String username, PrivateKey signingKey, long clockOffsetMs) {
		private static final SecureRandom RANDOM = new SecureRandom();

		/** The 4 headers to attach to one specific outgoing request - see SkyMellooApiClient's attachSignature. */
		public record SignedHeaders(String uuid, String timestamp, String nonce, String signature) {
		}

		/**
		 * Signs {@code method + path + timestamp + nonce + sha256(bodyBytes)} (newline-joined, same
		 * exact field order the server reconstructs in lib/modAuth.js#verifySignedRequest) with this
		 * identity's private key. A fresh random nonce and clock-corrected timestamp every call means
		 * no two requests are ever signed identically, even if sent back to back.
		 */
		public SignedHeaders sign(String method, String path, byte[] bodyBytes) {
			long timestamp = System.currentTimeMillis() + clockOffsetMs;
			byte[] nonceBytes = new byte[16];
			RANDOM.nextBytes(nonceBytes);
			String nonce = HexFormat.of().formatHex(nonceBytes);
			String bodyHash = HexFormat.of().formatHex(sha256(bodyBytes));
			String message = String.join("\n", uuid, method, path, String.valueOf(timestamp), nonce, bodyHash);
			try {
				Signature signer = Signature.getInstance("Ed25519");
				signer.initSign(signingKey);
				signer.update(message.getBytes(StandardCharsets.UTF_8));
				String signatureBase64 = Base64.getEncoder().encodeToString(signer.sign());
				return new SignedHeaders(uuid, String.valueOf(timestamp), nonce, signatureBase64);
			} catch (GeneralSecurityException e) {
				// Signing with our own freshly-generated, in-memory key should never fail.
				throw new RuntimeException(e);
			}
		}

		private static byte[] sha256(byte[] data) {
			try {
				return MessageDigest.getInstance("SHA-256").digest(data);
			} catch (NoSuchAlgorithmException e) {
				throw new RuntimeException(e);
			}
		}
	}

	/**
	 * Resolves to a currently-valid identity, transparently (re-)running the Mojang proof if none
	 * is cached, failed, or is about to expire. Deliberately NOT reset on every server join - an
	 * earlier version was, but the session proves account ownership (via Mojang), not anything tied
	 * to a specific TCP connection, so it stays valid across reconnects. Hypixel does its own
	 * internal server-hops (dungeon floor entry, "/server X") that fire the same join event other
	 * managers use for their own per-connection resets, and wiping+re-proving on every one of those
	 * was adding a full extra Mojang round trip (and, worse, occasionally racing two near-
	 * simultaneous re-auths - see the isDone() check below) right when party-join stat lookups
	 * needed it, which is what was making them slow or silently fail entirely.
	 */
	public static synchronized CompletableFuture<ModIdentity> getIdentity(Minecraft client) {
		// !isDone() matters as much as the expiry check: while a fetch is still in flight,
		// identityExpiresAt is still 0 from the previous (or no) session, so relying on the expiry
		// check alone would make a second near-simultaneous caller think it needs its own fresh
		// fetch too - kicking off a redundant, concurrent Mojang joinServer call that can get
		// rejected as a rate-limited duplicate, silently failing that caller's request.
		if (identityFuture != null && (!identityFuture.isDone() || System.currentTimeMillis() < identityExpiresAt - REFRESH_MARGIN_MS)) {
			return identityFuture;
		}
		DebugLog.log(DebugLog.Category.PERMISSIONS, "Mod auth: acquiring a fresh identity...");
		identityFuture = authenticate(client);
		return identityFuture;
	}

	private static synchronized KeyPair ephemeralKeyPair() {
		if (ephemeralKeyPair == null) {
			try {
				ephemeralKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
			} catch (NoSuchAlgorithmException e) {
				throw new RuntimeException(e);
			}
		}
		return ephemeralKeyPair;
	}

	private static CompletableFuture<ModIdentity> authenticate(Minecraft client) {
		User user = client.getUser();
		if (user == null || user.getAccessToken() == null || user.getAccessToken().isBlank()) {
			return CompletableFuture.failedFuture(new IllegalStateException("No Minecraft session available"));
		}
		String username = user.getName();
		String uuid = user.getProfileId().toString().replace("-", "").toLowerCase(Locale.ROOT);
		MinecraftSessionService sessionService = client.services().sessionService();
		KeyPair keyPair = ephemeralKeyPair();
		String publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

		return SkyMellooApiClient.requestAuthChallenge()
				.thenComposeAsync(challenge -> {
					long clockOffsetMs = challenge.serverTime() - System.currentTimeMillis();
					return CompletableFuture.supplyAsync(() -> {
						try {
							sessionService.joinServer(user.getProfileId(), user.getAccessToken(), challenge.serverId());
						} catch (AuthenticationException e) {
							throw new CompletionException(e);
						}
						return clockOffsetMs;
					}).thenCompose(offset -> SkyMellooApiClient
							.verifyAuthChallenge(challenge.serverId(), username, uuid, publicKeyBase64)
							.thenApply(result -> {
								identityExpiresAt = result.expiresAt();
								DebugLog.log(DebugLog.Category.PERMISSIONS, "Mod auth: identity acquired.");
								return new ModIdentity(uuid, username, keyPair.getPrivate(), offset);
							}));
				})
				.exceptionally(error -> {
					DebugLog.log(DebugLog.Category.PERMISSIONS, "Mod auth failed: " + error.getMessage());
					identityFuture = null; // don't keep serving a failed future - next getIdentity() retries
					if (isSessionExpired(error) && !sessionExpiredPromptShown) {
						sessionExpiredPromptShown = true;
						Minecraft.getInstance().execute(() -> {
							var player = Minecraft.getInstance().player;
							if (player != null) {
								player.sendSystemMessage(ChatUtil.prefixed(
										"§cYour Minecraft session has expired - SkyMelloo's account-linked features (Friends, Cloud Sync, Party stats, /skymelloo view, etc.) won't work again until you §lrestart the game§r§c to get a fresh session."));
							}
						});
					}
					throw new CompletionException(error);
				});
	}

	/** Specifically the Mojang joinServer call rejecting our OWN access token as invalid - a real, currently-running game session's access token going stale mid-session, not a transient network/backend error (those get retried normally and don't need a restart to fix). */
	private static boolean isSessionExpired(Throwable error) {
		Throwable cause = error;
		while (cause != null) {
			if (cause instanceof AuthenticationException) {
				return true;
			}
			cause = cause.getCause();
		}
		return false;
	}
}
