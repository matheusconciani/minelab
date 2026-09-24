package ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import auth.MicrosoftAuthService
import auth.MinecraftProfile
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

// Minecraft Java username: mais de 3 e menos de 17 caracteres (entre 4 e 16 caracteres), alfanumérico e sublinhados
private val MINECRAFT_USERNAME_REGEX = Regex("^[a-zA-Z0-9_]{4,16}$")

@Composable
fun LoginScreen(
    onLoggedIn: (MinecraftProfile) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var isMicrosoftLoading by remember { mutableStateOf(false) }
    var isLocalLoggingIn by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var authJob by remember { mutableStateOf<Job?>(null) }

    // Clean up coroutine when screen is disposed
    DisposableEffect(Unit) {
        onDispose {
            authJob?.cancel()
            authJob = null
        }
    }

    // Unified validation and login function used by both button click and Enter key
    fun performLocalLogin() {
        if (isLocalLoggingIn) return

        val trimmed = username.trim()
        if (trimmed.isEmpty()) {
            errorMessage = "Por favor, digite um nome de usuário."
            return
        }
        if (trimmed.length < 4 || trimmed.length > 16) {
            errorMessage = "O nome deve ter entre 4 e 16 caracteres."
            return
        }
        if (!MINECRAFT_USERNAME_REGEX.matches(trimmed)) {
            errorMessage = "O nome pode conter apenas letras, números e sublinhados (_)."
            return
        }

        isLocalLoggingIn = true

        // Cancel ongoing Microsoft auth if any before proceeding with local login
        authJob?.cancel()
        authJob = null
        isMicrosoftLoading = false
        statusMessage = ""

        val safeEncodedName = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.toString())
        val profile = MinecraftProfile(
            id = trimmed,
            name = trimmed,
            skinUrl = "https://minotar.net/skin/$safeEncodedName"
        )
        onLoggedIn(profile)
    }

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
                text = "Entrar no Minelab",
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

            // Modo Local: SEMPRE DISPONÍVEL E EDITÁVEL
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
                        Text("Ex: Steve_01 (4 a 16 caracteres)", color = Color(0xFFDCE5DF).copy(alpha = 0.4f), fontSize = 14.sp)
                    },
                    singleLine = true,
                    enabled = !isLocalLoggingIn, // Continua editável durante tentativa Microsoft
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            performLocalLogin()
                        }
                    ),
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

                Spacer(modifier = Modifier.height(14.dp))

                val isUsernameValid = username.trim().run {
                    length in 4..16 && MINECRAFT_USERNAME_REGEX.matches(this)
                }

                Button(
                    onClick = {
                        performLocalLogin()
                    },
                    enabled = isUsernameValid && !isLocalLoggingIn,
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
                    if (isMicrosoftLoading || isLocalLoggingIn) return@Button
                    isMicrosoftLoading = true
                    errorMessage = null
                    statusMessage = "Iniciando login com a Microsoft..."

                    authJob = coroutineScope.launch {
                        try {
                            val profile = MicrosoftAuthService.loginWithMicrosoft { msg ->
                                statusMessage = msg
                            }
                            onLoggedIn(profile)
                        } catch (e: CancellationException) {
                            // Cancelamento explícito: não exibe mensagem de erro
                            errorMessage = null
                        } catch (e: Exception) {
                            errorMessage = e.message ?: "Erro na autenticação com a Microsoft."
                        } finally {
                            isMicrosoftLoading = false
                            statusMessage = ""
                            authJob = null
                        }
                    }
                },
                enabled = !isMicrosoftLoading && !isLocalLoggingIn,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0B252E),
                    contentColor = Color(0xFFDCE5DF),
                    disabledContainerColor = Color(0xFF0B252E).copy(alpha = 0.5f),
                    disabledContentColor = Color(0xFFDCE5DF).copy(alpha = 0.4f)
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

            // Status or Cancel option while Microsoft auth is running
            AnimatedVisibility(visible = isMicrosoftLoading) {
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

                    Spacer(modifier = Modifier.height(8.dp))

                    // Explicit "Cancelar login Microsoft" button
                    Text(
                        text = "Cancelar login Microsoft",
                        color = Color(0xFF83B9AD),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(role = Role.Button) {
                                authJob?.cancel()
                                authJob = null
                                isMicrosoftLoading = false
                                statusMessage = ""
                                errorMessage = null
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            AnimatedVisibility(visible = errorMessage != null && !isMicrosoftLoading) {
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
