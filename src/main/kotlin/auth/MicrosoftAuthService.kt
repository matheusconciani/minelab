package auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.HttpClientFactory
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import com.sun.net.httpserver.HttpServer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

data class MinecraftProfile(
    val id: String,
    val name: String,
    val skinUrl: String? = null
)

object MicrosoftAuthService {
    // Azure App Client ID. Can be configured via environment variable `MINELAB_AZURE_CLIENT_ID`
    // Default fallback to standard public client ID for desktop OAuth
    private val CLIENT_ID: String = System.getenv("MINELAB_AZURE_CLIENT_ID")
        ?.takeIf { it.isNotBlank() }
        ?: "00000000402b5328"

    private const val REDIRECT_PORT = 28549
    private const val REDIRECT_URI = "http://localhost:$REDIRECT_PORT/callback"
    private const val SCOPES = "XboxLive.signin offline_access"

    private val json = Json { ignoreUnknownKeys = true }
    private val secureRandom = SecureRandom()

    suspend fun loginWithMicrosoft(onStatus: (String) -> Unit = {}): MinecraftProfile = withContext(Dispatchers.IO) {
        // Generate random state and PKCE code verifier / challenge
        val state = generateRandomString(32)
        val codeVerifier = generateRandomString(64)
        val codeChallenge = generateCodeChallenge(codeVerifier)

        onStatus("Abrindo o navegador para autenticação...")
        val authCode = try {
            withTimeout(120_000L) { // 2 minutes timeout
                waitForAuthCode(expectedState = state, codeChallenge = codeChallenge)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw IllegalStateException("A autenticação expirou. Por favor, tente novamente.")
        }

        onStatus("Validando autorização da Microsoft...")
        val msToken = exchangeAuthCodeForMicrosoftToken(authCode, codeVerifier)

        onStatus("Autenticando no Xbox Live...")
        val xblToken = authenticateXboxLive(msToken)

        onStatus("Obtendo autorização XSTS...")
        val xsts = authenticateXSTS(xblToken)

        onStatus("Autenticando na conta do Minecraft...")
        val mcToken = authenticateMinecraft(xsts.first, xsts.second)

        onStatus("Verificando posse do jogo...")
        verifyGameOwnership(mcToken)

        onStatus("Carregando perfil do jogador...")
        fetchMinecraftProfile(mcToken)
    }

    private suspend fun waitForAuthCode(expectedState: String, codeChallenge: String): String =
        suspendCancellableCoroutine { continuation ->
            val isCompleted = AtomicBoolean(false)
            var server: HttpServer? = null

            fun safeCloseServer() {
                Thread {
                    try {
                        Thread.sleep(300)
                        server?.stop(0)
                    } catch (_: Exception) {}
                }.start()
            }

            try {
                server = HttpServer.create(InetSocketAddress("localhost", REDIRECT_PORT), 0)
                server.createContext("/callback") { exchange ->
                    try {
                        val query = exchange.requestURI.query ?: ""
                        val params = query.split("&").mapNotNull {
                            val parts = it.split("=")
                            if (parts.isNotEmpty()) {
                                val key = java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8.toString())
                                val value = if (parts.size > 1) java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8.toString()) else ""
                                key to value
                            } else null
                        }.toMap()

                        val receivedState = params["state"]
                        val code = params["code"]
                        val error = params["error"]
                        val errorDesc = params["error_description"]

                        val isSuccess = code != null && receivedState == expectedState

                        val html = if (isSuccess) {
                            "<!DOCTYPE html><html><head><meta charset='utf-8'><title>Sucesso</title></head>" +
                            "<body style='font-family:system-ui,sans-serif;text-align:center;padding:50px;background:#031419;color:#dce5df;'>" +
                            "<h2 style='color:#83b9ad;'>Autenticado com sucesso!</h2>" +
                            "<p>Você já pode fechar esta janela e voltar para o aplicativo.</p></body></html>"
                        } else {
                            "<!DOCTYPE html><html><head><meta charset='utf-8'><title>Erro</title></head>" +
                            "<body style='font-family:system-ui,sans-serif;text-align:center;padding:50px;background:#031419;color:#dce5df;'>" +
                            "<h2 style='color:#ff7b7b;'>Falha na autenticação</h2>" +
                            "<p>A autenticação não pôde ser concluída. Pode fechar esta janela.</p></body></html>"
                        }

                        val responseBytes = html.toByteArray(StandardCharsets.UTF_8)
                        exchange.responseHeaders.set("Content-Type", "text/html; charset=UTF-8")
                        exchange.sendResponseHeaders(200, responseBytes.size.toLong())
                        exchange.responseBody.write(responseBytes)
                        exchange.responseBody.close()

                        if (isCompleted.compareAndSet(false, true)) {
                            safeCloseServer()
                            if (code != null) {
                                if (receivedState != expectedState) {
                                    continuation.resumeWithException(
                                        IllegalStateException("Falha na validação de segurança do estado OAuth.")
                                    )
                                } else {
                                    continuation.resume(code)
                                }
                            } else {
                                val userMessage = when (error) {
                                    "access_denied" -> "O login foi cancelado pelo usuário."
                                    "invalid_request" -> "Configuração de redirecionamento ou credencial inválida no servidor da Microsoft."
                                    else -> "Autenticação não autorizada ou cancelada."
                                }
                                continuation.resumeWithException(IllegalStateException(userMessage))
                            }
                        }
                    } catch (e: Exception) {
                        if (isCompleted.compareAndSet(false, true)) {
                            safeCloseServer()
                            continuation.resumeWithException(e)
                        }
                    }
                }
                server.start()

                val encodedRedirect = URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8.toString())
                val encodedScope = URLEncoder.encode(SCOPES, StandardCharsets.UTF_8.toString())
                val encodedState = URLEncoder.encode(expectedState, StandardCharsets.UTF_8.toString())
                val encodedChallenge = URLEncoder.encode(codeChallenge, StandardCharsets.UTF_8.toString())

                val loginUrl = "https://login.live.com/oauth20_authorize.srf" +
                        "?client_id=$CLIENT_ID" +
                        "&response_type=code" +
                        "&redirect_uri=$encodedRedirect" +
                        "&scope=$encodedScope" +
                        "&state=$encodedState" +
                        "&code_challenge=$encodedChallenge" +
                        "&code_challenge_method=S256"

                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI(loginUrl))
                } else {
                    val os = System.getProperty("os.name").lowercase()
                    if (os.contains("win")) {
                        ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", loginUrl).start()
                    }
                }
            } catch (e: Exception) {
                if (isCompleted.compareAndSet(false, true)) {
                    safeCloseServer()
                    continuation.resumeWithException(
                        IllegalStateException("Não foi possível iniciar o receptor de login local: ${e.message}")
                    )
                }
            }

            continuation.invokeOnCancellation {
                if (isCompleted.compareAndSet(false, true)) {
                    safeCloseServer()
                }
            }
        }

    private fun exchangeAuthCodeForMicrosoftToken(code: String, codeVerifier: String): String {
        val form = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("code", code)
            .add("grant_type", "authorization_code")
            .add("redirect_uri", REDIRECT_URI)
            .add("code_verifier", codeVerifier)
            .build()

        val request = Request.Builder()
            .url("https://login.live.com/oauth20_token.srf")
            .post(form)
            .build()

        HttpClientFactory.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Não foi possível trocar o código de autorização da Microsoft.")
            }
            val body = response.body?.string() ?: throw IllegalStateException("Resposta vazia da Microsoft.")
            val element = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
                throw IllegalStateException("Resposta inválida dos servidores da Microsoft.")
            }
            return element["access_token"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("Token de acesso não encontrado na resposta da Microsoft.")
        }
    }

    private fun authenticateXboxLive(msToken: String): String {
        val jsonPayload = """
            {
                "Properties": {
                    "AuthMethod": "RPS",
                    "SiteName": "user.auth.xboxlive.com",
                    "RpsTicket": "$msToken"
                },
                "RelyingParty": "http://auth.xboxlive.com",
                "TokenType": "JWT"
            }
        """.trimIndent()

        val request = Request.Builder()
            .url("https://user.auth.xboxlive.com/user/authenticate")
            .post(jsonPayload.toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .build()

        HttpClientFactory.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Falha na comunicação com os serviços Xbox Live.")
            }
            val body = response.body?.string() ?: throw IllegalStateException("Resposta vazia do Xbox Live.")
            val element = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
                throw IllegalStateException("Resposta inválida do serviço Xbox Live.")
            }
            return element["Token"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("Token Xbox Live não recebido.")
        }
    }

    private fun authenticateXSTS(xblToken: String): Pair<String, String> {
        val jsonPayload = """
            {
                "Properties": {
                    "SandboxId": "RETAIL",
                    "UserTokens": ["$xblToken"]
                },
                "RelyingParty": "rp://api.minecraftservices.com/",
                "TokenType": "JWT"
            }
        """.trimIndent()

        val request = Request.Builder()
            .url("https://xsts.auth.xboxlive.com/xsts/authorize")
            .post(jsonPayload.toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .build()

        HttpClientFactory.client.newCall(request).execute().use { response ->
            if (response.code == 401) {
                throw IllegalStateException("Conta Microsoft sem conta Xbox vinculada ou bloqueada por controle dos pais.")
            }
            if (!response.isSuccessful) {
                throw IllegalStateException("Não foi possível autorizar o serviço XSTS da conta Xbox.")
            }
            val body = response.body?.string() ?: throw IllegalStateException("Resposta vazia do serviço XSTS.")
            val element = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
                throw IllegalStateException("Resposta inválida do serviço XSTS.")
            }
            val token = element["Token"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("Token XSTS ausente.")
            val uhs = element["DisplayClaims"]?.jsonObject
                ?.get("xui")?.let { runCatching { json.parseToJsonElement(it.toString()) }.getOrNull() }
                ?.let { (it as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.jsonObject?.get("uhs")?.jsonPrimitive?.content }
                ?: throw IllegalStateException("Identificador de usuário do Xbox ausente.")
            return Pair(token, uhs)
        }
    }

    private fun authenticateMinecraft(xstsToken: String, uhs: String): String {
        val jsonPayload = """
            {
                "identityToken": "XBL3.0 x=$uhs;$xstsToken"
            }
        """.trimIndent()

        val request = Request.Builder()
            .url("https://api.minecraftservices.com/authentication/login_with_xbox")
            .post(jsonPayload.toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .build()

        HttpClientFactory.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Não foi possível autenticar a conta nos servidores do Minecraft.")
            }
            val body = response.body?.string() ?: throw IllegalStateException("Resposta vazia dos serviços Minecraft.")
            val element = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
                throw IllegalStateException("Resposta inválida dos serviços Minecraft.")
            }
            return element["access_token"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("Token de acesso do Minecraft não encontrado.")
        }
    }

    private fun verifyGameOwnership(mcAccessToken: String) {
        val request = Request.Builder()
            .url("https://api.minecraftservices.com/entitlements/mcstore")
            .get()
            .addHeader("Authorization", "Bearer $mcAccessToken")
            .build()

        HttpClientFactory.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // If entitlements endpoint fails or returns error, check profile will be definitive
                return
            }
            val body = response.body?.string() ?: return
            val element = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return
            val items = element["items"] as? kotlinx.serialization.json.JsonArray
            val ownsGame = items?.any {
                val name = it.jsonObject["name"]?.jsonPrimitive?.content
                name == "product_minecraft" || name == "game_minecraft"
            } ?: false

            if (items != null && items.isNotEmpty() && !ownsGame) {
                throw IllegalStateException("Esta conta Microsoft não possui uma licença do Minecraft Java Edition.")
            }
        }
    }

    private fun fetchMinecraftProfile(mcAccessToken: String): MinecraftProfile {
        val request = Request.Builder()
            .url("https://api.minecraftservices.com/minecraft/profile")
            .get()
            .addHeader("Authorization", "Bearer $mcAccessToken")
            .build()

        HttpClientFactory.client.newCall(request).execute().use { response ->
            if (response.code == 404) {
                throw IllegalStateException("Esta conta Microsoft não possui o Minecraft Java ou nenhum perfil criado.")
            }
            if (!response.isSuccessful) {
                throw IllegalStateException("Não foi possível carregar o perfil de jogador do Minecraft.")
            }
            val body = response.body?.string() ?: throw IllegalStateException("Resposta vazia do perfil do Minecraft.")
            val element = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
                throw IllegalStateException("Resposta inválida do perfil do Minecraft.")
            }
            val id = element["id"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("ID do perfil do Minecraft não encontrado.")
            val name = element["name"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("Nome do jogador não encontrado.")
            val skins = element["skins"] as? kotlinx.serialization.json.JsonArray
            val activeSkin = skins?.firstOrNull { it.jsonObject["state"]?.jsonPrimitive?.content == "ACTIVE" }
                ?: skins?.firstOrNull()
            val skinUrl = activeSkin?.jsonObject?.get("url")?.jsonPrimitive?.content
            return MinecraftProfile(id = id, name = name, skinUrl = skinUrl)
        }
    }

    private fun generateRandomString(length: Int): String {
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun generateCodeChallenge(codeVerifier: String): String {
        val bytes = codeVerifier.toByteArray(StandardCharsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
