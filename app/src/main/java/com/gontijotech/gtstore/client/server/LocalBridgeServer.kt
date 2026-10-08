package com.gontijotech.gtstore.client.server

import android.content.Context
import android.util.Log
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
import java.io.InputStream
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

    private val payloader =
        Ps4Payloader(context)

    /**
     * Cache dos manifestos.
     *
     * Chave:
     * Content-ID
     *
     * Valor:
     * JSON do manifesto BGFT.
     */
    private val manifestCache =
        ConcurrentHashMap<String, String>()

    @Volatile
    private var lastContentId: String? = null

    override fun serve(
        session: IHTTPSession
    ): Response {

        val uri = session.uri
        val method = session.method

        Log.i(
            tag,
            "Requisição: $method $uri"
        )

        // -------------------------------------------------------------
        // CORS / PREFLIGHT
        // -------------------------------------------------------------

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

                // -----------------------------------------------------
                // INJEÇÃO LOCAL
                // -----------------------------------------------------

                method == Method.POST &&
                    uri == "/api/local/inject" -> {

                    handleLocalInject(session)
                }

                // -----------------------------------------------------
                // MANIFESTO LOCAL
                // -----------------------------------------------------

                method == Method.GET &&
                    (
                        uri.startsWith("/manifest/") ||
                            uri == "/local-manifest.json"
                        ) -> {

                    handleServeLocalManifest(session)
                }

                // -----------------------------------------------------
                // INTERFACE PRINCIPAL
                // -----------------------------------------------------

                uri == "/" ||
                    uri == "/index.html" -> {

                    handleProxyStatic(
                        "$adminHost/index.html",
                        "text/html",
                        session
                    )
                }

                // -----------------------------------------------------
                // DEMAIS REQUISIÇÕES
                // -----------------------------------------------------

                else -> {

                    handleProxyForward(session)
                }
            }

        } catch (e: Exception) {

            Log.e(
                tag,
                "Erro no processamento da rota $uri: ${e.message}",
                e
            )

            val err =
                JSONObject()
                    .put(
                        "success",
                        false
                    )
                    .put(
                        "error",
                        e.message
                            ?: "Erro interno no LocalBridgeServer"
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

    /**
     * Recebe:
     *
     * {
     *   "ps4Ip": "...",
     *   "packageUrl": "...",
     *   "manifestUrl": "...",
     *   "title": "...",
     *   "contentId": "...",
     *   "category": "...",
     *   "size": 123
     * }
     *
     * O manifesto é reconstruído no Android.
     *
     * Depois o Android dispara o payload para o PS4.
     */
    private fun handleLocalInject(
        session: IHTTPSession
    ): Response {

        val map =
            HashMap<String, String>()

        session.parseBody(map)

        val postData =
            map["postData"] ?: "{}"

        Log.i(
            tag,
            "Payload recebido da interface: $postData"
        )

        val json =
            JSONObject(postData)

        // -------------------------------------------------------------
        // PS4
        // -------------------------------------------------------------

        val ps4Ip =
            json
                .optString("ps4Ip")
                .trim()

        if (ps4Ip.isEmpty()) {

            return jsonError(
                session,
                Response.Status.BAD_REQUEST,
                "O campo 'ps4Ip' é obrigatório."
            )
        }

        // -------------------------------------------------------------
        // TÍTULO
        // -------------------------------------------------------------

        val title =
            json.optString(
                "title",
                "Jogo PS4"
            )

        // -------------------------------------------------------------
        // CATEGORIA
        // -------------------------------------------------------------

        val rawCategory =
            json
                .optString(
                    "category",
                    "gd"
                )
                .trim()

        val bgftCategory =
            if (
                rawCategory.startsWith(
                    "PS4",
                    ignoreCase = true
                )
            ) {
                rawCategory.uppercase()
            } else {
                "PS4${rawCategory.uppercase()}"
            }

        // -------------------------------------------------------------
        // URL DO PKG
        // -------------------------------------------------------------

        val directPkgUrl =
            (
                json.optString("packageUrl")
                    .ifBlank {
                        json.optString("pkgUrl")
                    }
                    .ifBlank {
                        json.optString("url")
                    }
                    .ifBlank {
                        json.optString("directUrl")
                    }
                ).trim()

        if (directPkgUrl.isEmpty()) {

            return jsonError(
                session,
                Response.Status.BAD_REQUEST,
                "Nenhum link direto configurado."
            )
        }

        // -------------------------------------------------------------
        // CONTENT ID
        // -------------------------------------------------------------

        var detectedContentId =
            json
                .optString("contentId")
                .ifBlank {
                    json.optString("content_id")
                }
                .ifBlank {
                    json.optString("cusa")
                }
                .ifBlank {
                    json.optString("id")
                }
                .trim()

        // -------------------------------------------------------------
        // TAMANHO
        // -------------------------------------------------------------

        var realFileSize =
            json.optLong(
                "size",
                0L
            )

        // -------------------------------------------------------------
        // DIGEST
        // -------------------------------------------------------------

        var realDigest =
            "0".repeat(64)

        // -------------------------------------------------------------
        // METADADOS REMOTOS
        // -------------------------------------------------------------

        try {

            Log.i(
                tag,
                "Consultando cabeçalho do PKG:"
            )

            Log.i(
                tag,
                directPkgUrl
            )

            val rangeRequest =
                Request.Builder()
                    .url(directPkgUrl)
                    .addHeader(
                        "Range",
                        "bytes=0-4095"
                    )
                    .build()

            proxyClient
                .newCall(rangeRequest)
                .execute()
                .use { response ->

                    Log.i(
                        tag,
                        "Resposta HTTP do PKG: ${response.code}"
                    )

                    val contentRange =
                        response.header(
                            "Content-Range"
                        )

                    val totalLength =
                        contentRange
                            ?.substringAfterLast("/")
                            ?.toLongOrNull()
                            ?: response
                                .header(
                                    "Content-Length"
                                )
                                ?.toLongOrNull()
                            ?: 0L

                    if (totalLength > 0L) {

                        realFileSize =
                            totalLength

                        Log.i(
                            tag,
                            "Tamanho exato do PKG remoto: $realFileSize bytes"
                        )
                    }

                    val stream: InputStream? =
                        response.body?.byteStream()

                    if (stream != null) {

                        val headerBytes =
                            ByteArray(0x1000)

                        var bytesRead = 0

                        while (
                            bytesRead < 0x1000
                        ) {

                            val count =
                                stream.read(
                                    headerBytes,
                                    bytesRead,
                                    0x1000 - bytesRead
                                )

                            if (count == -1) {
                                break
                            }

                            bytesRead += count
                        }

                        Log.i(
                            tag,
                            "Bytes do cabeçalho recebidos: $bytesRead"
                        )

                        // -------------------------------------------------
                        // CONTENT ID
                        // -------------------------------------------------

                        if (
                            bytesRead >=
                            0x40 + 36
                        ) {

                            val pkgContentId =
                                String(
                                    headerBytes,
                                    0x40,
                                    36,
                                    Charsets.US_ASCII
                                ).trim(
                                    '\u0000',
                                    ' '
                                )

                            if (
                                pkgContentId.length >= 16 &&
                                pkgContentId.contains("-")
                            ) {

                                detectedContentId =
                                    pkgContentId

                                Log.i(
                                    tag,
                                    "Content-ID oficial extraído: $detectedContentId"
                                )
                            }
                        }

                        // -------------------------------------------------
                        // DIGEST
                        // -------------------------------------------------

                        if (
                            bytesRead >= 0x1000
                        ) {

                            realDigest =
                                headerBytes
                                    .copyOfRange(
                                        0xFE0,
                                        0x1000
                                    )
                                    .joinToString("") {
                                        "%02X".format(it)
                                    }

                            Log.i(
                                tag,
                                "Digest extraído: $realDigest"
                            )
                        }
                    }
                }

        } catch (e: Exception) {

            Log.w(
                tag,
                "Não foi possível obter todos os metadados remotos: ${e.message}"
            )
        }

        // -------------------------------------------------------------
        // CONTENT ID FINAL
        // -------------------------------------------------------------

        val finalContentId =
            if (detectedContentId.isNotBlank()) {

                detectedContentId

            } else {

                "CUSA" +
                    System
                        .currentTimeMillis()
                        .toString()
                        .takeLast(5)
            }

        // -------------------------------------------------------------
        // TAMANHO FALLBACK
        // -------------------------------------------------------------

        if (realFileSize <= 0L) {

            realFileSize =
                1024L *
                    1024L *
                    500L

            Log.w(
                tag,
                "Servidor remoto não informou tamanho. " +
                    "Usando fallback de 500 MB."
            )
        }

        // -------------------------------------------------------------
        // MANIFESTO BGFT
        // -------------------------------------------------------------

        val manifestJsonString =
            JSONObject().apply {

                put(
                    "originalFileSize",
                    realFileSize
                )

                put(
                    "packageDigest",
                    realDigest
                )

                put(
                    "numberOfSplitFiles",
                    1
                )

                put(
                    "pieces",
                    JSONArray().apply {

                        put(
                            JSONObject().apply {

                                put(
                                    "url",
                                    directPkgUrl
                                )

                                put(
                                    "fileOffset",
                                    0L
                                )

                                put(
                                    "fileSize",
                                    realFileSize
                                )

                                put(
                                    "hashValue",
                                    "0000000000000000000000000000000000000000"
                                )
                            }
                        )
                    }
                )

            }.toString()

        // -------------------------------------------------------------
        // CACHE
        // -------------------------------------------------------------

        manifestCache[
            finalContentId
        ] = manifestJsonString

        lastContentId =
            finalContentId

        // -------------------------------------------------------------
        // IP LOCAL DO ANDROID
        // -------------------------------------------------------------

        val localIp =
            getLocalWifiAddress()

        if (localIp == null) {

            return jsonError(
                session,
                Response.Status.BAD_REQUEST,
                "O aparelho não está conectado ao Wi-Fi local."
            )
        }

        // -------------------------------------------------------------
        // URL DO MANIFESTO
        // -------------------------------------------------------------

        val localManifestUrl =
            "http://$localIp:$port/manifest/$finalContentId.json"

        Log.i(
            tag,
            "========================================"
        )

        Log.i(
            tag,
            "TAREFA DE INJEÇÃO"
        )

        Log.i(
            tag,
            "Título: $title"
        )

        Log.i(
            tag,
            "Content-ID: $finalContentId"
        )

        Log.i(
            tag,
            "Categoria: $bgftCategory"
        )

        Log.i(
            tag,
            "Tamanho: $realFileSize"
        )

        Log.i(
            tag,
            "PKG: $directPkgUrl"
        )

        Log.i(
            tag,
            "Manifesto: $localManifestUrl"
        )

        Log.i(
            tag,
            "PS4: $ps4Ip"
        )

        Log.i(
            tag,
            "Android: $localIp"
        )

        Log.i(
            tag,
            "========================================"
        )

        // -------------------------------------------------------------
        // DISPARA PAYLOAD
        // -------------------------------------------------------------

        val result =
            runBlocking {

                payloader.injectDpiPayload(
                    ps4Ip = ps4Ip,
                    localIp = localIp,
                    manifestUrl = localManifestUrl,
                    itemTitle = title,
                    contentId = finalContentId,
                    category = bgftCategory,
                    fileSize = realFileSize,
                    iconBytes = null
                )
            }

        // -------------------------------------------------------------
        // RESPOSTA
        // -------------------------------------------------------------

        val responseJson =
            JSONObject().apply {

                put(
                    "success",
                    result.isSuccess
                )

                put(
                    "contentId",
                    finalContentId
                )

                put(
                    "manifestUrl",
                    localManifestUrl
                )

                if (result.isFailure) {

                    put(
                        "error",
                        result
                            .exceptionOrNull()
                            ?.message
                            ?: "Falha desconhecida na injeção."
                    )
                }
            }.toString()

        Log.i(
            tag,
            "Resposta da injeção: $responseJson"
        )

        return addCors(
            session,
            newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                responseJson
            )
        )
    }

    /**
     * Entrega o manifesto solicitado pelo PS4.
     */
    private fun handleServeLocalManifest(
        session: IHTTPSession
    ): Response {

        val uri =
            session.uri

        val idFromPath =
            if (
                uri.startsWith("/manifest/")
            ) {

                uri
                    .removePrefix(
                        "/manifest/"
                    )
                    .removeSuffix(
                        ".json"
                    )

            } else {

                null
            }

        val requestId =
            idFromPath
                ?: session
                    .parameters["id"]
                    ?.firstOrNull()
                ?: lastContentId

        val manifest =
            if (!requestId.isNullOrBlank()) {

                manifestCache[
                    requestId
                ]

            } else {

                manifestCache.values.lastOrNull()
            }

        return if (manifest != null) {

            Log.i(
                tag,
                "Manifesto entregue ao PS4: $requestId"
            )

            addCors(
                session,
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json; charset=utf-8",
                    manifest
                )
            )

        } else {

            Log.w(
                tag,
                "PS4 solicitou manifesto inexistente: $requestId"
            )

            addCors(
                session,
                newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "application/json",
                    """{"error":"Manifesto ausente"}"""
                )
            )
        }
    }

    /**
     * Proxy da página principal.
     */
    private fun handleProxyStatic(
        targetUrl: String,
        mime: String,
        session: IHTTPSession
    ): Response {

        val request =
            Request.Builder()
                .url(targetUrl)
                .build()

        return proxyClient
            .newCall(request)
            .execute()
            .use { response ->

                val body =
                    response
                        .body
                        ?.string()
                        ?: "Falha ao carregar interface remota."

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

    /**
     * Encaminha requisições da interface para o servidor remoto.
     */
    private fun handleProxyForward(
        session: IHTTPSession
    ): Response {

        val queryString =
            if (
                !session
                    .queryParameterString
                    .isNullOrBlank()
            ) {

                "?${session.queryParameterString}"

            } else {

                ""
            }

        val targetUrl =
            "$adminHost${session.uri}$queryString"

        Log.i(
            tag,
            "Proxy -> $targetUrl"
        )

        val requestBuilder =
            Request.Builder()
                .url(targetUrl)

        when (session.method) {

            Method.POST -> {

                val map =
                    HashMap<String, String>()

                session.parseBody(map)

                val bodyText =
                    map["postData"] ?: ""

                val contentType =
                    session.headers["content-type"]
                        ?: "application/json"

                requestBuilder.post(
                    bodyText.toRequestBody(
                        contentType.toMediaTypeOrNull()
                    )
                )
            }

            Method.PUT -> {

                val map =
                    HashMap<String, String>()

                session.parseBody(map)

                val bodyText =
                    map["postData"] ?: ""

                val contentType =
                    session.headers["content-type"]
                        ?: "application/json"

                requestBuilder.put(
                    bodyText.toRequestBody(
                        contentType.toMediaTypeOrNull()
                    )
                )
            }

            Method.DELETE -> {
                requestBuilder.delete()
            }

            Method.HEAD -> {
                requestBuilder.head()
            }

            else -> {
                requestBuilder.get()
            }
        }

        return proxyClient
            .newCall(
                requestBuilder.build()
            )
            .execute()
            .use { response ->

                val bytes =
                    response
                        .body
                        ?.bytes()
                        ?: ByteArray(0)

                val contentType =
                    response.header(
                        "Content-Type"
                    )
                        ?: "application/octet-stream"

                val status =
                    Response.Status.lookup(
                        response.code
                    )
                        ?: object : Response.IStatus {

                            override fun getRequestStatus(): Int =
                                response.code

                            override fun getDescription(): String =
                                response.message
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

    /**
     * Descobre o IPv4 Wi-Fi do Android.
     *
     * Interfaces móveis/túnel são ignoradas.
     */
    private fun getLocalWifiAddress(): String? {

        try {

            val interfaces =
                NetworkInterface
                    .getNetworkInterfaces()
                    ?.toList()
                    ?: return null

            val validInterfaces =
                interfaces.filter { networkInterface ->

                    val name =
                        networkInterface
                            .name
                            .lowercase()

                    networkInterface.isUp &&
                        !networkInterface.isLoopback &&
                        !name.contains("tun") &&
                        !name.contains("tap") &&
                        !name.contains("rmnet") &&
                        !name.contains("pdp") &&
                        !name.contains("dummy")
                }

            val sorted =
                validInterfaces.sortedByDescending {

                    it.name.startsWith("wlan") ||
                        it.name.startsWith("ap")
                }

            for (networkInterface in sorted) {

                for (
                    address in
                    networkInterface.inetAddresses
                ) {

                    if (
                        !address.isLoopbackAddress &&
                        address is Inet4Address &&
                        address.isSiteLocalAddress
                    ) {

                        val host =
                            address.hostAddress

                        Log.i(
                            tag,
                            "IP Wi-Fi encontrado: $host"
                        )

                        return host
                    }
                }
            }

        } catch (e: Exception) {

            Log.e(
                tag,
                "Erro ao detectar IP Wi-Fi: ${e.message}",
                e
            )
        }

        return null
    }

    /**
     * Adiciona cabeçalhos CORS.
     */
    private fun addCors(
        session: IHTTPSession,
        response: Response
    ): Response {

        val requestedHeaders =
            session.headers[
                "access-control-request-headers"
            ]
                ?: "Content-Type, Authorization, Range, X-Requested-With"

        response.addHeader(
            "Access-Control-Allow-Origin",
            "*"
        )

        response.addHeader(
            "Access-Control-Allow-Methods",
            "GET, POST, OPTIONS, PUT, DELETE"
        )

        response.addHeader(
            "Access-Control-Allow-Headers",
            requestedHeaders
        )

        response.addHeader(
            "Access-Control-Max-Age",
            "86400"
        )

        return response
    }

    /**
     * Resposta JSON de erro.
     */
    private fun jsonError(
        session: IHTTPSession,
        status: Response.IStatus,
        message: String
    ): Response {

        val json =
            JSONObject()
                .put(
                    "success",
                    false
                )
                .put(
                    "error",
                    message
                )
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
