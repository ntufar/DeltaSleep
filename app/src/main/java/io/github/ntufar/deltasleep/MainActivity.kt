package io.github.ntufar.deltasleep

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.ntufar.deltasleep.settings.SettingsStore
import io.github.ntufar.deltasleep.ui.ActiveSleepScreen
import io.github.ntufar.deltasleep.ui.ApneaQuestionnaireScreen
import io.github.ntufar.deltasleep.ui.ApneaReportScreen
import io.github.ntufar.deltasleep.ui.ApneaSetupScreen
import io.github.ntufar.deltasleep.ui.HelpScreen
import io.github.ntufar.deltasleep.ui.HomeScreen
import io.github.ntufar.deltasleep.ui.SessionScreen
import io.github.ntufar.deltasleep.ui.SettingsScreen
import io.github.ntufar.deltasleep.ui.TrendsScreen
import io.github.ntufar.deltasleep.ui.theme.DeltaSleepTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled at runtime via dialog */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(perms.toTypedArray())

        setContent {
            val context = LocalContext.current
            val store = remember { SettingsStore(context) }
            val settings by store.settings.collectAsState()
            DeltaSleepTheme(theme = settings.theme) {
                DeltaSleepNavGraph()
            }
        }
    }
}

/** Third top-level destination (D-1): Home / Trends / Report. */
private val TOP_LEVEL_ROUTES = listOf(
    Triple("home", "Home", "🌙"),
    Triple("trends", "Trends", "📊"),
    Triple("apnea", "Report", "❤"),
)

@Composable
private fun DeltaSleepNavGraph() {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            if (route in TOP_LEVEL_ROUTES.map { it.first }) {
                NavigationBar {
                    TOP_LEVEL_ROUTES.forEach { (r, label, glyph) ->
                        NavigationBarItem(
                            selected = route == r,
                            onClick = {
                                nav.navigate(r) {
                                    launchSingleTop = true
                                    restoreState = true
                                    popUpTo("home") { saveState = true }
                                }
                            },
                            icon = { Text(glyph) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = Modifier.padding(padding),
        ) {
        composable("home") {
            HomeScreen(
                onSessionTap = { sessionId -> nav.navigate("session/$sessionId") },
                onActiveSession = { sessionId ->
                    nav.navigate("active/$sessionId") {
                        launchSingleTop = true
                    }
                },
                onHelp = { nav.navigate("help") },
                onApnea = { nav.navigate("apnea") },
                onApneaSetup = { nav.navigate("apnea_setup") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onApneaSetup = { nav.navigate("apnea_setup") },
            )
        }
        composable("apnea") {
            ApneaReportScreen(
                onBack = { nav.popBackStack() },
                onQuestionnaire = { nav.navigate("apnea_questionnaire") },
                onSetup = { nav.navigate("apnea_setup") },
            )
        }
        composable("apnea_setup") {
            ApneaSetupScreen(onBack = { nav.popBackStack() })
        }
        composable("apnea_questionnaire") {
            ApneaQuestionnaireScreen(
                onBack = { nav.popBackStack() },
                onSaved = { nav.popBackStack() },
            )
        }
        composable(
            route = "active/{sessionId}",
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
        ) {
            ActiveSleepScreen(
                onStop = { sessionId ->
                    nav.navigate("session/$sessionId") {
                        popUpTo("home")
                    }
                },
            )
        }
        composable(
            route = "session/{sessionId}",
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
        ) {
            SessionScreen(onBack = { nav.popBackStack() })
        }
        composable("help") {
            HelpScreen(onBack = { nav.popBackStack() })
        }
        composable("trends") {
            TrendsScreen()
        }
        }
    }
}
