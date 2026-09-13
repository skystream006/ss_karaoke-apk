package com.sskaraoke.app

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Numeric comparison without integer overflow, including arbitrarily large components. */
internal class UpdateVersion private constructor(private val components: List<String>) :
    Comparable<UpdateVersion> {
    override fun compareTo(other: UpdateVersion): Int {
        for (index in 0 until maxOf(components.size, other.components.size)) {
            val left = components.getOrElse(index) { "0" }
            val right = other.components.getOrElse(index) { "0" }
            val comparison = left.length.compareTo(right.length).takeIf { it != 0 }
                ?: left.compareTo(right)
            if (comparison != 0) return comparison
        }
        return 0
    }

    companion object {
        fun parse(value: String?): UpdateVersion? {
            if (value == null || !value.matches(Regex("v?[0-9]+(?:\\.[0-9]+)*"))) return null
            return UpdateVersion(value.removePrefix("v").split('.').map {
                it.trimStart('0').ifEmpty { "0" }
            })
        }
    }
}

internal data class UpdateRelease(
    val tag: String,
    val version: UpdateVersion,
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
        val assets = json.getJSONArray("assets")
        val candidates = mutableListOf<UpdateRelease>()
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            if (!asset.getString("name").endsWith(".apk", ignoreCase = true)) continue
            val size = asset.getLong("size")
            if (size !in 1..APK_LIMIT) throw IOException("Invalid APK size")
            val uri = URI(asset.getString("browser_download_url"))
            validateUrl(uri, false)
            if (uri.host != "github.com" ||
                !uri.rawPath.startsWith("/skystream006/ss_karaoke-apk/releases/download/")) {
                throw IOException("APK is not a repository release asset")
            }
            candidates.add(UpdateRelease(tag, version, uri, size))
        }
        // Do not guess between architecture-specific or otherwise ambiguous packages.
        return candidates.singleOrNull() ?: throw IOException("No unique release APK")
    }

    fun download(release: UpdateRelease, destination: File, progress: (Int) -> Unit) {
        read(release.downloadUrl, APK_LIMIT, release.size, 15 * 60_000L) { input, _, deadline ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var count = 0L
                var lastProgress = -1
                while (true) {
                    checkDeadline(deadline)
                    val length = input.read(buffer)
                    if (length < 0) break
                    count += length
                    if (count > release.size || count > APK_LIMIT) throw IOException("APK too large")
                    output.write(buffer, 0, length)
                    val percent = (count * 100 / release.size).toInt()
                    if (percent != lastProgress) {
                        progress(percent)
                        lastProgress = percent
                    }
                }
                if (count != release.size) throw IOException("Truncated APK")
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
            validateUrl(uri, metadata)
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
                    validateUrl(uri, metadata)
                } else {
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
        private val ASSET_HOSTS = setOf(
            "github.com", "release-assets.githubusercontent.com",
            "objects.githubusercontent.com", "github-releases.githubusercontent.com"
        )

        private fun validateUrl(uri: URI, metadata: Boolean) {
            val hosts = if (metadata) setOf("api.github.com") else ASSET_HOSTS
            if (uri.scheme != "https" || uri.host !in hosts || uri.rawUserInfo != null ||
                uri.port !in setOf(-1, 443) || uri.rawFragment != null) {
                throw IOException("Untrusted update URL")
            }
        }
    }
}
