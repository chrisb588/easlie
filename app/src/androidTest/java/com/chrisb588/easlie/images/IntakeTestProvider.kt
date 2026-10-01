package com.chrisb588.easlie.images

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/** Test APK only. Fixtures exercise the real ContentResolver path. */
class IntakeTestProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = when (uri.lastPathSegment?.substringAfterLast('.')) {
        "png" -> "image/png"
        "jpg" -> "image/jpeg"
        "txt" -> "text/plain"
        else -> "image/png"
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val name = requireNotNull(uri.lastPathSegment)
        require(name.matches(Regex("[a-zA-Z0-9.-]+")))
        return ParcelFileDescriptor.open(File(requireNotNull(context).filesDir, name), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
