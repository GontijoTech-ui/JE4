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

        // ============================================================
        // ESTRUTURA BÁSICA DO CABEÇALHO PKG
        // ============================================================

        private const val PKG_MAGIC = 0x7F434E54L

        private const val PKG_HEADER_SIZE = 0x1000

        private const val CONTENT_ID_OFFSET = 0x40
        private const val CONTENT_ID_LENGTH = 36

        private const val DIGEST_OFFSET = 0xFE0
        private const val DIGEST_LENGTH = 32

        // Quantidade máxima de HTML que será gravada no log.
        // Evita colocar uma página inteira no GTSTORE-log.txt.
        private const val MAX_HTML_LOG = 2500
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
            .retryOnConnectionFailure(true)
            .build()

    // ================================================================
    // CLIENTE PARA STREAMING
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

    // ================================================================
    // CACHE
    // ================================================================

    private val manifestCache =
        ConcurrentHashMap<String, String>()

    private val pkgUrlCache =
        ConcurrentHashMap<String, String>()

    @Volatile
    private var lastContentId: String? = null

    // ================================================================
    // LOG
    // ================================================================

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
                        ?.let { " | $it" }
                        ?: ""
                    )
        )
    }

    // ================================================================
    // ROUTER
    // ================================================================

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

            val errorJson =
                JSONObject()
                    .put("success", false)
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
                    errorJson
                )
            )
        }
    }

    // ================================================================
    // INJEÇÃO LOCAL
    // ================================================================

    private fun handleLocalInject(
        session: IHTTPSession
    ): Response {

        val map =
            HashMap<String, String>()

        session.parseBody(map)

        val postData =
            map["postData"] ?: "{}"

        fileLog("========================================")
        fileLog("INÍCIO DA INJEÇÃO")
        fileLog("JSON recebido da interface:")
        fileLog(postData)

        val json =
            JSONObject(postData)

        val ps4Ip =
            json.optString("ps4Ip")
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
            json.optString(
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

        // ============================================================
        // URL DO PKG
        // ============================================================

        val directPkgUrl =
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
                .trim()

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

        // ============================================================
        // CONTENT-ID DA INTERFACE
        // ============================================================

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
                if (
                    detectedContentId.isBlank()
                ) {
                    "(vazio)"
                } else {
                    detectedContentId
                }
        )

        fileLog(
            "Tamanho informado pela interface: " +
                realFileSize
        )

        // ============================================================
        // DIAGNÓSTICO REMOTO
        // ============================================================

        inspectRemotePackage(
            directPkgUrl = directPkgUrl,
            initialContentId = detectedContentId
        )?.let { metadata ->

            if (
                metadata.isValidPkg
            ) {

                fileLog(
                    "========================================"
                )

                fileLog(
                    "PKG REMOTO VALIDADO COM SUCESSO"
                )

                fileLog(
                    "Content-ID remoto: " +
                        metadata.contentId
                )

                fileLog(
                    "Tamanho remoto: " +
                        metadata.fileSize
                )

                fileLog(
                    "Digest remoto: " +
                        metadata.digest
                )

                fileLog(
                    "========================================"
                )

                if (
                    isValidContentId(
                        metadata.contentId
                    )
                ) {

                    detectedContentId =
                        metadata.contentId

                    fileLog(
                        "Content-ID oficial utilizado: " +
                            detectedContentId
                    )
                }

                if (
                    metadata.fileSize > 0L
                ) {

                    realFileSize =
                        metadata.fileSize

                    fileLog(
                        "Tamanho atualizado pelo PKG: " +
                            realFileSize
                    )
                }

                if (
                    metadata.digest.length == 64
                ) {

                    realDigest =
                        metadata.digest

                    fileLog(
                        "Digest atualizado pelo PKG: " +
                            realDigest
                    )
                }

            } else {

                fileWarn(
                    "A resposta remota NÃO é um PKG válido."
                )

                fileWarn(
                    "Content-ID original será preservado."
                )

                fileLog(
                    "Content-ID preservado: " +
                        detectedContentId
                )

                fileLog(
                    "Tamanho preservado: " +
                        realFileSize
                )

                realDigest =
                    "0".repeat(64)
            }
        }

        // ============================================================
        // CONTENT-ID FINAL
        // ============================================================

        val finalContentId =
            if (
                isValidContentId(
                    detectedContentId
                )
            ) {

                detectedContentId

            } else {

                val fallback =
                    "CUSA" +
                        System.currentTimeMillis()
                            .toString()
                            .takeLast(5)

                fileWarn(
                    "Content-ID recebido não passou na validação: " +
                        detectedContentId
                )

                fileWarn(
                    "Usando fallback: $fallback"
                )

                fallback
            }

        fileLog(
            "Content-ID FINAL: " +
                finalContentId
        )

        // ============================================================
        // TAMANHO
        // ============================================================

        if (
            realFileSize <= 0L
        ) {

            realFileSize =
                1024L *
                    1024L *
                    500L

            fileWarn(
                "Tamanho não encontrado."
            )

            fileWarn(
                "Usando fallback de 500 MB."
            )
        }

        // ============================================================
        // IP LOCAL
        // ============================================================

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

        // ============================================================
        // CACHE DA URL REMOTA
        // ============================================================

        pkgUrlCache[
            finalContentId
        ] = directPkgUrl

        fileLog(
            "URL remota associada:"
        )

        fileLog(
            "$finalContentId -> $directPkgUrl"
        )

        // ============================================================
        // URL LOCAL DO PKG
        // ============================================================

        val localPkgUrl =
            "http://$localIp:$port/download-pkg/$finalContentId"

        fileLog(
            "URL local do PKG: $localPkgUrl"
        )

        // ============================================================
        // MANIFESTO
        // ============================================================

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

        val localManifestUrl =
            "http://$localIp:$port/manifest/$finalContentId.json"

        fileLog(
            "URL do manifesto local: " +
                localManifestUrl
        )

        // ============================================================
        // PAYLOADER
        // ============================================================

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

        if (
            result.isSuccess
        ) {

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

        // ============================================================
        // RESPOSTA
        // ============================================================

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

                if (
                    result.isFailure
                ) {

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
    // INSPEÇÃO REMOTA
    // ================================================================

    private fun inspectRemotePackage(
        directPkgUrl: String,
        initialContentId: String
    ): RemotePkgMetadata? {

        fileLog("========================================")
        fileLog("DIAGNÓSTICO REMOTO DO PKG")
        fileLog("URL original:")
        fileLog(directPkgUrl)
        fileLog("Content-ID recebido:")
        fileLog(initialContentId)
        fileLog("========================================")

        return try {

            val request =
                Request.Builder()
                    .url(directPkgUrl)

                    /*
                     * O mesmo User-Agent usado posteriormente
                     * no streaming para manter o comportamento
                     * consistente.
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
                        "Accept-Encoding",
                        "identity"
                    )

                    .addHeader(
                        "Range",
                        "bytes=0-4095"
                    )

                    .build()

            fileLog(
                "Enviando requisição Range..."
            )

            proxyClient
                .newCall(request)
                .execute()
                .use { response ->

                    fileLog(
                        "HTTP: ${response.code}"
                    )

                    fileLog(
                        "Mensagem HTTP: ${response.message}"
                    )

                    fileLog(
                        "URL original da requisição: " +
                            request.url
                    )

                    fileLog(
                        "URL final após redirects: " +
                            response.request.url
                    )

                    val contentType =
                        response.header(
                            "Content-Type"
                        )

                    val contentLength =
                        response.header(
                            "Content-Length"
                        )

                    val contentRange =
                        response.header(
                            "Content-Range"
                        )

                    val location =
                        response.header(
                            "Location"
                        )

                    fileLog(
                        "Content-Type: " +
                            (
                                contentType
                                    ?: "(não informado)"
                                )
                    )

                    fileLog(
                        "Content-Length: " +
                            (
                                contentLength
                                    ?: "(não informado)"
                                )
                    )

                    fileLog(
                        "Content-Range: " +
                            (
                                contentRange
                                    ?: "(não informado)"
                                )
                    )

                    if (
                        location != null
                    ) {

                        fileLog(
                            "Location: $location"
                        )
                    }

                    fileLog(
                        "Resposta bem-sucedida: " +
                            response.isSuccessful
                    )

                    val body =
                        response.body

                    if (body == null) {

                        fileWarn(
                            "Servidor remoto não retornou body."
                        )

                        return@use RemotePkgMetadata.invalid(
                            initialContentId
                        )
                    }

                    // ====================================================
                    // LER PRIMEIROS 4096 BYTES
                    // ====================================================

                    val headerBytes =
                        ByteArray(
                            PKG_HEADER_SIZE
                        )

                    var bytesRead = 0

                    val stream =
                        body.byteStream()

                    while (
                        bytesRead <
                        PKG_HEADER_SIZE
                    ) {

                        val count =
                            stream.read(
                                headerBytes,
                                bytesRead,
                                PKG_HEADER_SIZE -
                                    bytesRead
                            )

                        if (
                            count == -1
                        ) {
                            break
                        }

                        if (
                            count == 0
                        ) {
                            break
                        }

                        bytesRead += count
                    }

                    fileLog(
                        "Bytes recebidos para diagnóstico: " +
                            bytesRead
                    )

                    if (
                        bytesRead <= 0
                    ) {

                        fileWarn(
                            "Resposta remota sem dados."
                        )

                        return@use RemotePkgMetadata.invalid(
                            initialContentId
                        )
                    }

                    // ====================================================
                    // HEX
                    // ====================================================

                    val previewLength =
                        minOf(
                            bytesRead,
                            64
                        )

                    val previewBytes =
                        headerBytes
                            .copyOfRange(
                                0,
                                previewLength
                            )

                    val previewHex =
                        previewBytes.joinToString(
                            separator = " "
                        ) {

                            "%02X".format(
                                it.toInt() and 0xFF
                            )
                        }

                    fileLog(
                        "Primeiros bytes HEX:"
                    )

                    fileLog(
                        previewHex
                    )

                    // ====================================================
                    // TEXTO
                    // ====================================================

                    val textPreview =
                        headerBytes
                            .copyOfRange(
                                0,
                                minOf(
                                    bytesRead,
                                    512
                                )
                            )
                            .toString(
                                Charsets.UTF_8
                            )
                            .replace(
                                Regex(
                                    "[^\\x20-\\x7E\\r\\n\\t]"
                                ),
                                "."
                            )

                    fileLog(
                        "Prévia textual da resposta:"
                    )

                    fileLog(
                        textPreview
                    )

                    // ====================================================
                    // HTML
                    // ====================================================

                    val normalizedContentType =
                        contentType
                            ?.lowercase()
                            ?: ""

                    val appearsHtml =
                        normalizedContentType
                            .contains("text/html") ||
                            normalizedContentType
                                .contains("application/xhtml")

                    if (
                        appearsHtml
                    ) {

                        fileWarn(
                            "ATENÇÃO: servidor remoto " +
                                "respondeu HTML."
                        )

                        fileWarn(
                            "A URL fornecida não está entregando " +
                                "um PKG diretamente nesta requisição."
                        )

                        logHtmlDiagnostics(
                            headerBytes,
                            bytesRead
                        )
                    }

                    // ====================================================
                    // MAGIC
                    // ====================================================

                    if (
                        bytesRead < 4
                    ) {

                        fileWarn(
                            "Menos de 4 bytes recebidos."
                        )

                        return@use RemotePkgMetadata.invalid(
                            initialContentId
                        )
                    }

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
                        "MAGIC recebido: " +
                            "0x${magic.toString(16).uppercase()}"
                    )

                    fileLog(
                        "MAGIC esperado: " +
                            "0x${PKG_MAGIC.toString(16).uppercase()}"
                    )

                    val validPkg =
                        magic == PKG_MAGIC

                    if (!validPkg) {

                        fileWarn(
                            "========================================"
                        )

                        fileWarn(
                            "RESPOSTA REMOTA NÃO É UM PKG"
                        )

                        fileWarn(
                            "O Content-ID da interface será preservado."
                        )

                        fileWarn(
                            "========================================"
                        )

                        return@use RemotePkgMetadata.invalid(
                            initialContentId
                        )
                    }

                    fileLog(
                        "PKG MAGIC válido."
                    )

                    // ====================================================
                    // CONTENT ID
                    // ====================================================

                    var pkgContentId = ""

                    if (
                        bytesRead >=
                        CONTENT_ID_OFFSET +
                        CONTENT_ID_LENGTH
                    ) {

                        pkgContentId =
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
                            "Content-ID extraído: " +
                                pkgContentId
                        )

                    } else {

                        fileWarn(
                            "Cabeçalho insuficiente para Content-ID."
                        )
                    }

                    if (
                        !isValidContentId(
                            pkgContentId
                        )
                    ) {

                        fileWarn(
                            "Content-ID do PKG não passou na validação."
                        )

                        fileWarn(
                            "Valor encontrado: $pkgContentId"
                        )

                        pkgContentId =
                            initialContentId
                    }

                    // ====================================================
                    // DIGEST
                    // ====================================================

                    var digest =
                        "0".repeat(64)

                    if (
                        bytesRead >=
                        DIGEST_OFFSET +
                        DIGEST_LENGTH
                    ) {

                        digest =
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
                            "Digest extraído: $digest"
                        )

                    } else {

                        fileWarn(
                            "Cabeçalho insuficiente para Digest."
                        )
                    }

                    // ====================================================
                    // TAMANHO
                    // ====================================================

                    val totalLength =
                        contentRange
                            ?.substringAfterLast("/")
                            ?.toLongOrNull()
                            ?: contentLength
                                ?.toLongOrNull()
                            ?: 0L

                    fileLog(
                        "Tamanho remoto detectado: " +
                            totalLength
                    )

                    RemotePkgMetadata(
                        isValidPkg = true,
                        contentId = pkgContentId,
                        fileSize = totalLength,
                        digest = digest
                    )
                }

        } catch (e: Exception) {

            fileError(
                "Erro durante diagnóstico remoto: " +
                    e.message,
                e
            )

            RemotePkgMetadata.invalid(
                initialContentId
            )
        }
    }

    // ================================================================
    // DIAGNÓSTICO DE HTML
    // ================================================================

    private fun logHtmlDiagnostics(
        bytes: ByteArray,
        length: Int
    ) {

        if (
            length <= 0
        ) {
            return
        }

        val sampleLength =
            minOf(
                length,
                MAX_HTML_LOG
            )

        val sample =
            bytes
                .copyOfRange(
                    0,
                    sampleLength
                )
                .toString(
                    Charsets.UTF_8
                )
                .replace(
                    Regex(
                        "[^\\x20-\\x7E\\r\\n\\t]"
                    ),
                    ""
                )

        fileLog(
            "========================================"
        )

        fileLog(
            "DIAGNÓSTICO DA RESPOSTA HTML"
        )

        fileLog(
            "Tamanho da amostra: $sampleLength bytes"
        )

        fileLog(
            sample
        )

        fileLog(
            "========================================"
        )

        val lower =
            sample.lowercase()

        if (
            lower.contains("cloudflare")
        ) {

            fileWarn(
                "A resposta HTML contém referência a Cloudflare."
            )
        }

        if (
            lower.contains("captcha")
        ) {

            fileWarn(
                "A resposta HTML contém referência a CAPTCHA."
            )
        }

        if (
            lower.contains("verify you are human")
        ) {

            fileWarn(
                "A resposta HTML parece ser uma verificação anti-bot."
            )
        }

        if (
            lower.contains("access denied")
        ) {

            fileWarn(
                "A resposta HTML indica ACCESS DENIED."
            )
        }

        if (
            lower.contains("forbidden")
        ) {

            fileWarn(
                "A resposta HTML indica FORBIDDEN."
            )
        }

        if (
            lower.contains("login")
        ) {

            fileWarn(
                "A resposta HTML contém indicação de LOGIN."
            )
        }

        if (
            lower.contains("download")
        ) {

            fileLog(
                "A página HTML contém referência a DOWNLOAD."
            )
        }
    }

    // ================================================================
    // VALIDAÇÃO DO CONTENT-ID
    // ================================================================

    private fun isValidContentId(
        contentId: String
    ): Boolean {

        val id =
            contentId.trim()

        if (
            id.length !=
            CONTENT_ID_LENGTH
        ) {
            return false
        }

        if (
            id.any {
                it.isWhitespace()
            }
        ) {
            return false
        }

        if (
            !id.contains("-")
        ) {
            return false
        }

        val prefix =
            id.substringBefore("-")

        if (
            prefix.length < 4
        ) {
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
            if (
                uri.startsWith("/manifest/")
            ) {

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
            "PS4 solicitou manifesto: " +
                requestId
        )

        val manifest =
            if (
                !requestId.isNullOrBlank()
            ) {

                manifestCache[
                    requestId
                ]

            } else {

                manifestCache
                    .values
                    .lastOrNull()
            }

        return if (
            manifest != null
        ) {

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
                "Manifesto inexistente para: " +
                    requestId
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
    // STREAMING DO PKG
    // ================================================================

    private fun handleProxyPkg(
        session: IHTTPSession
    ): Response {

        val rawContentId =
            session.uri
                .removePrefix(
                    "/download-pkg/"
                )

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
            pkgUrlCache[
                contentId
            ]

        if (
            remoteUrl.isNullOrBlank()
        ) {

            fileWarn(
                "URL remota do PKG não encontrada para: " +
                    contentId
            )

            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "Arquivo não encontrado"
            )
        }

        val rangeHeader =
            session.headers[
                "range"
            ]

        fileLog(
            "========================================"
        )

        fileLog(
            "STREAMING DO PKG"
        )

        fileLog(
            "Content-ID: $contentId"
        )

        fileLog(
            "URL remota: $remoteUrl"
        )

        fileLog(
            "Range solicitado pelo PS4: " +
                (
                    rangeHeader
                        ?: "Completo"
                    )
        )

        fileLog(
            "========================================"
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
                .addHeader(
                    "Accept-Encoding",
                    "identity"
                )

        if (
            !rangeHeader.isNullOrBlank()
        ) {

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

            fileLog(
                "Servidor remoto respondeu HTTP " +
                    remoteResponse.code
            )

            fileLog(
                "URL final do streaming: " +
                    remoteResponse.request.url
            )

            val remoteContentType =
                remoteResponse.header(
                    "Content-Type"
                )

            val remoteContentRange =
                remoteResponse.header(
                    "Content-Range"
                )

            fileLog(
                "Content-Type remoto: " +
                    (
                        remoteContentType
                            ?: "(não informado)"
                        )
            )

            fileLog(
                "Content-Range remoto: " +
                    (
                        remoteContentRange
                            ?: "(não informado)"
                        )
            )

            fileLog(
                "Content-Length remoto: " +
                    (
                        remoteResponse
                            .header("Content-Length")
                            ?: "(não informado)"
                        )
            )

            // ------------------------------------------------------------
            // SE O STREAMING RECEBER HTML
            // ------------------------------------------------------------

            if (
                remoteContentType
                    ?.lowercase()
                    ?.contains("text/html") == true
            ) {

                fileWarn(
                    "========================================"
                )

                fileWarn(
                    "ERRO CRÍTICO NO STREAMING"
                )

                fileWarn(
                    "O servidor remoto está devolvendo HTML " +
                        "no lugar do PKG."
                )

                fileWarn(
                    "========================================"
                )

                /*
                 * Neste ponto não consumimos o body inteiro.
                 * Apenas lemos uma pequena amostra para diagnóstico.
                 */

                if (
                    responseBody != null
                ) {

                    try {

                        val sample =
                            responseBody
                                .byteStream()
                                .use { input ->

                                    val buffer =
                                        ByteArray(1024)

                                    val count =
                                        input.read(
                                            buffer
                                        )

                                    if (
                                        count > 0
                                    ) {

                                        buffer
                                            .copyOf(count)
                                            .toString(
                                                Charsets.UTF_8
                                            )
                                            .replace(
                                                Regex(
                                                    "[^\\x20-\\x7E\\r\\n\\t]"
                                                ),
                                                ""
                                            )

                                    } else {
                                        ""
                                    }
                                }

                        fileWarn(
                            "Prévia HTML do streaming:"
                        )

                        fileWarn(
                            sample
                        )

                    } catch (e: Exception) {

                        fileWarn(
                            "Não foi possível obter prévia HTML: " +
                                e.message
                        )
                    }
                }

                remoteResponse.close()

                return newFixedLengthResponse(
                    Response.Status.BAD_GATEWAY,
                    "text/plain",
                    "Servidor remoto devolveu HTML em vez do PKG"
                )
            }

            if (
                !remoteResponse.isSuccessful ||
                responseBody == null
            ) {

                fileError(
                    "Servidor remoto recusou a requisição. " +
                        "HTTP ${remoteResponse.code}"
                )

                remoteResponse.close()

                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "text/plain",
                    "Falha ao obter pacote da nuvem"
                )
            }

            val contentType =
                remoteContentType
                    ?: "application/octet-stream"

            val contentRange =
                remoteContentRange

            val contentLength =
                responseBody.contentLength()

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

            if (
                !contentRange.isNullOrBlank()
            ) {

                nanoResponse.addHeader(
                    "Content-Range",
                    contentRange
                )

                fileLog(
                    "Retransmitindo Content-Range: " +
                        "$contentRange"
                )
            }

            addCors(
                session,
                nanoResponse
            )

        } catch (e: Exception) {

            fileError(
                "Interrupção durante streaming do PKG: " +
                    e.message,
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

        when (
            session.method
        ) {

            Method.POST -> {

                val map =
                    HashMap<String, String>()

                session.parseBody(map)

                val bodyText =
                    map["postData"] ?: ""

                val contentType =
                    session.headers[
                        "content-type"
                    ] ?: "application/json"

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
                    session.headers[
                        "content-type"
                    ] ?: "application/json"

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
    // IP WI-FI
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
    // JSON ERROR
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

    // ================================================================
    // MODELO DE METADADOS REMOTOS
    // ================================================================

    private data class RemotePkgMetadata(
        val isValidPkg: Boolean,
        val contentId: String,
        val fileSize: Long,
        val digest: String
    ) {

        companion object {

            fun invalid(
                contentId: String
            ): RemotePkgMetadata {

                return RemotePkgMetadata(
                    isValidPkg = false,
                    contentId = contentId,
                    fileSize = 0L,
                    digest = "0".repeat(64)
                )
            }
        }
    }
}


