package com.pos.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pos.config.ConfigManager;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * Service to check for application updates and download them.
 */
public class UpdateService {
    private static final Logger logger = LoggerFactory.getLogger(UpdateService.class);
    private static UpdateService instance;

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final ConfigManager config;

    private static final String GITHUB_API_URL = "https://api.github.com/repos";

    private UpdateService() {
        this.config = ConfigManager.getInstance();
        this.gson = new GsonBuilder().create();
        this.httpClient = createUnsafeClient();
        
        // Clean up any leftover update files from previous sessions
        cleanupOldUpdates();
    }

    private OkHttpClient createUnsafeClient() {
        try {
            // Create a trust manager that does not validate certificate chains
            final TrustManager[] trustAllCerts = new TrustManager[] {
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType)
                                throws CertificateException {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType)
                                throws CertificateException {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[] {};
                        }
                    }
            };

            // Install the all-trusting trust manager
            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new SecureRandom());

            // Create an ssl socket factory with our all-trusting manager
            final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            return new OkHttpClient.Builder()
                    .sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0])
                    .hostnameVerifier((hostname, session) -> true)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build();
        } catch (Exception e) {
            logger.error("Failed to create unsafe OkHttpClient", e);
            // Fallback to default
            return new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build();
        }
    }

    public static synchronized UpdateService getInstance() {
        if (instance == null) {
            instance = new UpdateService();
        }
        return instance;
    }

    /**
     * Check for updates.
     * 
     * @return UpdateInfo if update available, null otherwise.
     */
    public UpdateInfo checkForUpdates() throws IOException {
        return checkForUpdates(false);
    }

    /**
     * Check for updates with option to force check even if auto-update is disabled.
     * 
     * @param forceCheck If true, bypasses the app.autoupdate.enabled check.
     * @return UpdateInfo if update available, null otherwise.
     */
    public UpdateInfo checkForUpdates(boolean forceCheck) throws IOException {
        logger.info("Checking for updates (forceCheck={})...", forceCheck);
        // Check if auto-update is enabled in configuration
        if (!forceCheck) {
            boolean autoUpdateEnabled = Boolean.parseBoolean(config.getProperty("app.autoupdate.enabled", "true"));
            if (!autoUpdateEnabled) {
                logger.info("Auto-update is disabled in configuration (app.autoupdate.enabled=false). Skipping check.");
                return null;
            }
        }

        String repo = config.getProperty("app.github.repo");
        if (repo == null || repo.isEmpty() || repo.contains("your-username")) {
            logger.debug("GitHub repository not configured, skipping update check.");
            return null;
        }

        // Fetch all releases to find the highest version (instead of just /latest)
        String url = GITHUB_API_URL + "/" + repo + "/releases";
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github.v3+json");

        logger.debug("Performing public update check (no authentication)");

        Request request = requestBuilder.build();

        try (Response response = httpClient.newCall(request).execute()) {
            return parseUpdateResponse(response);
        } catch (IOException e) {
            logger.error("IO Exception during update check", e);
            throw e;
        }
    }

    private UpdateInfo parseUpdateResponse(Response response) throws IOException {
        if (!response.isSuccessful()) {
            String errorMsg;
            if (response.code() == 403 || response.code() == 429) {
                errorMsg = "Update check rate limited (HTTP " + response.code() + "). Please try again later.";
            } else if (response.code() == 404) {
                errorMsg = "Release repository not found (HTTP 404).";
            } else {
                errorMsg = "Failed to update check: HTTP " + response.code() + " " + response.message();
            }
            throw new IOException(errorMsg);
        }

        ResponseBody body = response.body();
        if (body == null)
            return null;

        String json = body.string();
        JsonElement element = gson.fromJson(json, JsonElement.class);

        JsonArray releases;
        if (element.isJsonArray()) {
            releases = element.getAsJsonArray();
        } else if (element.isJsonObject()) {
            // Handle case where API might return a single release object (e.g. from
            // /latest)
            releases = new JsonArray();
            releases.add(element.getAsJsonObject());
        } else {
            return null;
        }

        String currentVersion = config.getProperty("app.version", "1.0.0");
        String cleanCurrentVersion = currentVersion.startsWith("v") ? currentVersion.substring(1) : currentVersion;

        UpdateInfo bestUpdate = null;
        String bestVersion = cleanCurrentVersion;

        logger.info("Comparing current version '{}' with GitHub releases...", currentVersion);

        for (JsonElement releaseElement : releases) {
            JsonObject release = releaseElement.getAsJsonObject();

            // Skip drafts if not explicitly asked? GitHub API usually doesn't include them
            // in public lists
            if (release.has("draft") && release.get("draft").getAsBoolean())
                continue;

            String tagName = release.get("tag_name").getAsString();
            String cleanTagName = tagName.startsWith("v") ? tagName.substring(1) : tagName;

            logger.debug("Found release: {} (clean: {})", tagName, cleanTagName);

            // Skip non-semver releases (e.g., "release-3", "beta-1")
            if (!isSemanticVersion(cleanTagName)) {
                logger.debug("Skipping non-semver release: {}", tagName);
                continue;
            }

            if (isNewer(cleanTagName, bestVersion)) {
                AssetInfo assetInfo = getAssetInfo(release, System.getProperty("os.name"));

                UpdateInfo info = new UpdateInfo();
                info.version = tagName;
                info.releaseNotes = release.has("body") && !release.get("body").isJsonNull()
                        ? release.get("body").getAsString()
                        : "";
                if (release.has("html_url")) {
                    info.htmlUrl = release.get("html_url").getAsString();
                }

                if (assetInfo != null) {
                    // Use browser_download_url for public repos
                    info.downloadUrl = assetInfo.browserUrl;
                    info.isPrivate = false;
                    info.fileName = assetInfo.name;
                }

                bestUpdate = info;
                bestVersion = cleanTagName;
                logger.info("Found a newer version: {}", tagName);
            }
        }

        if (bestUpdate == null) {
            logger.info("No newer version found. System is up to date.");
        }

        return bestUpdate;
    }

    /**
     * Download the update file.
     * 
     * @param updateInfo       The update information.
     * @param progressCallback Callback for progress (0.0 to 1.0).
     * @return Path to the downloaded file.
     */
    public Path downloadUpdate(UpdateInfo updateInfo, ProgressCallback progressCallback) throws IOException {
        if (updateInfo.downloadUrl == null) {
            throw new IOException("No suitable download URL found for this OS.");
        }

        Request.Builder requestBuilder = new Request.Builder().url(updateInfo.downloadUrl);

        // No authorization header needed for public release assets check

        Request request = requestBuilder.build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to download update: " + response.code());
            }

            ResponseBody body = response.body();
            if (body == null)
                throw new IOException("Empty response body");

            long contentLength = body.contentLength();
            Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));
            
            // Use a unique filename to avoid "file in use" errors if a previous download attempt failed
            // or if multiple downloads are happening (unlikely but safe)
            String uniqueFileName = java.util.UUID.randomUUID().toString().substring(0, 8) + "_" + updateInfo.fileName;
            Path targetPath = tempDir.resolve(uniqueFileName);

            try (InputStream in = new BufferedInputStream(body.byteStream());
                    FileOutputStream out = new FileOutputStream(targetPath.toFile())) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalRead = 0;

                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                    if (progressCallback != null && contentLength > 0) {
                        progressCallback.onProgress((double) totalRead / contentLength);
                    }
                }
            }

            return targetPath;
        }
    }

    /**
     * Launch the downloaded installer/file.
     * On Windows, we use ProcessBuilder to directly execute the .exe installer
     * because Desktop.open() can fail with "Unsupported URI content" for executables.
     */
    public void installUpdate(Path filePath) throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        String absolutePath = filePath.toAbsolutePath().toString();

        if (os.contains("win")) {
            // On Windows, directly execute the .exe installer via ProcessBuilder
            logger.info("Launching Windows installer: {}", absolutePath);
            new ProcessBuilder(absolutePath)
                    .inheritIO()
                    .start();
        } else if (os.contains("mac")) {
            new ProcessBuilder("open", absolutePath).start();
        } else if (os.contains("nix") || os.contains("nux")) {
            new ProcessBuilder("xdg-open", absolutePath).start();
        } else if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(filePath.toFile());
        } else {
            throw new IOException("No method available to launch installer on this OS: " + os);
        }
    }

    /**
     * Clean up temporary update files from previous sessions.
     * Scans the system temp directory for files matching the update pattern.
     */
    private void cleanupOldUpdates() {
        new Thread(() -> {
            try {
                Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));
                logger.debug("Scanning for old update files in {}", tempDir);

                try (Stream<Path> files = Files.list(tempDir)) {
                    files.filter(path -> {
                        String fileName = path.getFileName().toString();
                        // Match unique filenames: [uuid-prefix]_pos-system-[version]-[platform].[ext]
                        // or previous fixed filenames if any still exist
                        return fileName.contains("pos-system-v") && 
                               (fileName.endsWith(".exe") || fileName.endsWith(".dmg") || fileName.endsWith(".deb") || fileName.endsWith(".jar"));
                    }).forEach(path -> {
                        try {
                            logger.info("Cleaning up old update file: {}", path);
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            logger.debug("Could not delete old update file {} (it might still be in use): {}", path, e.getMessage());
                        }
                    });
                }
            } catch (Exception e) {
                logger.warn("Failed to complete update cleanup: {}", e.getMessage());
            }
        }, "UpdateCleanupThread").start();
    }

    /**
     * Check if a version string follows semantic versioning format (e.g., "1.0.0",
     * "1.2.3").
     * This helps filter out non-standard release tags like "release-3" or "beta-1".
     */
    private boolean isSemanticVersion(String version) {
        // Match patterns like: 1.0.0, 1.2.3, 1.0, 1.0.0-beta, etc.
        // Must start with a number and contain at least one dot followed by a number
        return version != null && Pattern.matches("^\\d+\\.\\d+(\\.\\d+)?(-[a-zA-Z0-9.]+)?$", version);
    }

    private boolean isNewer(String remote, String current) {
        String[] remoteParts = remote.split("\\.");
        String[] currentParts = current.split("\\.");
        int length = Math.max(remoteParts.length, currentParts.length);

        for (int i = 0; i < length; i++) {
            int r = i < remoteParts.length ? parseVersionPart(remoteParts[i]) : 0;
            int c = i < currentParts.length ? parseVersionPart(currentParts[i]) : 0;

            if (r > c)
                return true;
            if (r < c)
                return false;
        }
        return false;
    }

    private int parseVersionPart(String part) {
        Matcher m = Pattern.compile("\\d+").matcher(part);
        if (m.find()) {
            return Integer.parseInt(m.group());
        }
        return 0;
    }

    private static class AssetInfo {
        String name;
        String browserUrl;
    }

    private AssetInfo getAssetInfo(JsonObject release, String osName) {
        osName = osName.toLowerCase();
        JsonArray assets = release.getAsJsonArray("assets");

        String extension = "";
        if (osName.contains("win"))
            extension = ".exe";
        else if (osName.contains("mac"))
            extension = ".dmg"; // or .pkg
        else if (osName.contains("nix") || osName.contains("nux") || osName.contains("linux"))
            extension = ".deb"; // or .rpm, .AppImage

        // If we can't determine extension, return null or try to find jar
        if (extension.isEmpty())
            return null;

        for (JsonElement element : assets) {
            JsonObject asset = element.getAsJsonObject();
            String name = asset.get("name").getAsString().toLowerCase();
            if (name.endsWith(extension)) {
                AssetInfo info = new AssetInfo();
                info.name = asset.get("name").getAsString();
                info.browserUrl = asset.get("browser_download_url").getAsString();
                return info;
            }
        }

        // Fallback: look for a fat jar if native installer not found
        for (JsonElement element : assets) {
            JsonObject asset = element.getAsJsonObject();
            String name = asset.get("name").getAsString().toLowerCase();
            if (name.endsWith(".jar")) {
                AssetInfo info = new AssetInfo();
                info.name = asset.get("name").getAsString();
                info.browserUrl = asset.get("browser_download_url").getAsString();
                return info;
            }
        }

        return null;
    }

    public static class UpdateInfo {
        public String version;
        public String releaseNotes;
        public String downloadUrl;
        public String fileName;
        public boolean isPrivate;
        public String htmlUrl;
    }

    public interface ProgressCallback {
        void onProgress(double progress);
    }
}
