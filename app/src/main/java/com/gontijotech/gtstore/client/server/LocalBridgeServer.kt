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
            .connectTimeout(
                15,
                TimeUnit.SECONDS
            )
            .readTimeout(
                45,
                TimeUnit.SECONDS
            )
            .build()

    private val payloader =
        Ps4Payloader(context)

    /**
     * Manifestos que serão disponibilizados
     * pelo servidor local do Android.
     *
     * Chave:
     * Content-ID
     *
     * Valor:
     * JSON do manifesto
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
            "${method.name} $uri"
        )

        // -------------------------------------------------------------
        // CORS / Preflight
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
                // INJEÇÃO
                // -----------------------------------------------------

                method == Method.POST &&
                    uri == "/api/local/inject" -> {

                    handleLocalInject(
                        session
                    )
                }

                // -----------------------------------------------------
                // MANIFESTO
                // -----------------------------------------------------

                method == Method.GET &&
                    (
                        uri.startsWith("/manifest/") ||
                            uri == "/local-manifest.json"
                        ) -> {

                    handleServeLocalManifest(
                        session
                    )
                }

                // -----------------------------------------------------
                // INTERFACE PRINCIPAL
                // -----------------------------------------------------

                uri == "/" ||
                    uri == "/index.html" -> {

                    handleProxyStatic(
                        "$adminHost/index.html",
                        "text/html; charset=utf-8",
                        session
                    )
                }

                // -----------------------------------------------------
                // RESTANTE
                // -----------------------------------------------------

                else -> {

                    handleProxyForward(
                        session
                    )
                }
            }

        } catch (e: Exception) {

            Log.e(
                tag,
                "Erro processando $uri",
                e
            )

            val error =
                JSONObject()
                    .put(
                        "success",
                        false
                    )
                    .put(
                        "error",
                        e.message
                            ?: "Erro interno"
                    )
                    .toString()

            addCors(
                session,
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json; charset=utf-8",
                    error
                )
            )
        }
    }

    // =================================================================
    // INJEÇÃO
    // =================================================================

    private fun handleLocalInject(
        session: IHTTPSession
    ): Response {

        val body =
            HashMap<String, String>()

        session.parseBody(body)

        val rawJson =
            body["postData"]
                ?: "{}"

        Log.i(
            tag,
            "Payload recebido do cliente:"
        )

        Log.i(
            tag,
            rawJson
        )

        val json =
            try {
                JSONObject(rawJson)
            } catch (e: Exception) {

                return jsonError(
                    session,
                    Response.Status.BAD_REQUEST,
                    "JSON inválido: ${e.message}"
                )
            }

        // -------------------------------------------------------------
        // IP DO PS4
        // -------------------------------------------------------------

        val ps4Ip =
            json.optString(
                "ps4Ip"
            ).trim()

        if (ps4Ip.isBlank()) {

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
            ).trim()
                .ifBlank {
                    "Jogo PS4"
                }

        // -------------------------------------------------------------
        // CATEGORIA
        // -------------------------------------------------------------

        val rawCategory =
            json.optString(
                "category",
                "gd"
            ).trim()

        val bgftCategory =
            normalizeCategory(
                rawCategory
            )

        // -------------------------------------------------------------
        // URL DIRETA DO PKG
        // -------------------------------------------------------------

        val directPkgUrl =
            firstNotBlank(
                json.optString("packageUrl"),
                json.optString("pkgUrl"),
                json.optString("url"),
                json.optString("directUrl")
            )

        if (directPkgUrl.isBlank()) {

            return jsonError(
                session,
                Response.Status.BAD_REQUEST,
                "Nenhum link direto do PKG foi informado."
            )
        }

        Log.i(
            tag,
            "URL do PKG: $directPkgUrl"
        )

        // -------------------------------------------------------------
        // CONTENT ID INICIAL
        // -------------------------------------------------------------

        var detectedContentId =
            firstNotBlank(
                json.optString("contentId"),
                json.optString("content_id"),
                json.optString("cusa"),
                json.optString("id")
            )

        // -------------------------------------------------------------
        // TAMANHO INICIAL
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
        // METADADOS DO PKG REMOTO
        // -------------------------------------------------------------

        try {

            val metadata =
                readRemotePkgMetadata(
                    directPkgUrl
                )

            if (
                metadata.fileSize > 0L
            ) {

                realFileSize =
                    metadata.fileSize

                Log.i(
                    tag,
                    "Tamanho remoto confirmado: $realFileSize"
                )
            }

            if (
                metadata.contentId.isNotBlank()
            ) {

                detectedContentId =
                    metadata.contentId

                Log.i(
                    tag,
                    "Content-ID detectado: $detectedContentId"
                )
            }

            if (
                metadata.digest.isNotBlank()
            ) {

                realDigest =
                    metadata.digest

                Log.i(
                    tag,
                    "Digest detectado: $realDigest"
                )
            }

        } catch (e: Exception) {

            Log.w(
                tag,
                "Não foi possível ler todos os metadados do PKG: ${e.message}"
            )
        }

        // -------------------------------------------------------------
        // GARANTE CONTENT ID
        // -------------------------------------------------------------

        val finalContentId =
            detectedContentId
                .trim()
                .ifBlank {

                    "CUSA" +
                        System.currentTimeMillis()
                            .toString()
                            .takeLast(5)
                }

        // -------------------------------------------------------------
        // GARANTE TAMANHO
        // -------------------------------------------------------------

        if (realFileSize <= 0L) {

            realFileSize =
                500L *
                    1024L *
                    1024L

            Log.w(
                tag,
                "Tamanho não identificado. Usando fallback: $realFileSize"
            )
        }

        // -------------------------------------------------------------
        // MANIFESTO
        // -------------------------------------------------------------

        val manifestJson =
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

                                /*
                                 * IMPORTANTE:
                                 *
                                 * O manifesto é servido pelo Android,
                                 * mas o PKG continua sendo externo.
                                 */
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

                                /*
                                 * Mantido igual ao projeto antigo.
                                 */
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
        ] = manifestJson

        lastContentId =
            finalContentId

        Log.i(
            tag,
            "Manifesto armazenado para $finalContentId"
        )

        // -------------------------------------------------------------
        // IP DO ANDROID
        // -------------------------------------------------------------

        val localIp =
            getLocalWifiAddress()

        if (localIp == null) {

            return jsonError(
                session,
                Response.Status.BAD_REQUEST,
                "Não foi possível encontrar o IPv4 Wi-Fi do Android."
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
            "INICIANDO INJEÇÃO"
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
            "Manifesto: $localManifestUrl"
        )

        Log.i(
            tag,
            "PKG: $directPkgUrl"
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
            "========================================"
        )

        // -------------------------------------------------------------
        // PAYLOADER
        // -------------------------------------------------------------

        val result =
            runBlocking {

                payloader.injectDpiPayload(

                    ps4Ip = ps4Ip,

                    localIp = localIp,

                    manifestUrl =
                        localManifestUrl,

                    itemTitle =
                        title,

                    contentId =
                        finalContentId,

                    category =
                        bgftCategory,

                    fileSize =
                        realFileSize,

                    iconBytes =
                        null
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
                    "ps4Ip",
                    ps4Ip
                )

                put(
                    "localIp",
                    localIp
                )

                put(
                    "manifestUrl",
                    localManifestUrl
                )

                put(
                    "contentId",
                    finalContentId
                )

                put(
                    "packageUrl",
                    directPkgUrl
                )

                put(
                    "category",
                    bgftCategory
                )

                put(
                    "size",
                    realFileSize
                )

                if (result.isFailure) {

                    put(
                        "error",
                        result.exceptionOrNull()
                            ?.message
                            ?: "Falha desconhecida"
                    )
                }
            }.toString()

        if (result.isSuccess) {

            Log.i(
                tag,
                "INJEÇÃO FINALIZADA COM SUCESSO"
            )

        } else {

            Log.e(
                tag,
                "INJEÇÃO FALHOU: ${
                    result.exceptionOrNull()?.message
                }"
            )
        }

        return addCors(
            session,
            newFixedLengthResponse(
                Response.Status.OK,
                "application/json; charset=utf-8",
                responseJson
            )
        )
    }

    // =================================================================
    // MANIFESTO LOCAL
    // =================================================================

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
                    .removePrefix("/manifest/")
                    .removeSuffix(".json")

            } else {
                null
            }

        val requestId =
            idFromPath
                ?: session
                    .parameters[
                        "id"
                    ]
                    ?.firstOrNull()
                ?: lastContentId

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

            Log.i(
                tag,
                "Manifesto solicitado pelo PS4: $requestId"
            )

            Log.i(
                tag,
                "Entregando manifesto: $manifest"
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
                "Manifesto não encontrado: $requestId"
            )

            jsonError(
                session,
                Response.Status.NOT_FOUND,
                "Manifesto ausente."
            )
        }
    }

    // =================================================================
    // LEITURA DE METADADOS DO PKG
    // =================================================================

    private fun readRemotePkgMetadata(
        url: String
    ): RemotePkgMetadata {

        val request =
            Request.Builder()
                .url(url)
                .addHeader(
                    "Range",
                    "bytes=0-4095"
                )
                .build()

        proxyClient
            .newCall(request)
            .execute()
            .use { response ->

                if (!response.isSuccessful) {

                    throw Exception(
                        "Servidor retornou HTTP ${response.code}"
                    )
                }

                val contentRange =
                    response.header(
                        "Content-Range"
                    )

                val fileSize =
                    contentRange
                        ?.substringAfterLast("/")
                        ?.toLongOrNull()
                        ?: response
                            .header(
                                "Content-Length"
                            )
                            ?.toLongOrNull()
                            ?: 0L

                val body =
                    response.body
                        ?: throw Exception(
                            "Resposta sem corpo."
                        )

                val bytes =
                    body.bytes()

                if (bytes.size < 0x1000) {

                    throw Exception(
                        "O servidor retornou somente ${bytes.size} bytes do cabeçalho."
                    )
                }

                // -----------------------------------------------------
                // Digest
                // -----------------------------------------------------

                val digest =
                    bytes
                        .copyOfRange(
                            0xFE0,
                            0x1000
                        )
                        .joinToString("") {
                            "%02X".format(it)
                        }

                // -----------------------------------------------------
                // Content-ID
                // -----------------------------------------------------

                val contentId =
                    extractContentIdFromPkgHeader(
                        bytes
                    )

                return RemotePkgMetadata(
                    fileSize = fileSize,
                    contentId = contentId,
                    digest = digest
                )
            }
    }

    /**
     * Tenta localizar um Content-ID ASCII no header do PKG.
     *
     * Primeiro tenta o offset tradicional utilizado pelo
     * fluxo atual. Se não encontrar, procura uma sequência
     * com aparência de Content-ID.
     */
    private fun extractContentIdFromPkgHeader(
        bytes: ByteArray
    ): String {

        // Tentativa direta no offset 0x40
        if (
            bytes.size >=
            0x40 + 36
        ) {

            val candidate =
                String(
                    bytes,
                    0x40,
                    36,
                    Charsets.US_ASCII
                )
                    .trim(
                        '\u0000',
                        ' '
                    )

            if (
                looksLikeContentId(
                    candidate
                )
            ) {

                return candidate
            }
        }

        // Busca genérica por padrões do tipo:
        //
        // UP0000-CUSA00000_00-XXXXXXXXXXXXXXX
        //
        // ou
        //
        // EP0000-CUSA00000_00-XXXXXXXXXXXXXXX
        //

        val ascii =
            buildString {

                for (b in bytes) {

                    val c =
                        b.toInt()
                            .and(0xFF)
                            .toChar()

                    append(
                        if (
                            c.code in 32..126
                        ) {
                            c
                        } else {
                            ' '
                        }
                    )
                }
            }

        val regex =
            Regex(
                "[A-Z]{2}\\d{4}-[A-Z0-9]{9,16}_[0-9]{2}-[A-Z0-9_]{4,20}",
                RegexOption.IGNORE_CASE
            )

        return regex
            .find(ascii)
            ?.value
            ?.trim()
            ?: ""
    }

    private fun looksLikeContentId(
        value: String
    ): Boolean {

        if (
            value.length != 36
        ) {
            return false
        }

        return value.contains("-") &&
            value.contains("_") &&
            value.count {
                it == '-'
            } >= 2
    }

    // =================================================================
    // CATEGORIA
    // =================================================================

    private fun normalizeCategory(
        category: String
    ): String {

        val clean =
            category
                .trim()
                .removePrefix("PS4")
                .removePrefix("ps4")
                .uppercase()

        return when (clean) {

            "GP",
            "PATCH",
            "UPDATE" -> "PS4GP"

            "AC",
            "DLC" -> "PS4AC"

            else -> "PS4GD"
        }
    }

    // =================================================================
    // PROXY HTML
    // =================================================================

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
                    response.body
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

    // =================================================================
    // PROXY GERAL
    // =================================================================

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
                    map["postData"]
                        ?: ""

                val contentType =
                    session
                        .headers[
                            "content-type"
                        ]
                        ?: "application/json"

                requestBuilder.post(
                    bodyText.toRequestBody(
                        contentType
                            .toMediaTypeOrNull()
                    )
                )
            }

            Method.PUT -> {

                val map =
                    HashMap<String, String>()

                session.parseBody(map)

                val bodyText =
                    map["postData"]
                        ?: ""

                val contentType =
                    session
                        .headers[
                            "content-type"
                        ]
                        ?: "application/json"

                requestBuilder.put(
                    bodyText.toRequestBody(
                        contentType
                            .toMediaTypeOrNull()
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
                    response.body
                        ?.bytes()
                        ?: ByteArray(0)

                val contentType =
                    response.header(
                        "Content-Type"
                    )
                        ?: "application/octet-stream"

                val status =
                    Response.Status
                        .lookup(
                            response.code
                        )
                        ?: object :
                        Response.IStatus {

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
                        ByteArrayInputStream(
                            bytes
                        ),
                        bytes.size.toLong()
                    )
                )
            }
    }

    // =================================================================
    // IP WI-FI DO ANDROID
    // =================================================================

    private fun getLocalWifiAddress(): String? {

        return try {

            val interfaces =
                NetworkInterface
                    .getNetworkInterfaces()
                    ?.toList()
                    ?: return null

            val validInterfaces =
                interfaces.filter { intf ->

                    val name =
                        intf.name
                            .lowercase()

                    intf.isUp &&
                        !intf.isLoopback &&
                        !name.contains("tun") &&
                        !name.contains("tap") &&
                        !name.contains("rmnet") &&
                        !name.contains("pdp") &&
                        !name.contains("dummy")
                }

            val sorted =
                validInterfaces
                    .sortedByDescending {

                        it.name.startsWith(
                            "wlan"
                        ) ||
                            it.name.startsWith(
                                "ap"
                            )
                    }

            for (intf in sorted) {

                for (
                    address in
                    intf.inetAddresses
                ) {

                    if (
                        !address.isLoopbackAddress &&
                        address is Inet4Address &&
                        address.isSiteLocalAddress
                    ) {

                        val ip =
                            address.hostAddress

                        Log.i(
                            tag,
                            "IP Wi-Fi selecionado: $ip (${intf.name})"
                        )

                        return ip
                    }
                }
            }

            null

        } catch (e: Exception) {

            Log.e(
                tag,
                "Erro obtendo IP Wi-Fi",
                e
            )

            null
        }
    }

    // =================================================================
    // CORS
    // =================================================================

    private fun addCors(
        session: IHTTPSession,
        response: Response
    ): Response {

        val requestedHeaders =
            session
                .headers[
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

    // =================================================================
    // HELPERS
    // =================================================================

    private fun firstNotBlank(
        vararg values: String
    ): String {

        for (value in values) {

            if (
                value.isNotBlank()
            ) {
                return value.trim()
            }
        }

        return ""
    }

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
                "application/json; charset=utf-8",
                json
            )
        )
    }

    // =================================================================
    // DATA CLASS
    // =================================================================

    private data class RemotePkgMetadata(
        val fileSize: Long,
        val contentId: String,
        val digest: String
    )
}
