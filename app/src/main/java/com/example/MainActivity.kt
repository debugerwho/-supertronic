package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.supertonic.engine.ModelDownloadState
import com.example.supertonic.ui.MainViewModel
import com.example.supertonic.ui.screens.DiagnosticScreen
import com.example.supertonic.ui.screens.LibraryScreen
import com.example.supertonic.ui.screens.ModelSetupScreen
import com.example.supertonic.ui.screens.ReaderScreen
import com.example.ui.theme.CoralPrimary
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.SupertonicTheme
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Handle notification permission result
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Check notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        handleIncomingIntent(intent, isNewIntent = false)

        setContent {
            SupertonicTheme {
                val downloadState by viewModel.downloadState.collectAsState()
                var currentTab by remember { mutableStateOf(0) }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = ObsidianBackground
                ) {
                    if (downloadState !is ModelDownloadState.Ready && currentTab == 0) {
                        ModelSetupScreen(viewModel = viewModel)
                    } else {
                        Scaffold(
                            bottomBar = {
                                NavigationBar(
                                    containerColor = ObsidianCard,
                                    tonalElevation = 8.dp,
                                    modifier = Modifier.testTag("bottom_nav_bar")
                                ) {
                                    NavigationBarItem(
                                        selected = currentTab == 0,
                                        onClick = { currentTab = 0 },
                                        icon = { Icon(Icons.Default.MenuBook, contentDescription = "Читать") },
                                        label = { Text("Читать") },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = CoralPrimary,
                                            selectedTextColor = CoralPrimary,
                                            unselectedIconColor = TextSecondary,
                                            unselectedTextColor = TextSecondary,
                                            indicatorColor = CoralPrimary.copy(alpha = 0.2f)
                                        )
                                    )

                                    NavigationBarItem(
                                        selected = currentTab == 1,
                                        onClick = {
                                            currentTab = 1
                                            viewModel.refreshSavedTracks()
                                        },
                                        icon = { Icon(Icons.Default.Audiotrack, contentDescription = "Аудиотека") },
                                        label = { Text("Аудиотека") },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = CoralPrimary,
                                            selectedTextColor = CoralPrimary,
                                            unselectedIconColor = TextSecondary,
                                            unselectedTextColor = TextSecondary,
                                            indicatorColor = CoralPrimary.copy(alpha = 0.2f)
                                        )
                                    )

                                    NavigationBarItem(
                                        selected = currentTab == 2,
                                        onClick = { currentTab = 2 },
                                        icon = { Icon(Icons.Default.Settings, contentDescription = "Настройки") },
                                        label = { Text("Настройки") },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = CoralPrimary,
                                            selectedTextColor = CoralPrimary,
                                            unselectedIconColor = TextSecondary,
                                            unselectedTextColor = TextSecondary,
                                            indicatorColor = CoralPrimary.copy(alpha = 0.2f)
                                        )
                                    )
                                }
                            }
                        ) { innerPadding ->
                            Box(modifier = Modifier.padding(innerPadding)) {
                                when (currentTab) {
                                    0 -> ReaderScreen(viewModel = viewModel)
                                    1 -> LibraryScreen(viewModel = viewModel)
                                    2 -> DiagnosticScreen(viewModel = viewModel)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.onAppPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent, isNewIntent = true)
    }

    private fun handleIncomingIntent(intent: Intent?, isNewIntent: Boolean) {
        if (intent == null) return
        val action = intent.action

        val incomingText = when (action) {
            Intent.ACTION_PROCESS_TEXT -> {
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            }
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    intent.getStringExtra(Intent.EXTRA_TEXT)
                } else null
            }
            else -> null
        }

        if (!incomingText.isNullOrBlank()) {
            viewModel.saveManualText("Входящий текст", incomingText)
            Toast.makeText(this, "Текст загружен в Supertonic Reader", Toast.LENGTH_SHORT).show()

            // If model is ready, begin reading
            if (viewModel.modelManager.checkInstalledState()) {
                viewModel.togglePlayPause()
            }
        }
    }
}
