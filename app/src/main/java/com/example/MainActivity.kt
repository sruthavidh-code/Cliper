package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ai.configureAppCheck
import com.example.ui.screens.AnalysisProgressScreen
import com.example.ui.screens.ClipEditorScreen
import com.example.ui.screens.ClipsFeedScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.theme.ClipPulseTheme
import com.example.ui.viewmodel.AnalysisViewModel
import com.example.ui.viewmodel.ClipEditorViewModel
import com.example.ui.viewmodel.DashboardViewModel

private const val ROUTE_DASHBOARD = "dashboard"
private const val ROUTE_ANALYSIS = "analysis/{projectId}"
private const val ROUTE_CLIPS = "clips/{projectId}"
private const val ROUTE_EDITOR = "editor/{projectId}/{clipId}"

class MainActivity : ComponentActivity() {

    private lateinit var appContainer: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Mandatory Firebase App Check wiring with launch intent before UI or ViewModels
        configureAppCheck(this, intent)

        // STEP 2: Edge-to-edge dark-first system bar styling
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        appContainer = AppContainer.getInstance(applicationContext)

        setContent {
            ClipPulseTheme {
                ClipPulseNavGraph(appContainer = appContainer)
            }
        }
    }
}

@Composable
private fun ClipPulseNavGraph(appContainer: AppContainer) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = ROUTE_DASHBOARD
    ) {
        composable(ROUTE_DASHBOARD) {
            val dashboardVm: DashboardViewModel = viewModel(
                factory = DashboardViewModel.factory(
                    repository = appContainer.repository,
                    sampleVideoGenerator = appContainer.sampleVideoGenerator
                )
            )
            DashboardScreen(
                viewModel = dashboardVm,
                onOpenAnalysis = { projectId ->
                    navController.navigate("analysis/$projectId")
                },
                onOpenClipsFeed = { projectId ->
                    navController.navigate("clips/$projectId")
                },
                onOpenClipEditor = { projectId, clipId ->
                    navController.navigate("editor/$projectId/$clipId")
                }
            )
        }

        composable(
            route = ROUTE_ANALYSIS,
            arguments = listOf(navArgument("projectId") { type = NavType.LongType })
        ) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getLong("projectId") ?: return@composable
            val analysisVm: AnalysisViewModel = viewModel(
                key = "analysis_$projectId",
                factory = AnalysisViewModel.factory(
                    projectId = projectId,
                    repository = appContainer.repository,
                    geminiAnalyzer = appContainer.geminiClipAnalyzer,
                    faceAnalyzer = appContainer.faceReframeAnalyzer,
                    audioExtractor = appContainer.audioExtractor
                )
            )
            AnalysisProgressScreen(
                viewModel = analysisVm,
                onNavigateBack = { navController.popBackStack() },
                onAnalysisCompleted = { readyProjectId ->
                    navController.navigate("clips/$readyProjectId") {
                        popUpTo("analysis/$projectId") { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = ROUTE_CLIPS,
            arguments = listOf(navArgument("projectId") { type = NavType.LongType })
        ) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getLong("projectId") ?: return@composable
            val editorVm: ClipEditorViewModel = viewModel(
                key = "project_clips_$projectId",
                factory = ClipEditorViewModel.factory(
                    projectId = projectId,
                    repository = appContainer.repository,
                    faceAnalyzer = appContainer.faceReframeAnalyzer,
                    exportCoordinator = appContainer.exportCoordinator
                )
            )
            ClipsFeedScreen(
                viewModel = editorVm,
                onNavigateBack = { navController.popBackStack() },
                onOpenEditor = { clipId ->
                    navController.navigate("editor/$projectId/$clipId")
                }
            )
        }

        composable(
            route = ROUTE_EDITOR,
            arguments = listOf(
                navArgument("projectId") { type = NavType.LongType },
                navArgument("clipId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getLong("projectId") ?: return@composable
            val clipId = backStackEntry.arguments?.getLong("clipId") ?: return@composable
            val parentEntry = remember(backStackEntry) {
                try {
                    navController.getBackStackEntry("clips/$projectId")
                } catch (_: Exception) {
                    backStackEntry
                }
            }
            val editorVm: ClipEditorViewModel = viewModel(
                viewModelStoreOwner = parentEntry,
                key = "project_clips_$projectId",
                factory = ClipEditorViewModel.factory(
                    projectId = projectId,
                    repository = appContainer.repository,
                    faceAnalyzer = appContainer.faceReframeAnalyzer,
                    exportCoordinator = appContainer.exportCoordinator
                )
            )
            ClipEditorScreen(
                clipId = clipId,
                viewModel = editorVm,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
