package com.spotdl.gui.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class UpdateManager {
    record Release(String version, String assetName, String downloadUrl, String digest, String notes) {}

    private static final String RELEASE_API = "https://api.github.com/repos/chkontog2026/spotdl-android/releases/latest";
    private static final String PREFS = "app_updates";
    private static final String KEY_LAST_CHECK = "last_check_ms";
    private static final String KEY_DOWNLOAD_ID = "download_id";
    private static final String KEY_ASSET_NAME = "asset_name";
    private static final String KEY_DIGEST = "asset_digest";
    private static final String KEY_INSTALL_REQUESTED = "install_requested";
    private static final String KEY_PERMISSION_PROMPTED = "permission_prompted";
    private static final long AUTO_CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean VERIFYING = new AtomicBoolean();

    private UpdateManager() {}

    static void checkForUpdates(Activity activity, boolean manual) {
        SharedPreferences prefs = prefs(activity);
        if (!manual && System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0L) < AUTO_CHECK_INTERVAL_MS) return;
        if (manual) Toast.makeText(activity, "Έλεγχος για νέα έκδοση…", Toast.LENGTH_SHORT).show();
        EXECUTOR.execute(() -> {
            try {
                Release release = readLatestRelease();
                if (compareVersions(release.version(), BuildConfig.VERSION_NAME) <= 0) {
                    prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
                    if (manual) onMain(() -> Toast.makeText(activity, "Έχεις ήδη την τελευταία έκδοση.", Toast.LENGTH_LONG).show());
                    return;
                }
                onMain(() -> startDownload(activity, release));
            } catch (Exception error) {
                if (manual) onMain(() -> Toast.makeText(
                        activity,
                        "Δεν ήταν δυνατός ο έλεγχος ενημερώσεων: " + readable(error),
                        Toast.LENGTH_LONG
                ).show());
            }
        });
    }

    private static Release readLatestRelease() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(RELEASE_API).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        connection.setRequestProperty("User-Agent", "SpotDL-Android/" + BuildConfig.VERSION_NAME);
        int status = connection.getResponseCode();
        if (status != 200) {
            connection.disconnect();
            throw new IllegalStateException("GitHub HTTP " + status);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
            JSONObject release = new JSONObject(body.toString());
            String version = release.optString("tag_name", "").replaceFirst("^[vV]", "");
            JSONObject asset = selectAsset(release.getJSONArray("assets"), List.of(Build.SUPPORTED_ABIS));
            if (version.isBlank() || asset == null) throw new IllegalStateException("Δεν βρέθηκε συμβατό APK στο release.");
            return new Release(
                    version,
                    asset.getString("name"),
                    asset.getString("browser_download_url"),
                    asset.optString("digest", ""),
                    release.optString("body", "")
            );
        } finally {
            connection.disconnect();
        }
    }

    static JSONObject selectAsset(JSONArray assets, List<String> supportedAbis) {
        List<JSONObject> apks = new ArrayList<>();
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset != null && asset.optString("name", "").toLowerCase(Locale.ROOT).endsWith(".apk")) apks.add(asset);
        }
        boolean arm64 = supportedAbis.stream().anyMatch(abi -> abi.equalsIgnoreCase("arm64-v8a"));
        if (arm64) {
            for (JSONObject asset : apks) if (asset.optString("name", "").toLowerCase(Locale.ROOT).contains("arm64")) return asset;
        }
        for (JSONObject asset : apks) {
            String name = asset.optString("name", "").toLowerCase(Locale.ROOT);
            if (name.contains("universal")) return asset;
        }
        return apks.size() == 1 ? apks.get(0) : null;
    }

    private static void startDownload(Activity activity, Release release) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        File directory = updateDirectory(activity);
        if (!directory.exists() && !directory.mkdirs()) {
            Toast.makeText(activity, "Δεν δημιουργήθηκε ο φάκελος ενημερώσεων.", Toast.LENGTH_LONG).show();
            return;
        }
        File destination = new File(directory, release.assetName());
        if (destination.exists() && !destination.delete()) {
            Toast.makeText(activity, "Δεν καθαρίστηκε η προηγούμενη λήψη.", Toast.LENGTH_LONG).show();
            return;
        }
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(release.downloadUrl()))
                .setTitle("SpotDL Android " + release.version())
                .setDescription("Λήψη ενημέρωσης εφαρμογής")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setDestinationUri(Uri.fromFile(destination));
        long id;
        try {
            id = activity.getSystemService(DownloadManager.class).enqueue(request);
        } catch (Exception error) {
            Toast.makeText(
                    activity,
                    "Δεν ξεκίνησε η λήψη της ενημέρωσης: " + readable(error),
                    Toast.LENGTH_LONG
            ).show();
            return;
        }
        prefs(activity).edit()
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .putLong(KEY_DOWNLOAD_ID, id)
                .putString(KEY_ASSET_NAME, release.assetName())
                .putString(KEY_DIGEST, release.digest())
                .putBoolean(KEY_INSTALL_REQUESTED, false)
                .putBoolean(KEY_PERMISSION_PROMPTED, false)
                .apply();
        Toast.makeText(
                activity,
                "Βρέθηκε η έκδοση " + release.version() + " — η ενημέρωση κατεβαίνει αυτόματα…",
                Toast.LENGTH_LONG
        ).show();
    }

    static void onDownloadComplete(Activity activity, long id) {
        if (id == prefs(activity).getLong(KEY_DOWNLOAD_ID, -1L)) resumePendingInstall(activity);
    }

    static void resumePendingInstall(Activity activity) {
        SharedPreferences prefs = prefs(activity);
        long id = prefs.getLong(KEY_DOWNLOAD_ID, -1L);
        if (id < 0 || prefs.getBoolean(KEY_INSTALL_REQUESTED, false) || !VERIFYING.compareAndSet(false, true)) return;
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
        try (android.database.Cursor cursor = activity.getSystemService(DownloadManager.class).query(query)) {
            if (cursor == null || !cursor.moveToFirst()) {
                VERIFYING.set(false);
                return;
            }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_FAILED) {
                clearPending(activity, false);
                VERIFYING.set(false);
                Toast.makeText(activity, "Η λήψη της ενημέρωσης απέτυχε.", Toast.LENGTH_LONG).show();
                return;
            }
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                VERIFYING.set(false);
                return;
            }
        }

        File apk = new File(updateDirectory(activity), prefs.getString(KEY_ASSET_NAME, "update.apk"));
        String expectedDigest = prefs.getString(KEY_DIGEST, "");
        EXECUTOR.execute(() -> {
            try {
                verifyApk(activity, apk, expectedDigest);
                onMain(() -> requestInstall(activity, apk));
            } catch (Exception error) {
                clearPending(activity, false);
                onMain(() -> Toast.makeText(
                        activity,
                        "Η ενημέρωση απορρίφθηκε: " + readable(error),
                        Toast.LENGTH_LONG
                ).show());
            } finally {
                VERIFYING.set(false);
            }
        });
    }

    private static void verifyApk(Context context, File apk, String expectedDigest) throws Exception {
        if (!apk.isFile() || apk.length() == 0) throw new IllegalStateException("Το APK δεν βρέθηκε.");
        if (expectedDigest != null && expectedDigest.toLowerCase(Locale.ROOT).startsWith("sha256:")) {
            String expected = expectedDigest.substring("sha256:".length()).trim();
            String actual = sha256(apk);
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII))) {
                throw new SecurityException("Αποτυχία ελέγχου SHA-256.");
            }
        }

        PackageManager manager = context.getPackageManager();
        int signatureFlags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
        PackageInfo candidate = manager.getPackageArchiveInfo(apk.getAbsolutePath(), signatureFlags);
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), signatureFlags);
        if (candidate == null || !context.getPackageName().equals(candidate.packageName)) {
            throw new SecurityException("Λάθος πακέτο εφαρμογής.");
        }
        long candidateVersion = Build.VERSION.SDK_INT >= 28 ? candidate.getLongVersionCode() : candidate.versionCode;
        long installedVersion = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
        if (candidateVersion <= installedVersion) {
            throw new SecurityException("Η έκδοση δεν είναι νεότερη.");
        }
        if (!signerDigests(candidate).equals(signerDigests(installed))) {
            throw new SecurityException("Η ψηφιακή υπογραφή δεν ταιριάζει.");
        }
    }

    private static Set<String> signerDigests(PackageInfo info) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28) {
            if (info.signingInfo == null) throw new SecurityException("Λείπει η ψηφιακή υπογραφή.");
            signatures = info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        } else {
            signatures = info.signatures;
        }
        if (signatures == null || signatures.length == 0) throw new SecurityException("Λείπει η ψηφιακή υπογραφή.");
        Set<String> result = new HashSet<>();
        for (Signature signature : signatures) result.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
        return result;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) value.append(String.format(Locale.ROOT, "%02x", current & 0xff));
        return value.toString();
    }

    private static void requestInstall(Activity activity, File apk) {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            SharedPreferences prefs = prefs(activity);
            if (!prefs.getBoolean(KEY_PERMISSION_PROMPTED, false)) {
                prefs.edit().putBoolean(KEY_PERMISSION_PROMPTED, true).apply();
                new AlertDialog.Builder(activity)
                        .setTitle("Άδεια εγκατάστασης")
                        .setMessage("Επίτρεψε στο SpotDL Android να εγκαθιστά ενημερώσεις και επέστρεψε στην εφαρμογή.")
                        .setNegativeButton("ΑΚΥΡΩΣΗ", null)
                        .setPositiveButton("ΡΥΘΜΙΣΕΙΣ", (dialog, which) -> activity.startActivity(
                                new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()))
                        ))
                        .show();
            }
            return;
        }
        try {
            PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(activity.getPackageName());
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
            int sessionId = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                try (InputStream input = new BufferedInputStream(new FileInputStream(apk));
                     OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                    session.fsync(output);
                }
                Intent result = new Intent(activity, UpdateInstallReceiver.class).setAction(UpdateInstallReceiver.ACTION_INSTALL_STATUS);
                PendingIntent callback = PendingIntent.getBroadcast(
                        activity,
                        sessionId,
                        result,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
                );
                prefs(activity).edit().putBoolean(KEY_INSTALL_REQUESTED, true).apply();
                session.commit(callback.getIntentSender());
            }
        } catch (Exception error) {
            prefs(activity).edit().putBoolean(KEY_INSTALL_REQUESTED, false).apply();
            Toast.makeText(activity, "Δεν ξεκίνησε η εγκατάσταση: " + readable(error), Toast.LENGTH_LONG).show();
        }
    }

    static void installationFinished(Context context, boolean success) {
        clearPending(context, success);
    }

    static void installationCanRetry(Context context) {
        prefs(context).edit().putBoolean(KEY_INSTALL_REQUESTED, false).apply();
    }

    private static void clearPending(Context context, boolean deleteApk) {
        SharedPreferences prefs = prefs(context);
        if (deleteApk) {
            File apk = new File(updateDirectory(context), prefs.getString(KEY_ASSET_NAME, ""));
            if (apk.isFile()) apk.delete();
        }
        prefs.edit().clear().apply();
    }

    private static File updateDirectory(Context context) {
        return new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates");
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int compareVersions(String left, String right) {
        String[] a = left.replaceFirst("^[vV]", "").split("[-+.]", -1);
        String[] b = right.replaceFirst("^[vV]", "").split("[-+.]", -1);
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int av = i < a.length ? leadingNumber(a[i]) : 0;
            int bv = i < b.length ? leadingNumber(b[i]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static int leadingNumber(String value) {
        String digits = value.replaceFirst("^(\\d+).*$", "$1");
        if (!digits.matches("\\d+")) return 0;
        try { return Integer.parseInt(digits); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static String readable(Exception error) {
        String message = error.getLocalizedMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static void onMain(Runnable action) {
        new Handler(Looper.getMainLooper()).post(action);
    }
}
