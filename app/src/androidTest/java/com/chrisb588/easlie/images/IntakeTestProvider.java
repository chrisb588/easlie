package com.chrisb588.easlie.images;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** Test APK only. Uses framework classes because the provider runs outside instrumentation. */
public class IntakeTestProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"create-task-fixtures".equals(method)) return super.call(method, arg, extras);
        Bitmap image = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888);
        try {
            for (String name : new String[]{"task-first.png", "task-second.png"}) {
                try (FileOutputStream output = new FileOutputStream(new File(getContext().getFilesDir(), name))) {
                    image.compress(Bitmap.CompressFormat.PNG, 100, output);
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not create share fixtures", failure);
        } finally {
            image.recycle();
        }
        return Bundle.EMPTY;
    }

    @Override public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name != null && name.endsWith(".jpg")) return "image/jpeg";
        if (name != null && name.endsWith(".txt")) return "text/plain";
        return "image/png";
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("[a-zA-Z0-9.-]+")) throw new FileNotFoundException("Invalid fixture name");
        return ParcelFileDescriptor.open(new File(getContext().getFilesDir(), name), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
}
