package com.househunter.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.househunter.app.data.prefs.PrefsRepository
import com.househunter.app.domain.model.UserPrefs
import com.househunter.app.ui.addsite.AddSiteScreen
import com.househunter.app.ui.details.DetailScreen
import com.househunter.app.ui.home.FilterSheet
import com.househunter.app.ui.home.HomeScreen
import com.househunter.app.ui.home.HomeViewModel
import com.househunter.app.ui.preferences.PreferencesScreen
import com.househunter.app.ui.sites.SitesScreen
import com.househunter.app.ui.workmap.WorkMapScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

object Routes {
    const val PREFERENCES = "preferences"
    const val WORKMAP = "workmap"
    const val HOME = "home"
    const val SITES = "sites"
    const val ADD_SITE = "addSite"
    const val DETAILS = "details/{listingId}"

    fun details(id: String) = "details/$id"
}

@HiltViewModel
class AppViewModel @Inject constructor(
    prefsRepository: PrefsRepository,
) : ViewModel() {
    val prefs: StateFlow<UserPrefs> = prefsRepository.prefs
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserPrefs())
}

@Composable
fun HouseHunterNavHost(
    appViewModel: AppViewModel = hiltViewModel(),
    navController: NavHostController = rememberNavController(),
) {
    val prefs by appViewModel.prefs.collectAsStateWithLifecycle()

    NavHost(
        navController = navController,
        startDestination = if (prefs.onboarded) Routes.HOME else Routes.PREFERENCES,
    ) {
        composable(Routes.PREFERENCES) {
            PreferencesScreen(
                onContinue = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.PREFERENCES) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.WORKMAP) {
            WorkMapScreen(
                onBack = { navController.popBackStack() },
                onDone = { navController.popBackStack() },
            )
        }

        composable(Routes.HOME) {
            val homeViewModel: HomeViewModel = hiltViewModel()
            val state by homeViewModel.uiState.collectAsStateWithLifecycle()
            val filterOpen = remember { mutableStateOf(false) }

            if (filterOpen.value) {
                FilterSheet(
                    prefs = state.prefs,
                    onDismiss = { filterOpen.value = false },
                    onApply = { filters ->
                        homeViewModel.setFilters(filters)
                        filterOpen.value = false
                    },
                )
            }

            HomeScreen(
                onOpenListing = { id -> navController.navigate(Routes.details(id)) },
                onOpenSites = { navController.navigate(Routes.SITES) },
                onOpenWorkMap = { navController.navigate(Routes.WORKMAP) },
                onOpenFilters = { filterOpen.value = true },
                viewModel = homeViewModel,
            )
        }

        composable(
            route = Routes.DETAILS,
            arguments = listOf(navArgument("listingId") { type = NavType.StringType }),
        ) {
            DetailScreen(
                prefs = prefs,
                onBack = { navController.popBackStack() },
                onSetWorkLocations = { navController.navigate(Routes.WORKMAP) },
            )
        }

        composable(Routes.SITES) {
            SitesScreen(
                onBack = { navController.popBackStack() },
                onAddSite = { navController.navigate(Routes.ADD_SITE) },
            )
        }

        composable(Routes.ADD_SITE) {
            AddSiteScreen(
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
            )
        }
    }
}

@Composable
fun LoadingBox() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}