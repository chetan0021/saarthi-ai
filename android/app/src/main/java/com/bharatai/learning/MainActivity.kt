package com.bharatai.learning

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.bharatai.learning.ui.AppViewModel
import com.bharatai.learning.ui.components.BusyBanner
import com.bharatai.learning.ui.components.TelemetryBar
import com.bharatai.learning.ui.screens.DeviceScreen
import com.bharatai.learning.ui.screens.GoalScreen
import com.bharatai.learning.ui.screens.MentorScreen
import com.bharatai.learning.ui.screens.PathScreen
import com.bharatai.learning.ui.screens.QuizScreen
import com.bharatai.learning.ui.theme.SaarthiTheme

class MainActivity : AppCompatActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { SaarthiTheme { App(vm) } }
    }
}

@androidx.compose.runtime.Composable
private fun App(vm: AppViewModel) {
    val tab by vm.selectedTab.collectAsState()
    val telemetry by vm.telemetry.collectAsState()
    val busy by vm.busy.collectAsState()
    val roadmap by vm.roadmap.collectAsState()
    var autoNavigated by rememberSaveable { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }
    // returning learners land on their roadmap
    LaunchedEffect(roadmap != null) {
        if (roadmap != null && !autoNavigated) { autoNavigated = true; if (tab == 0) vm.selectedTab.value = 1 }
    }

    val tabs = listOf(
        Triple(R.string.tab_goal, Icons.Outlined.Flag, 0),
        Triple(R.string.tab_path, Icons.Outlined.Map, 1),
        Triple(R.string.tab_quiz, Icons.Outlined.Quiz, 2),
        Triple(R.string.tab_mentor, Icons.Outlined.Forum, 3),
        Triple(R.string.tab_device, Icons.Outlined.Memory, 4),
    )
    Scaffold(
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                TelemetryBar(telemetry) { vm.selectedTab.value = 4 }
                BusyBanner(busy ?: telemetry.statusMessage.takeIf { it.isNotEmpty() }) { vm.stopGeneration() }
            }
        },
        bottomBar = {
            NavigationBar {
                tabs.forEach { (label, icon, idx) ->
                    NavigationBarItem(selected = tab == idx, onClick = { vm.selectedTab.value = idx },
                        icon = { Icon(icon, null) }, label = { Text(stringResource(label), style = MaterialTheme.typography.labelSmall) })
                }
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                0 -> GoalScreen(vm)
                1 -> PathScreen(vm)
                2 -> QuizScreen(vm)
                3 -> MentorScreen(vm)
                else -> DeviceScreen(vm)
            }
        }
    }
}
