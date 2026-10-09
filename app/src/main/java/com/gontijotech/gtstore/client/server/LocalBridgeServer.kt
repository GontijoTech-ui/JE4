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

    /*
     * Cliente usado somente para:
     * - carregar a interface web
     * - encaminhar requisições da interface para o servidor
     *
     * NÃO é usado para baixar/streamar PKG.
     */
    private val proxyClient =
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .build()

    /*
     * Mecanismo de payload/callback/DPI.
     */
    private val payloader =
        Ps4Payloader(context)

    /*
     * Cache dos manifestos locais em memória.
     * Chave: catalogIndex normalizado ("1", "2", ...)
     */
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

    private fun fileError(
        message: String,
        throwable: Throwable? = null
    ) {
        Log.e(tag, message, throwable)

        GTStoreFileLogger.log(
            context,
            tag,
            "ERROR: $message" +
                (throwable?.message?.let { " | $it" } ?: "")
        )
    }

    override fun serve(session: IHTTPSession): Response {

        val uri = session.uri
        val method = session.method

        fileLog("Requisição: $method $uri")

        if (method == Method.OPTIONS) {
            return addCors(
                session,
                newFixedLengthResponse(
                    Response.Status.OK,
                    MIME_PLAINTEXT,
                    ""
                )
            )
        }

        return try {

            when {

                /*
                 * =====================================================
                 * INJEÇÃO LOCAL
                 * =====================================================
                 */
                method == Method.POST &&
                    uri == "/api/local/inject" -> {

                    handleLocalInject(session)
                }

                /*
                 * =====================================================
                 * MANIFESTO DO PS4 (BGFT)
                 * /json/1.json, /manifest/1.json, etc.
                 * =====================================================
                 */
                method == Method.GET &&
                    (
                        uri.startsWith("/json/") ||
                        uri.startsWith("/manifest/") ||
                        uri == "/local-manifest.json"
                    ) -> {

                    handleServeLocalManifest(session)
                }

                /*
                 * =====================================================
                 * INTERFACE WEB
                 * =====================================================
                 */
                uri == "/" ||
                    uri == "/index.html" -> {

                    handleProxyStatic(
                        "$adminHost/index.html",
                        "text/html",
                        session
                    )
                }

                /*
                 * =====================================================
                 * DEMAIS REQUISIÇÕES (PROXY REVERSO DA LOJA)
                 * =====================================================
                 */
                else -> {

                    handleProxyForward(session)
                }
            }

        } catch (e: Exception) {

            fileError(
                "Erro no processamento da rota $uri: ${e.message}",
                e
            )

            val err = JSONObject()
                .put("success", false)
                .put(
                    "error",
                    e.message ?: "Erro interno no LocalBridgeServer"
                )
                .toString()

            addCors(
                session,
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    err
                )
            )
        }
    }

    /*
     * ================================================================
     * INJEÇÃO LOCAL
     * ================================================================
     */
    private fun handleLocalInject(
        session: IHTTPSession
    ): Response {

        val map = HashMap<String, String>()
        session.parseBody(map)

        val postData = map["postData"] ?: "{}"

        fileLog("========================================")
        fileLog("INÍCIO DA INJEÇÃO LOCAL")
        fileLog("JSON recebido da interface:")
        fileLog(postData)

        val json = JSONObject(postData)

        // 1. IP DO PS4
        val ps4Ip = json.optString("ps4Ip").trim()
        if (ps4Ip.isEmpty()) {
            fileWarn("IP do PS4 não informado.")
            return jsonError(
                session,
                Response.Status.BAD_REQUEST,
                "O campo 'ps4Ip' é obrigatório."
            )
        }

        // 2. DADOS DO PACOTE
        val title = json.optString("title", "Jogo PS4").trim()
        val rawCategory = json.optString("category", "gd").trim()
        val bgftCategory = if (rawCategory.startsWith("PS4", ignoreCase = true)) {
            rawCategory.uppercase()
        } else {
            "PS4${rawCategory.uppercase()}"
        }

        // 3. URL DIRETA DO PKG
        val directPkgUrl = (
            json.optString("packageUrl")
                .ifBlank { json.optString("pkgUrl") }
                .ifBlank { json.optString("url") }
                .ifBlank { json.optString("directUrl") }
        ).trim()

        if (directPkgUrl.isEmpty()) {
            fileWarn("Nenhum link direto configurado.")
            return jsonError(
                session,
                Response.Status.BAD_REQUEST,
                "Nenhum link direto configurado para o pacote."
            )
        }

        // 4. CONTENT-ID
        val contentId = (
            json.optString("contentId")
                .ifBlank { json.optString("content_id") }
        ).trim().uppercase()

        if (contentId.isEmpty()) {
            fileWarn("Content-ID não informado pelo catálogo.")
            return jsonError(
                session,
                Response.Status.BAD_REQUEST,
                "Content-ID não informado."
            )
        }

        // 5. CATALOG INDEX NORMALIZADO
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

        // 6. TAMANHO EM BYTES
        val fileSize = json.optLong("size", 0L)
        if (fileSize <= 0L) {
            fileWarn("Tamanho inválido recebido: $fileSize")
            return jsonError(
                session,
                Response.Status.BAD_REQUEST,
                "Tamanho do PKG inválido ou não informado."
            )
        }

        // 7. DIGEST (64 HEXADECIMAIS)
        val rawDigest = json.optString("digest").trim()
        val packageDigest = if (rawDigest.length == 64 && rawDigest.all { it.isLetterOrDigit() }) {
            rawDigest.uppercase()
        } else {
            "0".repeat(64)
        }

        // 8. DECODIFICAÇÃO DA CAPA (BASE64 -> BYTEARRAY)
        val iconBytes: ByteArray? = try {
            val iconStr = (
                json.optString("iconUrl")
                    .ifBlank { json.optString("icon") }
            ).trim()

            if (iconStr.contains("base64,")) {
                val cleanBase64 = iconStr.substringAfter("base64,")
                Base64.decode(cleanBase64, Base64.DEFAULT)
            } else if (iconStr.startsWith("iVBORw0KGgo") || iconStr.startsWith("/9j/")) {
                Base64.decode(iconStr, Base64.DEFAULT)
            } else {
                null
            }
        } catch (e: Exception) {
            fileWarn("Falha ao decodificar ícone em Base64: ${e.message}")
            null
        }

        // 9. IP WI-FI LOCAL DO ANDROID
        val localIp = getLocalWifiAddress()
        if (localIp == null) {
            fileError("Não foi possível encontrar o IP Wi-Fi local do Android.")
            return jsonError(
                session,
                Response.Status.BAD_REQUEST,
                "O aparelho não está conectado ao Wi-Fi local."
            )
        }

        fileLog("PS4        : $ps4Ip")
        fileLog("Android    : $localIp")
        fileLog("Título     : $title")
        fileLog("Índice     : $normalizedCatalogIndex")
        fileLog("Content-ID : $contentId")
        fileLog("Categoria  : $bgftCategory")
        fileLog("Tamanho    : $fileSize bytes")
        fileLog("Digest     : $packageDigest")
        fileLog("Capa bytes : ${iconBytes?.size ?: 0} bytes")
        fileLog("PKG Direto : $directPkgUrl")

        // 10. GERAÇÃO DO MANIFESTO RIGOROSO (BGFT)
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

        // 11. ARMAZENA NO CACHE EM MEMÓRIA
        manifestCache[normalizedCatalogIndex] = manifestJsonString
        lastCatalogIndex = normalizedCatalogIndex

        val localManifestUrl = "http://$localIp:$port/json/$normalizedCatalogIndex.json"
        fileLog("Manifesto criado e disponível em: $localManifestUrl")

        // 12. EXECUTA A INJEÇÃO NO CONSOLE VIA COROUTINES
        fileLog("Disparando injeção no BinLoader ($ps4Ip:9090)...")
        val result = runBlocking {
            payloader.injectDpiPayload(
                ps4Ip = ps4Ip,
                localIp = localIp,
                manifestUrl = localManifestUrl,
                itemTitle = title,
                contentId = contentId,
                category = bgftCategory,
                fileSize = fileSize,
                iconBytes = iconBytes
            )
        }

        if (result.isSuccess) {
            fileLog("Injeção DPI finalizada com sucesso.")
        } else {
            val error = result.exceptionOrNull()
            fileError("Falha na injeção DPI: ${error?.message}", error)
        }

        // 13. RETORNO PARA O CLIENTE WEB
        val responseJson = JSONObject().apply {
            put("success", result.isSuccess)
            put("catalogIndex", normalizedCatalogIndex)
            put("contentId", contentId)
            put("manifestUrl", localManifestUrl)
            put("packageUrl", directPkgUrl)
            if (result.isFailure) {
                put(
                    "error",
                    result.exceptionOrNull()?.message ?: "Falha desconhecida na injeção."
                )
            }
        }.toString()

        return addCors(
            session,
            newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                responseJson
            )
        )
    }

    /*
     * ================================================================
     * SERVIR MANIFESTO (COM FALLBACK ANTI-404)
     * ================================================================
     */
    private fun handleServeLocalManifest(
        session: IHTTPSession
    ): Response {

        val uri = session.uri

        // Extrai a parte da URI referente ao índice
        val rawRequest = when {
            uri.startsWith("/json/") -> uri.removePrefix("/json/").removeSuffix(".json")
            uri.startsWith("/manifest/") -> uri.removePrefix("/manifest/").removeSuffix(".json")
            else -> session.parameters["id"]?.firstOrNull() ?: ""
        }.trim()

        val numericIndex = rawRequest.filter { it.isDigit() }.toLongOrNull()?.toString()

        fileLog("PS4 solicitou manifesto. URI: $uri | Índice bruto: '$rawRequest' | Numérico: '$numericIndex'")

        // Busca com 4 níveis de tolerância a falhas
        val manifest = (if (!numericIndex.isNullOrBlank()) manifestCache[numericIndex] else null)
            ?: manifestCache[rawRequest]
            ?: lastCatalogIndex?.let { manifestCache[it] }
            ?: if (manifestCache.size == 1) manifestCache.values.firstOrNull() else null

        return if (manifest != null) {
            fileLog("Manifesto entregue ao console com sucesso!")

            val response = newFixedLengthResponse(
                Response.Status.OK,
                "application/json; charset=utf-8",
                manifest
            )

            // Evita que o PS4 guarde cache de manifestos obsoletos
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            response.addHeader("Pragma", "no-cache")
            response.addHeader("Expires", "0")

            addCors(session, response)

        } else {
            fileWarn("Manifesto não encontrado para a requisição: $uri")

            addCors(
                session,
                newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "application/json",
                    """{"error":"Manifesto ausente no servidor local"}"""
                )
            )
        }
    }

    /*
     * ================================================================
     * CARREGAMENTO DA INTERFACE WEB
     * ================================================================
     */
    private fun handleProxyStatic(
        targetUrl: String,
        mime: String,
        session: IHTTPSession
    ): Response {

        fileLog("Carregando interface remota: $targetUrl")

        val request = Request.Builder().url(targetUrl).build()

        return proxyClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: "Falha ao carregar interface remota."

            addCors(
                session,
                newFixedLengthResponse(
                    Response.Status.OK,
                    mime,
                    body
                )
            )
        }
    }

    /*
     * ================================================================
     * PROXY REVERSO DE DADOS DA INTERFACE
     * ================================================================
     */
    private fun handleProxyForward(
        session: IHTTPSession
    ): Response {

        val queryString = if (!session.queryParameterString.isNullOrBlank()) {
            "?${session.queryParameterString}"
        } else {
            ""
        }

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
                newFixedLengthResponse(
                    status,
                    contentType,
                    ByteArrayInputStream(bytes),
                    bytes.size.toLong()
                )
            )
        }
    }

    /*
     * ================================================================
     * DETECÇÃO ROBUSTA DO IP WI-FI LOCAL
     * ================================================================
     */
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

    /*
     * ================================================================
     * CABEÇALHOS CORS
     * ================================================================
     */
    private fun addCors(
        session: IHTTPSession,
        response: Response
    ): Response {

        val requestedHeaders = session.headers["access-control-request-headers"]
            ?: "Content-Type, Authorization, Range, X-Requested-With"

        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, DELETE")
        response.addHeader("Access-Control-Allow-Headers", requestedHeaders)
        response.addHeader("Access-Control-Max-Age", "86400")

        return response
    }

    /*
     * ================================================================
     * RESPOSTA PADRÃO DE ERRO JSON
     * ================================================================
     */
    private fun jsonError(
        session: IHTTPSession,
        status: Response.IStatus,
        message: String
    ): Response {

        fileError("Erro HTTP ${status.requestStatus}: $message")

        val json = JSONObject()
            .put("success", false)
            .put("error", message)
            .toString()

        return addCors(
            session,
            newFixedLengthResponse(
                status,
                "application/json",
                json
            )
        )
    }
}
