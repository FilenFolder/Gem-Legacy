package com.filenfolder.gemlegacy

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

class PhotoProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("No context")
        val dir = photoDir(ctx) ?: throw FileNotFoundException("No storage")
        val name = uri.lastPathSegment ?: throw FileNotFoundException("No file name")
        val file = File(dir, name)
        if (file.canonicalFile.parentFile != dir.canonicalFile) throw FileNotFoundException("Bad path")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    companion object {
        fun photoDir(context: Context): File? {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return null
            if (!dir.exists() && !dir.mkdirs()) return null
            return dir
        }

        fun uriFor(context: Context, file: File): Uri =
            Uri.parse("content://${context.packageName}.photos/${file.name}")
    }
}
