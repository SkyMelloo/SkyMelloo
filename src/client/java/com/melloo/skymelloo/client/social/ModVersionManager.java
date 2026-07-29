package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.SkyMellooClient;
import com.melloo.skymelloo.client.api.SkyMellooApiClient;
import com.melloo.skymelloo.client.util.ChatUtil;
import com.melloo.skymelloo.client.util.DebugLog;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;

/**
 * Checked once per join, right alongside {@link WhitelistManager} - tells the backend which mod
 * build is connecting and gets told back immediately if it's too old for the current API, instead
 * of individual features just silently failing or behaving oddly with no explanation the moment a
 * backend change stops being compatible with an old client. Not gated behind the whitelist check
 * itself (this fires independently) - an outdated build should find out regardless of whitelist
 * status.
 * <p>
 * Also reports this build's own jar SHA-256 hash alongside the version - the backend cross-checks it
 * against a list of cryptographically-signed official releases (plus a smaller admin-managed
 * dev-build whitelist) and flags anything else as unverified ("mod damit auch signieren... wenn
 * falsche also ganz anders dann wird mod komplett disabled", 2026-07-26). This is honest about what
 * it actually proves: it can't stop someone with the source from ALSO recompiling and re-signing
 * their own fork if they somehow got the private key, but it does mean an unmodified recompile from
 * the public source - even byte-identical - has no valid signature at all, since that's only ever
 * produced out-of-band by the maintainer's own signing step, never by the build itself. {@code null}
 * hash (dev/exploded classpath, not a real packaged jar) is treated as "unknown", not "invalid" - see
 * the server-side comment on why that's not the same as tampering.
 */
public final class ModVersionManager {
	private static volatile boolean checkStarted = false;
	private static volatile boolean compatible = true;
	// Cached for "/sm version"/"/sm info" (2026-07-26) so those commands can answer instantly from
	// whatever the one join-time check already found, instead of firing a whole new network request.
	private static volatile String localVersion = "0.0.0";
	// The user-facing release number (2026-07-28) - completely separate from localVersion above,
	// which bumps on every internal change and was never meant to be shown to end users. Baked into
	// fabric.mod.json's custom "skymelloo:publicVersion" field by build.gradle's processResources
	// (see gradle.properties#public_version for the full reasoning). "unreleased" is the honest
	// answer for a dev/exploded classpath run (no packaged jar, so no custom value was ever expanded).
	private static volatile String publicVersion = "unreleased";
	private static volatile String localJarHash = null;
	private static volatile SkyMellooApiClient.VersionCheckResult lastResult = null;

	private ModVersionManager() {
	}

	public static boolean isCompatible() {
		return compatible;
	}

	public static String getLocalVersion() {
		return localVersion;
	}

	public static String getPublicVersion() {
		return publicVersion;
	}

	public static String getLocalJarHash() {
		return localJarHash;
	}

	/** {@code null} until the one join-time check actually completes (or if it failed outright - a network hiccup). */
	public static SkyMellooApiClient.VersionCheckResult getLastResult() {
		return lastResult;
	}

	public static void checkOnce(Minecraft client) {
		if (checkStarted || client.player == null) {
			return;
		}
		checkStarted = true;
		var containerOpt = FabricLoader.getInstance().getModContainer(SkyMellooClient.MOD_ID);
		String version = containerOpt
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("0.0.0");
		localVersion = version;
		publicVersion = containerOpt
				.map(container -> container.getMetadata().getCustomValue("skymelloo:publicVersion"))
				.filter(value -> value != null && value.getType() == net.fabricmc.loader.api.metadata.CustomValue.CvType.STRING)
				.map(net.fabricmc.loader.api.metadata.CustomValue::getAsString)
				.orElse("unreleased");
		String jarHash = computeOwnJarHash();
		localJarHash = jarHash;
		DebugLog.log(DebugLog.Category.PERMISSIONS, "Checking mod version compatibility (running " + version + ", jarHash=" + jarHash + ")...");
		SkyMellooApiClient.checkVersion(version, jarHash).whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
			if (error != null) {
				// A network hiccup here isn't evidence of being outdated - stay optimistic rather than
				// false-alarming every time the check itself just fails to reach the server.
				DebugLog.log(DebugLog.Category.PERMISSIONS, "Version check failed: " + error.getMessage());
				return;
			}
			lastResult = result;
			compatible = result.compatible();
			DebugLog.log(DebugLog.Category.PERMISSIONS, "Mod version: " + (compatible ? (result.upToDate() ? "up to date" : "behind latest, still compatible") : "outdated/unverified (min " + result.minVersion() + ")") + ", integrityOk=" + result.integrityOk() + ", buildKind=" + result.buildKind() + ", jarHash=" + jarHash);
			// Used to hard-disable every SkyMelloo feature here for an unverified build or too-old
			// version - removed 2026-07-28 ("generell nix mehr blocken über permissions das komplett
			// ausbauen"). Now purely informational: an unverified build (not signed/registered by the
			// maintainer - private builds included, since anyone can compile the now-open-source mod
			// themselves) gets a one-time handshake chat notice, nothing is ever disabled.
			if (!result.integrityOk() && client.player != null) {
				client.player.sendSystemMessage(ChatUtil.prefixed(
						"§eYou're on an unofficial SkyMelloo build (not one hexedmaya built/signed) - everything still works, this is just so you know. Official builds: §fsky.melloo.me/download"));
			} else if (!compatible && client.player != null) {
				client.player.sendSystemMessage(ChatUtil.prefixed("§e" + result.message()));
			} else if (compatible && !result.upToDate() && client.player != null && result.updateAvailableMessage() != null) {
				// Soft nudge for being behind the latest release. "wenn nur alte sagt der im chat youre
				// running an old skymelloo version..." (2026-07-26).
				client.player.sendSystemMessage(ChatUtil.prefixed("§e" + result.updateAvailableMessage()));
			}
		}));
	}

	/**
	 * Lowercase hex SHA-256 of this mod's OWN compiled classes specifically - not the whole packaged
	 * jar/root Fabric Loader resolves. {@code null} if that can't be determined safely (Gradle's
	 * runClient dev environment before anything's compiled; or an origin shape this doesn't
	 * confidently recognize).
	 * <p>
	 * Scoped to just the {@code com/melloo/skymelloo} package on purpose (2026-07-26, "es geht darum
	 * dass die mod verifiziert ist nicht lunar oder so"): an earlier version hashed the ENTIRE
	 * resolved root, which under Lunar Client's own Genesis loading is whatever container/merged
	 * representation Lunar's loader builds around the jar, not the plain jar file itself - that made
	 * the "verified" hash depend on Lunar's own repackaging behavior rather than purely on this mod's
	 * actual code, which is the whole reason it never matched a plain file hash of the built jar.
	 * Hashing only this mod's own class files - opening the packaged jar as its own zip filesystem
	 * first if the resolved root is a single file, so this reads the REAL compiled bytecode
	 * regardless of whatever container Lunar wraps it in - should be reproducible from the same
	 * classes' bytes no matter what repackages them, since repackaging a jar doesn't recompile the
	 * class files inside it.
	 * <p>
	 * Goes through Fabric Loader's own {@code Path}-based {@link net.fabricmc.loader.api.ModContainer#getRootPaths()}
	 * rather than raw {@code CodeSource}/{@code File}/{@code URI} conversion - confirmed as the real
	 * cause of an actual game crash (2026-07-26, {@code IllegalArgumentException: URI is not
	 * hierarchical}): Lunar Client's own mod-loading wraps this mod's jar in a URI scheme
	 * {@code java.io.File}'s constructor can't handle at all. Broadly catches {@code Throwable}, not
	 * just the specific exceptions expected here - this runs on the render thread on every launch and
	 * must never be able to crash the game again; a missing hash is only ever treated as "unknown" by
	 * the backend (see {@code /api/mod/version-check}'s own comment), never as "invalid".
	 */
	private static String computeOwnJarHash() {
		try {
			var containerOpt = FabricLoader.getInstance().getModContainer(SkyMellooClient.MOD_ID);
			if (containerOpt.isEmpty()) {
				return null;
			}
			List<Path> roots = containerOpt.get().getRootPaths();
			if (roots.size() != 1) {
				// Not the plain single-jar shape this is built for - rather than guess how to combine
				// multiple roots, just report unknown.
				return null;
			}
			Path root = roots.get(0);
			if (Files.isRegularFile(root)) {
				// root IS a packaged jar (or Lunar's own equivalent) - open it as its OWN zip
				// filesystem so what gets hashed is genuinely this jar's real class bytes, independent
				// of whatever outer container/path scheme Lunar's loader itself uses.
				try (FileSystem zipFs = FileSystems.newFileSystem(root)) {
					return hashOwnClassesUnder(zipFs.getPath("com", "melloo", "skymelloo"));
				}
			}
			if (Files.isDirectory(root)) {
				return hashOwnClassesUnder(root.resolve("com").resolve("melloo").resolve("skymelloo"));
			}
			return null;
		} catch (Throwable e) {
			DebugLog.log(DebugLog.Category.PERMISSIONS, "Could not hash own jar for integrity check: " + e);
			return null;
		}
	}

	/** Hashes every {@code .class} file under {@code packageRoot} in a stable (sorted, relative-path) order, or {@code null} if there's nothing there. */
	private static String hashOwnClassesUnder(Path packageRoot) throws Exception {
		if (!Files.isDirectory(packageRoot)) {
			return null;
		}
		List<Path> classFiles;
		try (var walk = Files.walk(packageRoot)) {
			classFiles = walk.filter(p -> p.toString().endsWith(".class"))
					.sorted(Comparator.comparing(p -> packageRoot.relativize(p).toString()))
					.toList();
		}
		if (classFiles.isEmpty()) {
			return null;
		}
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		for (Path file : classFiles) {
			digest.update(packageRoot.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
			digest.update(Files.readAllBytes(file));
		}
		StringBuilder hex = new StringBuilder();
		for (byte b : digest.digest()) {
			hex.append(String.format("%02x", b));
		}
		return hex.toString();
	}
}
