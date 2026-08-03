package com.example.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.model.UserRole
import com.example.data.repository.CampusRideRepository
import com.example.ui.driver.DriverDashboardScreen
import com.example.ui.faculty.FacultyDashboardScreen
import com.example.ui.roleselection.RoleSelectionScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.splash.SplashScreen
import com.example.ui.student.StudentDashboardScreen

object NavRoutes {
    const val SPLASH = "splash"
    const val PERMISSIONS = "permissions"
    const val ROLE_SELECTION = "role_selection"
    const val STUDENT_DASHBOARD = "student_dashboard"
    const val DRIVER_DASHBOARD = "driver_dashboard"
    const val FACULTY_DASHBOARD = "faculty_dashboard"
    const val SETTINGS = "settings"
}

@Composable
fun CampusAppNavigation() {
    val context = LocalContext.current
    val repository = remember { CampusRideRepository(context) }
    val navController = rememberNavController()
    val currentRole by repository.currentRole.collectAsState()
    val isDarkMode by repository.isDarkMode.collectAsState()

    com.example.ui.theme.CampusRideTheme(darkTheme = isDarkMode) {
        NavHost(
            navController = navController,
            startDestination = NavRoutes.SPLASH
        ) {
        composable(NavRoutes.SPLASH) {
            SplashScreen(
                onNavigateNext = {
                    val saved = currentRole
                    if (saved != null) {
                        when (saved) {
                            UserRole.STUDENT -> navController.navigate(NavRoutes.STUDENT_DASHBOARD) {
                                popUpTo(NavRoutes.SPLASH) { inclusive = true }
                            }
                            UserRole.DRIVER -> navController.navigate(NavRoutes.DRIVER_DASHBOARD) {
                                popUpTo(NavRoutes.SPLASH) { inclusive = true }
                            }
                            UserRole.FACULTY -> navController.navigate(NavRoutes.FACULTY_DASHBOARD) {
                                popUpTo(NavRoutes.SPLASH) { inclusive = true }
                            }
                        }
                    } else {
                        navController.navigate(NavRoutes.ROLE_SELECTION) {
                            popUpTo(NavRoutes.SPLASH) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(NavRoutes.PERMISSIONS) {
            com.example.ui.permissions.PermissionOnboardingScreen(
                onPermissionsConfigured = {
                    val saved = currentRole
                    if (saved != null) {
                        when (saved) {
                            UserRole.STUDENT -> navController.navigate(NavRoutes.STUDENT_DASHBOARD) {
                                popUpTo(NavRoutes.PERMISSIONS) { inclusive = true }
                            }
                            UserRole.DRIVER -> navController.navigate(NavRoutes.DRIVER_DASHBOARD) {
                                popUpTo(NavRoutes.PERMISSIONS) { inclusive = true }
                            }
                            UserRole.FACULTY -> navController.navigate(NavRoutes.FACULTY_DASHBOARD) {
                                popUpTo(NavRoutes.PERMISSIONS) { inclusive = true }
                            }
                        }
                    } else {
                        navController.navigate(NavRoutes.ROLE_SELECTION) {
                            popUpTo(NavRoutes.PERMISSIONS) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(NavRoutes.ROLE_SELECTION) {
            RoleSelectionScreen(
                repository = repository,
                onSelectRole = { role ->
                    repository.saveRole(role)
                    when (role) {
                        UserRole.STUDENT -> navController.navigate(NavRoutes.STUDENT_DASHBOARD) {
                            popUpTo(NavRoutes.ROLE_SELECTION) { inclusive = true }
                        }
                        UserRole.DRIVER -> navController.navigate(NavRoutes.DRIVER_DASHBOARD) {
                            popUpTo(NavRoutes.ROLE_SELECTION) { inclusive = true }
                        }
                        UserRole.FACULTY -> navController.navigate(NavRoutes.FACULTY_DASHBOARD) {
                            popUpTo(NavRoutes.ROLE_SELECTION) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(NavRoutes.STUDENT_DASHBOARD) {
            com.example.ui.permissions.StudentPermissionGuard {
                StudentDashboardScreen(
                    repository = repository,
                    onOpenSettings = { navController.navigate(NavRoutes.SETTINGS) }
                )
            }
        }

        composable(NavRoutes.DRIVER_DASHBOARD) {
            com.example.ui.permissions.DriverPermissionGuard {
                DriverDashboardScreen(
                    repository = repository,
                    onOpenSettings = { navController.navigate(NavRoutes.SETTINGS) }
                )
            }
        }

        composable(NavRoutes.FACULTY_DASHBOARD) {
            com.example.ui.permissions.FacultyPermissionGuard {
                FacultyDashboardScreen(
                    repository = repository,
                    onOpenSettings = { navController.navigate(NavRoutes.SETTINGS) }
                )
            }
        }

        composable(NavRoutes.SETTINGS) {
            SettingsScreen(
                repository = repository,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
}
