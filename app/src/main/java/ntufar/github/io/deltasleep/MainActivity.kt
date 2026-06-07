package ntufar.github.io.deltasleep

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ntufar.github.io.deltasleep.ui.HomeScreen
import ntufar.github.io.deltasleep.ui.SessionScreen
import ntufar.github.io.deltasleep.ui.theme.DeltaSleepTheme

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
            DeltaSleepTheme {
                DeltaSleepNavGraph()
            }
        }
    }
}

@Composable
private fun DeltaSleepNavGraph() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onSessionTap = { sessionId -> nav.navigate("session/$sessionId") },
            )
        }
        composable(
            route = "session/{sessionId}",
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
        ) {
            SessionScreen(onBack = { nav.popBackStack() })
        }
    }
}
