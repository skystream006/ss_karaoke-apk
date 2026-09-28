package com.sskaraoke.app

import java.io.IOException
import java.net.URI

/** The same host policy applies before opening the initial URL and every redirect. */
internal object UpdateUrlPolicy {
    private val ASSET_HOSTS = setOf(
        "github.com", "release-assets.githubusercontent.com",
        "objects.githubusercontent.com", "github-releases.githubusercontent.com"
    )

    fun validate(uri: URI, metadata: Boolean) {
        val hosts = if (metadata) setOf("api.github.com") else ASSET_HOSTS
        if (uri.scheme != "https" || uri.host !in hosts || uri.rawUserInfo != null ||
            uri.port !in setOf(-1, 443) || uri.rawFragment != null) {
            throw IOException("Untrusted update URL")
        }
    }
}
