import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

/**
 * Signs and registers a build's release entry, run by Gradle's "signAndRegisterBuild" task right
 * after every build:
 *   1. Hashes ONLY this build's own compiled classes (com/melloo/skymelloo/*.class), same
 *      scoped-hash approach as ModVersionManager's own runtime check.
 *   2. Signs {version}:{hash} with the Ed25519 private key that lives ONLY on this machine
 *      (~/.skymelloo-signing/private_key.pem), never in either repo. The server independently
 *      verifies the signature against the matching public key before trusting anything.
 *   3. POSTs {version, hash, signature} to sky.melloo.me, authenticated with a separate shared
 *      token (not the private key).
 * Never fails the actual Gradle build - every real error here is caught and logged, not thrown.
 */
public class SignAndRegister {
    public static void main(String[] args) {
        try {
            if (args.length < 2) {
                System.err.println("Usage: java SignAndRegister.java <version> <path-to-jar> [changelog-file-path]");
                return;
            }
            String version = args[0];
            Path jarPath = Paths.get(args[1]);
            // Required by build.gradle's requireChangelog task before this even runs - defaulted to
            // "" defensively rather than crashing. Read from a file path, not a raw CLI arg, since
            // gradlew.bat's cmd.exe re-invocation mangles special characters in multi-line values.
            String changelog = "";
            if (args.length >= 3) {
                try {
                    changelog = Files.readString(Paths.get(args[2]));
                } catch (Exception e) {
                    System.err.println("[sign-and-register] Could not read changelog file at " + args[2] + " (non-fatal): " + e.getMessage());
                }
            }

            String hash = hashOwnClasses(jarPath);
            if (hash == null) {
                System.err.println("[sign-and-register] Could not compute a class-scoped hash - skipping (non-fatal).");
                return;
            }

            Path keyFile = Paths.get(System.getProperty("user.home"), ".skymelloo-signing", "private_key.pem");
            if (!Files.exists(keyFile)) {
                System.err.println("[sign-and-register] No private key file found - skipping (non-fatal).");
                return;
            }
            PrivateKey privateKey = loadEd25519PrivateKey(keyFile);

            String message = version + ":" + hash;
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            String signature = Base64.getEncoder().encodeToString(signer.sign());

            Path tokenFile = Paths.get(System.getProperty("user.home"), ".skymelloo-signing", "build_report_token.txt");
            if (!Files.exists(tokenFile)) {
                System.err.println("[sign-and-register] No build-report token file found - skipping (non-fatal).");
                return;
            }
            String token = Files.readString(tokenFile).trim();

            String body = "{\"version\":\"" + escapeJson(version) + "\",\"hash\":\"" + escapeJson(hash) + "\",\"signature\":\"" + escapeJson(signature) + "\",\"changelog\":\"" + escapeJson(changelog) + "\"}";
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://sky.melloo.me/api/mod/releases"))
                    .header("Content-Type", "application/json")
                    .header("X-Build-Report-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                System.out.println("[sign-and-register] Signed and registered " + version + " (" + hash + ") as a trusted release.");
            } else {
                System.err.println("[sign-and-register] Server rejected the release (" + response.statusCode() + "): " + response.body());
            }
        } catch (Exception e) {
            System.err.println("[sign-and-register] Failed (non-fatal): " + e);
        }
    }

    /** Same scoped-hash approach as ModVersionManager#computeOwnJarHash on the mod side - hashes ONLY com/melloo/skymelloo/*.class, opened via the jar's own zip filesystem, in a stable sorted order. */
    private static String hashOwnClasses(Path jarPath) throws Exception {
        try (FileSystem zipFs = FileSystems.newFileSystem(jarPath)) {
            Path packageRoot = zipFs.getPath("com", "melloo", "skymelloo");
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

    private static PrivateKey loadEd25519PrivateKey(Path pemFile) throws Exception {
        String pem = Files.readString(pemFile);
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
        return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /** Escapes backslash/quote/newline/control characters for embedding in a JSON string body. */
    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
