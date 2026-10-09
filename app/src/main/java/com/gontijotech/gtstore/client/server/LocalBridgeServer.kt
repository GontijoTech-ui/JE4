package com.gontijotech.gtstore.client.server

import android.content.Context
import android.util.Base64
import android.util.Log
import com.gontijotech.gtstore.client.GTStoreFileLogger
import com.gontijotech.gtstore.client.network.Ps4Payloader
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class LocalBridgeServer(
    private val context: Context,
    private val port: Int = 8080,
    private val adminHost: String = "https://loja.gontijotech.com.br"
) : NanoHTTPD(port) {

    private val tag = "GTStore-Bridge"

    private val proxyClient =
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .build()

    // Cliente com timeout curto para checar a porta 12800 sem travar a interface
    private val rpiClient =
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

    private val payloader =
        Ps4Payloader(context)

    private val manifestCache =
        ConcurrentHashMap<String, String>()

    @Volatile
    private var lastCatalogIndex: String? = null

    private fun fileLog(message: String) {
        Log.i(tag, message)
        GTStoreFileLogger.log(context, tag, message)
    }

    private fun fileWarn(message: String) {
        Log.w(tag, message)
        GTStoreFileLogger.log(context, tag, "WARN: $message")
    }

    private fun fileError(message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        GTStoreFileLogger.log(
            context,
            tag,
            "ERROR: $message" + (throwable?.message?.let { " | $it" } ?: "")
        )
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        fileLog("Requisição: $method $uri")

        if (method == Method.OPTIONS) {
            return addCors(
                session,
                newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
            )
        }

        return try {
            when {
                method == Method.POST && uri == "/api/local/inject" -> {
                    handleLocalInject(session)
                }

                method == Method.GET && (
                    uri.startsWith("/json/") ||
                    uri.startsWith("/manifest/") ||
                    uri == "/local-manifest.json"
                ) -> {
                    handleServeLocalManifest(session)
                }

                uri == "/" || uri == "/index.html" -> {
                    handleProxyStatic("$adminHost/index.html", "text/html", session)
                }

                else -> {
                    handleProxyForward(session)
                }
            }
        } catch (e: Exception) {
            fileError("Erro no processamento da rota $uri: ${e.message}", e)
            val err = JSONObject()
                .put("success", false)
                .put("error", e.message ?: "Erro interno no LocalBridgeServer")
                .toString()

            addCors(
                session,
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", err)
            )
        }
    }

    /*
     * ================================================================
     * INJEÇÃO (PORTA 12800 COM FALLBACK PARA 9090)
     * ================================================================
     */
    private fun handleLocalInject(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        session.parseBody(map)

        val postData = map["postData"] ?: "{}"
        fileLog("========================================")
        fileLog("INÍCIO DA INJEÇÃO")
        fileLog(postData)

        val json = JSONObject(postData)

        val ps4Ip = json.optString("ps4Ip").trim()
        if (ps4Ip.isEmpty()) {
            fileWarn("IP do PS4 não informado.")
            return jsonError(session, Response.Status.BAD_REQUEST, "O campo 'ps4Ip' é obrigatório.")
        }

        val title = json.optString("title", "Jogo PS4").trim()
        val rawCategory = json.optString("category", "gd").trim()
        val bgftCategory = if (rawCategory.startsWith("PS4", ignoreCase = true)) {
            rawCategory.uppercase()
        } else {
            "PS4${rawCategory.uppercase()}"
        }

        // Sanitiza a URL garantindo scheme minúsculo
        var directPkgUrl = (
            json.optString("packageUrl")
                .ifBlank { json.optString("pkgUrl") }
                .ifBlank { json.optString("url") }
                .ifBlank { json.optString("directUrl") }
        ).trim()

        if (directPkgUrl.startsWith("Https://", ignoreCase = false)) {
            directPkgUrl = "https://" + directPkgUrl.removePrefix("Https://")
        } else if (directPkgUrl.startsWith("Http://", ignoreCase = false)) {
            directPkgUrl = "http://" + directPkgUrl.removePrefix("Http://")
        }

        if (directPkgUrl.isEmpty()) {
            fileWarn("Nenhum link direto configurado.")
            return jsonError(session, Response.Status.BAD_REQUEST, "Nenhum link direto configurado para o pacote.")
        }

        val contentId = (
            json.optString("contentId")
                .ifBlank { json.optString("content_id") }
        ).trim().uppercase()

        val rawCatalogIndex = (
            json.optString("catalogIndex")
                .ifBlank { json.optString("id") }
                .ifBlank { json.optString("index") }
        ).trim()

        val normalizedCatalogIndex = rawCatalogIndex
            .filter { it.isDigit() }
            .toLongOrNull()
            ?.toString()
            ?: rawCatalogIndex.ifBlank { "1" }

        val fileSize = json.optLong("size", 0L)

        val rawDigest = json.optString("digest").trim()
        val packageDigest = if (rawDigest.length == 64 && rawDigest.all { it.isLetterOrDigit() }) {
            rawDigest.uppercase()
        } else {
            "0".repeat(64)
        }

        val iconBytes: ByteArray? = try {
            val iconStr = (
                json.optString("iconUrl")
                    .ifBlank { json.optString("icon") }
            ).trim()

            if (iconStr.contains("base64,")) {
                Base64.decode(iconStr.substringAfter("base64,"), Base64.DEFAULT)
            } else if (iconStr.startsWith("iVBORw0KGgo") || iconStr.startsWith("/9j/")) {
                Base64.decode(iconStr, Base64.DEFAULT)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

        fileLog("Tentando envio via GoldHEN RPI (Porta 12800)...")

        // 1. TENTA PRIMEIRO PELA PORTA 12800 (RPI NATIVO DO GOLDHEN)
        val rpiResult = sendViaRpiPort12800(ps4Ip, directPkgUrl)

        val finalSuccess: Boolean
        var usedMethod = "RPI_12800"
        var errorMessage: String? = null

        if (rpiResult.isSuccess) {
            finalSuccess = true
            fileLog("✓ Sucesso imediato via GoldHEN RPI (Porta 12800)!")
        } else {
            fileWarn("Porta 12800 indisponível (${rpiResult.exceptionOrNull()?.message}). Recorrendo ao BGFT/DPI (Porta 9090)...")
            usedMethod = "BGFT_9090"

            val localIp = getLocalWifiAddress()
            if (localIp == null) {
                return jsonError(session, Response.Status.BAD_REQUEST, "O aparelho não está conectado ao Wi-Fi local.")
            }

            // Monta o manifesto BGFT de contingência
            val manifestJsonString = JSONObject().apply {
                put("originalFileSize", fileSize)
                put("packageDigest", packageDigest)
                put("numberOfSplitFiles", 1)
                put(
                    "pieces",
                    JSONArray().apply {
                        put(
                            JSONObject().apply {
                                put("url", directPkgUrl)
                                put("fileOffset", 0L)
                                put("fileSize", fileSize)
                                put("hashValue", "0000000000000000000000000000000000000000")
                            }
                        )
                    }
                )
            }.toString()

            manifestCache[normalizedCatalogIndex] = manifestJsonString
            lastCatalogIndex = normalizedCatalogIndex

            val localManifestUrl = "http://$localIp:$port/json/$normalizedCatalogIndex.json"

            val dpiResult = runBlocking {
                payloader.injectDpiPayload(
                    ps4Ip = ps4Ip,
                    localIp = localIp,
                    manifestUrl = localManifestUrl,
                    itemTitle = title,
                    contentId = contentId.ifBlank { "EP0000-CUSA00000_00-0000000000000000" },
                    category = bgftCategory,
                    fileSize = if (fileSize > 0) fileSize else 1024L,
                    iconBytes = iconBytes
                )
            }

            finalSuccess = dpiResult.isSuccess
            if (!finalSuccess) {
                errorMessage = dpiResult.exceptionOrNull()?.message ?: "Falha em ambos os métodos (12800 e 9090)."
            }
        }

        val responseJson = JSONObject().apply {
            put("success", finalSuccess)
            put("method", usedMethod)
            put("catalogIndex", normalizedCatalogIndex)
            put("packageUrl", directPkgUrl)
            if (!finalSuccess) {
                put("error", errorMessage)
            }
        }.toString()

        fileLog("Fim da injeção. Resultado: $responseJson")
        fileLog("========================================")

        return addCors(
            session,
            newFixedLengthResponse(Response.Status.OK, "application/json", responseJson)
        )
    }

    /**
     * Envia o link diretamente para a porta 12800 do GoldHEN.
     * Não valida digest, aceita URLs longas com tokens e não exige app aberto no console.
     */
    private fun sendViaRpiPort12800(ps4Ip: String, directPkgUrl: String): Result<Unit> {
        return try {
            val rpiUrl = "http://$ps4Ip:12800/api/install"

            val payload = JSONObject().apply {
                put("type", "direct")
                put("packages", JSONArray().apply { put(directPkgUrl) })
            }.toString()

            val request = Request.Builder()
                .url(rpiUrl)
                .post(payload.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            rpiClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                fileLog("Resposta da porta 12800 [HTTP ${response.code}]: $body")

                if (response.isSuccessful && (body.contains("success", ignoreCase = true) || body.contains("task_id", ignoreCase = true))) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("HTTP ${response.code}: $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /*
     * ================================================================
     * SERVIR MANIFESTO (BGFT)
     * ================================================================
     */
    private fun handleServeLocalManifest(session: IHTTPSession): Response {
        val uri = session.uri
        val rawRequest = when {
            uri.startsWith("/json/") -> uri.removePrefix("/json/").removeSuffix(".json")
            uri.startsWith("/manifest/") -> uri.removePrefix("/manifest/").removeSuffix(".json")
            else -> session.parameters["id"]?.firstOrNull() ?: ""
        }.trim()

        val numericIndex = rawRequest.filter { it.isDigit() }.toLongOrNull()?.toString()
        val manifest = (if (!numericIndex.isNullOrBlank()) manifestCache[numericIndex] else null)
            ?: manifestCache[rawRequest]
            ?: lastCatalogIndex?.let { manifestCache[it] }
            ?: if (manifestCache.size == 1) manifestCache.values.firstOrNull() else null

        return if (manifest != null) {
            val response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", manifest)
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            response.addHeader("Pragma", "no-cache")
            response.addHeader("Expires", "0")
            addCors(session, response)
        } else {
            addCors(
                session,
                newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"Manifesto ausente"}""")
            )
        }
    }

    private fun handleProxyStatic(targetUrl: String, mime: String, session: IHTTPSession): Response {
        val request = Request.Builder().url(targetUrl).build()
        return proxyClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: "Falha ao carregar interface remota."
            addCors(session, newFixedLengthResponse(Response.Status.OK, mime, body))
        }
    }

    private fun handleProxyForward(session: IHTTPSession): Response {
        val queryString = if (!session.queryParameterString.isNullOrBlank()) "?${session.queryParameterString}" else ""
        val targetUrl = "$adminHost${session.uri}$queryString"
        val requestBuilder = Request.Builder().url(targetUrl)

        when (session.method) {
            Method.POST -> {
                val map = HashMap<String, String>()
                session.parseBody(map)
                val bodyText = map["postData"] ?: ""
                val contentType = session.headers["content-type"] ?: "application/json"
                requestBuilder.post(bodyText.toRequestBody(contentType.toMediaTypeOrNull()))
            }
            Method.PUT -> {
                val map = HashMap<String, String>()
                session.parseBody(map)
                val bodyText = map["postData"] ?: ""
                val contentType = session.headers["content-type"] ?: "application/json"
                requestBuilder.put(bodyText.toRequestBody(contentType.toMediaTypeOrNull()))
            }
            Method.DELETE -> requestBuilder.delete()
            Method.HEAD -> requestBuilder.head()
            else -> requestBuilder.get()
        }

        return proxyClient.newCall(requestBuilder.build()).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            val contentType = response.header("Content-Type") ?: "application/octet-stream"
            val status = Response.Status.lookup(response.code)
                ?: object : Response.IStatus {
                    override fun getRequestStatus(): Int = response.code
                    override fun getDescription(): String = response.message
                }

            addCors(
                session,
                newFixedLengthResponse(status, contentType, ByteArrayInputStream(bytes), bytes.size.toLong())
            )
        }
    }

    private fun getLocalWifiAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            val validInterfaces = interfaces.filter { networkInterface ->
                val name = networkInterface.name.lowercase()
                networkInterface.isUp &&
                    !networkInterface.isLoopback &&
                    !name.contains("tun") &&
                    !name.contains("tap") &&
                    !name.contains("rmnet") &&
                    !name.contains("pdp") &&
                    !name.contains("dummy")
            }.sortedByDescending {
                it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("eth")
            }

            for (networkInterface in validInterfaces) {
                for (address in networkInterface.inetAddresses) {
                    if (!address.isLoopbackAddress && address is Inet4Address && address.isSiteLocalAddress) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            fileError("Erro ao detectar IP local: ${e.message}", e)
        }
        return null
    }

    private fun addCors(session: IHTTPSession, response: Response): Response {
        val requestedHeaders = session.headers["access-control-request-headers"]
            ?: "Content-Type, Authorization, Range, X-Requested-With"

        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, DELETE")
        response.addHeader("Access-Control-Allow-Headers", requestedHeaders)
        response.addHeader("Access-Control-Max-Age", "86400")
        return response
    }

    private fun jsonError(session: IHTTPSession, status: Response.IStatus, message: String): Response {
        fileError("Erro HTTP ${status.requestStatus}: $message")
        val json = JSONObject().put("success", false).put("error", message).toString()
        return addCors(session, newFixedLengthResponse(status, "application/json", json))
    }
}
