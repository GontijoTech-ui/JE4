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
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // Sem timeout de leitura para suportar streaming longo
            .build()

    private val payloader = Ps4Payloader(context)

    // Cache de URLs remotas para alimentar o proxy de streaming
    private val remoteUrlCache = ConcurrentHashMap<String, String>()

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

        if (method == Method.OPTIONS) {
            return addCors(
                session,
                newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
            )
        }

        return try {
            when {
                // Rota unificada de injeção de pacotes
                method == Method.POST && uri == "/api/local/inject" -> {
                    handleLocalInject(session)
                }

                // Streaming Proxy Local
                (method == Method.GET || method == Method.HEAD) && uri.startsWith("/stream/") -> {
                    handleStreamProxy(session)
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

    private fun isBgftCompatible(url: String): Boolean {
        // Verifica se a URL contém tokens de expiração que causam conflitos
        if (url.contains("?") && (url.contains("token=", ignoreCase = true) || url.contains("expires=", ignoreCase = true))) {
            return false
        }
        // Limite rígido da API BGFT da Sony
        if (url.length > 220) {
            return false
        }
        // Caracteres que quebram a rota de CDN nativa do console
        if (url.contains("(") || url.contains(")") || url.contains("%2B", ignoreCase = true)) {
            return false
        }
        return true
    }

    private fun handleLocalInject(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        session.parseBody(map)

        val postData = map["postData"] ?: "{}"
        fileLog("========================================")
        fileLog("SOLICITAÇÃO DE INJEÇÃO RECEBIDA:")
        fileLog(postData)

        val json = JSONObject(postData)

        val ps4Ip = json.optString("ps4Ip").trim()
        if (ps4Ip.isEmpty()) {
            return jsonError(session, Response.Status.BAD_REQUEST, "O campo 'ps4Ip' é obrigatório.")
        }

        val localIp = getLocalWifiAddress()
        if (localIp == null) {
            return jsonError(session, Response.Status.BAD_REQUEST, "O aparelho não está conectado ao Wi-Fi local.")
        }

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
            return jsonError(session, Response.Status.BAD_REQUEST, "URL direta do pacote não encontrada.")
        }

        val title = json.optString("title", "Jogo PS4").trim()
        val rawCategory = json.optString("category", "gd").trim()
        val bgftCategory = if (rawCategory.startsWith("PS4", ignoreCase = true)) {
            rawCategory.uppercase()
        } else {
            "PS4${rawCategory.uppercase()}"
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

        // Tenta processar o Base64 do ícone
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

        val finalSuccess: Boolean
        val usedMethod: String
        var errorMessage: String? = null
        val targetDownloadUrl: String

        // A DECISÃO PRINCIPAL: Direto da internet ou através do Proxy local?
        if (isBgftCompatible(directPkgUrl)) {
            usedMethod = "DIRECT_PAYLOAD"
            targetDownloadUrl = directPkgUrl
            fileLog("URL limpa detectada. O PS4 baixará diretamente de: $targetDownloadUrl")
        } else {
            remoteUrlCache[normalizedCatalogIndex] = directPkgUrl
            usedMethod = "LOCAL_PROXY_PAYLOAD"
            targetDownloadUrl = "http://$localIp:$port/stream/$normalizedCatalogIndex.pkg"
            fileLog("URL complexa detectada. O PS4 baixará através do proxy local: $targetDownloadUrl")
        }

        // Executamos a Injeção de Payload APENAS UMA VEZ
        val successFlag = runBlocking {
            try {
                val result = payloader.injectDpiPayload(
                    ps4Ip = ps4Ip,
                    localIp = localIp,
                    downloadUrl = targetDownloadUrl,
                    itemTitle = title,
                    contentId = contentId.ifBlank { "EP0000-CUSA00000_00-0000000000000000" },
                    category = bgftCategory,
                    fileSize = if (fileSize > 0) fileSize else 1024L,
                    iconBytes = iconBytes
                )
                
                if (result.isFailure) {
                    errorMessage = result.exceptionOrNull()?.message
                }
                return@runBlocking result.isSuccess
            } catch (e: Exception) {
                errorMessage = e.message
                return@runBlocking false
            }
        }
        
        finalSuccess = successFlag

        val responseJson = JSONObject().apply {
            put("success", finalSuccess)
            put("method", usedMethod)
            put("catalogIndex", normalizedCatalogIndex)
            put("packageUrl", directPkgUrl)
            if (!finalSuccess) {
                put("error", errorMessage)
            }
        }.toString()

        fileLog("Fim do processo de injeção. Resultado: $responseJson")
        fileLog("========================================")

        return addCors(
            session,
            newFixedLengthResponse(Response.Status.OK, "application/json", responseJson)
        )
    }

    private fun handleStreamProxy(session: IHTTPSession): Response {
        val uri = session.uri
        val catalogIndex = uri.removePrefix("/stream/").removeSuffix(".pkg").trim()

        val targetUrl = remoteUrlCache[catalogIndex]
        if (targetUrl.isNullOrBlank()) {
            fileWarn("URL externa de streaming não encontrada para o índice $catalogIndex")
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Ficheiro não configurado.")
        }

        val rangeHeader = session.headers["range"]
        fileLog("Requisição de Stream da PS4 [$catalogIndex] | Range: $rangeHeader")

        val reqBuilder = Request.Builder()
            .url(targetUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (PlayStation 4 11.50) AppleWebKit/605.1.15")

        if (!rangeHeader.isNullOrBlank()) {
            reqBuilder.addHeader("Range", rangeHeader)
        }

        val remoteResponse = proxyClient.newCall(reqBuilder.build()).execute()
        val responseBody = remoteResponse.body ?: return newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR, "text/plain", "Falha de resposta no servidor remoto."
        )

        val contentLength = responseBody.contentLength()
        val contentType = remoteResponse.header("Content-Type") ?: "application/octet-stream"
        val contentRange = remoteResponse.header("Content-Range")

        val status = if (remoteResponse.code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK

        val response = newFixedLengthResponse(
            status,
            contentType,
            responseBody.byteStream(),
            contentLength
        )

        response.addHeader("Accept-Ranges", "bytes")
        if (!contentRange.isNullOrBlank()) {
            response.addHeader("Content-Range", contentRange)
        }

        return addCors(session, response)
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
            fileError("Erro ao detetar IP local: ${e.message}", e)
        }
        return null
    }

    private fun addCors(session: IHTTPSession, response: Response): Response {
        val requestedHeaders = session.headers["access-control-request-headers"]
            ?: "Content-Type, Authorization, Range, X-Requested-With"

        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, DELETE, HEAD")
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
