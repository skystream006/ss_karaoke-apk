package com.sskaraoke.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Construct and restore in onCreate, before STARTED, so Android can redeliver external
 * activity results. Only an explicit manual check may download or start installation.
 */
class UpdateManager(private val activity: AppCompatActivity) {
    private enum class State { IDLE, CHECKING, DOWNLOADING, READY, PERMISSION, INSTALLING }

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var state = State.IDLE
    private var destroyed = false
    private var startupChecked = false
    private var operation = 0L
    private var client: UpdateReleaseClient? = null
    private var task: Future<*>? = null
    private var dialog: AlertDialog? = null
    private var pendingDirectory: File? = null
    private var cachePrepared = false

    private val permissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (!destroyed && state == State.PERMISSION) {
            state = State.READY
            if (canInstall()) verifyPendingAndInstall() else {
                clearPending()
                toast(R.string.update_permission_denied)
            }
        }
    }

    private val installerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (!destroyed && state == State.INSTALLING) {
            clearPending()
            toast(R.string.update_install_returned)
        }
    }

    fun startupCheck() {
        if (destroyed || startupChecked) return
        startupChecked = true
        prepareCache()
        if (state == State.IDLE) check(manual = false)
    }

    fun checkForUpdates() {
        if (destroyed) return
        prepareCache()
        when (state) {
            State.IDLE -> check(manual = true)
            State.READY -> requestInstallation()
            else -> toast(R.string.update_busy)
        }
    }

    fun saveState(outState: Bundle) {
        outState.putBoolean(KEY_STARTUP, startupChecked)
        outState.putString(KEY_STATE, state.name)
        pendingDirectory?.name?.takeIf { DIRECTORY_PATTERN.matches(it) }?.let {
            outState.putString(KEY_DIRECTORY, it)
        }
    }

    fun restoreState(savedInstanceState: Bundle?) {
        startupChecked = savedInstanceState?.getBoolean(KEY_STARTUP, false) ?: false
        val restoredState = savedInstanceState?.getString(KEY_STATE)
        val basename = savedInstanceState?.getString(KEY_DIRECTORY)
        if (basename != null && DIRECTORY_PATTERN.matches(basename)) {
            val directory = File(updateRoot(), basename)
            if (isSafeDirectory(directory) && directory.isDirectory) pendingDirectory = directory
        }
        state = when (restoredState) {
            State.PERMISSION.name -> State.PERMISSION
            State.INSTALLING.name -> State.INSTALLING
            State.READY.name -> if (pendingDirectory != null) State.READY else State.IDLE
            else -> State.IDLE
        }
        prepareCache()
        if (state == State.READY || restoredState == State.CHECKING.name ||
            restoredState == State.DOWNLOADING.name) {
            toast(R.string.update_interrupted)
        }
    }

    fun destroy() {
        destroyed = true
        operation++
        client?.cancel()
        task?.cancel(true)
        executor.shutdownNow()
        dismissDialog()
        // External activities can still be reading the APK, including during recreation.
        // The next instance owns the saved basename; stale files are swept on a cold start.
        if (state !in setOf(State.PERMISSION, State.INSTALLING, State.READY)) clearPending()
    }

    private fun check(manual: Boolean) {
        state = State.CHECKING
        val token = ++operation
        val request = UpdateReleaseClient()
        client = request
        if (manual) showProgress(token, checking = true)
        task = executor.submit {
            var directory: File? = null
            var handedOff = false
            try {
                val installed = installedPackage()
                val installedVersion = UpdateVersion.parse(installed.versionName)
                    ?: throw IOException("Invalid installed version")
                val release = request.latest()
                request.checkCancelled()
                if (release.version <= installedVersion) {
                    post(token) {
                        finishWork()
                        if (manual) toast(R.string.update_current, installed.versionName.orEmpty().take(80))
                    }
                } else if (!manual) {
                    post(token) {
                        finishWork()
                        toast(R.string.update_available, release.tag.take(80))
                    }
                } else {
                    post(token) {
                        state = State.DOWNLOADING
                        toast(R.string.update_available, release.tag.take(80))
                        showProgress(token, checking = false)
                    }
                    val asset = request.selectAsset(release)
                    directory = newDirectory()
                    val part = File(directory, "update.apk.part")
                    request.download(asset, part) { percent ->
                        post(token) {
                            dialog?.setMessage(activity.getString(R.string.update_download_progress, percent))
                        }
                    }
                    validateArchive(part, release.version)
                    request.checkCancelled()
                    val apk = File(directory, APK_NAME)
                    if (!part.renameTo(apk)) throw IOException("Cannot finalize APK")
                    val completedDirectory = directory
                    handler.post {
                        if (destroyed || token != operation) {
                            deleteDirectory(completedDirectory)
                        } else {
                            pendingDirectory = completedDirectory
                            state = State.READY
                            client = null
                            task = null
                            dismissDialog()
                            requestInstallation()
                        }
                    }
                    handedOff = true
                }
            } catch (_: UpdateAssetUnavailableException) {
                post(token) {
                    finishWork()
                    if (manual) toast(R.string.update_asset_unavailable)
                }
            } catch (_: UpdateReleaseUnavailableException) {
                post(token) {
                    finishWork()
                    if (manual) toast(R.string.update_no_release)
                }
            } catch (_: Exception) {
                post(token) {
                    finishWork()
                    if (manual) toast(R.string.update_failed)
                }
            } finally {
                if (!handedOff) directory?.let { deleteDirectory(it) }
            }
        }
    }

    private fun requestInstallation() {
        if (destroyed || state != State.READY || dialog != null) return
        // Never launch external UI after Android has saved this Activity's state.
        // A manual retry uses the already downloaded file instead.
        if (activity.isFinishing || activity.supportFragmentManager.isStateSaved ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (pendingApk() == null) {
            clearPending()
            toast(R.string.update_failed)
            return
        }
        if (canInstall()) {
            verifyPendingAndInstall()
            return
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_permission_title)
            .setMessage(R.string.update_permission_message)
            .setPositiveButton(R.string.update_open_settings) { _, _ ->
                dialog = null
                state = State.PERMISSION
                try {
                    permissionLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    )
                } catch (_: ActivityNotFoundException) {
                    unsupported()
                } catch (_: SecurityException) {
                    unsupported()
                }
            }
            .setNegativeButton(R.string.update_cancel) { _, _ ->
                dialog = null
                clearPending()
                toast(R.string.update_cancelled)
            }
            .setOnCancelListener {
                dialog = null
                clearPending()
                toast(R.string.update_cancelled)
            }
            .show()
    }

    private fun verifyPendingAndInstall() {
        val apk = pendingApk()
        if (apk == null) {
            clearPending()
            toast(R.string.update_failed)
            return
        }
        state = State.CHECKING
        val token = ++operation
        task = executor.submit {
            try {
                validateArchive(apk)
                post(token) {
                    task = null
                    launchInstaller(apk)
                }
            } catch (_: Exception) {
                post(token) {
                    clearPending()
                    toast(R.string.update_failed)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun launchInstaller(apk: File) {
        if (activity.isFinishing || activity.supportFragmentManager.isStateSaved ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            state = State.READY
            return
        }
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
            intent.clipData = android.content.ClipData.newRawUri("Update APK", uri)
            state = State.INSTALLING
            installerLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            unsupported()
        } catch (_: SecurityException) {
            unsupported()
        } catch (_: IllegalArgumentException) {
            unsupported()
        }
    }

    private fun canInstall(): Boolean = try {
        activity.packageManager.canRequestPackageInstalls()
    } catch (_: RuntimeException) {
        false
    }

    @Suppress("DEPRECATION")
    private fun installedPackage(): PackageInfo =
        activity.packageManager.getPackageInfo(activity.packageName, signatureFlags())

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }

    @Suppress("DEPRECATION")
    private fun validateArchive(apk: File, releaseVersion: UpdateVersion? = null) {
        if (!apk.isFile || apk.length() !in 1..UpdateReleaseClient.APK_LIMIT) throw IOException("Invalid APK")
        val archive = activity.packageManager.getPackageArchiveInfo(apk.absolutePath, signatureFlags())
            ?: throw IOException("Unreadable APK")
        val installed = installedPackage()
        val archiveVersion = UpdateVersion.parse(archive.versionName) ?: throw IOException("Invalid APK version")
        val installedVersion = UpdateVersion.parse(installed.versionName) ?: throw IOException("Invalid app version")
        val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val installedCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        if (archive.packageName != activity.packageName || archiveVersion <= installedVersion ||
            archiveCode <= installedCode || (releaseVersion != null && archiveVersion.compareTo(releaseVersion) != 0)) {
            throw IOException("APK does not match update")
        }
        val matchingSigners = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val old = installed.signingInfo ?: throw IOException("Missing installed signature")
            val next = archive.signingInfo ?: throw IOException("Missing APK signature")
            val oldSigners = old.apkContentsSigners?.toSet().orEmpty()
            val nextSigners = next.apkContentsSigners?.toSet().orEmpty()
            if (oldSigners.isEmpty() || nextSigners.isEmpty()) false
            else if (old.hasMultipleSigners() || next.hasMultipleSigners()) oldSigners == nextSigners
            else next.signingCertificateHistory?.toSet().orEmpty().containsAll(oldSigners)
        } else {
            val old = installed.signatures?.toSet().orEmpty()
            old.isNotEmpty() && old == archive.signatures?.toSet().orEmpty()
        }
        if (!matchingSigners) throw IOException("APK signing identity differs")
    }

    private fun showProgress(token: Long, checking: Boolean) {
        dismissDialog()
        dialog = AlertDialog.Builder(activity)
            .setTitle(if (checking) R.string.update_checking else R.string.update_download_title)
            .setMessage(if (checking) activity.getString(R.string.update_checking)
                else activity.getString(R.string.update_download_progress, 0))
            .setView(ProgressBar(activity).apply { isIndeterminate = true })
            .setNegativeButton(R.string.update_cancel) { _, _ -> cancelWork(token) }
            .setOnCancelListener { cancelWork(token) }
            .show()
    }

    private fun cancelWork(token: Long) {
        if (token != operation) return
        operation++
        client?.cancel()
        task?.cancel(true)
        finishWork()
        toast(R.string.update_cancelled)
    }

    private fun finishWork() {
        state = State.IDLE
        client = null
        task = null
        dismissDialog()
    }

    private fun clearPending() {
        pendingDirectory?.let { deleteDirectory(it) }
        pendingDirectory = null
        state = State.IDLE
        task = null
    }

    private fun unsupported() {
        clearPending()
        toast(R.string.update_unsupported)
    }

    private fun dismissDialog() {
        dialog?.setOnCancelListener(null)
        dialog?.dismiss()
        dialog = null
    }

    private fun post(token: Long, block: () -> Unit) {
        handler.post { if (!destroyed && operation == token) block() }
    }

    private fun toast(message: Int, vararg args: Any) {
        if (!destroyed) Toast.makeText(activity, activity.getString(message, *args), Toast.LENGTH_LONG).show()
    }

    private fun updateRoot(): File = File(activity.cacheDir, "updates")

    private fun isSafeDirectory(directory: File): Boolean =
        DIRECTORY_PATTERN.matches(directory.name) &&
            directory.canonicalFile == File(updateRoot().canonicalFile, directory.name)

    private fun pendingApk(): File? {
        val directory = pendingDirectory ?: return null
        if (!isSafeDirectory(directory)) return null
        val apk = File(directory, APK_NAME)
        return apk.takeIf { it.canonicalFile == File(directory.canonicalFile, APK_NAME) && it.isFile }
    }

    private fun prepareCache() {
        if (cachePrepared) return
        synchronized(CACHE_LOCK) {
            val root = updateRoot()
            if (!root.isDirectory && !root.mkdirs()) return
            if (!cacheSwept) {
                // Exactly once per process, before any new worker may create a directory.
                // Restored installer/permission state keeps its APK until the result arrives.
                root.listFiles()?.forEach {
                    if (it != pendingDirectory && DIRECTORY_PATTERN.matches(it.name)) deleteDirectory(it)
                }
                cacheSwept = true
            }
            cachePrepared = true
        }
    }

    private fun newDirectory(): File = synchronized(CACHE_LOCK) {
        val root = updateRoot()
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create update cache")
        File(root, UUID.randomUUID().toString()).also {
            if (!it.mkdir()) throw IOException("Cannot create update directory")
        }
    }

    private fun deleteDirectory(directory: File) {
        if (isSafeDirectory(directory)) directory.deleteRecursively()
    }

    companion object {
        private const val APK_NAME = "update.apk"
        private const val KEY_STARTUP = "updater.startup"
        private const val KEY_STATE = "updater.state"
        private const val KEY_DIRECTORY = "updater.directory"
        private val DIRECTORY_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val CACHE_LOCK = Any()
        private var cacheSwept = false
    }
}
