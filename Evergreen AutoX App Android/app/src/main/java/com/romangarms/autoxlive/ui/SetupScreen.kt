package com.romangarms.autoxlive.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.romangarms.autoxlive.AppState
import com.romangarms.autoxlive.AppViewModel
import com.romangarms.autoxlive.BuildConfig
import com.romangarms.autoxlive.update.UpdateState
import com.romangarms.autoxlive.update.Updater
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(vm: AppViewModel, state: AppState, updater: Updater) {
    val colors = EG.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var blockError by remember { mutableStateOf<String?>(null) }
    val hiddenCount = state.settings.hiddenCourseIds.size

    LaunchedEffect(Unit) { vm.loadBlocks() }

    fun open(path: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${BuildConfig.BASE_URL}$path")))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Section("COMING SOON") {
            Note("Signing in, posting times and new-time notifications from Android are coming in a later version. Until then you can follow events, pin friends and browse every leaderboard.")
        }

        if (hiddenCount > 0) {
            Section("LEADERBOARDS") {
                EGButton(
                    text = if (hiddenCount == 1) "UNHIDE 1 LEADERBOARD" else "UNHIDE $hiddenCount LEADERBOARDS",
                    onClick = { vm.unhideAllCourses() },
                )
            }
        }

        if (state.blocks.isNotEmpty()) {
            Section("BLOCKED POSTERS") {
                Note("You don't see leaderboards or times from these posters.")
                state.blocks.forEach { block ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EGText(block.label ?: "Unnamed poster", 12.5f, maxLines = 1, modifier = Modifier.weight(1f))
                        EGChipButton("UNBLOCK", onClick = {
                            scope.launch {
                                blockError = try {
                                    vm.unblock(block.id)
                                    null
                                } catch (e: Exception) {
                                    e.message ?: "Couldn't unblock."
                                }
                            }
                        })
                    }
                }
                blockError?.let { EGText(it, 11f, color = colors.red) }
            }
        }

        Section("UPDATES") {
            val updateState by updater.state.collectAsStateWithLifecycle()
            val status = when (val s = updateState) {
                UpdateState.Checking -> "Checking…"
                UpdateState.UpToDate -> "You have the latest version."
                is UpdateState.Available -> "Version ${s.release.versionName} is available."
                is UpdateState.NeedsPermission -> "Version ${s.release.versionName} is available."
                is UpdateState.Downloading -> "Downloading version ${s.release.versionName}…"
                is UpdateState.Failed -> s.message
                UpdateState.Idle -> null
            }
            status?.let { Note(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EGButton("CHECK FOR UPDATES", onClick = { updater.checkNow() }, enabled = updateState !is UpdateState.Downloading)
                if (updateState is UpdateState.Available || updateState is UpdateState.NeedsPermission ||
                    (updateState as? UpdateState.Failed)?.release != null
                ) {
                    EGButton("INSTALL", onClick = { updater.install() }, kind = EGButtonKind.PRIMARY)
                }
            }
        }

        Section("ABOUT") {
            Note("An independent app. Not affiliated with any event organizer or timing provider.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EGButton("SUPPORT", onClick = { open("/support") })
                EGButton("PRIVACY POLICY", onClick = { open("/privacy") })
            }
            EGText(
                "AutoX Live ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                11f,
                color = colors.gray,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EGText(title, 12f, weight = FontWeight.Black, tracking = 1.1f, color = EG.colors.gray)
        content()
    }
}

@Composable
private fun Note(text: String) {
    EGText(text, 11f, color = EG.colors.grayDark)
}

@Composable
fun UpdateBanner(updater: Updater) {
    val updateState by updater.state.collectAsStateWithLifecycle()
    val dismissed by updater.dismissed.collectAsStateWithLifecycle()
    val colors = EG.colors
    val s = updateState
    val release = when (s) {
        is UpdateState.Available -> s.release
        is UpdateState.NeedsPermission -> s.release
        is UpdateState.Downloading -> s.release
        is UpdateState.Failed -> s.release
        else -> null
    } ?: return
    if (dismissed) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.pinnedBg)
            .border(1.dp, colors.hairline, RectangleShape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EGText("Version ${release.versionName} is available", 12.5f, weight = FontWeight.Bold)
        when (s) {
            is UpdateState.NeedsPermission -> {
                EGText(
                    "Android asks once whether AutoX Live may install updates. Turn on \"Allow from this source\", then come back and tap Install.",
                    11f,
                    color = colors.grayDark,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EGButton("OPEN SETTINGS", onClick = { updater.openInstallPermissionSettings() }, kind = EGButtonKind.PRIMARY)
                    EGButton("INSTALL", onClick = { updater.install() })
                }
            }
            is UpdateState.Downloading -> {
                val progress = s.progress
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = colors.red)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = colors.red)
                }
            }
            is UpdateState.Failed -> {
                EGText(s.message, 11f, color = colors.red)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EGButton("TRY AGAIN", onClick = { updater.install() }, kind = EGButtonKind.PRIMARY)
                    EGButton("LATER", onClick = { updater.dismiss() })
                }
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EGButton("INSTALL", onClick = { updater.install() }, kind = EGButtonKind.PRIMARY)
                EGButton("LATER", onClick = { updater.dismiss() })
            }
        }
    }
}
