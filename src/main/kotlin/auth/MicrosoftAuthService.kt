package auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import com.sun.net.httpserver.HttpServer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

data class MinecraftProfile(
    val id: String,
    val name: String,
    val skinUrl: String? = null
)

object MicrosoftAuthService {
    // Standard Azure public client ID commonly used for Minecraft launcher authentication
    // (Minecraft launcher client ID: 00000000402b5328 or Azure standard client)
    private const val CLIENT_ID = "00000000402b5328"
    private const val REDIRECT_URI = "http://localhost:28549/callback"
    private const val SCOPES = "XboxLive.signin offline_access"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun loginWithMicrosoft(onStatus: (String) -> Unit = {}): MinecraftProfile = withContext(Dispatchers.IO) {
        onStatus("Abrindo navegador para autenticação...")
        val authCode = waitForAuthCode()

        onStatus("Obtendo token da Microsoft...")
        val msToken = exchangeAuthCodeForMicrosoftToken(authCode)

        onStatus("Autenticando no Xbox Live...")
        val xblToken = authenticateXboxLive(msToken)

        onStatus("Obtendo autorização XSTS...")
        val xsts = authenticateXSTS(xblToken)

        onStatus("Autenticando no Minecraft...")
        val mcToken = authenticateMinecraft(xsts.first, xsts.second)

        onStatus("Carregando perfil do Minecraft...")
        fetchMinecraftProfile(mcToken)
    }

    private suspend fun waitForAuthCode(): String = suspendCancellableCoroutine { continuation ->
        var server: HttpServer? = null
        try {
            server = HttpServer.create(InetSocketAddress(28549), 0)
            server.createContext("/callback") { exchange ->
                try {
                    val query = exchange.requestURI.query ?: ""
                    val params = query.split("&").associate {
                        val parts = it.split("=")
                        if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
                    }

                    val code = params["code"]
                    val error = params["error"]

                    val responseText = if (code != null) {
                        "<html><body style='font-family:sans-serif;text-align:center;padding-top:40px;background:#031419;color:#dce5df;'>" +
                                "<h2>Autenticado com sucesso!</h2><p>Você pode fechar esta aba e retornar ao inicializador.</p></body></html>"
                    } else {
                        "<html><body style='font-family:sans-serif;text-align:center;padding-top:40px;background:#031419;color:#ff6b6b;'>" +
                                "<h2>Erro na autenticação</h2><p>${error ?: "Código não recebido"}</p></body></html>"
                    }

                    val responseBytes = responseText.toByteArray(StandardCharsets.UTF_8)
                    exchange.responseHeaders.set("Content-Type", "text/html; charset=UTF-8")
                    exchange.sendResponseHeaders(200, responseBytes.size.toLong())
                    exchange.responseBody.write(responseBytes)
                    exchange.responseBody.close()

                    if (code != null) {
                        continuation.resume(code)
                    } else {
                        continuation.resumeWithException(IllegalStateException(error ?: "Login cancelado"))
                    }
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                } finally {
                    Thread {
                        Thread.sleep(500)
                        server?.stop(0)
                    }.start()
                }
            }
            server.start()

            val loginUrl = "https://login.live.com/oauth20_authorize.srf" +
                    "?client_id=$CLIENT_ID" +
                    "&response_type=code" +
                    "&redirect_uri=${URI(REDIRECT_URI)}" +
                    "&scope=${SCOPES.replace(" ", "%20")}"

            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(loginUrl))
            } else {
                val os = System.getProperty("os.name").lowercase()
                if (os.contains("win")) {
                    ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", loginUrl).start()
                }
            }
        } catch (e: Exception) {
            server?.stop(0)
            continuation.resumeWithException(e)
        }

        continuation.invokeOnCancellation {
            server?.stop(0)
        }
    }

    private fun exchangeAuthCodeForMicrosoftToken(code: String): String {
        val form = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("code", code)
            .add("grant_type", "authorization_code")
            .add("redirect_uri", REDIRECT_URI)
            .build()

        val request = Request.Builder()
            .url("https://login.live.com/oauth20_token.srf")
            .post(form)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw IllegalStateException("Resposta vazia da Microsoft")
            val element = json.parseToJsonElement(body).jsonObject
            return element["access_token"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("Token não recebido da Microsoft: $body")
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

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw IllegalStateException("Resposta vazia do Xbox Live")
            val element = json.parseToJsonElement(body).jsonObject
            return element["Token"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("Falha ao obter token XBL: $body")
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

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw IllegalStateException("Resposta vazia do XSTS")
            val element = json.parseToJsonElement(body).jsonObject
            val token = element["Token"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("Falha ao obter token XSTS: $body")
            val uhs = element["DisplayClaims"]?.jsonObject
                ?.get("xui")?.let { json.parseToJsonElement(it.toString()) }
                ?.let { (it as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.jsonObject?.get("uhs")?.jsonPrimitive?.content }
                ?: throw IllegalStateException("Falha ao obter UHS: $body")
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

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw IllegalStateException("Resposta vazia do Minecraft Services")
            val element = json.parseToJsonElement(body).jsonObject
            return element["access_token"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("Falha na autenticação do Minecraft: $body")
        }
    }

    private fun fetchMinecraftProfile(mcAccessToken: String): MinecraftProfile {
        val request = Request.Builder()
            .url("https://api.minecraftservices.com/minecraft/profile")
            .get()
            .addHeader("Authorization", "Bearer $mcAccessToken")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw IllegalStateException("Resposta vazia do perfil do Minecraft")
            val element = json.parseToJsonElement(body).jsonObject
            val id = element["id"]?.jsonPrimitive?.content ?: ""
            val name = element["name"]?.jsonPrimitive?.content ?: "Minecraft Player"
            val skins = element["skins"] as? kotlinx.serialization.json.JsonArray
            val activeSkin = skins?.firstOrNull { it.jsonObject["state"]?.jsonPrimitive?.content == "ACTIVE" }
                ?: skins?.firstOrNull()
            val skinUrl = activeSkin?.jsonObject?.get("url")?.jsonPrimitive?.content
            return MinecraftProfile(id = id, name = name, skinUrl = skinUrl)
        }
    }
}
