package org.localtranslator

import android.app.DownloadManager
import android.content.Context
import android.os.SystemClock
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

internal enum class Language(val code: String, val label: String) {
    ENGLISH("en", "English"),
    FRENCH("fr", "French"),
    ARABIC("ar", "Arabic")
}

internal class TranslationEngine(context: Context) {
    data class Result(val text: String, val status: String, val googleTranslation: Boolean = false)
    class InputError(message: String) : Exception(message)

    private val downloads = PackDownloads(context)
    private val models = RemoteModelManager.getInstance()

    private fun model(language: Language) = TranslateRemoteModel.Builder(language.code).build()

    fun installed(): Set<Language> =
        Language.entries.filterTo(mutableSetOf()) {
            it == Language.ENGLISH || await(models.isModelDownloaded(model(it)))
        }

    fun download(
        wifiOnly: Boolean,
        progress: (Language, Boolean, PackDownloads.Transfer?) -> Unit
    ) {
        val conditions = DownloadConditions.Builder().apply {
            if (wifiOnly) requireWifi()
        }.build()
        val failures = mutableListOf<String>()
        for (language in Language.entries.filter { it != Language.ENGLISH }) {
            try {
                if (!await(models.isModelDownloaded(model(language)))) {
                    val existing = downloads.latest(language)
                    if (!wifiOnly && existing?.status == DownloadManager.STATUS_PAUSED &&
                        existing.reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI
                    ) {
                        downloads.remove(existing.id)
                    }
                    progress(language, false, null)
                    val task = models.download(model(language), conditions)
                    var lastBytes = -1L
                    var lastChange = SystemClock.elapsedRealtime()
                    var transfer: PackDownloads.Transfer? = null
                    while (!task.isComplete) {
                        transfer = downloads.latest(language)
                        progress(language, false, transfer)
                        val bytes = transfer?.bytes ?: 0
                        if (bytes != lastBytes) {
                            lastBytes = bytes
                            lastChange = SystemClock.elapsedRealtime()
                        }
                        if (SystemClock.elapsedRealtime() - lastChange >= 45_000) {
                            if (transfer?.waitingForCooling == true) {
                                throw InputError(
                                    "Android is still holding the downloads because the phone is warm. " +
                                        "Let it cool with the screen off, then tap Retry download. The queued pack is kept."
                                )
                            }
                            if (transfer?.status == DownloadManager.STATUS_PAUSED) {
                                throw InputError("${transfer.detail()}. Reconnect or retry with a different connection.")
                            }
                            if (bytes == 0L && transfer?.status == DownloadManager.STATUS_PENDING) {
                                downloads.remove(transfer.id)
                            }
                            throw InputError("${language.label} made no download progress for 45 seconds. Tap Retry download.")
                        }
                        Thread.sleep(500)
                    }
                    try {
                        await(task)
                    } catch (error: java.util.concurrent.ExecutionException) {
                        val latest = downloads.latest(language) ?: transfer
                        val detail = latest?.takeIf { it.status == DownloadManager.STATUS_FAILED }?.detail()
                        throw InputError(detail ?: "Google could not install the pack")
                    }
                }
                progress(language, true, null)
            } catch (error: InterruptedException) {
                throw error
            } catch (error: InputError) {
                failures.add("${language.label}: ${error.message}")
            } catch (_: Exception) {
                failures.add("${language.label}: Could not prepare the download")
            }
        }
        if (failures.isNotEmpty()) {
            throw InputError(failures.joinToString("\n") + "\nCheck your connection and retry.")
        }
    }

    fun translate(text: String, source: Language?, target: Language, endpoint: String?): Result {
        val from = source ?: identify(text)
        if (from == target) return Result(text, "Source and target are both ${target.label}.")
        var onlineFailed = false
        if (endpoint != null) {
            try {
                return Result(online(endpoint, text, from, target), "Translated online with LibreTranslate")
            } catch (error: InterruptedException) {
                throw error
            } catch (_: Exception) {
                onlineFailed = true
            }
        }
        val installed = installed()
        if (from !in installed || target !in installed) {
            val message = "Download the offline languages to translate ${from.label} and ${target.label}."
            throw InputError(if (onlineFailed) "Online translation unavailable. $message" else message)
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(from.code)
            .setTargetLanguage(target.code)
            .build()
        val translator = Translation.getClient(options)
        try {
            val translation = text.split('\n').joinToString("\n") { line ->
                if (line.isBlank()) line else await(translator.translate(line))
            }
            return Result(translation, if (onlineFailed) {
                "Online translation unavailable. Translated on this device"
            } else {
                "Translated on this device"
            }, googleTranslation = true)
        } finally {
            translator.close()
        }
    }

    private fun identify(text: String): Language {
        val identifier = LanguageIdentification.getClient()
        try {
            val code = await(identifier.identifyLanguage(text))
            return Language.entries.firstOrNull { it.code == code }
                ?: throw InputError("Choose English, French, or Arabic as the source language.")
        } finally {
            identifier.close()
        }
    }

    private fun online(endpoint: String, text: String, source: Language, target: Language): String {
        val url = URI("${endpoint.trimEnd('/')}/translate").toURL()
        val connection = url.openConnection() as HttpsURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 8_000
        connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.doOutput = true
        try {
            val body = JSONObject().put("q", text).put("source", source.code)
                .put("target", target.code).put("format", "text").toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            check(connection.responseCode == 200)
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                val buffer = CharArray(65_536)
                var length = 0
                while (length < buffer.size) {
                    val count = it.read(buffer, length, buffer.size - length)
                    if (count == -1) break
                    length += count
                }
                check(length < buffer.size)
                String(buffer, 0, length)
            }
            return JSONObject(response).getString("translatedText").also { check(it.isNotBlank()) }
        } finally {
            connection.disconnect()
        }
    }

    private fun <T> await(task: Task<T>): T = Tasks.await(task, 20, TimeUnit.SECONDS)

    companion object {
        fun validEndpoint(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme == "https" && uri.host != null && uri.userInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null
        }.getOrDefault(false)
    }
}
