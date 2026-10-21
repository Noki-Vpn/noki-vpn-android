package com.noki.vpn

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

/** AndroidX's Linux-only canonical-path check rejects Windows separators in local Robolectric. */
@Implements(FileProvider::class)
class SupportFileProviderFixture {
    companion object {
        private val files = mutableMapOf<Uri, File>()

        @JvmStatic
        @Implementation
        fun getUriForFile(context: Context, authority: String, file: File): Uri {
            val uri = Uri.Builder().scheme("content").authority(authority).appendPath("fixture").appendPath(file.name).build()
            files[uri] = file
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri): String = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(files.getValue(uri).extension) ?: "application/octet-stream"
                override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
                    val source = files.getValue(uri)
                    val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
                    return MatrixCursor(columns).apply { addRow(columns.map { if (it == OpenableColumns.SIZE) source.length() else source.name }) }
                }
                override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(files.getValue(uri),
                    if (mode.startsWith("w")) ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE else ParcelFileDescriptor.MODE_READ_ONLY)
                override fun insert(uri: Uri, values: ContentValues?): Uri? = null
                override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
                override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            }
            provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
            ShadowContentResolver.registerProviderInternal(authority, provider)
            return uri
        }
    }
}
