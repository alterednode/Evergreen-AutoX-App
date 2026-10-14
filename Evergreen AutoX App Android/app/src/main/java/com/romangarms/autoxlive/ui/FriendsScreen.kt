package com.romangarms.autoxlive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.romangarms.autoxlive.AppState
import com.romangarms.autoxlive.AppViewModel
import com.romangarms.autoxlive.data.Driver
import com.romangarms.autoxlive.data.LapTime
import kotlinx.coroutines.launch

private fun tabular(size: Float, weight: FontWeight = FontWeight.Normal): TextStyle =
    egStyle(size, weight).copy(fontFeatureSettings = "tnum")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(
    vm: AppViewModel,
    state: AppState,
    openDriver: (String) -> Unit,
    openCompare: (String, String) -> Unit,
) {
    val colors = EG.colors
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingReset by rememberSaveable { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val friends = state.friends

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                vm.loadSessionData()
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            EGText("Pinned this session. Tap two to compare, or open anyone's runs.", 11.5f, color = colors.grayDark)

            if (friends.isEmpty()) {
                EGText(
                    "No one pinned yet — star drivers on the Live tab.",
                    12f,
                    color = colors.gray,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else if (state.me == null) {
                EGText(
                    "Pick This is me from a driver's ⋮ menu to see everyone's gap to you.",
                    11.5f,
                    color = colors.grayDark,
                )
            }

            friends.forEach { friend ->
                FriendRow(
                    vm = vm,
                    state = state,
                    friend = friend,
                    onOpen = { openDriver(friend.startNumber) },
                    onRename = { renaming = friend.startNumber },
                )
            }

            val selection = state.compareSelection
            if (selection.size == 2) {
                val a = state.driver(selection[0])
                val b = state.driver(selection[1])
                if (a != null && b != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.red)
                            .clickable { openCompare(a.startNumber, b.startNumber) }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EGText(
                            "COMPARE SELECTED",
                            12f,
                            weight = FontWeight.Black,
                            tracking = 0.8f,
                            color = colors.onRed,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = colors.onRed,
                            modifier = Modifier.size(egDp(16f)),
                        )
                    }
                }
            }

            EGButton(
                "RESET PINS & NICKNAMES",
                onClick = { confirmingReset = true },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    renaming?.let { number ->
        val driver = state.driver(number)
        var text by rememberSaveable(number) { mutableStateOf(state.nicknames[number] ?: "") }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Nickname") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    driver?.let {
                        Text("Shown instead of ${it.name}. Leave it empty to use their real name.")
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        placeholder = { driver?.let { Text(it.name) } },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setNickname(text, number)
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            title = { Text("Reset pins and nicknames?") },
            text = { Text("This clears every pinned driver, nickname and \"this is me\" choice, for every event.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.resetPersonalization()
                    confirmingReset = false
                }) { Text("Reset", color = colors.red) }
            },
            dismissButton = { TextButton(onClick = { confirmingReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FriendRow(
    vm: AppViewModel,
    state: AppState,
    friend: Driver,
    onOpen: () -> Unit,
    onRename: () -> Unit,
) {
    val colors = EG.colors
    val selected = friend.startNumber in state.compareSelection
    val isMe = friend.startNumber == state.meNumber
    val gap = state.gapToMe(friend)
    val nickname = state.nicknames[friend.startNumber]
    val subtitle = listOfNotNull(if (nickname != null) friend.name else null, runsText(friend)).joinToString(" · ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.pinnedBg else colors.card)
            .border(2.dp, if (selected) colors.red else colors.divider, RectangleShape)
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clickable { vm.toggleCompareSelection(friend.startNumber) }
                .padding(8.dp),
        ) {
            EGCheckbox(checked = selected, size = 26f)
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpen)
                .padding(start = 2.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                EGText(state.displayName(friend) + if (isMe) "  (me)" else "", 13f, weight = FontWeight.Black, maxLines = 1)
                if (subtitle.isNotEmpty()) EGText(subtitle, 10f, color = colors.gray, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp, end = 6.dp)) {
                Text("P${friend.position}", style = tabular(14f, FontWeight.Black), color = colors.ink, maxLines = 1)
                classText(friend)?.let {
                    Text(it, style = tabular(10f, FontWeight.Black), color = colors.grayDark, maxLines = 1)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(friend.bestString, style = tabular(14f, FontWeight.Black), color = colors.ink, maxLines = 1)
                Text(
                    gapText(state, friend, isMe, gap),
                    style = tabular(10f),
                    color = if (gap != null && gap < 0) colors.darkRed else colors.gray,
                    maxLines = 1,
                )
            }
        }
        FriendMenu(vm, state, friend, isMe, onRename)
    }
}

@Composable
private fun FriendMenu(vm: AppViewModel, state: AppState, friend: Driver, isMe: Boolean, onRename: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Options for ${state.displayName(friend)}", tint = EG.colors.ink)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (isMe) "This isn't me" else "This is me") },
                onClick = {
                    open = false
                    vm.setMe(if (isMe) null else friend.startNumber)
                },
            )
            DropdownMenuItem(
                text = { Text("Rename…") },
                onClick = {
                    open = false
                    onRename()
                },
            )
            // You are listed whether or not you starred yourself.
            if (friend.startNumber in state.pins) {
                DropdownMenuItem(
                    text = { Text("Unpin") },
                    onClick = {
                        open = false
                        vm.togglePin(friend.startNumber)
                    },
                )
            }
        }
    }
}

private fun classText(friend: Driver): String? {
    val carClass = friend.carClass ?: return null
    val position = friend.positionInClass ?: return carClass
    return "P$position $carClass"
}

private fun runsText(friend: Driver): String? {
    if (friend.runs.isEmpty()) return null
    val count = if (friend.runs.size == 1) "1 run" else "${friend.runs.size} runs"
    val average = friend.average
    if (average == null || friend.runs.size <= 1) return count
    return "$count · avg ${LapTime.format(average)}"
}

private fun gapText(state: AppState, friend: Driver, isMe: Boolean, gap: Double?): String {
    if (gap != null) return LapTime.gap(gap) + " vs me"
    if (isMe) return "baseline"
    if (state.me != null) return "—"
    val best = friend.best ?: return "—"
    val fastest = state.drivers.mapNotNull { it.best }.minOrNull() ?: return "—"
    return if (best == fastest) "fastest" else LapTime.gap(best - fastest) + " vs P1"
}
