package com.gontijotech.gtstore.client.server

import android.content.Context
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

    private val manifestCache =
        ConcurrentHashMap<String, String>()

    @Volatile
    private var lastContentId: String? = null

    /**
     * Grava no Logcat e no:
     *
     * Downloads/GTSTORE-log.txt
     */
    private fun fileLog(message: String) {
        Log.i(tag, message)

        GTStoreFileLogger.log(
            context,
            tag,
            message
        )
    }

    private fun fileWarn(message: String) {
        Log.w(tag, message)

        GTStoreFileLogger.log(
            context,
            tag,
            "WARN: $message"
        )
    }

    private fun fileError(
        message: String,
        throwable: Throwable? = null
    ) {
        Log.e(
            tag,
            message,
            throwable
        )

        GTStoreFileLogger.log(
            context,
            tag,
            "ERROR: $message" +
                (
                    throwable?.message
                        ?.let { " | ${it}" }
                        ?: ""
                )
        )
    }

    override fun serve(
        session: IHTTPSession
    ): Response {

        val uri = session.uri
        val method = session.method

        fileLog(
            "Requisição: $method $uri"
        )

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

                method == Method.POST &&
                    uri == "/api/local/inject" -> {

                    handleLocalInject(session)
                }

                method == Method.GET &&
                    (
                        uri.startsWith("/manifest/") ||
                            uri == "/local-manifest.json"
                        ) -> {

                    handleServeLocalManifest(session)
                }

                uri == "/" ||
                    uri == "/index.html" -> {

                    handleProxyStatic(
                        "$adminHost/index.html",
                        "text/html",
                        session
                    )
                }

                else -> {

                    handleProxyForward(session)
                }
            }

        } catch (e: Exception) {

            fileError(
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

    private fun handleLocalInject(
        session: IHTTPSession
    ): Response {

        val map =
            HashMap<String, String>()

        session.parseBody(map)

        val postData =
            map["postData"] ?: "{}"

        fileLog(
            "========================================"
        )

        fileLog(
            "INÍCIO DA INJEÇÃO"
        )

        fileLog(
            "JSON recebido da interface:"
        )

        fileLog(postData)

        val json =
            JSONObject(postData)

        val ps4Ip =
            json
                .optString("ps4Ip")
                .trim()

        if (ps4Ip.isEmpty()) {

            fileWarn(
                "IP do PS4 não informado."
            )

            return jsonError(
                session,
                Response.Status.BAD_REQUEST,
                "O campo 'ps4Ip' é obrigatório."
            )
        }

        val title =
            json.optString(
                "title",
                "Jogo PS4"
            )

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

            fileWarn(
                "Nenhum link direto configurado."
            )

            return jsonError(
                session,
                Response.Status.BAD_REQUEST,
                "Nenhum link direto configurado."
            )
        }

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

        var realFileSize =
            json.optLong(
                "size",
                0L
            )

        var realDigest =
            "0".repeat(64)

        fileLog(
            "PS4: $ps4Ip"
        )

        fileLog(
            "Título: $title"
        )

        fileLog(
            "Categoria recebida: $rawCategory"
        )

        fileLog(
            "Categoria BGFT: $bgftCategory"
        )

        fileLog(
            "PKG direto: $directPkgUrl"
        )

        fileLog(
            "Content-ID inicial: " +
                if (detectedContentId.isBlank()) {
                    "(vazio)"
                } else {
                    detectedContentId
                }
        )

        fileLog(
            "Tamanho informado pela interface: $realFileSize"
        )

        // -------------------------------------------------------------
        // METADADOS REMOTOS
        // -------------------------------------------------------------

        try {

            fileLog(
                "Consultando cabeçalho remoto do PKG..."
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

                    fileLog(
                        "Resposta HTTP do PKG: ${response.code}"
                    )

                    val contentRange =
                        response.header(
                            "Content-Range"
                        )

                    fileLog(
                        "Content-Range: " +
                            (contentRange ?: "(não informado)")
                    )

                    val contentLength =
                        response.header(
                            "Content-Length"
                        )

                    fileLog(
                        "Content-Length: " +
                            (contentLength ?: "(não informado)")
                    )

                    val totalLength =
                        contentRange
                            ?.substringAfterLast("/")
                            ?.toLongOrNull()
                            ?: contentLength
                                ?.toLongOrNull()
                            ?: 0L

                    if (totalLength > 0L) {

                        realFileSize =
                            totalLength

                        fileLog(
                            "Tamanho exato do PKG: $realFileSize bytes"
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

                        fileLog(
                            "Bytes de cabeçalho recebidos: $bytesRead"
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

                            fileLog(
                                "Content-ID encontrado no PKG: $pkgContentId"
                            )

                            if (
                                pkgContentId.length >= 16 &&
                                pkgContentId.contains("-")
                            ) {

                                detectedContentId =
                                    pkgContentId

                                fileLog(
                                    "Content-ID oficial utilizado: $detectedContentId"
                                )
                            }
                        } else {

                            fileWarn(
                                "Cabeçalho insuficiente para extrair Content-ID."
                            )
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

                            fileLog(
                                "Digest extraído: $realDigest"
                            )

                        } else {

                            fileWarn(
                                "Cabeçalho insuficiente para extrair Digest."
                            )
                        }
                    }
                }

        } catch (e: Exception) {

            fileWarn(
                "Falha ao obter metadados remotos: ${e.message}"
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

        fileLog(
            "Content-ID final: $finalContentId"
        )

        // -------------------------------------------------------------
        // TAMANHO FALLBACK
        // -------------------------------------------------------------

        if (realFileSize <= 0L) {

            realFileSize =
                1024L *
                    1024L *
                    500L

            fileWarn(
                "Tamanho não encontrado. " +
                    "Usando fallback de 500 MB."
            )
        }

        // -------------------------------------------------------------
        // MANIFESTO
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

        fileLog(
            "Manifesto criado:"
        )

        fileLog(
            manifestJsonString
        )

        manifestCache[
            finalContentId
        ] = manifestJsonString

        lastContentId =
            finalContentId

        fileLog(
            "Manifesto armazenado no cache para: $finalContentId"
        )

        // -------------------------------------------------------------
        // IP LOCAL
        // -------------------------------------------------------------

        val localIp =
            getLocalWifiAddress()

        if (localIp == null) {

            fileError(
                "Não foi possível encontrar o IP Wi-Fi do Android."
            )

            return jsonError(
                session,
                Response.Status.BAD_REQUEST,
                "O aparelho não está conectado ao Wi-Fi local."
            )
        }

        fileLog(
            "IP Wi-Fi do Android: $localIp"
        )

        // -------------------------------------------------------------
        // MANIFEST URL
        // -------------------------------------------------------------

        val localManifestUrl =
            "http://$localIp:$port/manifest/$finalContentId.json"

        fileLog(
            "URL do manifesto local: $localManifestUrl"
        )

        fileLog(
            "========================================"
        )

        fileLog(
            "DISPARANDO PAYLOADER"
        )

        fileLog(
            "PS4: $ps4Ip"
        )

        fileLog(
            "Android: $localIp"
        )

        fileLog(
            "Título: $title"
        )

        fileLog(
            "Content-ID: $finalContentId"
        )

        fileLog(
            "Categoria: $bgftCategory"
        )

        fileLog(
            "Tamanho: $realFileSize"
        )

        fileLog(
            "Manifesto: $localManifestUrl"
        )

        fileLog(
            "========================================"
        )

        // -------------------------------------------------------------
        // PAYLOAD
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
        // RESULTADO
        // -------------------------------------------------------------

        if (result.isSuccess) {

            fileLog(
                "PAYLOADER FINALIZADO COM SUCESSO."
            )

        } else {

            val error =
                result
                    .exceptionOrNull()

            fileError(
                "PAYLOADER FALHOU: " +
                    (
                        error?.message
                            ?: "erro desconhecido"
                    ),
                error
            )
        }

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

        fileLog(
            "Resposta enviada à interface: $responseJson"
        )

        fileLog(
            "FIM DA INJEÇÃO"
        )

        fileLog(
            "========================================"
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

        fileLog(
            "PS4 solicitou manifesto: $requestId"
        )

        val manifest =
            if (!requestId.isNullOrBlank()) {

                manifestCache[
                    requestId
                ]

            } else {

                manifestCache.values.lastOrNull()
            }

        return if (manifest != null) {

            fileLog(
                "Manifesto entregue com sucesso ao PS4: $requestId"
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

            fileWarn(
                "Manifesto inexistente para: $requestId"
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

        fileLog(
            "Carregando interface: $targetUrl"
        )

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

                fileLog(
                    "Interface remota respondeu HTTP ${response.code}"
                )

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

        fileLog(
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

                        fileLog(
                            "IP Wi-Fi encontrado: $host"
                        )

                        return host
                    }
                }
            }

        } catch (e: Exception) {

            fileError(
                "Erro ao detectar IP Wi-Fi: ${e.message}",
                e
            )
        }

        return null
    }

    /**
     * Adiciona CORS.
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

        fileError(
            "Erro HTTP ${status.requestStatus}: $message"
        )

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



