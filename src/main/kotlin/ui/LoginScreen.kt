package ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import auth.MicrosoftAuthService
import auth.MinecraftProfile
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

// Minecraft Java username: 3 to 16 alphanumeric characters and underscores
private val MINECRAFT_USERNAME_REGEX = Regex("^[a-zA-Z0-9_]{3,16}$")

@Composable
fun LoginScreen(
    onLoggedIn: (MinecraftProfile) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF031419).copy(alpha = 0.55f))
                .border(1.dp, Color(0xFF83B9AD).copy(alpha = 0.25f), RoundedCornerShape(20.dp))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Entrar no Minecraft",
                color = Color(0xFFDCE5DF),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Escolha como deseja se conectar para jogar",
                color = Color(0xFFDCE5DF).copy(alpha = 0.7f),
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Modo Local / Offline
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "Com qual nome você quer entrar?",
                    color = Color(0xFFDCE5DF).copy(alpha = 0.9f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        errorMessage = null
                    },
                    placeholder = {
                        Text("Ex: Steve_01 (3 a 16 caracteres)", color = Color(0xFFDCE5DF).copy(alpha = 0.4f), fontSize = 14.sp)
                    },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFDCE5DF),
                        unfocusedTextColor = Color(0xFFDCE5DF),
                        focusedBorderColor = Color(0xFF83B9AD),
                        unfocusedBorderColor = Color(0xFF83B9AD).copy(alpha = 0.4f),
                        cursorColor = Color(0xFF83B9AD)
                    ),
                    shape = RoundedCornerShape(10.dp)
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Modo local/offline: não autentica em servidores online protegidos.",
                    color = Color(0xFFDCE5DF).copy(alpha = 0.5f),
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        val trimmed = username.trim()
                        if (trimmed.isEmpty()) {
                            errorMessage = "Por favor, digite um nome de usuário."
                            return@Button
                        }
                        if (trimmed.length < 3 || trimmed.length > 16) {
                            errorMessage = "O nome deve ter entre 3 e 16 caracteres."
                            return@Button
                        }
                        if (!MINECRAFT_USERNAME_REGEX.matches(trimmed)) {
                            errorMessage = "O nome pode conter apenas letras, números e sublinhados (_)."
                            return@Button
                        }

                        // Strictly validate and safely encode to prevent URL path traversal/manipulation
                        val safeEncodedName = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.toString())
                        val profile = MinecraftProfile(
                            id = trimmed,
                            name = trimmed,
                            skinUrl = "https://minotar.net/skin/$safeEncodedName"
                        )
                        onLoggedIn(profile)
                    },
                    enabled = !isLoading && username.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF83B9AD),
                        contentColor = Color(0xFF031419),
                        disabledContainerColor = Color(0xFF83B9AD).copy(alpha = 0.3f),
                        disabledContentColor = Color(0xFF031419).copy(alpha = 0.4f)
                    )
                ) {
                    Text("Entrar no modo local", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Divider "ou"
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFF83B9AD).copy(alpha = 0.25f))
                Text(
                    text = "ou",
                    color = Color(0xFFDCE5DF).copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFF83B9AD).copy(alpha = 0.25f))
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Microsoft Login Button
            Button(
                onClick = {
                    isLoading = true
                    errorMessage = null
                    statusMessage = "Iniciando login com a Microsoft..."

                    coroutineScope.launch {
                        try {
                            val profile = MicrosoftAuthService.loginWithMicrosoft { msg ->
                                statusMessage = msg
                            }
                            onLoggedIn(profile)
                        } catch (e: Exception) {
                            errorMessage = e.message ?: "Erro na autenticação com a Microsoft."
                        } finally {
                            isLoading = false
                            statusMessage = ""
                        }
                    }
                },
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0B252E),
                    contentColor = Color(0xFFDCE5DF)
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF83B9AD).copy(alpha = 0.4f))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    // Microsoft 4-square logo
                    Column(
                        modifier = Modifier.size(16.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                            Box(modifier = Modifier.size(7.dp).background(Color(0xFFF25022)))
                            Box(modifier = Modifier.size(7.dp).background(Color(0xFF7FBA00)))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                            Box(modifier = Modifier.size(7.dp).background(Color(0xFF00A4EF)))
                            Box(modifier = Modifier.size(7.dp).background(Color(0xFFFFB900)))
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Text("Entrar com Microsoft", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }

            // Status or Error messages
            AnimatedVisibility(visible = isLoading) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color(0xFF83B9AD),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = statusMessage,
                        color = Color(0xFFDCE5DF).copy(alpha = 0.8f),
                        fontSize = 12.sp
                    )
                }
            }

            AnimatedVisibility(visible = errorMessage != null) {
                Text(
                    text = errorMessage ?: "",
                    color = Color(0xFFFF7B7B),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}
