package com.ybtarmy.app

import android.content.Context
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloads the quantized GGUF as a separate asset from the latest android-v* release.
 * The model is stored in app-private storage and is not included in the APK.
 */
object ModelRepository {
    private const val API_URL =
        "https://api.github.com/repos/CodeByArnob/YBT-Army-135M/releases?per_page=30"
    private const val MODEL_ASSET = "YBT-Army-135M-Q4_K_M.gguf"
    private const val CHECKSUM_ASSET = "$MODEL_ASSET.sha256"

    fun installedFile(context: Context): File =
        File(File(context.filesDir, "models"), MODEL_ASSET)

    suspend fun downloadLatest(
        context: Context,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val assets = resolveLatestAndroidRelease()
        val destination = installedFile(context)
        destination.parentFile?.mkdirs()

        val expectedHash = readText(assets.checksumUrl)
            .trim()
            .split(Regex("\\s+"))
            .firstOrNull()
            ?.lowercase()
            ?: error("Release checksum file is empty.")

        require(expectedHash.matches(Regex("[a-f0-9]{64}"))) {
            "Release checksum is invalid."
        }

        if (destination.isFile && sha256(destination).equals(expectedHash, ignoreCase = true)) {
            onProgress(destination.length(), destination.length())
            return@withContext destination
        }

        val partial = File(destination.parentFile, "$MODEL_ASSET.part")
        val connection = (URL(assets.modelUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "YBT-Army-Android")
        }

        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                error("Model download failed (HTTP ${connection.responseCode}).")
            }
            val total = connection.contentLengthLong
            var downloaded = 0L
            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(FileOutputStream(partial)).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, total)
                    }
                }
            }
            require(partial.length() > 10_000_000L) {
                "Downloaded model is unexpectedly small; the release asset may be incomplete."
            }
            val actualHash = sha256(partial)
            require(actualHash.equals(expectedHash, ignoreCase = true)) {
                "Model checksum verification failed. The downloaded file was rejected."
            }
            if (destination.exists() && !destination.delete()) {
                error("Unable to replace the previous model file.")
            }
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }
            destination
        } finally {
            connection.disconnect()
            if (partial.exists()) partial.delete()
        }
    }

    private data class ReleaseAssets(val modelUrl: String, val checksumUrl: String)

    private fun resolveLatestAndroidRelease(): ReleaseAssets {
        val json = JSONArray(readText(API_URL))
        for (index in 0 until json.length()) {
            val release = json.getJSONObject(index)
            val tag = release.optString("tag_name")
            if (!tag.startsWith("android-v") || release.optBoolean("draft", false)) continue
            val assets = release.optJSONArray("assets") ?: continue
            var modelUrl: String? = null
            var checksumUrl: String? = null
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.getJSONObject(assetIndex)
                when (asset.optString("name")) {
                    MODEL_ASSET -> modelUrl = asset.optString("browser_download_url")
                    CHECKSUM_ASSET -> checksumUrl = asset.optString("browser_download_url")
                }
            }
            if (!modelUrl.isNullOrBlank() && !checksumUrl.isNullOrBlank()) {
                return ReleaseAssets(modelUrl, checksumUrl)
            }
        }
        error("No published Android model was found. Please try again after an Android model release is published.")
    }

    private fun readText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "YBT-Army-Android")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            if (connection.responseCode !in 200..299) {
                error("Could not read release metadata (HTTP ${connection.responseCode}).")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
