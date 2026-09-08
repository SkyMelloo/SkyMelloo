package com.melloo.skymelloo.client.api;

import com.melloo.skymelloo.client.util.ChatUtil;
import com.melloo.skymelloo.client.util.DebugLog;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.network.chat.Component;

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

// Proves to sky.melloo.me that a request comes from a live, logged-in client, via the same
// joinServer/hasJoined handshake vanilla servers use. Requests are signed with a fresh in-memory
// Ed25519 keypair the backend only ever sees the public half of, so none can be replayed.
public final class ModAuthManager {
	// Refresh a little before actual expiry so an in-flight request never races a just-expired session.
	private static final long REFRESH_MARGIN_MS = 5 * 60 * 1000;

	private static volatile CompletableFuture<ModIdentity> identityFuture = null;
	private static volatile long identityExpiresAt = 0;
	// Generated once per launch, held only in memory - never serialized or sent anywhere but its public half.
	private static volatile KeyPair ephemeralKeyPair = null;
	// Shown at most once per session - a stale Mojang access token needs a game restart to fix, so
	// this tells the player instead of every account-linked feature silently failing forever.
	private static volatile boolean sessionExpiredPromptShown = false;

	private ModAuthManager() {
	}

	// A live, authenticated identity, including a clock-offset correction so signed timestamps use the server's clock.
	public record ModIdentity(String uuid, String username, PrivateKey signingKey, long clockOffsetMs) {
		private static final SecureRandom RANDOM = new SecureRandom();

		// The 4 headers to attach to one specific outgoing request - see SkyMellooApiClient's attachSignature.
		public record SignedHeaders(String uuid, String timestamp, String nonce, String signature) {
		}

		// Signs method+path+timestamp+nonce+sha256(body), same field order as lib/modAuth.js#verifySignedRequest.
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

	// Not reset on every server join - the session proves account ownership, not a specific TCP connection.
	public static synchronized CompletableFuture<ModIdentity> getIdentity(Minecraft client) {
		// !isDone() matters too - while a fetch is in flight, identityExpiresAt is still 0 from the
		// previous session, so a second near-simultaneous caller would otherwise start a redundant fetch.
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
										Component.translatable("skymelloo.chat.session_expired")));
							}
						});
					}
					throw new CompletionException(error);
				});
	}

	// Specifically the access token itself going stale mid-session, not a transient network/backend error.
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
