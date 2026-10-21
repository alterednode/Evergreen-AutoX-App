package com.romangarms.autoxlive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.romangarms.autoxlive.AccelerationEntryRoute
import com.romangarms.autoxlive.AccelerationRoute
import com.romangarms.autoxlive.AppState
import com.romangarms.autoxlive.AppViewModel
import com.romangarms.autoxlive.BoardsRoute
import com.romangarms.autoxlive.CompareRoute
import com.romangarms.autoxlive.DriverRoute
import com.romangarms.autoxlive.EventsRoute
import com.romangarms.autoxlive.FriendsRoute
import com.romangarms.autoxlive.LeaderboardDriverRoute
import com.romangarms.autoxlive.LeaderboardRoute
import com.romangarms.autoxlive.LiveRoute
import com.romangarms.autoxlive.NavCommand
import com.romangarms.autoxlive.RouteKind
import com.romangarms.autoxlive.SetupRoute
import com.romangarms.autoxlive.update.Updater
import kotlinx.coroutines.delay

private enum class Tab(val label: String, val icon: ImageVector, val route: Any, val kind: RouteKind) {
    LIVE("LIVE", Icons.Filled.MonitorHeart, LiveRoute, RouteKind.LIVE),
    FRIENDS("FRIENDS", Icons.Filled.People, FriendsRoute, RouteKind.FRIENDS),
    EVENTS("EVENTS", Icons.Filled.CalendarMonth, EventsRoute, RouteKind.EVENTS),
    BOARDS("BOARDS", Icons.Filled.FormatListNumbered, BoardsRoute, RouteKind.BOARDS),
    SETUP("SETUP", Icons.Filled.Settings, SetupRoute, RouteKind.SETUP),
}

private fun NavBackStackEntry.routeKind(): RouteKind {
    val d = destination
    return when {
        d.hasRoute<LiveRoute>() -> RouteKind.LIVE
        d.hasRoute<FriendsRoute>() -> RouteKind.FRIENDS
        d.hasRoute<EventsRoute>() -> RouteKind.EVENTS
        d.hasRoute<BoardsRoute>() -> RouteKind.BOARDS
        d.hasRoute<SetupRoute>() -> RouteKind.SETUP
        d.hasRoute<DriverRoute>() -> RouteKind.DRIVER
        d.hasRoute<CompareRoute>() -> RouteKind.COMPARE
        d.hasRoute<LeaderboardRoute>() || d.hasRoute<LeaderboardDriverRoute>() -> RouteKind.LEADERBOARD
        d.hasRoute<AccelerationRoute>() || d.hasRoute<AccelerationEntryRoute>() -> RouteKind.ACCELERATION
        else -> RouteKind.OTHER
    }
}

// The tab a screen belongs to is the tab root below it on the back stack.
private fun NavHostController.currentTab(): Tab? {
    val stack = currentBackStack.value
    for (entry in stack.asReversed()) {
        val kind = entry.routeKind()
        Tab.entries.firstOrNull { it.kind == kind }?.let { return it }
    }
    return null
}

fun NavHostController.selectTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun AppScaffold(vm: AppViewModel, updater: Updater) {
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val kind = entry?.routeKind() ?: RouteKind.LIVE
    val tab = entry?.let { nav.currentTab() } ?: Tab.LIVE

    LaunchedEffect(kind) { vm.setRoute(kind) }

    LaunchedEffect(nav) {
        vm.navCommands.collect { command ->
            when (command) {
                NavCommand.ShowLive -> {
                    nav.navigate(LiveRoute) {
                        popUpTo(nav.graph.findStartDestination().id) { inclusive = false }
                        launchSingleTop = true
                    }
                }
                is NavCommand.OpenLeaderboard -> {
                    nav.selectTab(BoardsRoute)
                    nav.navigate(LeaderboardRoute(command.courseId))
                    vm.openLeaderboard(command.courseId)
                }
                is NavCommand.LeaveLeaderboard -> {
                    val onBoard = nav.currentBackStack.value.any {
                        (it.destination.hasRoute<LeaderboardRoute>() && it.toRoute<LeaderboardRoute>().courseId == command.courseId) ||
                            (it.destination.hasRoute<LeaderboardDriverRoute>() && it.toRoute<LeaderboardDriverRoute>().courseId == command.courseId)
                    }
                    if (onBoard) nav.popBackStack<BoardsRoute>(inclusive = false)
                }
                is NavCommand.BackToLeaderboard -> {
                    if (nav.currentBackStackEntry?.destination?.hasRoute<LeaderboardDriverRoute>() == true) {
                        nav.popBackStack<LeaderboardRoute>(inclusive = false)
                    }
                }
                NavCommand.BackToAcceleration -> {
                    if (nav.currentBackStackEntry?.destination?.hasRoute<AccelerationEntryRoute>() == true) {
                        nav.popBackStack<AccelerationRoute>(inclusive = false)
                    }
                }
            }
        }
    }

    LiveRefresh(vm, state)

    LifecycleResumeEffect(updater) {
        updater.checkIfDue()
        onPauseOrDispose {}
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EG.colors.bg),
    ) {
        Header(vm, state, nav, entry)
        UpdateBanner(updater)
        state.staleSince?.let { StaleBanner(it, state.isRetrying, onRetry = vm::retryStale) }
        Box(modifier = Modifier.weight(1f)) {
            NavHost(
                navController = nav,
                startDestination = LiveRoute,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable<LiveRoute> {
                    LiveScreen(vm, state, openDriver = { nav.navigate(DriverRoute(it)) })
                }
                composable<FriendsRoute> {
                    FriendsScreen(
                        vm,
                        state,
                        openDriver = { nav.navigate(DriverRoute(it)) },
                        openCompare = { a, b -> nav.navigate(CompareRoute(a, b)) },
                    )
                }
                composable<EventsRoute> { EventsScreen(vm, state) }
                composable<BoardsRoute> {
                    BoardsScreen(
                        vm,
                        state,
                        openLeaderboard = {
                            vm.openLeaderboard(it)
                            nav.navigate(LeaderboardRoute(it))
                        },
                        openAcceleration = {
                            vm.loadAcceleration()
                            nav.navigate(AccelerationRoute)
                        },
                    )
                }
                composable<SetupRoute> { SetupScreen(vm, state, updater) }
                composable<DriverRoute> { backStackEntry ->
                    val route = backStackEntry.toRoute<DriverRoute>()
                    EGRefreshBox(vm::reloadResults) {
                        DriverScreen(vm, state, route.number, openCompare = { a, b -> nav.navigate(CompareRoute(a, b)) })
                    }
                }
                composable<CompareRoute> { backStackEntry ->
                    val route = backStackEntry.toRoute<CompareRoute>()
                    EGRefreshBox(vm::reloadResults) { CompareScreen(vm, state, route.a, route.b) }
                }
                composable<LeaderboardRoute> { backStackEntry ->
                    val route = backStackEntry.toRoute<LeaderboardRoute>()
                    EGRefreshBox(vm::reloadLeaderboard) {
                        LeaderboardScreen(
                            vm,
                            state,
                            route.courseId,
                            openDriver = { nav.navigate(LeaderboardDriverRoute(route.courseId, it)) },
                        )
                    }
                }
                composable<LeaderboardDriverRoute> { backStackEntry ->
                    val route = backStackEntry.toRoute<LeaderboardDriverRoute>()
                    EGRefreshBox(vm::reloadLeaderboard) {
                        LeaderboardDriverScreen(
                            vm,
                            state,
                            route.courseId,
                            route.position,
                            openDriver = { nav.navigate(LeaderboardDriverRoute(route.courseId, it)) },
                        )
                    }
                }
                composable<AccelerationRoute> {
                    EGRefreshBox(vm::reloadAcceleration) {
                        AccelerationScreen(vm, state, openEntry = { nav.navigate(AccelerationEntryRoute(it)) })
                    }
                }
                composable<AccelerationEntryRoute> { backStackEntry ->
                    EGRefreshBox(vm::reloadAcceleration) {
                        AccelerationEntryScreen(vm, state, backStackEntry.toRoute<AccelerationEntryRoute>().id)
                    }
                }
            }
        }
        BottomBar(tab) { selected ->
            if (selected == tab) {
                nav.popBackStack(selected.route, inclusive = false)
            } else {
                nav.selectTab(selected.route)
            }
        }
    }
}

// Every 10 seconds while the app is in the foreground and a results page for
// a current event is showing. Counted from the last load of any kind, so
// coming back to the app reloads at once and a manual reload restarts the wait.
@Composable
private fun LiveRefresh(vm: AppViewModel, state: AppState) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val active = lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && state.wantsLiveRefresh()
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            val wait = vm.lastSessionLoad + LIVE_REFRESH_MILLIS - System.currentTimeMillis()
            if (wait > 0) delay(wait) else vm.refreshSessionData()
        }
    }
}

private const val LIVE_REFRESH_MILLIS = 10_000L

@Composable
private fun StaleBanner(since: Long, retrying: Boolean, onRetry: () -> Unit) {
    val colors = EG.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.pinnedBg)
            .clickable(enabled = !retrying, onClickLabel = "Try again", onClick = onRetry)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EGTag("OFFLINE", background = colors.darkRed, size = 10f)
        EGText(
            "Couldn't refresh. Showing data from ${AppState.staleLabel(since)}.",
            11.5f,
            weight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (retrying) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = colors.darkRed, strokeWidth = 2.dp)
        } else {
            Icon(Icons.Filled.Refresh, contentDescription = null, tint = colors.darkRed, modifier = Modifier.size(18.dp))
        }
    }
}

private data class HeaderInfo(val tag: String, val title: String, val sub: String?, val changesEvent: Boolean)

private fun headerInfo(state: AppState, entry: NavBackStackEntry?, kind: RouteKind): HeaderInfo {
    val sessionName = state.selectedSession?.let { state.sessionLabel(it) }
    val d = entry?.destination
    return when {
        d == null -> HeaderInfo("LIVE", "AutoX Live", null, false)
        d.hasRoute<DriverRoute>() -> {
            val driver = state.driver(entry.toRoute<DriverRoute>().number)
            HeaderInfo("DRIVER", driver?.let { "P${it.position} — #${it.startNumber}" } ?: "Driver", sessionName, false)
        }
        d.hasRoute<CompareRoute>() -> HeaderInfo("VS", "Head-to-head", sessionName, false)
        d.hasRoute<LeaderboardRoute>() -> {
            val course = state.leaderboardCourses.firstOrNull { it.id == entry.toRoute<LeaderboardRoute>().courseId }
            HeaderInfo("LEADERBOARD", course?.name ?: "Leaderboard", course?.createdBy?.let { "Created by $it" }, false)
        }
        d.hasRoute<LeaderboardDriverRoute>() -> {
            val route = entry.toRoute<LeaderboardDriverRoute>()
            val course = state.leaderboardCourses.firstOrNull { it.id == route.courseId }
            val driver = state.leaderboardEntry(route.position)?.driver
            HeaderInfo("DRIVER", driver?.name ?: "Driver", course?.name, false)
        }
        d.hasRoute<AccelerationRoute>() -> HeaderInfo("LEADERBOARD", "Acceleration", null, false)
        d.hasRoute<AccelerationEntryRoute>() -> {
            val id = entry.toRoute<AccelerationEntryRoute>().id
            val title = state.accelerationEntries?.firstOrNull { it.id == id }?.title
            HeaderInfo("VEHICLE", title ?: "Vehicle", "Acceleration", false)
        }
        else -> when (kind) {
            // Many events share a name, so the date is what says which one is
            // loaded.
            RouteKind.LIVE -> HeaderInfo(
                "LIVE",
                state.selectedEvent?.name ?: "AutoX Live",
                AppState.eventDate(state.selectedEvent?.startDate) ?: sessionName,
                true,
            )
            RouteKind.FRIENDS -> HeaderInfo("FRIENDS", "Your people", sessionName, false)
            RouteKind.EVENTS -> HeaderInfo("EVENTS", "Pick an event", null, false)
            RouteKind.BOARDS -> HeaderInfo("BOARDS", "Leaderboards", "Posted by people using the app", false)
            else -> HeaderInfo("SETUP", "About & app", null, false)
        }
    }
}

@Composable
private fun Header(vm: AppViewModel, state: AppState, nav: NavHostController, entry: NavBackStackEntry?) {
    val kind = entry?.routeKind() ?: RouteKind.LIVE
    val info = headerInfo(state, entry, kind)
    val canGoBack = entry != null && Tab.entries.none { it.kind == kind }
    val colors = EG.colors
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bg)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (canGoBack) 4.dp else 16.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canGoBack) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.red)
                }
            }
            Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EGTag(info.tag)
                    EGText(info.title, 16f, weight = FontWeight.Black, maxLines = 1)
                }
                if (info.sub != null && !info.changesEvent) {
                    EGText(info.sub, 11.5f, color = colors.grayDark, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                }
            }
            HeaderMenu(vm, state, entry, kind, onRename = { renaming = it })
        }
        if (info.sub != null && info.changesEvent) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                EGChipButton(
                    onClick = { nav.selectTab(EventsRoute) },
                    tint = colors.red,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                ) { style, tint ->
                    Text(info.sub, style = egStyle(11.5f, FontWeight.SemiBold), color = colors.ink, maxLines = 1, modifier = Modifier.weight(1f))
                    Text("CHANGE EVENT", style = style, color = tint, maxLines = 1)
                    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = tint, modifier = Modifier.size(egDp(14f)))
                }
                if (state.sessions.size > 1) SessionMenu(vm, state)
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(colors.divider))
    }

    renaming?.let { number ->
        RenameDialog(
            initial = state.nicknames[number] ?: "",
            onDismiss = { renaming = null },
            onSave = {
                vm.setNickname(it, number)
                renaming = null
            },
        )
    }
}

@Composable
private fun SessionMenu(vm: AppViewModel, state: AppState) {
    var open by remember { mutableStateOf(false) }
    val colors = EG.colors
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .padding(horizontal = 10.dp)
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EGColumnLabel("SESSION")
            EGText(
                state.selectedSession?.let { state.sessionLabel(it) } ?: "—",
                11.5f,
                weight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = colors.gray, modifier = Modifier.size(egDp(16f)))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AppState.byMostRecent(state.sessions).forEach { session ->
                DropdownMenuItem(
                    text = { Text(state.sessionLabel(session)) },
                    leadingIcon = {
                        if (session.id == state.selectedSessionId) Icon(Icons.Filled.Check, contentDescription = null)
                    },
                    onClick = {
                        open = false
                        vm.pickSession(session.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun HeaderMenu(
    vm: AppViewModel,
    state: AppState,
    entry: NavBackStackEntry?,
    kind: RouteKind,
    onRename: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val items: List<Pair<String, () -> Unit>> = when {
        entry?.destination?.hasRoute<DriverRoute>() == true -> {
            val number = entry.toRoute<DriverRoute>().number
            if (state.driver(number) == null) {
                emptyList()
            } else {
                val isMe = number == state.meNumber
                val pinned = number in state.pins
                listOf(
                    (if (isMe) "This isn't me" else "This is me") to { vm.setMe(if (isMe) null else number) },
                    (if (pinned) "Unpin" else "Pin") to { vm.togglePin(number) },
                    "Rename…" to { onRename(number) },
                )
            }
        }
        kind == RouteKind.EVENTS -> listOf("Refresh" to { vm.refreshEvents() })
        kind == RouteKind.LIVE && state.drivers.isNotEmpty() -> listOf(
            "Sort by overall position" to { vm.setLiveSortByClass(false) },
            "Sort by class position" to { vm.setLiveSortByClass(true) },
        )
        else -> emptyList()
    }
    if (items.isEmpty()) return
    val checked = when (kind) {
        RouteKind.LIVE -> if (state.settings.liveSortByClass) 1 else 0
        else -> -1
    }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = EG.colors.ink)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEachIndexed { index, (label, action) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    trailingIcon = if (index == checked) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        action()
                    },
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nickname") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("Leave empty to use the real name") },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val colors = EG.colors
    Column(Modifier.background(colors.tabBg).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(colors.divider))
        NavigationBar(containerColor = colors.tabBg, tonalElevation = 0.dp, windowInsets = WindowInsets(0)) {
            Tab.entries.forEach { tab ->
                NavigationBarItem(
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    icon = { Icon(tab.icon, contentDescription = null, modifier = Modifier.size(egDp(20f))) },
                    label = { Text(tab.label, style = egStyle(8.5f, FontWeight.Black, 0.9f), maxLines = 1) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colors.red,
                        selectedTextColor = colors.red,
                        unselectedIconColor = colors.gray,
                        unselectedTextColor = colors.gray,
                        indicatorColor = colors.bg,
                    ),
                )
            }
        }
    }
}
