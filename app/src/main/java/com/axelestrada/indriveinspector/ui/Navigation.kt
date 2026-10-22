package com.axelestrada.indriveinspector.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable

/** Rutas de la app. */
object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
}

private val Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Navegador con transiciones suaves: las páginas entran desde la derecha y
 * se deslizan al volver.
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    home: @Composable () -> Unit,
    settings: @Composable (onBack: () -> Unit) -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        enterTransition = {
            slideInHorizontally(tween(380, easing = Easing)) { it / 5 } +
                fadeIn(tween(280))
        },
        exitTransition = {
            slideOutHorizontally(tween(380, easing = Easing)) { -it / 8 } +
                fadeOut(tween(220))
        },
        popEnterTransition = {
            slideInHorizontally(tween(380, easing = Easing)) { -it / 8 } +
                fadeIn(tween(280))
        },
        popExitTransition = {
            slideOutHorizontally(tween(380, easing = Easing)) { it / 5 } +
                fadeOut(tween(220))
        }
    ) {
        composable(Routes.HOME) {
            home()
        }
        composable(Routes.SETTINGS) {
            settings { navController.popBackStack() }
        }
    }
}
