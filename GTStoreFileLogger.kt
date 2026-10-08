package com.gontijotech.gtstore.client

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GTStoreFileLogger {

    private const val FILE_NAME = "GTSTORE-log.txt"

    @Synchronized
    fun log(
        context: Context,
        tag: String,
        message: String
    ) {
        try {
            val time = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                Locale.US
            ).format(Date())

            val line =
                "[$time] [$tag] $message\n"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                val resolver = context.contentResolver

                val collection =
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI

                var uri = findExistingFile(context)

                if (uri == null) {

                    val values =
                        ContentValues().apply {
                            put(
                                MediaStore.Downloads.DISPLAY_NAME,
                                FILE_NAME
                            )

                            put(
                                MediaStore.Downloads.MIME_TYPE,
                                "text/plain"
                            )

                            put(
                                MediaStore.Downloads.RELATIVE_PATH,
                                Environment.DIRECTORY_DOWNLOADS
                            )
                        }

                    uri = resolver.insert(
                        collection,
                        values
                    )
                }

                if (uri != null) {

                    resolver.openOutputStream(
                        uri,
                        "wa"
                    )?.use { output ->

                        output.write(
                            line.toByteArray(
                                Charsets.UTF_8
                            )
                        )

                        output.flush()
                    }
                }

            } else {

                @Suppress("DEPRECATION")

                val directory =
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    )

                if (!directory.exists()) {
                    directory.mkdirs()
                }

                val file =
                    java.io.File(
                        directory,
                        FILE_NAME
                    )

                file.appendText(
                    line,
                    Charsets.UTF_8
                )
            }

        } catch (_: Exception) {
            // Nunca deixar o logger interromper o GTSTORE.
        }
    }

    @Synchronized
    fun clear(
        context: Context
    ) {
        try {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                val resolver =
                    context.contentResolver

                val uri =
                    findExistingFile(context)

                if (uri != null) {
                    resolver.delete(
                        uri,
                        null,
                        null
                    )
                }

            } else {

                @Suppress("DEPRECATION")

                val directory =
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    )

                val file =
                    java.io.File(
                        directory,
                        FILE_NAME
                    )

                if (file.exists()) {
                    file.delete()
                }
            }

        } catch (_: Exception) {
        }
    }

    private fun findExistingFile(
        context: Context
    ): android.net.Uri? {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null
        }

        val resolver =
            context.contentResolver

        val collection =
            MediaStore.Downloads.EXTERNAL_CONTENT_URI

        val projection =
            arrayOf(
                MediaStore.Downloads._ID
            )

        val selection =
            "${MediaStore.Downloads.DISPLAY_NAME}=?"

        val selectionArgs =
            arrayOf(FILE_NAME)

        resolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->

            if (cursor.moveToFirst()) {

                val id =
                    cursor.getLong(
                        cursor.getColumnIndexOrThrow(
                            MediaStore.Downloads._ID
                        )
                    )

                return android.content.ContentUris.withAppendedId(
                    collection,
                    id
                )
            }
        }

        return null
    }
}
