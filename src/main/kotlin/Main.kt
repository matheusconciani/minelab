import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import auth.MinecraftProfile
import ui.CustomTitleBar
import ui.DashboardScreen
import ui.GrainGradientBackground
import ui.LoginScreen
import java.awt.Dimension

fun main() = application {
    val windowState = remember {
        WindowState(
            size = DpSize(1000.dp, 650.dp)
        )
    }

    var loggedInProfile by remember { mutableStateOf<MinecraftProfile?>(null) }

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "Minecraft Launcher",
        undecorated = true,
        resizable = false
    ) {
        // Enforce fixed moderate size 1000x650 px on the underlying AWT window as well
        LaunchedEffect(window) {
            window.minimumSize = Dimension(1000, 650)
            window.maximumSize = Dimension(1000, 650)
            window.size = Dimension(1000, 650)
        }

        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Color(0xFF83B9AD),
                background = Color(0xFF031419),
                surface = Color(0xFF031419)
            )
        ) {
            // Full-window background with GrainGradient including the custom title bar
            GrainGradientBackground(
                colorLight = Color(0xFFDCE5DF),
                colorMid = Color(0xFF83B9AD),
                colorDark = Color(0xFF031419),
                angle = 0f,
                position = 0f,
                curve = 0.48f,
                softness = 0.13f,
                scale = 1f,
                grain = 0.32f,
                grainSize = 1f,
                seed = 1f,
                speed = 1f
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Custom title bar seamlessly blended with the animated gradient background
                    CustomTitleBar(
                        onMinimize = {
                            windowState.isMinimized = true
                        },
                        onClose = {
                            exitApplication()
                        }
                    )

                    // Main window content area
                    Box(modifier = Modifier.weight(1f)) {
                        val currentProfile = loggedInProfile
                        if (currentProfile == null) {
                            LoginScreen(
                                onLoggedIn = { profile ->
                                    loggedInProfile = profile
                                }
                            )
                        } else {
                            DashboardScreen(profile = currentProfile)
                        }
                    }
                }
            }
        }
    }
}
