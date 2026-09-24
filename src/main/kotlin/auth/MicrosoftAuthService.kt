package auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.HttpClientFactory
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

data class MinecraftProfile(
    val id: String,
    val name: String,
    val skinUrl: String? = null
)

object MicrosoftAuthService {
    // Azure App Client ID from Azure App Registration (microsoft.txt)
    // Allows overriding via MINELAB_AZURE_CLIENT_ID environment variable
    val CLIENT_ID: String = System.getenv("MINELAB_AZURE_CLIENT_ID")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "8d8cf468-fbf0-486c-9553-5277622126ef"

    private const val SCOPES = "XboxLive.signin offline_access"
    private const val DEVICE_CODE_ENDPOINT =
        "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode"
    private const val TOKEN_ENDPOINT =
        "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Authenticates via OAuth 2.0 Device Code Flow (no redirect URI / local HTTP server required).
     *
     * @param onStatus  Called with human-readable progress messages (UI status).
     * @param onDeviceCode  Called when the device code step succeeds, providing the user code and
     *                      verification URI so the UI can display them and let the user open the
     *                      browser and copy the code.
     */
    suspend fun loginWithMicrosoft(
        onStatus: (String) -> Unit = {},
        onDeviceCode: ((userCode: String, verificationUri: String) -> Unit)? = null
    ): MinecraftProfile = withContext(Dispatchers.IO) {
        val clientId = CLIENT_ID

        // ── Step 1: Request device code ──────────────────────────────────────
        onStatus("Solicitando código de autenticação...")

        val deviceCodeForm = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", SCOPES)
            .build()

        val deviceCodeRequest = Request.Builder()
            .url(DEVICE_CODE_ENDPOINT)
            .post(deviceCodeForm)
            .build()

        val deviceCodeBody = executeRequestCancellable(deviceCodeRequest)
        val deviceCodeJson = runCatching { json.parseToJsonElement(deviceCodeBody).jsonObject }
            .getOrElse { throw IllegalStateException("Resposta inválida ao solicitar código de dispositivo.") }

        // Surface any device code endpoint errors
        deviceCodeJson["error"]?.jsonPrimitive?.content?.let { err ->
            val desc = deviceCodeJson["error_description"]?.jsonPrimitive?.content ?: ""
            val msg = when {
                err == "invalid_client" && "mobile" in desc ->
                    "O aplicativo não está configurado para fluxo de código de dispositivo. " +
                    "Ative 'Allow public client flows' no portal Azure."
                err == "unauthorized_client" ->
                    "Aplicativo não autorizado para este fluxo. Verifique o registro no Azure."
                else -> "Erro ao iniciar autenticação Microsoft. Tente novamente."
            }
            throw IllegalStateException(msg)
        }

        val deviceCode = deviceCodeJson["device_code"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Código de dispositivo não recebido da Microsoft.")
        val userCode = deviceCodeJson["user_code"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Código de usuário não recebido da Microsoft.")
        val verificationUri = deviceCodeJson["verification_uri"]?.jsonPrimitive?.content
            ?: deviceCodeJson["verification_url"]?.jsonPrimitive?.content
            ?: "https://microsoft.com/link"
        val expiresIn = deviceCodeJson["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 900L
        val pollIntervalSec = deviceCodeJson["interval"]?.jsonPrimitive?.content?.toLongOrNull() ?: 5L

        // Notify UI so it can show the code
        onDeviceCode?.invoke(userCode, verificationUri)
        onStatus("Aguardando autorização no navegador...")

        // ── Step 2: Poll for token ────────────────────────────────────────────
        val msToken = pollForToken(
            clientId = clientId,
            deviceCode = deviceCode,
            pollIntervalMs = pollIntervalSec * 1000L,
            expiresInMs = expiresIn * 1000L
        )

        // ── Step 3: Xbox Live → XSTS → Minecraft Services ───────────────────
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

    /**
     * Polls the token endpoint until authorization is complete, expired, or denied.
     * Respects coroutine cancellation — cancelling the parent Job stops polling immediately.
     */
    private suspend fun pollForToken(
        clientId: String,
        deviceCode: String,
        pollIntervalMs: Long,
        expiresInMs: Long
    ): String = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + expiresInMs
        var currentIntervalMs = pollIntervalMs

        while (isActive) {
            delay(currentIntervalMs)

            if (System.currentTimeMillis() >= deadline) {
                throw IllegalStateException("O código expirou. Por favor, tente novamente.")
            }

            val form = FormBody.Builder()
                .add("client_id", clientId)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .add("device_code", deviceCode)
                .build()

            val request = Request.Builder()
                .url(TOKEN_ENDPOINT)
                .post(form)
                .build()

            // Execute HTTP call in a cancellable way
            val responseBody: String = try {
                executeRequestRaw(request)
            } catch (e: IOException) {
                // Network blip — retry on next interval
                continue
            }

            val responseJson = runCatching { json.parseToJsonElement(responseBody).jsonObject }
                .getOrNull() ?: continue

            val accessToken = responseJson["access_token"]?.jsonPrimitive?.content
            if (!accessToken.isNullOrBlank()) {
                return@withContext accessToken
            }

            val error = responseJson["error"]?.jsonPrimitive?.content ?: continue

            when (error) {
                "authorization_pending" -> {
                    // Normal — user hasn't completed auth yet; keep polling
                }
                "slow_down" -> {
                    // Server requests slower polling
                    currentIntervalMs += 5_000L
                }
                "authorization_declined" -> {
                    throw IllegalStateException("O acesso foi recusado. Tente novamente.")
                }
                "expired_token" -> {
                    throw IllegalStateException("O código expirou. Por favor, tente novamente.")
                }
                else -> {
                    throw IllegalStateException("Erro na autenticação Microsoft. Tente novamente.")
                }
            }
        }

        // Coroutine was cancelled — throw CancellationException to propagate cancel cleanly
        throw kotlinx.coroutines.CancellationException("Login Microsoft cancelado.")
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    /**
     * Executes an OkHttp request in a cancellable coroutine, returning body on 2xx.
     * Throws [IllegalStateException] on non-2xx HTTP or empty body.
     */
    private suspend fun executeRequestCancellable(request: Request): String =
        suspendCancellableCoroutine { cont ->
            val call: Call = HttpClientFactory.client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }

            call.enqueue(object : okhttp3.Callback {
                override fun onResponse(call: Call, response: okhttp3.Response) {
                    response.use { res ->
                        if (!res.isSuccessful) {
                            cont.resumeWithException(
                                IllegalStateException("Serviço de autenticação retornou código HTTP ${res.code}.")
                            )
                            return
                        }
                        val body = res.body?.string()
                        if (body.isNullOrBlank()) {
                            cont.resumeWithException(IllegalStateException("Resposta vazia do servidor."))
                        } else {
                            cont.resume(body)
                        }
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isCancelled) cont.resumeWithException(e)
                }
            })
        }

    /**
     * Like [executeRequestCancellable] but does NOT throw on non-2xx, returning the raw body
     * instead. Used for polling where error semantics are encoded in the JSON body.
     */
    private suspend fun executeRequestRaw(request: Request): String =
        suspendCancellableCoroutine { cont ->
            val call: Call = HttpClientFactory.client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }

            call.enqueue(object : okhttp3.Callback {
                override fun onResponse(call: Call, response: okhttp3.Response) {
                    response.use { res ->
                        val body = res.body?.string()
                        if (body.isNullOrBlank()) {
                            cont.resumeWithException(IOException("Resposta vazia do servidor de token."))
                        } else {
                            cont.resume(body)
                        }
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isCancelled) cont.resumeWithException(e)
                }
            })
        }

    private suspend fun exchangeRequestWithHandling(request: Request, fallbackError: String): String {
        return try {
            executeRequestCancellable(request)
        } catch (e: Exception) {
            throw if (e is IllegalStateException) e else IllegalStateException(fallbackError)
        }
    }

    // ── Xbox Live / XSTS / Minecraft auth chain (unchanged) ─────────────────

    private suspend fun authenticateXboxLive(msToken: String): String {
        val jsonPayload = """
            {
                "Properties": {
                    "AuthMethod": "RPS",
                    "SiteName": "user.auth.xboxlive.com",
                    "RpsTicket": "d=$msToken"
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

        val body = exchangeRequestWithHandling(request, "Falha na comunicação com os serviços Xbox Live.")
        val element = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            throw IllegalStateException("Resposta inválida do serviço Xbox Live.")
        }
        return element["Token"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Token Xbox Live não recebido.")
    }

    private suspend fun authenticateXSTS(xblToken: String): Pair<String, String> {
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

        val body = exchangeRequestWithHandling(request, "Não foi possível autorizar o serviço XSTS da conta Xbox.")
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

    private suspend fun authenticateMinecraft(xstsToken: String, uhs: String): String {
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

        val body = exchangeRequestWithHandling(request, "Não foi possível autenticar a conta nos servidores do Minecraft.")
        val element = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            throw IllegalStateException("Resposta inválida dos serviços Minecraft.")
        }
        return element["access_token"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Token de acesso do Minecraft não encontrado.")
    }

    private suspend fun verifyGameOwnership(mcAccessToken: String) {
        val request = Request.Builder()
            .url("https://api.minecraftservices.com/entitlements/mcstore")
            .get()
            .addHeader("Authorization", "Bearer $mcAccessToken")
            .build()

        val body = try {
            executeRequestCancellable(request)
        } catch (_: Exception) {
            // Entitlements endpoint error does not block profile check
            return
        }
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

    private suspend fun fetchMinecraftProfile(mcAccessToken: String): MinecraftProfile {
        val request = Request.Builder()
            .url("https://api.minecraftservices.com/minecraft/profile")
            .get()
            .addHeader("Authorization", "Bearer $mcAccessToken")
            .build()

        val body = try {
            executeRequestCancellable(request)
        } catch (e: Exception) {
            if (e.message?.contains("404") == true) {
                throw IllegalStateException("Esta conta Microsoft não possui o Minecraft Java ou nenhum perfil criado.")
            }
            throw IllegalStateException("Não foi possível carregar o perfil de jogador do Minecraft.")
        }

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
