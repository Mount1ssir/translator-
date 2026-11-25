package org.localtranslator

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PowerManager

internal class PackDownloads(context: Context) {
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    data class Transfer(
        val id: Long, val status: Int, val reason: Int, val bytes: Long, val total: Long,
        val waitingForCooling: Boolean
    ) {
        val percent: Int? get() = if (total > 0) (100 * bytes / total).toInt().coerceIn(0, 100) else null

        fun detail(): String = when (status) {
            DownloadManager.STATUS_PENDING -> if (waitingForCooling) {
                "Waiting for the phone to cool down"
            } else "Queued by Android"
            DownloadManager.STATUS_PAUSED -> when (reason) {
                DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi"
                DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for a network connection"
                DownloadManager.PAUSED_WAITING_TO_RETRY -> "Connection interrupted. Android is waiting to retry"
                else -> "Android paused the transfer"
            }
            DownloadManager.STATUS_SUCCESSFUL -> "Transfer complete. Installing pack…"
            DownloadManager.STATUS_FAILED -> when (reason) {
                DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage"
                DownloadManager.ERROR_CANNOT_RESUME -> "The server could not resume the transfer"
                DownloadManager.ERROR_HTTP_DATA_ERROR -> "Connection to the model server failed"
                else -> if (reason in 400..599) "Model server returned HTTP $reason" else "Android download failed ($reason)"
            }
            else -> "Downloading"
        }

        fun progressText(context: Context): String {
            val received = android.text.format.Formatter.formatShortFileSize(context, bytes)
            val expected = if (total > 0) android.text.format.Formatter.formatShortFileSize(context, total) else null
            return when {
                expected != null -> "$percent% · $received / $expected"
                bytes > 0 -> "$received downloaded"
                else -> ""
            }
        }
    }

    fun latest(language: Language): Transfer? = runCatching {
        val archive = when (language) {
            Language.FRENCH -> "en_fr.zip"
            Language.ARABIC -> "ar_en.zip"
            Language.ENGLISH -> return null
        }
        manager.query(DownloadManager.Query())?.use { cursor ->
            val uriIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
            val idIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val statusIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val reasonIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
            val bytesIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            var newest: Transfer? = null
            while (cursor.moveToNext()) {
                val uri = Uri.parse(cursor.getString(uriIndex) ?: continue)
                if (uri.scheme != "https" || uri.path?.contains("/translate/offline/") != true ||
                    uri.lastPathSegment != archive
                ) continue
                val id = cursor.getLong(idIndex)
                if (id <= (newest?.id ?: -1)) continue
                val status = cursor.getInt(statusIndex)
                val waitingForCooling = status == DownloadManager.STATUS_PENDING &&
                    Build.VERSION.SDK_INT >= 29 && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
                newest = Transfer(id, status, cursor.getInt(reasonIndex),
                    cursor.getLong(bytesIndex).coerceAtLeast(0), cursor.getLong(totalIndex), waitingForCooling)
            }
            newest
        }
    }.getOrNull()

    fun remove(id: Long) {
        manager.remove(id)
    }
}
