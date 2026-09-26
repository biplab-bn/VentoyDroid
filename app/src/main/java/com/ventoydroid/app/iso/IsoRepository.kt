package com.ventoydroid.app.iso

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ventoydroid.app.install.IsoSource

/**
 * Turns SAF document URIs (from the system file picker) into [IsoSource]s
 * whose contents are streamed lazily — multi-GB ISOs never sit in RAM.
 */
object IsoRepository {

    fun fromUris(context: Context, uris: List<Uri>): List<IsoSource> =
        uris.map { uri -> fromUri(context, uri) }

    fun fromUri(context: Context, uri: Uri): IsoSource {
        val resolver = context.contentResolver
        val name = queryDisplayName(context, uri) ?: "install.iso"
        val size = querySize(context, uri)
            ?: throw IllegalArgumentException("Cannot determine the size of $name")
        return IsoSource(
            displayName = name,
            size = size,
            open = { resolver.openInputStream(uri) ?: error("Could not reopen $name") },
        )
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun querySize(context: Context, uri: Uri): Long? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val idx = c.getColumnIndexOrThrow(OpenableColumns.SIZE)
            if (c.isNull(idx)) return null
            val v = if (c.getType(idx) == android.database.Cursor.FIELD_TYPE_STRING) {
                c.getString(idx).toLongOrNull()
            } else {
                c.getLong(idx)
            }
            v?.takeIf { it >= 0 }
        }
}
