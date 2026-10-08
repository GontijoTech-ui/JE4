package com.gontijotech.gtstore.client.server

import android.content.Context
import com.gontijotech.gtstore.client.network.Ps4Payloader
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class LocalBridgeServer(
    private val context: Context,
    port: Int = 8080,
    private val adminHost: String = "https://loja.gontijotech.com.br"
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "GTStore-Bridge"

        private const val PKG_MAGIC = 0x7F434E54L
        private const val PKG_HEADER_SIZE = 0x1000

        private const val CONTENT_ID_OFFSET = 0x40
        private const val CONTENT_ID_LENGTH = 36

        private const val DIGEST_OFFSET = 0xFE0
        private const val DIGEST_LENGTH = 32

        private const val MAX_HTML_LOG = 2500
        private const val MAX_TEXT_PREVIEW = 512

        private const val USER_AGENT = "PlayStation 4"
    }

    private val proxyClient: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(35, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

    private val streamClient: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

    private val payloader = Ps4Payloader(context)

    private val manifestCache =
        ConcurrentHashMap<String, String>()

    private val pkgUrlCache =
        ConcurrentHashMap<String, String>()

    @Volatile
    private var lastContentId: String? = null

    private fun log(message: String) {
        android.util.Log.d(TAG, message)
        try {
            GTStoreFileLogger.log("[$TAG] $message")
        } catch (_: Exception) {
        }
    }

    private fun logWarn(message: String) {
        android.util.Log.w(TAG, message)
        try {
            GTStoreFileLogger.log("[$TAG][WARN] $message")
        } catch (_: Exception) {
        }
    }

    private fun logError(message: String) {
        android.util.Log.e(TAG, message)
        try {
            GTStoreFileLogger.log("[$TAG][ERROR] $message")
        } catch (_: Exception) {
        }
    }

    override fun serve(session: IHTTPSession): Response {

        return try {

            addCorsHeaders(
                when (session.method) {

                    Method.OPTIONS -> {
                        newFixedLengthResponse(
                            Response.Status.OK,
                            "text/plain; charset=utf-8",
                            ""
                        )
                    }

                    Method.POST -> {
                        when {
                            session.uri == "/api/local/inject" ->
                                handleLocalInject(session)

                            else ->
                                proxyAdmin(session)
                        }
                    }

                    Method.GET -> {
                        when {
                            session.uri.startsWith("/manifest/") ||
                                    session.uri == "/local-manifest.json" ->
                                handleServeLocalManifest(session)

                            session.uri.startsWith("/download-pkg/") ->
                                handleProxyPkg(session)

                            session.uri == "/" ||
                                    session.uri == "/index.html" ->
                                proxyAdmin(session)

                            else ->
                                proxyAdmin(session)
                        }
                    }

                    else ->
                        proxyAdmin(session)
                }
            )

        } catch (e: Exception) {

            logError(
                "Erro geral no serve(): ${e.javaClass.simpleName}: ${e.message}"
            )

            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json; charset=utf-8",
                """
                {
                  "success": false,
                  "error": "${jsonEscape(e.message ?: "Erro interno")}"
                }
                """.trimIndent()
            )
        }
    }

    private fun addCorsHeaders(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader(
            "Access-Control-Allow-Methods",
            "GET, POST, OPTIONS"
        )
        response.addHeader(
            "Access-Control-Allow-Headers",
            "Content-Type, Range, Accept, Origin, User-Agent"
        )
        response.addHeader("Access-Control-Expose-Headers", "*")
        return response
    }

    private fun handleLocalInject(
        session: IHTTPSession
    ): Response {

        return try {

            val body = readRequestBody(session)

            log("POST /api/local/inject")
            log("Body recebido: ${body.take(2000)}")

            val json = org.json.JSONObject(body)

            val ps4Ip =
                json.optString("ps4Ip")
                    .trim()

            if (ps4Ip.isBlank()) {
                return jsonError(
                    "IP do PS4 não informado"
                )
            }

            val title =
                json.optString(
                    "title",
                    "Jogo PS4"
                ).ifBlank {
                    "Jogo PS4"
                }

            val category =
                json.optString(
                    "category",
                    "gd"
                ).ifBlank {
                    "gd"
                }

            val categoryBgft =
                when (category.lowercase()) {
                    "bgft",
                    "ps4bgft" -> "PS4BGFT"

                    else -> "PS4GD"
                }

            val directPkgUrl =
                firstNonBlank(
                    json.optString("packageUrl"),
                    json.optString("pkgUrl"),
                    json.optString("url"),
                    json.optString("directUrl")
                )

            if (directPkgUrl.isBlank()) {
                return jsonError(
                    "URL direta do PKG não informada"
                )
            }

            var detectedContentId =
                firstNonBlank(
                    json.optString("contentId"),
                    json.optString("content_id"),
                    json.optString("cusa"),
                    json.optString("id")
                )

            var realFileSize =
                json.optLong("size", 0L)

            var realDigest =
                "0".repeat(64)

            log("========== INÍCIO INJECT ==========")
            log("PS4 IP: $ps4Ip")
            log("Título: $title")
            log("Categoria: $category")
            log("Categoria BGFT: $categoryBgft")
            log("URL recebida: $directPkgUrl")
            log("Content-ID inicial: $detectedContentId")
            log("Tamanho inicial: $realFileSize")

            /*
             * IMPORTANTE:
             *
             * A URL recebida pode ser uma página HTML, uma página
             * intermediária, Cloudflare, login ou outro conteúdo.
             *
             * Só podemos substituir o Content-ID quando o conteúdo
             * remoto tiver realmente o cabeçalho PKG válido.
             */
            val remoteMeta =
                inspectRemotePackage(
                    directPkgUrl = directPkgUrl,
                    initialContentId = detectedContentId
                )

            if (remoteMeta.validPkg) {

                if (isValidContentId(remoteMeta.contentId)) {
                    detectedContentId =
                        remoteMeta.contentId

                    log(
                        "Content-ID substituído pelo Content-ID real do PKG: " +
                                detectedContentId
                    )
                } else {
                    logWarn(
                        "PKG válido, mas Content-ID extraído é inválido: " +
                                remoteMeta.contentId
                    )
                }

                if (remoteMeta.size > 0L) {
                    realFileSize =
                        remoteMeta.size
                }

                if (remoteMeta.digest.isNotBlank()) {
                    realDigest =
                        remoteMeta.digest
                }

            } else {

                logWarn(
                    "Resposta remota NÃO é um PKG válido. " +
                            "Content-ID informado pela interface será preservado."
                )
            }

            /*
             * Se o ID recebido pela interface também for inválido,
             * não podemos montar uma rota segura para manifesto.
             */
            if (!isValidContentId(detectedContentId)) {

                logError(
                    "Content-ID final inválido: [$detectedContentId]"
                )

                return jsonError(
                    "Content-ID inválido ou ausente"
                )
            }

            val finalContentId =
                detectedContentId.trim()

            /*
             * Descobre o IP local do Android.
             */
            val localIp =
                getLocalWifiAddress()

            if (localIp.isBlank()) {

                return jsonError(
                    "Não foi possível determinar o IP Wi-Fi do Android"
                )
            }

            /*
             * Guarda o URL real associado ao Content-ID.
             */
            pkgUrlCache[finalContentId] =
                directPkgUrl

            val localPkgUrl =
                "http://$localIp:$listeningPort/download-pkg/" +
                        encodePathSegment(finalContentId)

            /*
             * Manifesto que será solicitado pelo PS4.
             */
            val manifestJson =
                buildManifestJson(
                    contentId = finalContentId,
                    title = title,
                    category = categoryBgft,
                    originalFileSize = realFileSize,
                    packageDigest = realDigest,
                    packageUrl = localPkgUrl
                )

            manifestCache[finalContentId] =
                manifestJson

            lastContentId =
                finalContentId

            val localManifestUrl =
                "http://$localIp:$listeningPort/manifest/" +
                        encodePathSegment(finalContentId) +
                        ".json"

            log("Content-ID FINAL: $finalContentId")
            log("Tamanho FINAL: $realFileSize")
            log("Digest FINAL: $realDigest")
            log("URL local do PKG: $localPkgUrl")
            log("URL local do manifesto: $localManifestUrl")

            /*
             * Injeta o payload no BinLoader do PS4.
             */
            log(
                "Iniciando injeção do payload em " +
                        "$ps4Ip:9090"
            )

            val injectionResult =
                payloader.injectDpiPayload(
                    ps4Ip = ps4Ip,
                    packageUrl = localManifestUrl,
                    title = title,
                    contentId = finalContentId,
                    category = categoryBgft,
                    size = realFileSize,
                    digest = realDigest
                )

            if (!injectionResult.success) {

                logError(
                    "Falha na injeção: ${injectionResult.error}"
                )

                return jsonError(
                    injectionResult.error
                        ?: "Falha ao injetar payload"
                )
            }

            log("Payload injetado com sucesso")
            log("========== FIM INJECT ==========")

            newFixedLengthResponse(
                Response.Status.OK,
                "application/json; charset=utf-8",
                """
                {
                  "success": true,
                  "contentId": "${jsonEscape(finalContentId)}",
                  "manifestUrl": "${jsonEscape(localManifestUrl)}",
                  "packageUrl": "${jsonEscape(localPkgUrl)}",
                  "title": "${jsonEscape(title)}",
                  "size": $realFileSize,
                  "digest": "${jsonEscape(realDigest)}"
                }
                """.trimIndent()
            )

        } catch (e: Exception) {

            logError(
                "handleLocalInject: ${e.javaClass.simpleName}: ${e.message}"
            )

            jsonError(
                e.message ?: "Erro ao processar injeção"
            )
        }
    }

    private fun inspectRemotePackage(
        directPkgUrl: String,
        initialContentId: String
    ): RemotePkgMetadata {

        log("========== INSPEÇÃO REMOTA ==========")
        log("URL original: $directPkgUrl")

        return try {

            val request =
                Request.Builder()
                    .url(directPkgUrl)
                    .get()
                    .header(
                        "User-Agent",
                        USER_AGENT
                    )
                    .header(
                        "Accept",
                        "application/octet-stream,*/*"
                    )
                    .header(
                        "Accept-Encoding",
                        "identity"
                    )
                    .header(
                        "Range",
                        "bytes=0-4095"
                    )
                    .build()

            proxyClient.newCall(request)
                .execute()
                .use { response ->

                    val responseBody =
                        response.body

                    val contentType =
                        response.header("Content-Type")
                            ?: ""

                    val contentLength =
                        response.header("Content-Length")
                            ?: ""

                    val contentRange =
                        response.header("Content-Range")
                            ?: ""

                    val location =
                        response.header("Location")
                            ?: ""

                    val finalUrl =
                        response.request.url.toString()

                    log(
                        "HTTP code: ${response.code}"
                    )

                    log(
                        "HTTP message: ${response.message}"
                    )

                    log(
                        "URL final: $finalUrl"
                    )

                    log(
                        "Content-Type: $contentType"
                    )

                    log(
                        "Content-Length: $contentLength"
                    )

                    log(
                        "Content-Range: $contentRange"
                    )

                    if (location.isNotBlank()) {
                        log(
                            "Location: $location"
                        )
                    }

                    log(
                        "HTTP success: ${response.isSuccessful}"
                    )

                    if (responseBody == null) {

                        logError(
                            "Resposta remota sem body"
                        )

                        return@use RemotePkgMetadata.invalid(
                            initialContentId
                        )
                    }

                    val input =
                        responseBody.byteStream()

                    val header =
                        ByteArray(PKG_HEADER_SIZE)

                    var totalRead = 0

                    while (
                        totalRead < PKG_HEADER_SIZE
                    ) {

                        val read =
                            input.read(
                                header,
                                totalRead,
                                PKG_HEADER_SIZE - totalRead
                            )

                        if (read <= 0) {
                            break
                        }

                        totalRead += read
                    }

                    try {
                        input.close()
                    } catch (_: Exception) {
                    }

                    log(
                        "Bytes lidos para inspeção: $totalRead"
                    )

                    if (totalRead > 0) {

                        val hexCount =
                            minOf(
                                totalRead,
                                64
                            )

                        val hex =
                            header
                                .copyOfRange(
                                    0,
                                    hexCount
                                )
                                .joinToString(" ") {
                                    "%02X".format(
                                        it.toInt() and 0xFF
                                    )
                                }

                        log(
                            "Primeiros $hexCount bytes HEX: $hex"
                        )

                        val previewCount =
                            minOf(
                                totalRead,
                                MAX_TEXT_PREVIEW
                            )

                        val preview =
                            sanitizeText(
                                header.copyOfRange(
                                    0,
                                    previewCount
                                )
                            )

                        log(
                            "Preview textual: $preview"
                        )
                    }

                    if (
                        contentType
                            .lowercase()
                            .contains("text/html")
                        ||
                        contentType
                            .lowercase()
                            .contains("application/xhtml")
                    ) {

                        logWarn(
                            "SERVIDOR REMOTO DEVOLVEU HTML/XHTML"
                        )

                        logHtmlDiagnostics(
                            header,
                            totalRead
                        )
                    }

                    if (totalRead < 4) {

                        logError(
                            "Resposta remota pequena demais para validar PKG"
                        )

                        return@use RemotePkgMetadata.invalid(
                            initialContentId
                        )
                    }

                    val magic =
                        readUInt32BE(header, 0)

                    log(
                        "PKG magic detectado: " +
                                "0x${magic.toString(16).uppercase()}"
                    )

                    if (magic != PKG_MAGIC) {

                        logWarn(
                            "MAGIC PKG INVÁLIDO. " +
                                    "Resposta remota não parece ser um PKG."
                        )

                        return@use RemotePkgMetadata.invalid(
                            initialContentId
                        )
                    }

                    if (totalRead < PKG_HEADER_SIZE) {

                        logWarn(
                            "Magic válido, mas header possui apenas " +
                                    "$totalRead bytes."
                        )

                        return@use RemotePkgMetadata.invalid(
                            initialContentId
                        )
                    }

                    val extractedContentId =
                        String(
                            header,
                            CONTENT_ID_OFFSET,
                            CONTENT_ID_LENGTH,
                            StandardCharsets.US_ASCII
                        )
                            .trim(
                                '\u0000',
                                ' '
                            )

                    log(
                        "Content-ID extraído do PKG: " +
                                extractedContentId
                    )

                    if (
                        !isValidContentId(
                            extractedContentId
                        )
                    ) {

                        logWarn(
                            "Content-ID extraído do PKG é inválido."
                        )

                        return@use RemotePkgMetadata.invalid(
                            initialContentId
                        )
                    }

                    val digestBytes =
                        header.copyOfRange(
                            DIGEST_OFFSET,
                            DIGEST_OFFSET + DIGEST_LENGTH
                        )

                    val digest =
                        digestBytes.joinToString("") {
                            "%02X".format(
                                it.toInt() and 0xFF
                            )
                        }

                    log(
                        "Digest PKG: $digest"
                    )

                    val remoteSize =
                        extractTotalSize(
                            contentRange,
                            contentLength
                        )

                    log(
                        "Tamanho remoto detectado: $remoteSize"
                    )

                    log(
                        "PKG remoto VALIDADO COM SUCESSO"
                    )

                    RemotePkgMetadata(
                        validPkg = true,
                        contentId = extractedContentId,
                        digest = digest,
                        size = remoteSize
                    )
                }

        } catch (e: Exception) {

            logError(
                "Falha na inspeção remota: " +
                        "${e.javaClass.simpleName}: ${e.message}"
            )

            RemotePkgMetadata.invalid(
                initialContentId
            )

        } finally {

            log("========== FIM INSPEÇÃO REMOTA ==========")
        }
    }

    private fun logHtmlDiagnostics(
        bytes: ByteArray,
        count: Int
    ) {

        if (count <= 0) {
            return
        }

        val sampleSize =
            minOf(
                count,
                MAX_HTML_LOG
            )

        val html =
            sanitizeText(
                bytes.copyOfRange(
                    0,
                    sampleSize
                )
            )

        logWarn(
            "HTML REMOTO — primeiros $sampleSize bytes:\n$html"
        )

        val lower =
            html.lowercase()

        val keywords =
            listOf(
                "cloudflare",
                "captcha",
                "verify you are human",
                "access denied",
                "forbidden",
                "login",
                "sign in",
                "download",
                "javascript",
                "checking your browser",
                "security check",
                "attention required"
            )

        for (keyword in keywords) {

            if (lower.contains(keyword)) {

                logWarn(
                    "HTML contém indicador: [$keyword]"
                )
            }
        }
    }

    private fun handleServeLocalManifest(
        session: IHTTPSession
    ): Response {

        return try {

            val rawPath =
                session.uri

            var idFromPath =
                rawPath
                    .substringAfter(
                        "/manifest/",
                        ""
                    )

            if (idFromPath.endsWith(".json")) {
                idFromPath =
                    idFromPath.removeSuffix(
                        ".json"
                    )
            }

            idFromPath =
                decodePathSegment(
                    idFromPath
                )

            val queryId =
                session.parameters["id"]
                    ?.firstOrNull()
                    ?.let {
                        decodePathSegment(it)
                    }

            val requestId =
                when {
                    isValidContentId(idFromPath) ->
                        idFromPath

                    isValidContentId(queryId) ->
                        queryId

                    isValidContentId(lastContentId) ->
                        lastContentId!!

                    else ->
                        null
                }

            log(
                "GET manifesto: uri=${session.uri}"
            )

            log(
                "ID extraído do path: [$idFromPath]"
            )

            log(
                "ID da query: [$queryId]"
            )

            log(
                "ID final solicitado: [$requestId]"
            )

            if (requestId.isNullOrBlank()) {

                logWarn(
                    "Manifesto solicitado sem Content-ID válido"
                )

                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "application/json; charset=utf-8",
                    """
                    {
                      "success": false,
                      "error": "Content-ID inválido"
                    }
                    """.trimIndent()
                )
            }

            val manifest =
                manifestCache[requestId]

            if (manifest == null) {

                logWarn(
                    "Manifesto inexistente para o Content-ID: $requestId"
                )

                log(
                    "Manifestos atualmente em cache: " +
                            manifestCache.keys.joinToString(", ")
                )

                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "application/json; charset=utf-8",
                    """
                    {
                      "success": false,
                      "error": "Manifesto inexistente",
                      "contentId": "${jsonEscape(requestId)}"
                    }
                    """.trimIndent()
                )
            }

            log(
                "Manifesto encontrado para: $requestId"
            )

            newFixedLengthResponse(
                Response.Status.OK,
                "application/json; charset=utf-8",
                manifest
            ).apply {
                addHeader(
                    "Cache-Control",
                    "no-cache, no-store, must-revalidate"
                )
            }

        } catch (e: Exception) {

            logError(
                "handleServeLocalManifest: " +
                        "${e.javaClass.simpleName}: ${e.message}"
            )

            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain; charset=utf-8",
                "Erro interno ao servir manifesto"
            )
        }
    }

    private fun handleProxyPkg(
        session: IHTTPSession
    ): Response {

        return try {

            val rawPath =
                session.uri

            var contentId =
                rawPath.substringAfter(
                    "/download-pkg/",
                    ""
                )

            contentId =
                decodePathSegment(
                    contentId
                )

            if (!isValidContentId(contentId)) {

                logWarn(
                    "Download solicitado com Content-ID inválido: [$contentId]"
                )

                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain; charset=utf-8",
                    "Content-ID inválido"
                )
            }

            val remoteUrl =
                pkgUrlCache[contentId]

            if (remoteUrl.isNullOrBlank()) {

                logWarn(
                    "Nenhuma URL remota encontrada para: $contentId"
                )

                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain; charset=utf-8",
                    "PKG não encontrado"
                )
            }

            val range =
                session.headers["range"]
                    ?: session.headers["Range"]

            log(
                "========== PROXY PKG =========="
            )

            log(
                "Content-ID: $contentId"
            )

            log(
                "URL remota: $remoteUrl"
            )

            log(
                "Range PS4: ${range ?: "(nenhum)"}"
            )

            val requestBuilder =
                Request.Builder()
                    .url(remoteUrl)
                    .get()
                    .header(
                        "User-Agent",
                        USER_AGENT
                    )
                    .header(
                        "Accept",
                        "application/octet-stream,*/*"
                    )
                    .header(
                        "Accept-Encoding",
                        "identity"
                    )

            if (!range.isNullOrBlank()) {
                requestBuilder.header(
                    "Range",
                    range
                )
            }

            val remoteResponse =
                streamClient.newCall(
                    requestBuilder.build()
                ).execute()

            val responseBody =
                remoteResponse.body

            if (responseBody == null) {

                remoteResponse.close()

                logError(
                    "Servidor remoto retornou body nulo"
                )

                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "text/plain; charset=utf-8",
                    "Resposta remota vazia"
                )
            }

            val responseContentType =
                remoteResponse.header(
                    "Content-Type"
                ) ?: ""

            val responseContentRange =
                remoteResponse.header(
                    "Content-Range"
                ) ?: ""

            val responseContentLength =
                remoteResponse.header(
                    "Content-Length"
                ) ?: ""

            val finalUrl =
                remoteResponse.request.url.toString()

            log(
                "HTTP remoto: ${remoteResponse.code} ${remoteResponse.message}"
            )

            log(
                "URL final remota: $finalUrl"
            )

            log(
                "Content-Type remoto: $responseContentType"
            )

            log(
                "Content-Range remoto: $responseContentRange"
            )

            log(
                "Content-Length remoto: $responseContentLength"
            )

            /*
             * Se o servidor remoto devolveu HTML,
             * não devemos encaminhá-lo para o PS4 como se fosse PKG.
             */
            if (
                responseContentType
                    .lowercase()
                    .contains("text/html")
                ||
                responseContentType
                    .lowercase()
                    .contains("application/xhtml")
            ) {

                val sample =
                    try {

                        val input =
                            responseBody.byteStream()

                        val buffer =
                            ByteArray(1024)

                        val count =
                            input.read(
                                buffer
                            )

                        try {
                            input.close()
                        } catch (_: Exception) {
                        }

                        if (count > 0) {
                            sanitizeText(
                                buffer.copyOfRange(
                                    0,
                                    count
                                )
                            )
                        } else {
                            ""
                        }

                    } catch (e: Exception) {
                        "Não foi possível ler amostra HTML: ${e.message}"
                    }

                remoteResponse.close()

                logWarn(
                    "Servidor remoto devolveu HTML em vez do PKG."
                )

                logWarn(
                    "Amostra HTML:\n$sample"
                )

                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "text/plain; charset=utf-8",
                    "Servidor remoto devolveu HTML em vez do PKG"
                )
            }

            /*
             * Mantemos o body aberto para o PS4.
             */
            val status =
                when {

                    remoteResponse.code == 206 ->
                        Response.Status.PARTIAL_CONTENT

                    remoteResponse.code in 200..299 ->
                        Response.Status.OK

                    else ->
                        Response.Status.INTERNAL_ERROR
                }

            val response =
                newChunkedResponse(
                    status,
                    responseContentType.ifBlank {
                        "application/octet-stream"
                    },
                    responseBody.byteStream()
                )

            response.addHeader(
                "Accept-Ranges",
                "bytes"
            )

            if (responseContentRange.isNotBlank()) {

                response.addHeader(
                    "Content-Range",
                    responseContentRange
                )
            }

            if (responseContentLength.isNotBlank()) {

                response.addHeader(
                    "Content-Length",
                    responseContentLength
                )
            }

            response.addHeader(
                "Cache-Control",
                "no-cache"
            )

            log(
                "Proxy PKG entregue ao PS4"
            )

            log(
                "========== FIM PROXY PKG =========="
            )

            response

        } catch (e: Exception) {

            logError(
                "handleProxyPkg: " +
                        "${e.javaClass.simpleName}: ${e.message}"
            )

            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain; charset=utf-8",
                "Erro no proxy do PKG: ${e.message ?: "erro desconhecido"}"
            )
        }
    }

    private fun proxyAdmin(
        session: IHTTPSession
    ): Response {

        return try {

            val targetUrl =
                if (
                    session.uri == "/" ||
                    session.uri.isBlank()
                ) {
                    adminHost
                } else {
                    adminHost.trimEnd('/') +
                            session.uri
                }

            log(
                "Proxy administrativo: $targetUrl"
            )

            val requestBuilder =
                Request.Builder()
                    .url(targetUrl)
                    .get()
                    .header(
                        "User-Agent",
                        session.headers["user-agent"]
                            ?: "GTStore-Android"
                    )

            session.headers["accept"]?.let {
                requestBuilder.header(
                    "Accept",
                    it
                )
            }

            val response =
                proxyClient.newCall(
                    requestBuilder.build()
                ).execute()

            val body =
                response.body

            if (body == null) {

                response.close()

                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "text/plain; charset=utf-8",
                    "Resposta administrativa vazia"
                )
            }

            val contentType =
                response.header(
                    "Content-Type"
                ) ?: "text/html; charset=utf-8"

            val responseBody =
                body.byteStream()

            val nanoStatus =
                when {

                    response.code in 200..299 ->
                        Response.Status.OK

                    response.code == 404 ->
                        Response.Status.NOT_FOUND

                    else ->
                        Response.Status.INTERNAL_ERROR
                }

            newChunkedResponse(
                nanoStatus,
                contentType,
                responseBody
            )

        } catch (e: Exception) {

            logError(
                "proxyAdmin: " +
                        "${e.javaClass.simpleName}: ${e.message}"
            )

            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain; charset=utf-8",
                "Erro no proxy administrativo"
            )
        }
    }

    private fun buildManifestJson(
        contentId: String,
        title: String,
        category: String,
        originalFileSize: Long,
        packageDigest: String,
        packageUrl: String
    ): String {

        val safeSize =
            originalFileSize.coerceAtLeast(0L)

        val safeDigest =
            if (
                packageDigest.length == 64
            ) {
                packageDigest
            } else {
                "0".repeat(64)
            }

        /*
         * hashValue permanece como zeros porque o proxy
         * não está armazenando o PKG localmente.
         */
        val hashValue =
            "0".repeat(40)

        return """
        {
          "contentId": "${jsonEscape(contentId)}",
          "title": "${jsonEscape(title)}",
          "category": "${jsonEscape(category)}",
          "originalFileSize": $safeSize,
          "packageDigest": "${jsonEscape(safeDigest)}",
          "numberOfSplitFiles": 1,
          "pieces": [
            {
              "url": "${jsonEscape(packageUrl)}",
              "fileOffset": 0,
              "fileSize": $safeSize,
              "hashValue": "$hashValue"
            }
          ]
        }
        """.trimIndent()
    }

    private fun readRequestBody(
        session: IHTTPSession
    ): String {

        val contentLength =
            session.headers["content-length"]
                ?.toIntOrNull()
                ?: 0

        val bodyMap =
            HashMap<String, String>()

        session.parseBody(
            bodyMap
        )

        val postData =
            bodyMap["postData"]

        if (postData != null) {
            return postData
        }

        if (contentLength <= 0) {
            return ""
        }

        val input =
            session.inputStream

        val buffer =
            ByteArray(contentLength)

        var offset = 0

        while (
            offset < contentLength
        ) {

            val read =
                input.read(
                    buffer,
                    offset,
                    contentLength - offset
                )

            if (read <= 0) {
                break
            }

            offset += read
        }

        return String(
            buffer,
            0,
            offset,
            StandardCharsets.UTF_8
        )
    }

    private fun jsonError(
        message: String
    ): Response {

        return newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR,
            "application/json; charset=utf-8",
            """
            {
              "success": false,
              "error": "${jsonEscape(message)}"
            }
            """.trimIndent()
        )
    }

    private fun firstNonBlank(
        vararg values: String
    ): String {

        return values.firstOrNull {
            it.isNotBlank()
        }?.trim() ?: ""
    }

    private fun isValidContentId(
        contentId: String?
    ): Boolean {

        if (contentId.isNullOrBlank()) {
            return false
        }

        val id =
            contentId.trim()

        if (id.length != CONTENT_ID_LENGTH) {
            return false
        }

        if (id.any { it.isWhitespace() }) {
            return false
        }

        if (!id.contains("-")) {
            return false
        }

        if (
            id.take(4)
                .any {
                    !it.isLetterOrDigit()
                }
        ) {
            return false
        }

        return id.all {
            it.code in 0x20..0x7E
        }
    }

    private fun readUInt32BE(
        bytes: ByteArray,
        offset: Int
    ): Long {

        if (
            offset < 0 ||
            offset + 4 > bytes.size
        ) {
            return 0L
        }

        return (
                ((bytes[offset].toInt() and 0xFF)
                        .toLong() shl 24)
                        or
                        ((bytes[offset + 1].toInt() and 0xFF)
                                .toLong() shl 16)
                        or
                        ((bytes[offset + 2].toInt() and 0xFF)
                                .toLong() shl 8)
                        or
                        (bytes[offset + 3].toInt() and 0xFF)
                )
    }

    private fun extractTotalSize(
        contentRange: String,
        contentLength: String
    ): Long {

        /*
         * Exemplo:
         * bytes 0-4095/1423900672
         */
        if (contentRange.isNotBlank()) {

            val slash =
                contentRange.lastIndexOf('/')

            if (slash >= 0) {

                val total =
                    contentRange
                        .substring(
                            slash + 1
                        )
                        .trim()

                val parsed =
                    total.toLongOrNull()

                if (
                    parsed != null &&
                    parsed > 0L
                ) {
                    return parsed
                }
            }
        }

        return contentLength
            .toLongOrNull()
            ?.takeIf { it > 0L }
            ?: 0L
    }

    private fun sanitizeText(
        bytes: ByteArray
    ): String {

        return String(
            bytes,
            StandardCharsets.UTF_8
        )
            .replace("\u0000", "")
            .replace("\r", "")
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun jsonEscape(
        value: String
    ): String {

        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
    }

    private fun encodePathSegment(
        value: String
    ): String {

        return java.net.URLEncoder
            .encode(
                value,
                StandardCharsets.UTF_8.name()
            )
            .replace("+", "%20")
    }

    private fun decodePathSegment(
        value: String
    ): String {

        return try {

            URLDecoder.decode(
                value,
                StandardCharsets.UTF_8.name()
            )

        } catch (_: Exception) {

            value
        }
    }

    private fun getLocalWifiAddress(): String {

        return try {

            val interfaces =
                java.net.NetworkInterface
                    .getNetworkInterfaces()

            while (
                interfaces.hasMoreElements()
            ) {

                val networkInterface =
                    interfaces.nextElement()

                if (
                    !networkInterface.isUp ||
                    networkInterface.isLoopback
                ) {
                    continue
                }

                val addresses =
                    networkInterface.inetAddresses

                while (
                    addresses.hasMoreElements()
                ) {

                    val address =
                        addresses.nextElement()

                    if (
                        address is Inet4Address &&
                        !address.isLoopbackAddress
                    ) {

                        val host =
                            address.hostAddress

                        if (
                            host != null &&
                            (
                                host.startsWith("192.168.") ||
                                host.startsWith("10.") ||
                                is172Private(host)
                            )
                        ) {
                            return host
                        }
                    }
                }
            }

            InetAddress
                .getLocalHost()
                .hostAddress
                ?: ""

        } catch (e: Exception) {

            logError(
                "getLocalWifiAddress: ${e.message}"
            )

            ""
        }
    }

    private fun is172Private(
        ip: String
    ): Boolean {

        return try {

            val firstTwo =
                ip.split(".")

            if (firstTwo.size < 2) {
                false
            } else {

                val first =
                    firstTwo[0].toInt()

                val second =
                    firstTwo[1].toInt()

                first == 172 &&
                        second in 16..31
            }

        } catch (_: Exception) {

            false
        }
    }

    private data class RemotePkgMetadata(
        val validPkg: Boolean,
        val contentId: String,
        val digest: String,
        val size: Long
    ) {

        companion object {

            fun invalid(
                suppliedContentId: String
            ): RemotePkgMetadata {

                return RemotePkgMetadata(
                    validPkg = false,
                    contentId = suppliedContentId,
                    digest = "",
                    size = 0L
                )
            }
        }
    }
}



