package com.spotdl.gui.android;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.Locale;

final class DownloadFolderStore {
    private static final String PREFS = "download_location";
    private static final String KEY_TREE_URI = "tree_uri";
    private static final String KEY_LABEL = "tree_label";

    private DownloadFolderStore() {}

    static void saveSelection(Context context, Uri treeUri, int resultFlags) {
        int takeFlags = resultFlags & (
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        );
        takeFlags |= Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        context.getContentResolver().takePersistableUriPermission(treeUri, takeFlags);

        Uri previous = rawSelection(context);
        if (previous != null && !previous.equals(treeUri)) release(context, previous);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TREE_URI, treeUri.toString())
                .putString(KEY_LABEL, readDisplayName(context, treeUri))
                .apply();
    }

    static void useDefault(Context context) {
        Uri previous = rawSelection(context);
        if (previous != null) release(context, previous);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    static Intent openFolderIntent(Context context) {
        Uri folder = selectedTree(context);
        if (folder != null) {
            folder = DocumentsContract.buildDocumentUriUsingTree(
                    folder,
                    DocumentsContract.getTreeDocumentId(folder)
            );
        } else {
            folder = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:Download/SpotDL Android"
            );
        }
        return new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    }

    static Uri selectedTree(Context context) {
        Uri selected = rawSelection(context);
        if (selected == null) return null;
        for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            if (selected.equals(permission.getUri())
                    && permission.isReadPermission()
                    && permission.isWritePermission()) return selected;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        return null;
    }

    static String selectionLabel(Context context) {
        Uri selected = selectedTree(context);
        if (selected == null) return "Downloads/SpotDL Android";
        String label = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LABEL, "");
        return label == null || label.isBlank() ? "Επιλεγμένος φάκελος" : label;
    }

    static File outputDirectory(Context context, String folderName, boolean customFolder) {
        File root;
        if (customFolder) {
            root = new File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "download-staging");
            return new File(root, System.currentTimeMillis() + "-" + SpotifyEmbedParser.safeName(folderName));
        } else {
            root = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SpotDL Android");
        }
        return new File(root, SpotifyEmbedParser.safeName(folderName));
    }

    static String deliverToSelection(Context context, Uri treeUri, File sourceDirectory, String folderName) throws Exception {
        Uri destinationDirectory = findOrCreateDirectory(context, treeUri, SpotifyEmbedParser.safeName(folderName));
        File[] files = sourceDirectory.listFiles((dir, name) ->
                name.toLowerCase(Locale.ROOT).endsWith(".mp3") || name.equalsIgnoreCase("Cover.jpg"));
        if (files == null || files.length == 0) throw new IllegalStateException("Δεν βρέθηκαν αρχεία για αποθήκευση.");
        for (File file : files) copyFile(context, treeUri, destinationDirectory, file);
        deleteStagingDirectory(context, sourceDirectory);
        return selectionLabel(context) + "/" + SpotifyEmbedParser.safeName(folderName);
    }

    static void discardEmptyStaging(Context context, File sourceDirectory) {
        File[] files = sourceDirectory.listFiles();
        if (files == null || files.length == 0) deleteStagingDirectory(context, sourceDirectory);
    }

    private static Uri findOrCreateDirectory(Context context, Uri treeUri, String name) throws Exception {
        Uri rootDocument = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
        );
        Child existing = findChild(context, treeUri, rootDocument, name);
        if (existing != null) {
            if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(existing.mimeType())) {
                throw new IllegalStateException("Υπάρχει ήδη αρχείο με το όνομα " + name + ".");
            }
            return existing.uri();
        }
        Uri created = DocumentsContract.createDocument(
                context.getContentResolver(),
                rootDocument,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name
        );
        if (created == null) throw new IllegalStateException("Δεν δημιουργήθηκε ο φάκελος " + name + ".");
        return created;
    }

    private static void copyFile(Context context, Uri treeUri, Uri parent, File source) throws Exception {
        Child existing = findChild(context, treeUri, parent, source.getName());
        Uri destination;
        if (existing == null) {
            destination = DocumentsContract.createDocument(
                    context.getContentResolver(),
                    parent,
                    source.getName().equalsIgnoreCase("Cover.jpg") ? "image/jpeg" : "audio/mpeg",
                    source.getName()
            );
        } else {
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(existing.mimeType())) {
                throw new IllegalStateException("Υπάρχει φάκελος με το όνομα " + source.getName() + ".");
            }
            destination = existing.uri();
        }
        if (destination == null) throw new IllegalStateException("Δεν δημιουργήθηκε το " + source.getName() + ".");

        ContentResolver resolver = context.getContentResolver();
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(source));
             OutputStream rawOutput = resolver.openOutputStream(destination, "w")) {
            if (rawOutput == null) throw new IllegalStateException("Δεν άνοιξε το " + source.getName() + " για εγγραφή.");
            try (BufferedOutputStream output = new BufferedOutputStream(rawOutput)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            }
        }
    }

    private static Child findChild(Context context, Uri treeUri, Uri parent, String name) throws Exception {
        String parentId = DocumentsContract.getDocumentId(parent);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        try (Cursor cursor = context.getContentResolver().query(children, projection, null, null, null)) {
            if (cursor == null) return null;
            while (cursor.moveToNext()) {
                if (!name.equals(cursor.getString(1))) continue;
                return new Child(
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0)),
                        cursor.getString(2)
                );
            }
        }
        return null;
    }

    private static String readDisplayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
                uri,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isBlank()) return name;
            }
        } catch (Exception ignored) {}
        String fallback = uri.getLastPathSegment();
        return fallback == null || fallback.isBlank() ? "Επιλεγμένος φάκελος" : fallback;
    }

    private static Uri rawSelection(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE_URI, "");
        if (value == null || value.isBlank()) return null;
        try { return Uri.parse(value); }
        catch (Exception ignored) { return null; }
    }

    private static void release(Context context, Uri uri) {
        try {
            context.getContentResolver().releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );
        } catch (Exception ignored) {}
    }

    private static void deleteStagingDirectory(Context context, File directory) {
        File externalFiles = context.getExternalFilesDir(null);
        if (externalFiles == null) return;
        try {
            String allowedRoot = externalFiles.getCanonicalPath() + File.separator;
            String target = directory.getCanonicalPath() + File.separator;
            if (!target.startsWith(allowedRoot)) return;
            deleteRecursively(directory);
        } catch (Exception ignored) {}
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }

    private record Child(Uri uri, String mimeType) {}
}
