package com.romangarms.autoxlive.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.romangarms.autoxlive.AppState
import com.romangarms.autoxlive.AppViewModel
import com.romangarms.autoxlive.data.LBCourse
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardsScreen(vm: AppViewModel, state: AppState, openLeaderboard: (Int) -> Unit, openAcceleration: () -> Unit) {
    if (!state.settings.acceptedGuidelines) {
        LBGuidelines(onAgree = vm::acceptGuidelines)
        return
    }
    var joining by rememberSaveable { mutableStateOf(false) }
    var refreshing by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val hidden = state.settings.hiddenCourseIds
    val boards = state.leaderboardCourses.filter { it.id !in hidden }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            scope.launch {
                vm.refreshLeaderboardEvents()
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 12.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LBComingSoon(Modifier.weight(1f))
                    EGChipButton("JOIN WITH CODE", onClick = { joining = true })
                }
            }
            item {
                LBNavRow("Acceleration", "0–60, 0–30 and drag strip times", openAcceleration)
            }
            if (boards.isEmpty()) {
                item {
                    EGText(
                        if (state.events.isEmpty() && state.errorMessage != null) {
                            state.errorMessage
                        } else {
                            "No leaderboards yet."
                        },
                        12f,
                        color = EG.colors.grayDark,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            items(boards, key = { it.id }) { course ->
                LBNavRow(course.name, boardSubtitle(course)) { openLeaderboard(course.id) }
            }
            item { Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }

    if (joining) {
        LBJoinSheet(vm, onDismiss = { joining = false })
    }
}

private fun boardSubtitle(course: LBCourse): String {
    val parts = listOfNotNull(
        course.distanceMiles?.let { String.format(Locale.US, "%.2f mi course", it) },
        course.createdBy?.let { "by $it" },
    )
    return if (parts.isEmpty()) "Community leaderboard" else parts.joinToString(" · ")
}
