package com.romangarms.autoxlive.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.romangarms.autoxlive.AppState
import com.romangarms.autoxlive.AppViewModel
import com.romangarms.autoxlive.data.AccelEntry
import com.romangarms.autoxlive.data.LBReportTarget
import com.romangarms.autoxlive.data.LBReportType
import kotlinx.coroutines.launch
import java.util.Locale

private data class AccelRowLayout(val wide: Boolean, val largeText: Boolean) {
    val showsHP: Boolean get() = wide || !largeText

    companion object {
        const val RANK = 30f
        const val TIME = 46f
        const val HP = 42f
        const val STRIP = 56f
        const val DRIVER = 60f
        const val WEIGHT = 56f
        const val CHEVRON = 12f
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AccelerationScreen(vm: AppViewModel, state: AppState, openEntry: (Int) -> Unit) {
    val largeText = EG.largeText
    LaunchedEffect(Unit) {
        if (state.accelerationEntries == null) vm.loadAcceleration()
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = AccelRowLayout(wide = maxWidth.value >= LB_WIDE_THRESHOLD, largeText = largeText)
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp).padding(top = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    EGText(
                        "Ranked by 0–60 time. Times are from drag strips and closed courses. Never test on public roads.",
                        11f,
                        color = EG.colors.grayDark,
                    )
                    LBComingSoon()
                }
            }
            val entries = state.accelerationEntries
            when {
                entries != null && entries.isEmpty() -> item { LBStatusMessage("No times yet.") }
                entries != null -> {
                    stickyHeader { AccelColumnHeader(layout, Modifier.background(EG.colors.bg).padding(top = 10.dp)) }
                    itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
                        AccelRow(entry, index + 1, layout) { openEntry(entry.id) }
                    }
                }
                state.accelerationError != null -> item { LBStatusMessage(state.accelerationError) { vm.loadAcceleration() } }
                else -> item { LBProgress() }
            }
            item { LBSpacer(24) }
        }
    }
}

@Composable
private fun RightCell(width: Float, content: @Composable () -> Unit) {
    Box(Modifier.egWidth(width), contentAlignment = Alignment.CenterEnd) { content() }
}

@Composable
private fun AccelColumnHeader(layout: AccelRowLayout, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EGColumnLabel("POS", Modifier.egWidth(AccelRowLayout.RANK))
        RightCell(AccelRowLayout.TIME) { EGColumnLabel("0–60") }
        RightCell(AccelRowLayout.TIME) { EGColumnLabel("0–30") }
        if (layout.showsHP) {
            Box(Modifier.egWidth(AccelRowLayout.HP), contentAlignment = Alignment.Center) { EGColumnLabel("HP") }
        }
        EGColumnLabel(if (layout.wide) "VEHICLE" else "VEHICLE · DRIVER", Modifier.weight(1f))
        if (layout.wide) {
            RightCell(AccelRowLayout.STRIP) { EGColumnLabel("¼ MI") }
            RightCell(AccelRowLayout.STRIP) { EGColumnLabel("¼ MPH") }
            RightCell(AccelRowLayout.STRIP) { EGColumnLabel("⅛ MI") }
            RightCell(AccelRowLayout.STRIP) { EGColumnLabel("⅛ MPH") }
            EGColumnLabel("DRIVER", Modifier.egWidth(AccelRowLayout.DRIVER))
            RightCell(AccelRowLayout.WEIGHT) { EGColumnLabel("WEIGHT") }
        }
        Box(Modifier.egWidth(AccelRowLayout.CHEVRON))
    }
}

@Composable
private fun AccelRow(entry: AccelEntry, rank: Int, layout: AccelRowLayout, onTap: () -> Unit) {
    val colors = EG.colors
    val subtitle = listOfNotNull(
        entry.driver,
        entry.quarterMileSeconds?.let { "¼ mi ${lbNumber(it)}s" },
        entry.eighthMileSeconds?.let { "⅛ mi ${lbNumber(it)}s" },
    ).takeIf { it.isNotEmpty() }?.joinToString(" · ")

    @Composable
    fun stat(value: Double?, width: Float) = RightCell(width) {
        EGText(lbNumber(value), 12f, color = colors.grayDark, maxLines = 1)
    }

    Column(Modifier.fillMaxWidth().clickable(onClick = onTap)) {
        LBHairline()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EGText("P$rank", 14f, weight = FontWeight.Black, color = lbRankColor(rank), maxLines = 1, modifier = Modifier.egWidth(AccelRowLayout.RANK))
            RightCell(AccelRowLayout.TIME) { EGText(lbNumber(entry.zeroTo60), 14f, weight = FontWeight.Black, maxLines = 1) }
            stat(entry.zeroTo30, AccelRowLayout.TIME)
            if (layout.showsHP) {
                LBTintCell(entry.hp?.toString() ?: "—", LBTint.hp(entry.hp), Modifier.egWidth(AccelRowLayout.HP))
            }
            Column(Modifier.weight(1f).padding(vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                EGText(entry.title, 13f, weight = FontWeight.Black, maxLines = 2)
                if (!layout.wide && subtitle != null) EGText(subtitle, 10f, color = colors.gray, maxLines = 1)
            }
            if (layout.wide) {
                stat(entry.quarterMileSeconds, AccelRowLayout.STRIP)
                stat(entry.quarterMileMph, AccelRowLayout.STRIP)
                stat(entry.eighthMileSeconds, AccelRowLayout.STRIP)
                stat(entry.eighthMileMph, AccelRowLayout.STRIP)
                EGText(entry.driver ?: "—", 12f, weight = FontWeight.Black, maxLines = 1, modifier = Modifier.egWidth(AccelRowLayout.DRIVER))
                RightCell(AccelRowLayout.WEIGHT) {
                    EGText(entry.weightLb?.let { "$it lb" } ?: "—", 12f, color = colors.grayDark, maxLines = 1)
                }
            }
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = LBChevronColor,
                modifier = Modifier.egWidth(AccelRowLayout.CHEVRON).size(egDp(14f)),
            )
        }
    }
}

@Composable
fun AccelerationEntryScreen(vm: AppViewModel, state: AppState, id: Int) {
    val entries = state.accelerationEntries
    val index = entries?.indexOfFirst { it.id == id } ?: -1
    if (entries == null || index < 0) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (entries == null && state.accelerationError == null) {
                LBProgress()
            } else {
                LBStatusMessage("This entry is no longer on the board.")
            }
        }
        return
    }
    val entry = entries[index]
    val rank = index + 1
    val colors = EG.colors
    var reporting by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val (notice, showNotice) = rememberNotice()
    val scope = rememberCoroutineScope()

    fun seconds(value: Double?) = value?.let { "${lbNumber(it)}s" } ?: "—"
    val poundsPerHP = entry.weightLb?.let { weight ->
        entry.hp?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f", weight.toDouble() / it) }
    } ?: "—"

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        EGText(entry.title, 24f, weight = FontWeight.Black, maxLines = 2)
                        EGText("P$rank by 0–60 time", 12f, color = colors.grayDark)
                        entry.driver?.let { EGOutlineTag(it.uppercase(), Modifier.padding(top = 4.dp)) }
                    }
                    LBMenuButton(
                        buildList {
                            add(LBMenuItem("Report entry") { reporting = true })
                            if (entry.canBlockPoster) add(LBMenuItem("Block poster", destructive = true) { confirmBlock = true })
                        },
                    )
                }
                EGStatsGrid(
                    listOf(
                        listOf(
                            EGStat("0–60", seconds(entry.zeroTo60), colors.red),
                            EGStat("0–30", seconds(entry.zeroTo30), colors.ink),
                            EGStat("HP", entry.hp?.toString() ?: "—", colors.ink),
                        ),
                        listOf(
                            EGStat("¼ MILE", seconds(entry.quarterMileSeconds), colors.ink),
                            EGStat("¼ MILE MPH", lbNumber(entry.quarterMileMph), colors.ink),
                            EGStat("WEIGHT", entry.weightLb?.let { "$it lb" } ?: "—", colors.ink),
                        ),
                        listOf(
                            EGStat("⅛ MILE", seconds(entry.eighthMileSeconds), colors.ink),
                            EGStat("⅛ MILE MPH", lbNumber(entry.eighthMileMph), colors.ink),
                            EGStat("LB / HP", poundsPerHP, colors.ink),
                        ),
                    ),
                )
                entry.notes?.let { EGText(it, 12f) }
                LBNoticeText(notice)
                LBErrorText(actionError)
            }
        }
        val others = entry.otherRuns.orEmpty()
        if (others.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EGColumnLabel("OTHER RUNS", Modifier.weight(1f))
                    RightCell(52f) { EGColumnLabel("0–60") }
                    RightCell(52f) { EGColumnLabel("0–30") }
                    RightCell(52f) { EGColumnLabel("¼ MI") }
                }
            }
            items(others, key = { "other-${it.id}" }) { run ->
                Column {
                    LBHairline()
                    Row(
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 36.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        EGText(run.postedOn ?: "—", 12f, color = colors.grayDark, modifier = Modifier.weight(1f))
                        RightCell(52f) { EGText(lbNumber(run.zeroTo60), 13f, weight = FontWeight.Black) }
                        RightCell(52f) { EGText(lbNumber(run.zeroTo30), 12f, color = colors.grayDark) }
                        RightCell(52f) { EGText(lbNumber(run.quarterMileSeconds), 12f, color = colors.grayDark) }
                    }
                }
            }
        }
        item { LBSpacer(24) }
    }

    if (reporting) {
        LBReportSheet(
            vm,
            LBReportTarget(LBReportType.ACCELERATION, entry.id),
            "${entry.title} on the Acceleration board",
            onDismiss = { reporting = false },
        ) { showNotice("Report sent. Thanks.") }
    }
    if (confirmBlock) {
        LBConfirmDialog(
            title = "Block whoever posted this entry?",
            message = LB_BLOCK_MESSAGE,
            confirm = "Block Poster",
            onConfirm = {
                scope.launch {
                    try {
                        vm.blockAccelerationPoster(entry.id)
                    } catch (e: Exception) {
                        actionError = e.message
                    }
                }
            },
            onDismiss = { confirmBlock = false },
        )
    }
}
