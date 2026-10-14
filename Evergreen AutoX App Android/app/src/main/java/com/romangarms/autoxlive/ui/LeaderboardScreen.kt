package com.romangarms.autoxlive.ui

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.romangarms.autoxlive.AppState
import com.romangarms.autoxlive.AppViewModel
import com.romangarms.autoxlive.data.Driver
import com.romangarms.autoxlive.data.LBCourse
import com.romangarms.autoxlive.data.LBEntry
import com.romangarms.autoxlive.data.LBReportTarget
import com.romangarms.autoxlive.data.LBReportType
import com.romangarms.autoxlive.data.LBRun
import com.romangarms.autoxlive.data.LapTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class LBRowLayout(val wide: Boolean, val showsRaw: Boolean, val largeText: Boolean) {
    val showsHP: Boolean get() = wide || !largeText

    companion object {
        const val RANK = 30f
        const val TIME = 70f
        const val HP = 42f
        const val SPEED = 44f
        const val DRIVER = 72f
        const val DATE = 62f
        const val CONDITIONS = 44f
        const val CHEVRON = 12f
    }
}

private data class LBReportRequest(val target: LBReportTarget, val subject: String)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LeaderboardScreen(vm: AppViewModel, state: AppState, courseId: Int, openDriver: (Int) -> Unit) {
    val detail = state.leaderboardDetail?.takeIf { it.course.id == courseId }
    val course = detail?.course ?: state.leaderboardCourses.firstOrNull { it.id == courseId }
    val entries = if (detail != null) state.leaderboardEntries else emptyList()
    var report by remember { mutableStateOf<LBReportRequest?>(null) }
    var confirmBlock by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val (notice, showNotice) = rememberNotice()
    val scope = rememberCoroutineScope()
    val largeText = EG.largeText

    LaunchedEffect(courseId) {
        if (state.openCourseId != courseId) vm.openLeaderboard(courseId)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = LBRowLayout(
            wide = maxWidth.value >= LB_WIDE_THRESHOLD,
            showsRaw = entries.any { it.best.legacy },
            largeText = largeText,
        )
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp).padding(top = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    courseSubtitle(course)?.let { EGText(it, 11f, color = EG.colors.grayDark) }
                    course?.description?.let { EGText(it, 12f) }
                    if (course != null && course.unlisted && course.joinCode != null) {
                        JoinCodeRow(course.joinCode)
                    }
                    if (course != null) {
                        CourseActions(
                            course = course,
                            onReport = { report = LBReportRequest(LBReportTarget(LBReportType.COURSE, course.id), course.name) },
                            onBlock = { confirmBlock = true },
                            onLeave = {
                                scope.launch {
                                    try {
                                        vm.leaveCourse(course.id)
                                    } catch (e: Exception) {
                                        actionError = e.message
                                    }
                                }
                            },
                            onHide = { vm.hideCourse(course.id) },
                        )
                    }
                    LBNoticeText(notice)
                    LBErrorText(actionError)
                }
            }
            when {
                detail == null -> item {
                    val error = state.leaderboardError
                    if (error != null) LBStatusMessage(error) { vm.openLeaderboard(courseId) } else LBProgress()
                }
                entries.isEmpty() -> item { LBStatusMessage("No times yet.") }
                else -> {
                    stickyHeader { LBColumnHeader(layout, Modifier.background(EG.colors.bg).padding(top = 10.dp)) }
                    items(entries, key = { it.id }) { entry ->
                        LBEntryRow(entry, layout) { openDriver(entry.id) }
                    }
                }
            }
            item { LBSpacer(24) }
        }
    }

    report?.let { request ->
        LBReportSheet(vm, request.target, request.subject, onDismiss = { report = null }) {
            showNotice("Report sent. Thanks.")
        }
    }
    if (confirmBlock) {
        LBConfirmDialog(
            title = "Block this leaderboard's creator?",
            message = LB_BLOCK_MESSAGE,
            confirm = "Block Creator",
            onConfirm = {
                scope.launch {
                    try {
                        vm.blockPoster(LBReportTarget(LBReportType.COURSE, courseId), courseId)
                    } catch (e: Exception) {
                        actionError = e.message
                    }
                }
            },
            onDismiss = { confirmBlock = false },
        )
    }
}

@Composable
private fun JoinCodeRow(code: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EGText("Unlisted · join code $code", 11f, weight = FontWeight.SemiBold, color = EG.colors.grayDark)
        EGChipButton(if (copied) "COPIED" else "COPY", onClick = {
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Join code", code))) }
            copied = true
        })
    }
}

@Composable
private fun CourseActions(
    course: LBCourse,
    onReport: () -> Unit,
    onBlock: () -> Unit,
    onLeave: () -> Unit,
    onHide: () -> Unit,
) {
    val items = buildList {
        add(LBMenuItem("Report leaderboard", action = onReport))
        if (course.hasOwner && !course.isOwner) add(LBMenuItem("Block creator", destructive = true, action = onBlock))
        if (course.isMember) {
            add(LBMenuItem("Leave leaderboard", action = onLeave))
        } else {
            add(LBMenuItem("Hide leaderboard", action = onHide))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LBComingSoon(Modifier.weight(1f))
        LBMenuButton(items)
    }
}

private fun courseSubtitle(course: LBCourse?): String? {
    val parts = mutableListOf<String>()
    course?.distanceMiles?.let { parts += String.format(Locale.US, "%.2f mile course", it) }
    if (course?.legacyDistanceMiles != null) parts += "legacy runs scaled to the current course length"
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

@Composable
private fun LBColumnHeader(layout: LBRowLayout, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EGColumnLabel("POS", Modifier.egWidth(LBRowLayout.RANK))
        Box(Modifier.egWidth(LBRowLayout.TIME), contentAlignment = Alignment.CenterEnd) {
            EGColumnLabel(if (layout.wide && layout.showsRaw) "ADJ. TIME" else "TIME")
        }
        if (layout.wide && layout.showsRaw) {
            Box(Modifier.egWidth(LBRowLayout.TIME), contentAlignment = Alignment.CenterEnd) { EGColumnLabel("RAW TIME") }
        }
        if (layout.showsHP) {
            Box(Modifier.egWidth(LBRowLayout.HP), contentAlignment = Alignment.Center) { EGColumnLabel("HP") }
        }
        EGColumnLabel(if (layout.wide) "VEHICLE" else "VEHICLE · DRIVER", Modifier.weight(1f))
        if (layout.wide) {
            Box(Modifier.egWidth(LBRowLayout.SPEED), contentAlignment = Alignment.CenterEnd) { EGColumnLabel("AVG") }
            Box(Modifier.egWidth(LBRowLayout.SPEED), contentAlignment = Alignment.CenterEnd) { EGColumnLabel("TOP") }
            EGColumnLabel("DRIVER", Modifier.egWidth(LBRowLayout.DRIVER))
            Box(Modifier.egWidth(LBRowLayout.DATE), contentAlignment = Alignment.CenterEnd) { EGColumnLabel("DATE") }
        }
        Box(Modifier.egWidth(LBRowLayout.CONDITIONS), contentAlignment = Alignment.Center) { EGColumnLabel("COND") }
        Box(Modifier.egWidth(LBRowLayout.CHEVRON))
    }
}

private val shortDateFormat = DateTimeFormatter.ofPattern("M/d/yy", Locale.US)

private fun shortDate(run: LBRun): String? =
    run.runDate?.let { runCatching { LocalDate.parse(it.take(10)).format(shortDateFormat) }.getOrNull() }

@Composable
private fun LBEntryRow(entry: LBEntry, layout: LBRowLayout, onTap: (() -> Unit)?) {
    val best = entry.best
    val colors = EG.colors
    val date = shortDate(best)
    val subtitle = buildList {
        if (!layout.wide) {
            if (best.vehicle != null) add(best.driver)
            date?.let { add(it) }
        }
        if (entry.runs.size > 1) add("${entry.runs.size} runs")
    }.takeIf { it.isNotEmpty() }?.joinToString(" · ")

    Column(Modifier.fillMaxWidth().then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)) {
        LBHairline()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EGText("P${entry.id}", 14f, weight = FontWeight.Black, color = lbRankColor(entry.id), maxLines = 1, modifier = Modifier.egWidth(LBRowLayout.RANK))
            Column(Modifier.egWidth(LBRowLayout.TIME), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                EGText(best.adjustedTime, 14f, weight = FontWeight.Black, maxLines = 1)
                if (best.legacy && !layout.wide) {
                    EGText(
                        best.time,
                        10f,
                        weight = FontWeight.SemiBold,
                        color = LBTint.ink,
                        maxLines = 1,
                        modifier = Modifier.background(LBTint.LIGHT_GREEN.color).padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            if (layout.wide && layout.showsRaw) {
                LBTintCell(if (best.legacy) best.time else "—", if (best.legacy) LBTint.LIGHT_GREEN else null, Modifier.egWidth(LBRowLayout.TIME))
            }
            if (layout.showsHP) {
                LBTintCell(best.hp?.toString() ?: "—", LBTint.hp(best.hp), Modifier.egWidth(LBRowLayout.HP))
            }
            Column(Modifier.weight(1f).padding(vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                EGText(best.vehicle ?: best.driver, 13f, weight = FontWeight.Black, maxLines = 2)
                if (subtitle != null) EGText(subtitle, 10f, color = colors.gray, maxLines = 1)
            }
            if (layout.wide) {
                Box(Modifier.egWidth(LBRowLayout.SPEED), contentAlignment = Alignment.CenterEnd) {
                    EGText(lbOneDecimal(best.avgSpeedMph), 12f, color = colors.grayDark, maxLines = 1)
                }
                Box(Modifier.egWidth(LBRowLayout.SPEED), contentAlignment = Alignment.CenterEnd) {
                    EGText(lbOneDecimal(best.topSpeedMph), 12f, color = colors.grayDark, maxLines = 1)
                }
                EGText(best.driver, 12f, weight = FontWeight.Black, maxLines = 1, modifier = Modifier.egWidth(LBRowLayout.DRIVER))
                Box(Modifier.egWidth(LBRowLayout.DATE), contentAlignment = Alignment.CenterEnd) {
                    EGText(date ?: "—", 12f, color = colors.grayDark, maxLines = 1)
                }
            }
            LBTintCell(best.conditions ?: "—", LBTint.conditions(best.conditions), Modifier.egWidth(LBRowLayout.CONDITIONS))
            if (onTap != null) {
                Icon(
                    Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = LBChevronColor,
                    modifier = Modifier.egWidth(LBRowLayout.CHEVRON).size(egDp(14f)),
                )
            } else {
                Box(Modifier.egWidth(LBRowLayout.CHEVRON))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LeaderboardDriverScreen(
    vm: AppViewModel,
    state: AppState,
    courseId: Int,
    position: Int,
    openDriver: ((Int) -> Unit)? = null,
) {
    val entry = state.leaderboardEntry(position)?.takeIf { state.leaderboardDetail?.course?.id == courseId }
    if (entry == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (state.leaderboardDetail == null && state.leaderboardError == null) {
                LBProgress()
            } else {
                LBStatusMessage("This driver is no longer on the leaderboard.")
            }
        }
        return
    }
    val driver = entry.driver
    val course = state.leaderboardDetail?.course
    val lbRuns = entry.runs
    val byId = lbRuns.associateBy { it.id }
    val otherCars = state.leaderboardEntries.filter { it.best.driver == driver.name && it.id != entry.id }
    val avgSpeeds = lbRuns.mapNotNull { it.avgSpeedMph }
    val avgSpeed = avgSpeeds.takeIf { it.isNotEmpty() }?.average()
    val topSpeed = lbRuns.mapNotNull { it.topSpeedMph }.maxOrNull()
    val colors = EG.colors
    var reportRun by remember { mutableStateOf<LBRun?>(null) }
    var blockRun by remember { mutableStateOf<LBRun?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val (notice, showNotice) = rememberNotice()
    val scope = rememberCoroutineScope()
    val largeText = EG.largeText

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Column(
                modifier = Modifier.padding(top = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    EGText(driver.name, 24f, weight = FontWeight.Black, maxLines = 2)
                    course?.let { EGText(it.name, 12f, color = colors.grayDark) }
                    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        vehicleTags(lbRuns).forEach { EGOutlineTag(it.uppercase()) }
                    }
                }
                EGStatsGrid(
                    listOf(
                        listOf(
                            EGStat("BEST", driver.bestString, colors.red),
                            EGStat("AVERAGE", driver.average?.let(LapTime::format) ?: "—", colors.ink),
                            EGStat("SPREAD", driver.spread?.let { String.format(Locale.US, "%.2fs", it) } ?: "—", colors.ink),
                        ),
                        listOf(
                            EGStat("AVG MPH", lbOneDecimal(avgSpeed), colors.ink),
                            EGStat("TOP MPH", lbOneDecimal(topSpeed), colors.ink),
                            EGStat("RUNS", driver.runs.size.toString(), colors.ink),
                        ),
                    ),
                )
                LBNoticeText(notice)
                LBErrorText(actionError)
            }
        }
        driverRunsTable(
            driver,
            runDetail = { run -> byId[run.lapNumber]?.let(::runDetail) },
            menu = { run ->
                val lbRun = byId[run.lapNumber]
                buildList {
                    if (lbRun != null) {
                        add(RunMenuItem("Report run") { reportRun = lbRun })
                        if (lbRun.canBlockPoster) add(RunMenuItem("Block poster", destructive = true) { blockRun = lbRun })
                    }
                }
            },
        )
        if (otherCars.isNotEmpty()) {
            item {
                EGColumnLabel("OTHER CARS", Modifier.padding(top = 20.dp, bottom = 6.dp))
            }
            val layout = LBRowLayout(wide = false, showsRaw = false, largeText = largeText)
            items(otherCars, key = { "other-${it.id}" }) { other ->
                Box(Modifier.lbFullBleed(16.dp)) {
                    LBEntryRow(other, layout, onTap = openDriver?.let { open -> { open(other.id) } })
                }
            }
        }
        item { LBSpacer(24) }
    }

    reportRun?.let { run ->
        LBReportSheet(
            vm,
            LBReportTarget(LBReportType.RUN, run.id),
            "${driver.name}'s run on ${course?.name ?: "this leaderboard"}",
            onDismiss = { reportRun = null },
        ) { showNotice("Report sent. Thanks.") }
    }
    blockRun?.let { run ->
        LBConfirmDialog(
            title = "Block whoever posted this run?",
            message = LB_BLOCK_MESSAGE,
            confirm = "Block Poster",
            onConfirm = {
                scope.launch {
                    try {
                        vm.blockPoster(LBReportTarget(LBReportType.RUN, run.id), courseId)
                    } catch (e: Exception) {
                        actionError = e.message
                    }
                }
            },
            onDismiss = { blockRun = null },
        )
    }
}

private fun runDetail(run: LBRun): String? {
    val parts = listOfNotNull(run.vehicle, run.conditions, AppState.eventDate(run.runDate)).toMutableList()
    if (run.legacy) parts += "Legacy · raw ${run.time}"
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

private fun vehicleTags(runs: List<LBRun>): List<String> =
    runs.mapNotNull { run -> run.vehicle?.let { vehicle -> run.hp?.let { "$vehicle · $it HP" } ?: vehicle } }.distinct()
