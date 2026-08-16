package com.spotdl.gui.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.IBinder;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLException;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kotlin.Unit;

public final class DownloadService extends Service {
    private record TrackDownload(
            int index,
            SpotifyEmbedParser.Track track,
            String source,
            String outputTemplate,
            String label
    ) {}

    private record TrackFailure(TrackDownload download, String reason) {}

    static final String ACTION_PROGRESS = "com.spotdl.gui.android.PROGRESS";
    static final String ACTION_CANCEL = "com.spotdl.gui.android.CANCEL";
    static final String EXTRA_URL = "url";
    static final String EXTRA_QUALITY = "quality";
    static final String EXTRA_PROGRESS = "progress";
    static final String EXTRA_STATUS = "status";
    static final String EXTRA_DONE = "done";
    static final String EXTRA_FAILED = "failed";
    private static final String CHANNEL = "spotdl_downloads";
    private static final int NOTIFICATION_ID = 41;
    private static final String PROCESS_ID = "spotdl-android-download";
    private static final String ENGINE_PREFS = "download_engine";
    private static final String LAST_ENGINE_UPDATE = "last_nightly_update_ms";
    private static final long ENGINE_UPDATE_INTERVAL_MS = 12L * 60L * 60L * 1000L;
    private static final String FALLBACK_YOUTUBE_CLIENT = "android_vr";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean cancelled;

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Λήψεις μουσικής", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Πρόοδος λήψεων SpotDL Android");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelled = true;
            try { YoutubeDL.getInstance().destroyProcessById(PROCESS_ID); } catch (Exception ignored) {}
            sendProgress(0, "Η λήψη ακυρώθηκε.", true, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        String url = intent == null ? "" : intent.getStringExtra(EXTRA_URL);
        String quality = intent == null ? "0" : intent.getStringExtra(EXTRA_QUALITY);
        startForeground(NOTIFICATION_ID, notification(0, "Προετοιμασία λήψης…"));
        executor.execute(() -> download(url == null ? "" : url.trim(), quality == null ? "0" : quality));
        return START_NOT_STICKY;
    }

    private void download(String url, String quality) {
        try {
            waitForEngine();
            updateDownloadEngineIfNeeded();
            List<SpotifyEmbedParser.Track> tracks = new ArrayList<>();
            String folderName = "SpotDL Downloads";
            if (SpotifyEmbedParser.isSpotify(url)) {
                sendProgress(1, "Ανάγνωση στοιχείων Spotify…", false, false);
                SpotifyEmbedParser.Collection collection = SpotifyEmbedParser.read(url);
                tracks.addAll(collection.tracks());
                folderName = collection.title();
            } else {
                tracks.add(new SpotifyEmbedParser.Track("%(title)s", ""));
            }

            Uri customTree = DownloadFolderStore.selectedTree(this);
            boolean customFolder = customTree != null;
            File output = DownloadFolderStore.outputDirectory(this, folderName, customFolder);
            if (!output.exists() && !output.mkdirs()) throw new IllegalStateException("Δεν δημιουργήθηκε ο φάκελος λήψης.");

            boolean spotify = SpotifyEmbedParser.isSpotify(url);
            List<TrackFailure> failures = new ArrayList<>();
            for (int index = 0; index < tracks.size(); index++) {
                if (cancelled) return;
                SpotifyEmbedParser.Track track = tracks.get(index);
                String label = track.artist().isBlank() ? track.title() : track.artist() + " — " + track.title();
                String source = spotify
                        ? "ytsearch1:" + searchTerms(track) + " official audio"
                        : url;
                String fileName = spotify
                        ? SpotifyEmbedParser.trackFileName(index, track)
                        : "%(title)s.%(ext)s";
                TrackDownload download = new TrackDownload(
                        index,
                        track,
                        source,
                        new File(output, fileName).getAbsolutePath(),
                        label
                );
                int progressStart = (index * 90) / tracks.size();
                int progressEnd = ((index + 1) * 90) / tracks.size();

                sendProgress(progressStart, "Λήψη " + (index + 1) + "/" + tracks.size() + ": " + label, false, false);
                try {
                    executeWithFallback(download, quality, tracks.size(), progressStart, progressEnd);
                } catch (Exception error) {
                    if (cancelled) return;
                    failures.add(new TrackFailure(download, readable(error)));
                    sendProgress(
                            progressEnd,
                            "Προσωρινή αποτυχία στο " + (index + 1) + " — συνέχεια στα υπόλοιπα…",
                            false,
                            false
                    );
                }
            }

            if (!failures.isEmpty() && !cancelled) {
                List<TrackFailure> remaining = new ArrayList<>();
                int retryTotal = failures.size();
                for (int retryIndex = 0; retryIndex < retryTotal; retryIndex++) {
                    if (cancelled) return;
                    TrackDownload retry = failures.get(retryIndex).download();
                    int progressStart = 90 + (retryIndex * 9) / retryTotal;
                    int progressEnd = 90 + ((retryIndex + 1) * 9) / retryTotal;
                    sendProgress(
                            progressStart,
                            "Επανάληψη " + (retryIndex + 1) + "/" + retryTotal + ": " + retry.label(),
                            false,
                            false
                    );
                    try {
                        executeWithFallback(retry, quality, tracks.size(), progressStart, progressEnd);
                    } catch (Exception error) {
                        if (cancelled) return;
                        remaining.add(new TrackFailure(retry, readable(error)));
                    }
                }
                failures = remaining;
            }

            String destination;
            if (customFolder && containsMp3(output)) {
                sendProgress(99, "Αποθήκευση MP3 στον επιλεγμένο φάκελο…", false, false);
                destination = DownloadFolderStore.deliverToSelection(this, customTree, output, folderName);
            } else if (customFolder) {
                DownloadFolderStore.discardEmptyStaging(this, output);
                destination = DownloadFolderStore.selectionLabel(this) + "/" + SpotifyEmbedParser.safeName(folderName);
            } else {
                scan(output);
                destination = "Downloads/SpotDL Android/" + output.getName();
            }
            if (failures.isEmpty()) {
                sendProgress(100, "Ολοκληρώθηκε — Αρχεία: " + destination, true, false);
            } else {
                sendProgress(100, failureSummary(failures), true, true);
            }
        } catch (Exception error) {
            String message = error.getLocalizedMessage();
            if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
            sendProgress(0, "Αποτυχία: " + message, true, true);
        } finally {
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
        }
    }

    private void executeWithFallback(
            TrackDownload download,
            String quality,
            int totalTracks,
            int progressStart,
            int progressEnd
    ) throws Exception {
        String source = download.source();
        if (!isYoutubeSource(source)) {
            executeRequest(
                    buildRequest(source, quality, download.outputTemplate(), null, false),
                    download.index(),
                    totalTracks,
                    download.label(),
                    progressStart,
                    progressEnd
            );
            return;
        }

        try {
            executeRequest(buildRequest(
                    source,
                    quality,
                    download.outputTemplate(),
                    null,
                    false
            ), download.index(), totalTracks, download.label(), progressStart, progressEnd);
        } catch (YoutubeDLException firstError) {
            if (cancelled || !isRetryableYoutubeError(firstError)) throw firstError;
            sendProgress(
                    Math.max(1, progressStart),
                    "Η σύνδεση απορρίφθηκε — νέα προσπάθεια μέσω IPv4…",
                    false,
                    false
            );
            try {
                executeRequest(buildRequest(
                        source,
                        quality,
                        download.outputTemplate(),
                        null,
                        true
                ), download.index(), totalTracks, download.label(), progressStart, progressEnd);
            } catch (YoutubeDLException secondError) {
                if (cancelled || !isRetryableYoutubeError(secondError)) throw secondError;
                sendProgress(
                        Math.max(1, progressStart),
                        "Δοκιμή τελευταίας εναλλακτικής σύνδεσης…",
                        false,
                        false
                );
                executeRequest(buildRequest(
                        source,
                        quality,
                        download.outputTemplate(),
                        FALLBACK_YOUTUBE_CLIENT,
                        true
                ), download.index(), totalTracks, download.label(), progressStart, progressEnd);
            }
        }
    }

    private YoutubeDLRequest buildRequest(
            String source,
            String quality,
            String outputTemplate,
            String youtubeClient,
            boolean forceIpv4
    ) {
        YoutubeDLRequest request = new YoutubeDLRequest(source);
        request.addOption("--no-playlist");
        request.addOption("--no-mtime");
        request.addOption("--no-continue");
        request.addOption("--retries", "3");
        request.addOption("--fragment-retries", "3");
        request.addOption("--socket-timeout", "30");
        request.addOption("--format", "bestaudio/best");
        request.addOption("--extract-audio");
        request.addOption("--audio-format", "mp3");
        request.addOption("--audio-quality", quality);
        request.addOption("--embed-metadata");
        request.addOption("--embed-thumbnail");
        request.addOption("--convert-thumbnails", "jpg");
        request.addOption("--remote-components", "ejs:github");
        if (forceIpv4) request.addOption("--force-ipv4");
        if (youtubeClient != null) {
            request.addOption("--extractor-args", "youtube:player_client=" + youtubeClient);
        }
        request.addOption("--output", outputTemplate);
        return request;
    }

    private void executeRequest(
            YoutubeDLRequest request,
            int current,
            int totalTracks,
            String label,
            int progressStart,
            int progressEnd
    ) throws Exception {
        YoutubeDL.getInstance().execute(request, PROCESS_ID, (progress, eta, line) -> {
            int total = Math.min(99, progressStart + Math.round((progress / 100f) * (progressEnd - progressStart)));
            sendProgress(total, "Λήψη " + (current + 1) + "/" + totalTracks + ": " + label, false, false);
            return Unit.INSTANCE;
        });
    }

    private static String searchTerms(SpotifyEmbedParser.Track track) {
        return track.artist().isBlank() ? track.title() : track.artist() + " - " + track.title();
    }

    private static boolean isYoutubeSource(String source) {
        String normalized = source.toLowerCase(Locale.ROOT);
        return normalized.startsWith("ytsearch")
                || normalized.contains("youtube.com/")
                || normalized.contains("youtu.be/")
                || normalized.contains("music.youtube.com/");
    }

    private static boolean isRetryableYoutubeError(Exception error) {
        String message = error.getMessage();
        if (message == null) return true;
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("403")
                || normalized.contains("forbidden")
                || normalized.contains("video unavailable")
                || normalized.contains("requested format is not available")
                || normalized.contains("no video formats")
                || normalized.contains("no formats");
    }

    private static String failureSummary(List<TrackFailure> failures) {
        StringBuilder summary = new StringBuilder("Ολοκληρώθηκε με ")
                .append(failures.size())
                .append(failures.size() == 1 ? " αποτυχία: " : " αποτυχίες: ");
        int shown = Math.min(5, failures.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) summary.append(" · ");
            TrackFailure failure = failures.get(i);
            summary.append(String.format(Locale.ROOT, "%02d", failure.download().index() + 1))
                    .append(" ")
                    .append(failure.download().track().title());
        }
        if (failures.size() > shown) summary.append(" · +").append(failures.size() - shown).append(" ακόμη");
        return summary.toString();
    }

    private static String readable(Exception error) {
        String message = error.getLocalizedMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private void waitForEngine() throws Exception {
        for (int i = 0; i < 120 && !SpotDLApplication.isEngineReady(); i++) {
            if (SpotDLApplication.getEngineError() != null) throw new IllegalStateException(SpotDLApplication.getEngineError());
            Thread.sleep(250);
        }
        if (!SpotDLApplication.isEngineReady()) throw new IllegalStateException("Η μηχανή λήψης δεν αρχικοποιήθηκε.");
    }

    private void updateDownloadEngineIfNeeded() throws Exception {
        long lastUpdate = getSharedPreferences(ENGINE_PREFS, MODE_PRIVATE).getLong(LAST_ENGINE_UPDATE, 0L);
        if (System.currentTimeMillis() - lastUpdate < ENGINE_UPDATE_INTERVAL_MS) return;
        sendProgress(1, "Έλεγχος για νέα έκδοση yt-dlp…", false, false);
        try {
            YoutubeDL.getInstance().updateYoutubeDL(this, YoutubeDL.UpdateChannel._NIGHTLY);
            getSharedPreferences(ENGINE_PREFS, MODE_PRIVATE)
                    .edit()
                    .putLong(LAST_ENGINE_UPDATE, System.currentTimeMillis())
                    .apply();
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Δεν ήταν δυνατή η ενημέρωση του yt-dlp. Έλεγξε τη σύνδεση internet και δοκίμασε ξανά.",
                    error
            );
        }
    }

    private void scan(File directory) {
        File[] files = directory.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".mp3"));
        if (files == null) return;
        String[] paths = new String[files.length];
        for (int i = 0; i < files.length; i++) paths[i] = files[i].getAbsolutePath();
        MediaScannerConnection.scanFile(this, paths, null, null);
    }

    private static boolean containsMp3(File directory) {
        File[] files = directory.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".mp3"));
        return files != null && files.length > 0;
    }

    private void sendProgress(int progress, String status, boolean done, boolean failed) {
        Intent update = new Intent(ACTION_PROGRESS).setPackage(getPackageName());
        update.putExtra(EXTRA_PROGRESS, progress);
        update.putExtra(EXTRA_STATUS, status);
        update.putExtra(EXTRA_DONE, done);
        update.putExtra(EXTRA_FAILED, failed);
        sendBroadcast(update);
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(progress, status));
    }

    private Notification notification(int progress, String status) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent cancel = new Intent(this, DownloadService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelAction = PendingIntent.getService(this, 1, cancel, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("SpotDL Android")
                .setContentText(status)
                .setStyle(new Notification.BigTextStyle().bigText(status))
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setOngoing(progress < 100)
                .setProgress(100, progress, progress <= 1)
                .addAction(new Notification.Action.Builder(null, "Ακύρωση", cancelAction).build())
                .build();
    }

    @Override public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
