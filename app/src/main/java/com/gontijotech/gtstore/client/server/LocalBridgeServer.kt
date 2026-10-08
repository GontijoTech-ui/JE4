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
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class LocalBridgeServer(
    private val context: Context,
    private val port: Int = 8080,
    private val adminHost: String = "https://loja.gontijotech.com.br"
) : NanoHTTPD(port) {

    private val tag = "GTStore-Bridge"

    companion object {
        /*
         * Estrutura básica de PKG PS4.
         *
         * Magic:
         * 0x7F 43 4E 54
         *
         * Content ID:
         * offset 0x40
         * tamanho 36 bytes
         *
         * Digest:
         * offset 0xFE0
         * tamanho 32 bytes
         */
        private const val PKG_MAGIC = 0x7F434E54L

        private const val PKG_HEADER_SIZE = 0x1000

        private const val CONTENT_ID_OFFSET = 0x40
        private const val CONTENT_ID_LENGTH = 36

        private const val DIGEST_OFFSET = 0xFE0
        private const val DIGEST_LENGTH = 32
    }

    // ================================================================
    // CLIENTE PARA METADADOS / INTERFACE
    // ================================================================

    private val proxyClient =
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

    // ================================================================
    // CLIENTE PARA STREAMING DO PKG
    // ================================================================

    private val streamClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

    private val payloader =
        Ps4Payloader(context)

    // Content-ID -> manifesto JSON
    private val manifestCache =
        ConcurrentHashMap<String, String>()

    // Content-ID -> URL remota
    private val pkgUrlCache =
        ConcurrentHashMap<String, String>()

    @Volatile
    private var lastContentId: String? = null

    // ================================================================
    // LOG
    // ================================================================

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

    // ================================================================
    // ROUTER PRINCIPAL
    // ================================================================

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

                method == Method.GET &&
                    uri.startsWith("/download-pkg/") -> {

                    handleProxyPkg(session)
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

    // ================================================================
    // INJEÇÃO
    // ================================================================

    private fun handleLocalInject(
        session: IHTTPSession
    ): Response {

        val map = HashMap<String, String>()

        session.parseBody(map)

        val postData =
            map["postData"] ?: "{}"

        fileLog("========================================")
        fileLog("INÍCIO DA INJEÇÃO")
        fileLog("JSON recebido da interface:")
        fileLog(postData)

        val json = JSONObject(postData)

        val ps4Ip =
            json.optString("ps4Ip").trim()

        if (ps4Ip.isEmpty()) {

            fileWarn("IP do PS4 não informado.")

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
            json.optString(
                "category",
                "gd"
            ).trim()

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

        // ------------------------------------------------------------
        // URL DO PKG
        // ------------------------------------------------------------

        val directPkgUrl = (
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

        // ------------------------------------------------------------
        // CONTENT ID ORIGINAL DA INTERFACE
        // ------------------------------------------------------------

        var detectedContentId =
            json.optString("contentId")
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
            json.optLong("size", 0L)

        var realDigest =
            "0".repeat(64)

        fileLog("PS4: $ps4Ip")
        fileLog("Título: $title")
        fileLog("Categoria recebida: $rawCategory")
        fileLog("Categoria BGFT: $bgftCategory")
        fileLog("PKG direto: $directPkgUrl")
        fileLog(
            "Content-ID inicial: ${
                if (detectedContentId.isBlank()) {
                    "(vazio)"
                } else {
                    detectedContentId
                }
            }"
        )
        fileLog(
            "Tamanho informado pela interface: $realFileSize"
        )

        // ============================================================
        // INSPEÇÃO DO PKG REMOTO
        // ============================================================

        try {

            fileLog("----------------------------------------")
            fileLog("INSPEÇÃO DO PKG REMOTO")
            fileLog("URL: $directPkgUrl")
            fileLog("Solicitando Range: bytes=0-4095")

            val rangeRequest =
                Request.Builder()
                    .url(directPkgUrl)

                    /*
                     * Mantemos o User-Agent do PS4 para evitar
                     * respostas diferentes de alguns servidores/CDNs.
                     */
                    .addHeader(
                        "User-Agent",
                        "PlayStation 4"
                    )

                    .addHeader(
                        "Accept",
                        "application/octet-stream,*/*"
                    )

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
                        "HTTP remoto: ${response.code}"
                    )

                    fileLog(
                        "URL final: ${response.request.url}"
                    )

                    fileLog(
                        "Content-Type: ${
                            response.header("Content-Type")
                                ?: "(não informado)"
                        }"
                    )

                    val contentRange =
                        response.header("Content-Range")

                    fileLog(
                        "Content-Range: ${
                            contentRange ?: "(não informado)"
                        }"
                    )

                    val contentLength =
                        response.header("Content-Length")

                    fileLog(
                        "Content-Length: ${
                            contentLength ?: "(não informado)"
                        }"
                    )

                    val stream: InputStream? =
                        response.body?.byteStream()

                    if (stream == null) {

                        fileWarn(
                            "Resposta remota não possui corpo."
                        )

                    } else {

                        // ------------------------------------------------
                        // LER EXATAMENTE O CABEÇALHO NECESSÁRIO
                        // ------------------------------------------------

                        val headerBytes =
                            ByteArray(PKG_HEADER_SIZE)

                        var bytesRead = 0

                        while (
                            bytesRead < PKG_HEADER_SIZE
                        ) {

                            val count =
                                stream.read(
                                    headerBytes,
                                    bytesRead,
                                    PKG_HEADER_SIZE - bytesRead
                                )

                            if (count == -1) {
                                break
                            }

                            if (count == 0) {
                                break
                            }

                            bytesRead += count
                        }

                        fileLog(
                            "Bytes recebidos para inspeção: $bytesRead"
                        )

                        // ------------------------------------------------
                        // PRIMEIROS BYTES PARA DIAGNÓSTICO
                        // ------------------------------------------------

                        if (bytesRead >= 4) {

                            val firstBytes =
                                headerBytes
                                    .copyOfRange(
                                        0,
                                        minOf(bytesRead, 16)
                                    )

                            val firstHex =
                                firstBytes.joinToString("") {
                                    "%02X".format(
                                        it.toInt() and 0xFF
                                    )
                                }

                            fileLog(
                                "Primeiros bytes HEX: $firstHex"
                            )
                        }

                        // ------------------------------------------------
                        // VALIDAR MAGIC DO PKG
                        // ------------------------------------------------

                        var validPkg = false

                        if (bytesRead >= 4) {

                            val magic =
                                ByteBuffer
                                    .wrap(
                                        headerBytes,
                                        0,
                                        4
                                    )
                                    .order(
                                        ByteOrder.BIG_ENDIAN
                                    )
                                    .int
                                    .toLong() and
                                    0xFFFFFFFFL

                            fileLog(
                                "PKG MAGIC recebido: " +
                                    "0x${magic.toString(16).uppercase()}"
                            )

                            fileLog(
                                "PKG MAGIC esperado: " +
                                    "0x${PKG_MAGIC.toString(16).uppercase()}"
                            )

                            validPkg =
                                magic == PKG_MAGIC

                            if (validPkg) {

                                fileLog(
                                    "PKG válido: SIM"
                                )

                            } else {

                                fileWarn(
                                    "PKG válido: NÃO"
                                )

                                fileWarn(
                                    "A resposta remota não começa " +
                                        "com o MAGIC esperado de um PKG."
                                )

                                fileWarn(
                                    "O Content-ID recebido da interface " +
                                        "será preservado."
                                )
                            }

                        } else {

                            fileWarn(
                                "Não foi possível ler nem os 4 primeiros " +
                                    "bytes da resposta remota."
                            )
                        }

                        // =================================================
                        // SOMENTE PKG VÁLIDO PODE SOBRESCREVER METADADOS
                        // =================================================

                        if (validPkg) {

                            // ------------------------------------------------
                            // CONTENT ID
                            // ------------------------------------------------

                            if (
                                bytesRead >=
                                CONTENT_ID_OFFSET +
                                CONTENT_ID_LENGTH
                            ) {

                                val pkgContentId =
                                    String(
                                        headerBytes,
                                        CONTENT_ID_OFFSET,
                                        CONTENT_ID_LENGTH,
                                        Charsets.US_ASCII
                                    )
                                        .trim(
                                            '\u0000',
                                            ' ',
                                            '\t',
                                            '\r',
                                            '\n'
                                        )

                                fileLog(
                                    "Content-ID encontrado no PKG: " +
                                        pkgContentId
                                )

                                if (
                                    isValidContentId(
                                        pkgContentId
                                    )
                                ) {

                                    detectedContentId =
                                        pkgContentId

                                    fileLog(
                                        "Content-ID oficial utilizado: " +
                                            detectedContentId
                                    )

                                } else {

                                    fileWarn(
                                        "Content-ID encontrado no PKG " +
                                            "não passou na validação: " +
                                            pkgContentId
                                    )

                                    fileLog(
                                        "Content-ID informado pela " +
                                            "interface será preservado: " +
                                            detectedContentId
                                    )
                                }

                            } else {

                                fileWarn(
                                    "Cabeçalho insuficiente para " +
                                        "extrair Content-ID."
                                )
                            }

                            // ------------------------------------------------
                            // DIGEST
                            // ------------------------------------------------

                            if (
                                bytesRead >=
                                DIGEST_OFFSET + DIGEST_LENGTH
                            ) {

                                realDigest =
                                    headerBytes
                                        .copyOfRange(
                                            DIGEST_OFFSET,
                                            DIGEST_OFFSET +
                                                DIGEST_LENGTH
                                        )
                                        .joinToString("") {
                                            "%02X".format(
                                                it.toInt() and 0xFF
                                            )
                                        }

                                fileLog(
                                    "Digest PKG extraído: " +
                                        realDigest
                                )

                            } else {

                                fileWarn(
                                    "Cabeçalho insuficiente para " +
                                        "extrair o Digest."
                                )
                            }

                            // ------------------------------------------------
                            // TAMANHO
                            // ------------------------------------------------

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
                                    "Tamanho exato informado pelo " +
                                        "servidor remoto: " +
                                        "$realFileSize bytes"
                                )
                            }

                        } else {

                            /*
                             * MUITO IMPORTANTE:
                             *
                             * Não usamos Content-Length de uma página HTML
                             * como tamanho do PKG.
                             *
                             * Mantemos o tamanho recebido pela interface.
                             */

                            fileWarn(
                                "Metadados remotos ignorados porque " +
                                    "a resposta não é um PKG válido."
                            )

                            fileLog(
                                "Tamanho preservado da interface: " +
                                    "$realFileSize bytes"
                            )

                            fileLog(
                                "Content-ID preservado da interface: " +
                                    detectedContentId
                            )

                            /*
                             * O digest também permanece zerado.
                             */
                            realDigest =
                                "0".repeat(64)
                        }
                    }
                }

        } catch (e: Exception) {

            fileWarn(
                "Falha ao obter metadados remotos: " +
                    "${e.message}"
            )

            /*
             * Se a consulta remota falhar completamente,
             * NÃO apagamos o Content-ID fornecido pela interface.
             */

            fileLog(
                "Content-ID preservado após falha remota: " +
                    detectedContentId
            )

            fileLog(
                "Tamanho preservado após falha remota: " +
                    realFileSize
            )
        }

        // ================================================================
        // CONTENT ID FINAL
        // ================================================================

        val finalContentId =
            if (
                isValidContentId(
                    detectedContentId
                )
            ) {

                detectedContentId

            } else {

                /*
                 * Se a interface também não forneceu um Content-ID
                 * válido, usamos fallback apenas para evitar crash.
                 */

                val fallback =
                    "CUSA" +
                        System.currentTimeMillis()
                            .toString()
                            .takeLast(5)

                fileWarn(
                    "Nenhum Content-ID válido foi encontrado."
                )

                fileWarn(
                    "Usando fallback: $fallback"
                )

                fallback
            }

        fileLog(
            "Content-ID FINAL: $finalContentId"
        )

        // ================================================================
        // TAMANHO FINAL
        // ================================================================

        if (realFileSize <= 0L) {

            realFileSize =
                1024L * 1024L * 500L

            fileWarn(
                "Tamanho não encontrado."
            )

            fileWarn(
                "Usando fallback de 500 MB."
            )
        }

        // ================================================================
        // IP LOCAL
        // ================================================================

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

        // ================================================================
        // CACHE DA URL REMOTA
        // ================================================================

        pkgUrlCache[
            finalContentId
        ] = directPkgUrl

        fileLog(
            "URL remota associada ao Content-ID:"
        )

        fileLog(
            "$finalContentId -> $directPkgUrl"
        )

        // ================================================================
        // URL LOCAL DO PKG
        // ================================================================

        val localPkgUrl =
            "http://$localIp:$port/download-pkg/$finalContentId"

        fileLog(
            "URL local do PKG: $localPkgUrl"
        )

        // ================================================================
        // MANIFESTO
        // ================================================================

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
                                    localPkgUrl
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
            "Manifesto armazenado no cache para: " +
                finalContentId
        )

        // ================================================================
        // URL DO MANIFESTO
        // ================================================================

        val localManifestUrl =
            "http://$localIp:$port/manifest/$finalContentId.json"

        fileLog(
            "URL do manifesto local: " +
                localManifestUrl
        )

        // ================================================================
        // PAYLOADER
        // ================================================================

        fileLog("========================================")
        fileLog("DISPARANDO PAYLOADER")
        fileLog("PS4: $ps4Ip")
        fileLog("Android: $localIp")
        fileLog("Título: $title")
        fileLog("Content-ID: $finalContentId")
        fileLog("Categoria: $bgftCategory")
        fileLog("Tamanho: $realFileSize")
        fileLog("Manifesto: $localManifestUrl")
        fileLog("========================================")

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

        if (result.isSuccess) {

            fileLog(
                "PAYLOADER FINALIZADO COM SUCESSO."
            )

        } else {

            val error =
                result.exceptionOrNull()

            fileError(
                "PAYLOADER FALHOU: " +
                    (
                        error?.message
                            ?: "erro desconhecido"
                        ),
                error
            )
        }

        // ================================================================
        // RESPOSTA PARA A INTERFACE
        // ================================================================

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
                        result.exceptionOrNull()
                            ?.message
                            ?: "Falha desconhecida na injeção."
                    )
                }
            }.toString()

        fileLog(
            "Resposta enviada à interface: " +
                responseJson
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

    // ================================================================
    // VALIDAÇÃO DO CONTENT ID
    // ================================================================

    private fun isValidContentId(
        contentId: String
    ): Boolean {

        val id =
            contentId.trim()

        if (id.length != CONTENT_ID_LENGTH) {
            return false
        }

        if (
            id.any {
                it.isWhitespace()
            }
        ) {
            return false
        }

        if (!id.contains("-")) {
            return false
        }

        /*
         * Content-ID PS4 normalmente começa com um
         * prefixo de quatro caracteres, seguido por "-".
         *
         * Exemplos:
         *
         * EP0002-CUSA00184_00-ANGRYBSTARWARSDL
         * UP0001-CUSA...
         * NP...
         */

        val prefix =
            id.substringBefore("-")

        if (prefix.length < 4) {
            return false
        }

        if (
            prefix.any {
                !it.isLetterOrDigit()
            }
        ) {
            return false
        }

        return true
    }

    // ================================================================
    // MANIFESTO
    // ================================================================

    private fun handleServeLocalManifest(
        session: IHTTPSession
    ): Response {

        val uri =
            session.uri

        val idFromPath =
            if (uri.startsWith("/manifest/")) {

                val raw =
                    uri
                        .removePrefix("/manifest/")
                        .removeSuffix(".json")

                try {
                    URLDecoder.decode(
                        raw,
                        "UTF-8"
                    )
                } catch (_: Exception) {
                    raw
                }

            } else {
                null
            }

        val requestId =
            idFromPath
                ?: session.parameters["id"]
                    ?.firstOrNull()
                ?: lastContentId

        fileLog(
            "PS4 solicitou manifesto: $requestId"
        )

        val manifest =
            if (!requestId.isNullOrBlank()) {
                manifestCache[requestId]
            } else {
                manifestCache.values.lastOrNull()
            }

        return if (manifest != null) {

            fileLog(
                "Manifesto entregue com sucesso ao PS4: " +
                    requestId
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

    // ================================================================
    // PROXY DO PKG
    // ================================================================

    private fun handleProxyPkg(
        session: IHTTPSession
    ): Response {

        val rawContentId =
            session.uri
                .removePrefix("/download-pkg/")

        val contentId =
            try {
                URLDecoder.decode(
                    rawContentId,
                    "UTF-8"
                )
            } catch (_: Exception) {
                rawContentId
            }

        val remoteUrl =
            pkgUrlCache[contentId]

        if (remoteUrl.isNullOrBlank()) {

            fileWarn(
                "URL remota do PKG não encontrada para contentId: " +
                    contentId
            )

            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "Arquivo não encontrado"
            )
        }

        val rangeHeader =
            session.headers["range"]

        fileLog(
            "PS4 solicitou bloco para [$contentId] | " +
                "Range: ${rangeHeader ?: "Completo"}"
        )

        val requestBuilder =
            Request.Builder()
                .url(remoteUrl)
                .addHeader(
                    "User-Agent",
                    "PlayStation 4"
                )
                .addHeader(
                    "Accept",
                    "application/octet-stream,*/*"
                )

        if (!rangeHeader.isNullOrBlank()) {

            requestBuilder.addHeader(
                "Range",
                rangeHeader
            )
        }

        return try {

            val remoteResponse =
                streamClient
                    .newCall(
                        requestBuilder.build()
                    )
                    .execute()

            val responseBody =
                remoteResponse.body

            if (
                !remoteResponse.isSuccessful ||
                responseBody == null
            ) {

                fileError(
                    "Servidor remoto recusou a requisição. " +
                        "Código HTTP: ${remoteResponse.code}"
                )

                remoteResponse.close()

                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "text/plain",
                    "Falha ao obter pacote da nuvem"
                )
            }

            val contentType =
                remoteResponse.header(
                    "Content-Type"
                ) ?: "application/octet-stream"

            val contentRange =
                remoteResponse.header(
                    "Content-Range"
                )

            val contentLength =
                responseBody.contentLength()

            fileLog(
                "Resposta remota do PKG: HTTP " +
                    remoteResponse.code
            )

            fileLog(
                "Content-Type remoto: $contentType"
            )

            fileLog(
                "Content-Range remoto: " +
                    (contentRange ?: "(não informado)")
            )

            fileLog(
                "Content-Length remoto: " +
                    contentLength
            )

            /*
             * Se a resposta veio 200 para um pedido Range,
             * registramos isso explicitamente.
             */

            if (
                rangeHeader != null &&
                remoteResponse.code == 200 &&
                contentRange == null
            ) {

                fileWarn(
                    "Servidor remoto ignorou o Range solicitado."
                )
            }

            val status =
                if (
                    remoteResponse.code == 206 ||
                    contentRange != null
                ) {
                    Response.Status.PARTIAL_CONTENT
                } else {
                    Response.Status.OK
                }

            val nanoResponse =
                newFixedLengthResponse(
                    status,
                    contentType,
                    responseBody.byteStream(),
                    contentLength
                )

            nanoResponse.addHeader(
                "Accept-Ranges",
                "bytes"
            )

            if (!contentRange.isNullOrBlank()) {

                nanoResponse.addHeader(
                    "Content-Range",
                    contentRange
                )

                fileLog(
                    "Retransmitindo Content-Range: " +
                        "$contentRange ($contentLength bytes)"
                )
            }

            addCors(
                session,
                nanoResponse
            )

        } catch (e: Exception) {

            fileError(
                "Interrupção durante streaming do PKG " +
                    "para o PS4: ${e.message}",
                e
            )

            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Erro de conexão no streaming"
            )
        }
    }

    // ================================================================
    // INTERFACE ESTÁTICA
    // ================================================================

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
                    response.body?.string()
                        ?: "Falha ao carregar interface remota."

                fileLog(
                    "Interface remota respondeu HTTP " +
                        response.code
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

    // ================================================================
    // PROXY GERAL
    // ================================================================

    private fun handleProxyForward(
        session: IHTTPSession
    ): Response {

        val queryString =
            if (
                !session.queryParameterString
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
                    response.body?.bytes()
                        ?: ByteArray(0)

                val contentType =
                    response.header(
                        "Content-Type"
                    ) ?: "application/octet-stream"

                val status =
                    Response.Status.lookup(
                        response.code
                    ) ?: object : Response.IStatus {

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

    // ================================================================
    // IP LOCAL WI-FI
    // ================================================================

    private fun getLocalWifiAddress(): String? {

        try {

            val interfaces =
                NetworkInterface
                    .getNetworkInterfaces()
                    ?.toList()
                    ?: return null

            val validInterfaces =
                interfaces
                    .filter { networkInterface ->

                        val name =
                            networkInterface.name
                                .lowercase()

                        networkInterface.isUp &&
                            !networkInterface.isLoopback &&
                            !name.contains("tun") &&
                            !name.contains("tap") &&
                            !name.contains("rmnet") &&
                            !name.contains("pdp") &&
                            !name.contains("dummy")
                    }
                    .sortedByDescending {
                        it.name.startsWith("wlan") ||
                            it.name.startsWith("ap")
                    }

            for (
                networkInterface
                in validInterfaces
            ) {

                for (
                    address
                    in networkInterface.inetAddresses
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

    // ================================================================
    // CORS
    // ================================================================

    private fun addCors(
        session: IHTTPSession,
        response: Response
    ): Response {

        val requestedHeaders =
            session.headers[
                "access-control-request-headers"
            ] ?: "Content-Type, Authorization, Range, X-Requested-With"

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

    // ================================================================
    // ERRO JSON
    // ================================================================

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



