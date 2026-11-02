package info.cemu.cemu.setupwizard

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable
object SetupWizardRoute

fun NavGraphBuilder.setupWizardNavigation(navController: NavHostController) {
    composable<SetupWizardRoute> {
        SetupWizardScreen(onExit = { navController.popBackStack() })
    }
}
