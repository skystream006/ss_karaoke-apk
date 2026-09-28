package com.sskaraoke.app

import java.io.IOException
import java.net.URI

/**
 * Dependency-free checks: compile this file with UpdateVersion.kt and UpdateUrlPolicy.kt,
 * then run its main entry point. Not JUnit tests; Gradle's test task does not discover these.
 */
fun main() {
    var assertions = 0
    fun expect(condition: Boolean) {
        check(condition)
        assertions++
    }
    listOf(null, "", "v", "V1.2", ".1", "1.", "1..2", "1-beta", "1+2", " 1", "1\n",
        "-1", "1/2", "１.２").forEach { expect(UpdateVersion.parse(it) == null) }
    listOf("1" to "1.0.0", "v01.002.000" to "1.2", "00" to "0", "1.00.10" to "1.0.010")
        .forEach { (a, b) -> expect(UpdateVersion.parse(a)!!.compareTo(UpdateVersion.parse(b)!!) == 0) }
    listOf(
        "1.00.10" to "1.0.9", "1.2.1" to "1.2", "2" to "1.999999999999999999999999",
        "1.${"9".repeat(10_000)}" to "1.9223372036854775807",
        "1.${"0.".repeat(20_000)}1" to "1", "v0002" to "1"
    ).forEach { (a, b) ->
        expect(UpdateVersion.parse(a)!! > UpdateVersion.parse(b)!!)
        expect(UpdateVersion.parse(b)!! < UpdateVersion.parse(a)!!)
    }

    fun allowed(url: String, metadata: Boolean): Boolean = try {
        UpdateUrlPolicy.validate(URI(url), metadata)
        true
    } catch (_: IOException) {
        false
    }
    listOf(
        "https://github.com/path", "https://github.com:443/path",
        "https://release-assets.githubusercontent.com/file?signature=value",
        "https://objects.githubusercontent.com/file", "https://github-releases.githubusercontent.com/file"
    ).forEach { expect(allowed(it, false)) }
    expect(allowed("https://api.github.com/repos/skystream006/ss_karaoke-apk/releases/latest", true))
    listOf(
        "http://github.com/path", "https://github.com:444/path", "https://user@github.com/path",
        "https://github.com.evil.example/path", "https://evil.example/github.com",
        "https://github.com./path", "https://github.com/path#fragment",
        "https://api.github.com/path", "https://raw.githubusercontent.com/path",
        "file:///github.com/path", "https://127.0.0.1/path", "https://[::1]/path"
    ).forEach { expect(!allowed(it, false)) }
    expect(!allowed("https://github.com/path", true))
    expect(!allowed("https://api.github.com:444/path", true))
    expect(!allowed("https://user@api.github.com/path", true))
    val initial = URI("https://github.com/releases/download/tag/app.apk")
    expect(!allowed(initial.resolve("//evil.example/app.apk").toString(), false))
    expect(!allowed(initial.resolve("http://github.com/app.apk").toString(), false))
    expect(allowed(initial.resolve("/other.apk").toString(), false))
    println("Passed $assertions update-policy assertions.")
}
