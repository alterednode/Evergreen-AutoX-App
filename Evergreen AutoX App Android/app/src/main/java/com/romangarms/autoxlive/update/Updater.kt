package com.romangarms.autoxlive.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.edit
import com.romangarms.autoxlive.BuildConfig
import com.romangarms.autoxlive.data.AndroidRelease
import com.romangarms.autoxlive.data.ApiClient
import com.romangarms.autoxlive.data.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AndroidRelease) : UpdateState
    data class NeedsPermission(val release: AndroidRelease) : UpdateState
    data class Downloading(val release: AndroidRelease, val progress: Float?) : UpdateState
    data class Failed(val release: AndroidRelease?, val message: String) : UpdateState
}

class Updater(private val context: Context, private val client: ApiClient, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _dismissed = MutableStateFlow(false)
    val dismissed: StateFlow<Boolean> = _dismissed.asStateFlow()

    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private var job: Job? = null

    // The APK is fetched without the device token, matching the update check.
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val updatesDir get() = File(context.cacheDir, "updates")

    fun checkIfDue() {
        val current = _state.value
        if (current is UpdateState.NeedsPermission && canInstall()) {
            _state.value = UpdateState.Available(current.release)
        }
        val last = prefs.getLong(LAST_CHECK, 0)
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MILLIS) return
        check(manual = false)
    }

    fun checkNow() = check(manual = true)

    fun dismiss() {
        _dismissed.value = true
    }

    private fun check(manual: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            if (manual) _state.value = UpdateState.Checking
            try {
                val release = client.androidRelease()
                prefs.edit { putLong(LAST_CHECK, System.currentTimeMillis()) }
                if (release.versionCode > BuildConfig.VERSION_CODE) {
                    val previous = (_state.value as? UpdateState.Available)?.release
                    if (manual || previous?.versionCode != release.versionCode) _dismissed.value = false
                    _state.value = UpdateState.Available(release)
                } else {
                    _state.value = if (manual) UpdateState.UpToDate else UpdateState.Idle
                }
            } catch (e: ApiException) {
                // A server with no release yet answers 404, which is not an error worth showing.
                _state.value = when {
                    !manual -> UpdateState.Idle
                    e.status == 404 -> UpdateState.UpToDate
                    else -> UpdateState.Failed(null, e.message ?: "Couldn't check for updates.")
                }
            }
        }
    }

    fun install() {
        val release = when (val s = _state.value) {
            is UpdateState.Available -> s.release
            is UpdateState.NeedsPermission -> s.release
            is UpdateState.Failed -> s.release ?: return
            else -> return
        }
        _dismissed.value = false
        if (!canInstall()) {
            _state.value = UpdateState.NeedsPermission(release)
            return
        }
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Downloading(release, null)
            try {
                val apk = download(release)
                launchInstaller(apk)
                _state.value = UpdateState.Available(release)
            } catch (e: Exception) {
                updatesDir.deleteRecursively()
                _state.value = UpdateState.Failed(release, e.message ?: "The download failed.")
            }
        }
    }

    fun openInstallPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    private suspend fun download(release: AndroidRelease): File = withContext(Dispatchers.IO) {
        updatesDir.deleteRecursively()
        updatesDir.mkdirs()
        val file = File(updatesDir, "AutoX-Live-${release.versionName}.apk")
        val request = Request.Builder().url(client.resolve(release.downloadUrl)).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException("The download failed (${response.code}).", response.code)
            val body = response.body
            val total = body.contentLength().takeIf { it > 0 } ?: release.size
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            var lastReported = -1
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        read += count
                        if (total != null) {
                            val percent = (read * 100 / total).toInt()
                            if (percent != lastReported) {
                                lastReported = percent
                                _state.value = UpdateState.Downloading(release, percent / 100f)
                            }
                        }
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (!sha.equals(release.sha256, ignoreCase = true)) {
                file.delete()
                throw ApiException("The download didn't match the published checksum, so it wasn't installed.")
            }
        }
        file
    }

    private fun launchInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private companion object {
        const val LAST_CHECK = "lastCheck"
        const val CHECK_INTERVAL_MILLIS = 4 * 60 * 60 * 1000L
    }
}
