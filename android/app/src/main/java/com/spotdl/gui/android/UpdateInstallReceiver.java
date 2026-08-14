package com.spotdl.gui.android;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

public final class UpdateInstallReceiver extends BroadcastReceiver {
    static final String ACTION_INSTALL_STATUS = "com.spotdl.gui.android.INSTALL_STATUS";

    @Override public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation != null) {
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirmation);
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            UpdateManager.installationFinished(context, true);
            Toast.makeText(context, "Η ενημέρωση εγκαταστάθηκε.", Toast.LENGTH_LONG).show();
            return;
        }
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            UpdateManager.installationFinished(context, false);
            message = "Η εγκατάσταση ακυρώθηκε.";
        } else {
            UpdateManager.installationCanRetry(context);
        }
        if (message == null || message.isBlank()) message = "Η εγκατάσταση απέτυχε (" + status + ").";
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }
}
