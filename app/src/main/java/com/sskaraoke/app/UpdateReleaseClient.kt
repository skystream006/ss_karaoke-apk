package com.sskaraoke.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class UpdateReleaseUnavailableException : IOException("No published stable release")
internal class UpdateAssetUnavailableException(cause: Exception) : IOException("No supported release APK", cause)

internal data class UpdateRelease(
    val tag: String,
    val version: UpdateVersion,
    val assets: JSONArray?
)

internal data class UpdateAsset(
    val downloadUrl: URI,
    val size: Long
)

internal class UpdateReleaseClient {
    private val cancelled = AtomicBoolean(false)
    private val connection = AtomicReference<HttpURLConnection?>()

    fun cancel() {
        cancelled.set(true)
        connection.getAndSet(null)?.disconnect()
    }

    fun checkCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw IOException("Cancelled")
    }

    fun latest(): UpdateRelease {
        val bytes = read(URI(LATEST), METADATA_LIMIT, null, 60_000L) { input, _, deadline ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var count = 0L
            while (true) {
                checkDeadline(deadline)
                val length = input.read(buffer)
                if (length < 0) break
                count += length
                if (count > METADATA_LIMIT) throw IOException("Metadata too large")
                output.write(buffer, 0, length)
            }
            output.toByteArray()
        }
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        if (json.getBoolean("draft") || json.getBoolean("prerelease")) {
            throw IOException("Not a stable release")
        }
        val tag = json.getString("tag_name")
        val version = UpdateVersion.parse(tag) ?: throw IOException("Invalid release version")
        return UpdateRelease(tag, version, json.optJSONArray("assets"))
    }

    fun selectAsset(release: UpdateRelease): UpdateAsset = try {
        val assets = release.assets ?: throw IOException("Missing release assets")
        val candidates = mutableListOf<UpdateAsset>()
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            if (!asset.getString("name").endsWith(".apk", ignoreCase = true)) continue
            val size = asset.getLong("size")
            if (size !in 1..APK_LIMIT) throw IOException("Invalid APK size")
            val uri = URI(asset.getString("browser_download_url"))
            UpdateUrlPolicy.validate(uri, false)
            if (uri.host != "github.com" ||
                !uri.rawPath.startsWith("/skystream006/ss_karaoke-apk/releases/download/")) {
                throw IOException("APK is not a repository release asset")
            }
            candidates.add(UpdateAsset(uri, size))
        }
        // Do not guess between architecture-specific or otherwise ambiguous packages.
        candidates.singleOrNull() ?: throw IOException("No unique release APK")
    } catch (error: Exception) {
        throw UpdateAssetUnavailableException(error)
    }

    fun download(asset: UpdateAsset, destination: File, progress: (Int) -> Unit) {
        read(asset.downloadUrl, APK_LIMIT, asset.size, 15 * 60_000L) { input, _, deadline ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var count = 0L
                var lastProgress = -1
                while (true) {
                    checkDeadline(deadline)
                    val length = input.read(buffer)
                    if (length < 0) break
                    count += length
                    if (count > asset.size || count > APK_LIMIT) throw IOException("APK too large")
                    output.write(buffer, 0, length)
                    val percent = (count * 100 / asset.size).toInt()
                    if (percent != lastProgress) {
                        progress(percent)
                        lastProgress = percent
                    }
                }
                if (count != asset.size) throw IOException("Truncated APK")
                output.fd.sync()
            }
        }
    }

    private fun checkDeadline(deadline: Long) {
        checkCancelled()
        if (System.nanoTime() > deadline) throw IOException("Update request timed out")
    }

    private fun <T> read(
        initial: URI,
        limit: Long,
        expectedSize: Long?,
        timeoutMillis: Long,
        consume: (java.io.InputStream, Long, Long) -> T
    ): T {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        var uri = initial
        val metadata = initial.toString() == LATEST
        repeat(MAX_REDIRECTS + 1) { redirects ->
            checkDeadline(deadline)
            UpdateUrlPolicy.validate(uri, metadata)
            val current = uri.toURL().openConnection() as HttpURLConnection
            current.instanceFollowRedirects = false
            current.connectTimeout = 15_000
            current.readTimeout = 20_000
            current.setRequestProperty("Accept", if (metadata) "application/vnd.github+json" else "application/octet-stream")
            current.setRequestProperty("Accept-Encoding", "identity")
            current.setRequestProperty("User-Agent", "SS-Karaoke-Android-Updater")
            connection.set(current)
            try {
                checkDeadline(deadline)
                val status = current.responseCode
                if (status in REDIRECT_CODES) {
                    if (redirects == MAX_REDIRECTS) throw IOException("Too many redirects")
                    val location = current.getHeaderField("Location") ?: throw IOException("Missing redirect")
                    uri = uri.resolve(location)
                    UpdateUrlPolicy.validate(uri, metadata)
                } else {
                    if (metadata && status == HttpURLConnection.HTTP_NOT_FOUND) {
                        throw UpdateReleaseUnavailableException()
                    }
                    if (status != HttpURLConnection.HTTP_OK) throw IOException("HTTP $status")
                    val size = current.contentLengthLong
                    if (size > limit || (expectedSize != null && size >= 0 && size != expectedSize)) {
                        throw IOException("Unexpected response size")
                    }
                    val encoding = current.getHeaderField("Content-Encoding")
                    if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
                        throw IOException("Unexpected content encoding")
                    }
                    return current.inputStream.use { consume(it, size, deadline) }
                }
            } finally {
                connection.compareAndSet(current, null)
                current.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    companion object {
        private const val LATEST = "https://api.github.com/repos/skystream006/ss_karaoke-apk/releases/latest"
        private const val METADATA_LIMIT = 1024L * 1024
        internal const val APK_LIMIT = 250L * 1024 * 1024
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
