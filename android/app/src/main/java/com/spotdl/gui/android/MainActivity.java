package com.spotdl.gui.android;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private EditText urlInput;
    private Spinner qualityInput;
    private Button downloadButton;
    private Button cancelButton;
    private ProgressBar progressBar;
    private TextView statusText;
    private boolean downloading;

    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int progress = intent.getIntExtra(DownloadService.EXTRA_PROGRESS, 0);
            boolean done = intent.getBooleanExtra(DownloadService.EXTRA_DONE, false);
            boolean failed = intent.getBooleanExtra(DownloadService.EXTRA_FAILED, false);
            progressBar.setProgress(progress);
            statusText.setText(intent.getStringExtra(DownloadService.EXTRA_STATUS));
            if (done) {
                downloading = false;
                downloadButton.setEnabled(true);
                cancelButton.setVisibility(View.GONE);
                Toast.makeText(MainActivity.this, failed ? "Η λήψη απέτυχε" : "Η λήψη ολοκληρώθηκε", Toast.LENGTH_LONG).show();
            }
        }
    };

    private final BroadcastReceiver updateDownloads = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                UpdateManager.onDownloadComplete(
                        MainActivity.this,
                        intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                );
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(createContent());
        acceptSharedText(getIntent());
        UpdateManager.checkForUpdates(this, false);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        acceptSharedText(intent);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(DownloadService.ACTION_PROGRESS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updates, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(updates, filter);
        IntentFilter downloads = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updateDownloads, downloads, Context.RECEIVER_EXPORTED);
        else registerReceiver(updateDownloads, downloads);
    }

    @Override protected void onResume() {
        super.onResume();
        UpdateManager.resumePendingInstall(this);
    }

    @Override protected void onStop() {
        unregisterReceiver(updates);
        unregisterReceiver(updateDownloads);
        super.onStop();
    }

    private View createContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(18), dp(22), dp(24));
        root.setBackgroundColor(Color.rgb(245, 247, 249));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -1));

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(22), dp(24), dp(22), dp(24));
        hero.setBackground(rounded(Color.rgb(7, 29, 49), 20));
        root.addView(hero, margins(-1, -2, 0, 0, 0, 18));

        TextView brand = text("SPOTDL  ·  ANDROID", 13, Color.rgb(29, 185, 84), Typeface.BOLD);
        hero.addView(brand);
        TextView title = text("Η μουσική σου,\nοργανωμένη σωστά.", 30, Color.WHITE, Typeface.BOLD);
        title.setPadding(0, dp(10), 0, dp(10));
        hero.addView(title);
        hero.addView(text("Επικόλλησε Spotify album/track link ή ένα υποστηριζόμενο media link.", 15, Color.rgb(205, 218, 230), Typeface.NORMAL));

        TextView inputLabel = text("ΣΥΝΔΕΣΜΟΣ", 12, Color.rgb(96, 112, 128), Typeface.BOLD);
        root.addView(inputLabel, margins(-1, -2, 2, 0, 0, 7));
        urlInput = new EditText(this);
        urlInput.setHint("https://open.spotify.com/album/…");
        urlInput.setTextSize(15);
        urlInput.setSingleLine(false);
        urlInput.setMinLines(2);
        urlInput.setGravity(Gravity.TOP);
        urlInput.setPadding(dp(16), dp(14), dp(16), dp(14));
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setBackground(rounded(Color.WHITE, 13));
        root.addView(urlInput, margins(-1, -2, 0, 0, 0, 15));

        root.addView(text("ΠΟΙΟΤΗΤΑ MP3", 12, Color.rgb(96, 112, 128), Typeface.BOLD), margins(-1, -2, 0, 0, 0, 7));
        qualityInput = new Spinner(this);
        qualityInput.setPadding(dp(8), dp(5), dp(8), dp(5));
        qualityInput.setBackground(rounded(Color.WHITE, 13));
        String[] qualities = {"Υψηλή · V0 (~245 kbps)", "Ισορροπημένη · V2 (~190 kbps)", "Μικρό μέγεθος · V5 (~130 kbps)"};
        qualityInput.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, qualities));
        root.addView(qualityInput, margins(-1, dp(56), 0, 0, 0, 18));

        downloadButton = new Button(this);
        downloadButton.setText("↓  ΛΗΨΗ ΣΤΟ ΚΙΝΗΤΟ");
        downloadButton.setTextColor(Color.WHITE);
        downloadButton.setTextSize(15);
        downloadButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        downloadButton.setBackground(rounded(Color.rgb(29, 185, 84), 14));
        downloadButton.setOnClickListener(v -> startDownload());
        root.addView(downloadButton, margins(-1, dp(58), 0, 0, 0, 10));

        cancelButton = new Button(this);
        cancelButton.setText("ΑΚΥΡΩΣΗ");
        cancelButton.setVisibility(View.GONE);
        cancelButton.setOnClickListener(v -> {
            Intent cancel = new Intent(this, DownloadService.class).setAction(DownloadService.ACTION_CANCEL);
            startService(cancel);
        });
        root.addView(cancelButton, margins(-1, dp(48), 0, 0, 0, 12));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        root.addView(progressBar, margins(-1, dp(7), 0, 0, 0, 10));
        statusText = text("Έτοιμο. Τα MP3 αποθηκεύονται στο Downloads/SpotDL Android.", 13, Color.rgb(96, 112, 128), Typeface.NORMAL);
        root.addView(statusText);

        TextView updateStatus = text(
                "ΑΥΤΟΜΑΤΕΣ ΕΝΗΜΕΡΩΣΕΙΣ ΕΝΕΡΓΕΣ  ·  v" + BuildConfig.VERSION_NAME,
                12,
                Color.rgb(7, 29, 49),
                Typeface.BOLD
        );
        updateStatus.setGravity(Gravity.CENTER);
        updateStatus.setPadding(dp(12), dp(14), dp(12), dp(14));
        updateStatus.setBackground(rounded(Color.rgb(225, 231, 236), 12));
        root.addView(updateStatus, margins(-1, -2, 0, 18, 0, 0));

        TextView note = text("Χρησιμοποίησέ το μόνο για περιεχόμενο που σου ανήκει ή έχεις άδεια να κατεβάσεις.", 12, Color.rgb(112, 126, 138), Typeface.NORMAL);
        note.setPadding(0, dp(22), 0, 0);
        root.addView(note);
        return scroll;
    }

    private void startDownload() {
        if (downloading) return;
        String url = urlInput.getText().toString().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            urlInput.setError("Επικόλλησε έναν έγκυρο σύνδεσμο.");
            return;
        }
        downloading = true;
        downloadButton.setEnabled(false);
        cancelButton.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        statusText.setText("Εκκίνηση μηχανής λήψης…");
        String[] values = {"0", "2", "5"};
        Intent service = new Intent(this, DownloadService.class)
                .putExtra(DownloadService.EXTRA_URL, url)
                .putExtra(DownloadService.EXTRA_QUALITY, values[qualityInput.getSelectedItemPosition()]);
        startForegroundService(service);
    }

    private void acceptSharedText(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())) {
            String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (shared != null && urlInput != null) urlInput.setText(extractUrl(shared));
        }
    }

    private static String extractUrl(String value) {
        for (String token : value.split("\\s+")) if (token.startsWith("http://") || token.startsWith("https://")) return token;
        return value;
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setLineSpacing(0, 1.08f);
        return view;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams margins(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
