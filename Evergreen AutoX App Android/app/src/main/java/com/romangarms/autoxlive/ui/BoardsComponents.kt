package com.romangarms.autoxlive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.romangarms.autoxlive.AppViewModel
import com.romangarms.autoxlive.data.LBReportTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

const val LB_BLOCK_MESSAGE =
    "You won't see leaderboards or times from this poster, and we'll be told about it. Unblock from the Setup tab."

const val LB_POSTING_SOON = "Posting times from Android is coming in a later version."

const val LB_WIDE_THRESHOLD = 700

val LBChevronColor: Color
    @Composable get() = if (EG.colors.dark) Color(0xFF757070) else Color(0xFF9B9797)

val LBPodium: List<Color>
    @Composable get() = if (EG.colors.dark) {
        listOf(Color(0xFFF2C14E), Color(0xFFC0C0C0), Color(0xFFCD7F32))
    } else {
        listOf(Color(0xFFB8860B), Color(0xFF8A8A8A), Color(0xFFA0622A))
    }

@Composable
fun lbRankColor(rank: Int): Color = if (rank in 1..3) LBPodium[rank - 1] else EG.colors.ink

// Thresholds and colors match the HWY 9 sheet's conditional formatting, which
// the web leaderboard and iOS also copy, so a run reads the same everywhere.
enum class LBTint(val color: Color) {
    GREEN(Color(0xFF57BB8A)),
    LIGHT_GREEN(Color(0xFFA4D3A2)),
    YELLOW(Color(0xFFFFD54F)),
    ORANGE(Color(0xFFF6A45B)),
    RED(Color(0xFFE57373)),
    DAY(Color(0xFFF1C232)),
    GRAY(Color(0xFF9E9E9E)),
    ;

    companion object {
        // Deliberately non-adaptive: the tints stay the same in both modes.
        val ink = Color(0xFF1A1A1A)

        fun hp(hp: Int?): LBTint? = when {
            hp == null -> null
            hp >= 300 -> GREEN
            hp > 200 -> YELLOW
            hp > 150 -> ORANGE
            else -> RED
        }

        fun conditions(value: String?): LBTint? = when (value?.trim()?.lowercase()) {
            "dry" -> GREEN
            "wet" -> ORANGE
            "day" -> DAY
            "dark" -> GRAY
            else -> null
        }
    }
}

@Composable
fun LBTintCell(text: String, tint: LBTint?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(tint?.color ?: Color.Transparent)
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        EGText(
            text,
            12f,
            weight = if (tint == null) FontWeight.Normal else FontWeight.Black,
            color = if (tint == null) EG.colors.gray else LBTint.ink,
            maxLines = 1,
        )
    }
}

fun lbNumber(value: Double?): String {
    value ?: return "—"
    val text = String.format(Locale.US, "%.2f", value)
    return if (text.endsWith("0")) text.dropLast(1) else text
}

fun lbOneDecimal(value: Double?): String = value?.let { String.format(Locale.US, "%.1f", it) } ?: "—"

@Composable
fun LBHairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(EG.colors.hairline))
}

@Composable
fun LBNavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        LBHairline()
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                EGText(title, 13.5f, weight = FontWeight.Black)
                EGText(subtitle, 10.5f, color = EG.colors.gray)
            }
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = LBChevronColor, modifier = Modifier.size(egDp(18f)))
        }
    }
}

@Composable
fun LBStatusMessage(text: String, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text, style = egStyle(12f), color = EG.colors.grayDark, textAlign = TextAlign.Center)
        if (onRetry != null) EGButton("RETRY", onRetry, kind = EGButtonKind.PRIMARY)
    }
}

@Composable
fun LBProgress() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = EG.colors.red, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

@Composable
fun LBErrorText(text: String?) {
    if (text != null) EGText(text, 11.5f, weight = FontWeight.SemiBold, color = EG.colors.darkRed)
}

@Composable
fun LBNoticeText(text: String?) {
    if (text != null) EGText(text, 11f, weight = FontWeight.SemiBold, color = EG.colors.red)
}

@Composable
fun LBComingSoon(modifier: Modifier = Modifier) {
    EGText(LB_POSTING_SOON, 11f, color = EG.colors.grayDark, modifier = modifier)
}

@Composable
fun rememberNotice(): Pair<String?, (String) -> Unit> {
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(3000)
            notice = null
        }
    }
    return notice to { notice = it }
}

data class LBMenuItem(val label: String, val destructive: Boolean = false, val action: () -> Unit)

@Composable
fun LBMenuButton(items: List<LBMenuItem>, chip: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box {
        if (chip) {
            EGChipButton(onClick = { open = true }) { _, tint ->
                Icon(
                    Icons.Filled.MoreHoriz,
                    contentDescription = "More",
                    tint = tint,
                    modifier = Modifier.size(egDp(20f)).padding(horizontal = 0.dp),
                )
            }
        } else {
            Box(
                modifier = Modifier.width(30.dp).height(28.dp).clickable { open = true },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Filled.MoreHoriz, contentDescription = "More", tint = EG.colors.gray, modifier = Modifier.size(egDp(18f)))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label, color = if (item.destructive) EG.colors.red else EG.colors.ink) },
                    onClick = {
                        open = false
                        item.action()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LBSheet(title: String, subtitle: String?, onDismiss: () -> Unit, content: @Composable (close: () -> Unit) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = EG.colors.bg,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    EGText(title, 19f, weight = FontWeight.Black)
                    if (subtitle != null) EGText(subtitle, 11f, color = EG.colors.grayDark)
                }
                EGChipButton(onClick = close) { _, tint ->
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = tint, modifier = Modifier.size(egDp(14f)))
                }
            }
            content(close)
        }
    }
}

@Composable
fun LBFormField(
    label: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Words,
) {
    val colors = EG.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        EGColumnLabel(label)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, style = egStyle(13f), color = colors.gray) },
            textStyle = egStyle(13f).copy(color = colors.ink),
            singleLine = true,
            shape = RectangleShape,
            keyboardOptions = KeyboardOptions(capitalization = capitalization, autoCorrectEnabled = false),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = colors.card,
                unfocusedContainerColor = colors.card,
                focusedBorderColor = colors.red,
                unfocusedBorderColor = colors.divider,
                cursorColor = colors.red,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// Shown before the boards rather than before posting, since Android has no
// posting yet; Google Play's user-generated-content rules want the same terms.
@Composable
fun LBGuidelines(onAgree: () -> Unit) {
    val colors = EG.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            EGText("Community Leaderboards", 19f, weight = FontWeight.Black)
            EGText("Read this once before you use the leaderboards.", 11f, color = colors.grayDark)
        }
        EGText("Leaderboards and times are posted by people using this app, not by event organizers.", 13f)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.card)
                .border(2.dp, colors.divider, RectangleShape)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GUIDELINE_RULES.forEach { rule ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = colors.red,
                        modifier = Modifier.padding(top = 2.dp).size(egDp(14f)),
                    )
                    EGText(rule, 12.5f)
                }
            }
        }
        Row { EGButton("I AGREE", onAgree, kind = EGButtonKind.PRIMARY) }
    }
}

private val GUIDELINE_RULES = listOf(
    "Only post times set on closed courses, private property, or at sanctioned events. Never from public roads.",
    "Use your real name or a nickname. No offensive names, notes, or leaderboard titles.",
    "Everything you post is public and can be seen by anyone using the app.",
    "Anything posted that breaks these rules can be reported by anyone and will be removed, and whoever posted it can be banned from posting.",
)

@Composable
fun LBReportSheet(vm: AppViewModel, target: LBReportTarget, subject: String, onDismiss: () -> Unit, onSent: () -> Unit) {
    var reason by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LBSheet("Report", subject, onDismiss) { close ->
        EGText(
            "Tell us what's wrong. Reports go to the app's maintainer, who can remove anything that breaks the guidelines.",
            12.5f,
            color = EG.colors.grayDark,
        )
        LBFormField("REASON", "Public road, fake time, offensive name…", reason, { reason = it }, KeyboardCapitalization.Sentences)
        LBErrorText(error)
        Row {
            EGButton("SEND REPORT", enabled = !sending, kind = EGButtonKind.PRIMARY, onClick = {
                val trimmed = reason.trim()
                if (trimmed.isEmpty()) {
                    error = "Say what the problem is."
                    return@EGButton
                }
                sending = true
                error = null
                scope.launch {
                    try {
                        vm.report(target, trimmed)
                        onSent()
                        close()
                    } catch (e: Exception) {
                        error = e.message
                    }
                    sending = false
                }
            })
        }
    }
}

@Composable
fun LBJoinSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var joining by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LBSheet("Join a Leaderboard", "Unlisted leaderboards are added with a join code.", onDismiss) { _ ->
        EGText(
            "Ask whoever runs the leaderboard for its code. They can find it at the top of the leaderboard.",
            12.5f,
            color = EG.colors.grayDark,
        )
        LBFormField("JOIN CODE", "ABCD2345", code, { code = it }, KeyboardCapitalization.Characters)
        LBErrorText(error)
        Row {
            EGButton("JOIN", enabled = !joining, kind = EGButtonKind.PRIMARY, onClick = {
                val trimmed = code.trim()
                if (trimmed.isEmpty()) {
                    error = "Enter the join code."
                    return@EGButton
                }
                joining = true
                error = null
                scope.launch {
                    try {
                        vm.joinCourse(trimmed)
                        onDismiss()
                    } catch (e: Exception) {
                        error = e.message
                    }
                    joining = false
                }
            })
        }
    }
}

@Composable
fun LBConfirmDialog(
    title: String,
    message: String?,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = message?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(confirm, color = EG.colors.red) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun Modifier.lbFullBleed(inset: Dp): Modifier = layout { measurable, constraints ->
    val extra = inset.roundToPx() * 2
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra),
    )
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}

@Composable
fun LBSpacer(height: Int) = Spacer(Modifier.height(height.dp))
