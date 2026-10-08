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
     * Mecanismo antigo de payload/callback/DPI.
     *
     * Não alterar nesta etapa.
     */
    private val payloader =
        Ps4Payloader(context)

    /*
     * Cache dos manifestos locais.
     *
     * Chave:
     * catalogIndex
     *
     * Exemplo:
     * "1" -> manifesto do catalogIndex 1
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
                 * INJEÇÃO
                 * =====================================================
                 */
                method == Method.POST &&
                    uri == "/api/local/inject" -> {

                    handleLocalInject(session)
                }

                /*
                 * =====================================================
                 * MANIFESTO
                 *
                 * Compatibilidade com o mecanismo antigo:
                 *
                 * /json/1.json
                 * /json/2.json
                 * /json/3.json
                 * =====================================================
                 */
                method == Method.GET &&
                    uri.startsWith("/json/") -> {

                    handleServeLocalManifest(session)
                }

                /*
                 * Compatibilidade adicional.
                 *
                 * Não participa do fluxo principal.
                 */
                method == Method.GET &&
                    (
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
                 * DEMAIS REQUISIÇÕES DA INTERFACE
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
     *
     * IMPORTANTE:
     *
     * O Android NÃO inspeciona mais o PKG.
     *
     * Os metadados abaixo já devem vir do Firebase/catalogo:
     *
     * ps4Ip
     * packageUrl
     * manifestUrl
     * title
     * contentId
     * category
     * size
     * digest
     * catalogIndex
     *
     * O Android somente:
     *
     * 1. recebe os dados
     * 2. cria o manifesto
     * 3. disponibiliza o manifesto local
     * 4. injeta o payload
     * 5. recebe callback
     * 6. envia DPI
     *
     * O PS4 baixa o PKG diretamente da URL externa.
     */
    private fun handleLocalInject(
        session: IHTTPSession
    ): Response {

        val map = HashMap<String, String>()

        session.parseBody(map)

        val postData = map["postData"] ?: "{}"

        fileLog("========================================")
        fileLog("INÍCIO DA INJEÇÃO")
        fileLog("JSON recebido da interface:")
        fileLog(postData)

        val json = JSONObject(postData)

        /*
         * ============================================================
         * IP DO PS4
         * ============================================================
         */
        val ps4Ip = json
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

        /*
         * ============================================================
         * DADOS DO PACOTE
         * ============================================================
         */
        val title = json
            .optString("title", "Jogo PS4")
            .trim()

        val rawCategory = json
            .optString("category", "gd")
            .trim()

        val bgftCategory =
            if (rawCategory.startsWith("PS4", ignoreCase = true)) {
                rawCategory.uppercase()
            } else {
                "PS4${rawCategory.uppercase()}"
            }

        /*
         * URL DIRETA DO PKG
         *
         * Esta URL vem do Firebase.
         *
         * Exemplo:
         *
         * https://stor2.mocha.my/.../arquivo.pkg
         *
         * Esta URL será colocada DIRETAMENTE no manifesto.
         */
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

        /*
         * ============================================================
         * CONTENT-ID
         *
         * Não tentar descobrir no PKG.
         * Não criar Content-ID fictício.
         * O Admin/Firebase já deve fornecer o valor correto.
         * ============================================================
         */
        val contentId = (
            json.optString("contentId")
                .ifBlank {
                    json.optString("content_id")
                }
        ).trim()

        if (contentId.isEmpty()) {

            fileWarn(
                "Content-ID não informado pelo catálogo/Firebase."
            )

            return jsonError(
                session,
                Response.Status.BAD_REQUEST,
                "Content-ID não informado."
            )
        }

        /*
         * ============================================================
         * CATALOG INDEX
         *
         * Este é o identificador utilizado pelo manifesto antigo:
         *
         * /json/{catalogIndex}.json
         * ============================================================
         */
        var catalogIndex =
            json.optString("catalogIndex").trim()

        if (catalogIndex.isEmpty()) {
            catalogIndex =
                json.optString("index").trim()
        }

        if (catalogIndex.isEmpty()) {

            fileWarn(
                "catalogIndex não informado."
            )

            return jsonError(
                session,
                Response.Status.BAD_REQUEST,
                "catalogIndex não informado."
            )
        }

        /*
         * Se vier algo como 000001, normalizamos para 1.
         *
         * Isso mantém compatibilidade com o formato antigo:
         *
         * /json/1.json
         */
        val normalizedCatalogIndex =
            catalogIndex.toLongOrNull()?.toString()
                ?: catalogIndex

        /*
         * ============================================================
         * TAMANHO
         * ============================================================
         */
        val fileSize =
            json.optLong("size", 0L)

        if (fileSize <= 0L) {

            fileWarn(
                "Tamanho inválido recebido: $fileSize"
            )

            return jsonError(
                session,
                Response.Status.BAD_REQUEST,
                "Tamanho do PKG inválido ou não informado."
            )
        }

        /*
         * ============================================================
         * DIGEST
         *
         * O Admin já reconheceu o PKG.
         *
         * Se houver digest no Firebase, usamos.
         * Caso não exista, mantemos a representação de zeros usada
         * anteriormente pelo manifesto.
         *
         * Não calculamos digest do PKG no Client.
         * ============================================================
         */
        val firebaseDigest =
            json.optString("digest").trim()

        val packageDigest =
            if (firebaseDigest.isNotEmpty()) {
                firebaseDigest
            } else {
                "0".repeat(64)
            }

        /*
         * ============================================================
         * LOG DOS DADOS
         * ============================================================
         */
        fileLog("PS4: $ps4Ip")
        fileLog("Título: $title")
        fileLog("Categoria recebida: $rawCategory")
        fileLog("Categoria BGFT: $bgftCategory")
        fileLog("Catalog Index: $normalizedCatalogIndex")
        fileLog("Content-ID: $contentId")
        fileLog("Tamanho: $fileSize bytes")
        fileLog("Digest: $packageDigest")
        fileLog("PKG DIRETO: $directPkgUrl")

        /*
         * ============================================================
         * IP LOCAL DO ANDROID
         * ============================================================
         */
        val localIp = getLocalWifiAddress()

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

        /*
         * ============================================================
         * MANIFESTO
         * ============================================================
         *
         * ATENÇÃO:
         *
         * A URL abaixo é a URL EXTERNA do PKG.
         *
         * Não existe:
         *
         * /download-pkg/
         *
         * Portanto o Android não participa do download.
         */
        val manifestJsonString =
            JSONObject().apply {

                put(
                    "originalFileSize",
                    fileSize
                )

                put(
                    "packageDigest",
                    packageDigest
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
                                 * URL DIRETA DO PKG
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
                                    fileSize
                                )

                                /*
                                 * Mantido exatamente no formato
                                 * utilizado pelo fluxo anterior.
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

        fileLog("Manifesto criado:")
        fileLog(manifestJsonString)

        /*
         * ============================================================
         * CACHE DO MANIFESTO
         * ============================================================
         */
        manifestCache[
            normalizedCatalogIndex
        ] = manifestJsonString

        lastCatalogIndex =
            normalizedCatalogIndex

        fileLog(
            "Manifesto armazenado para catalogIndex: " +
                normalizedCatalogIndex
        )

        /*
         * ============================================================
         * URL LOCAL DO MANIFESTO
         * ============================================================
         *
         * O PS4 acessará:
         *
         * http://IP_ANDROID:8080/json/1.json
         *
         * O Android entrega apenas o JSON.
         *
         * O PKG continua externo.
         */
        val localManifestUrl =
            "http://$localIp:$port/json/$normalizedCatalogIndex.json"

        fileLog(
            "URL do manifesto local: $localManifestUrl"
        )

        /*
         * ============================================================
         * PAYLOADER
         * ============================================================
         */
        fileLog("========================================")
        fileLog("DISPARANDO PAYLOADER")
        fileLog("PS4: $ps4Ip")
        fileLog("Android: $localIp")
        fileLog("Título: $title")
        fileLog("Catalog Index: $normalizedCatalogIndex")
        fileLog("Content-ID: $contentId")
        fileLog("Categoria: $bgftCategory")
        fileLog("Tamanho: $fileSize")
        fileLog("Manifesto: $localManifestUrl")
        fileLog("PKG será baixado DIRETAMENTE pelo PS4.")
        fileLog("========================================")

        /*
         * Mantemos o mecanismo de injeção existente.
         */
        val result = runBlocking {

            payloader.injectDpiPayload(
                ps4Ip = ps4Ip,
                localIp = localIp,
                manifestUrl = localManifestUrl,
                itemTitle = title,
                contentId = contentId,
                category = bgftCategory,
                fileSize = fileSize,
                iconBytes = null
            )
        }

        /*
         * ============================================================
         * RESULTADO DO PAYLOADER
         * ============================================================
         */
        if (result.isSuccess) {

            fileLog(
                "PAYLOADER FINALIZADO COM SUCESSO."
            )

        } else {

            val error =
                result.exceptionOrNull()

            fileError(
                "PAYLOADER FALHOU: " +
                    (error?.message ?: "erro desconhecido"),
                error
            )
        }

        /*
         * ============================================================
         * RESPOSTA PARA A INTERFACE
         * ============================================================
         */
        val responseJson =
            JSONObject().apply {

                put(
                    "success",
                    result.isSuccess
                )

                put(
                    "catalogIndex",
                    normalizedCatalogIndex
                )

                put(
                    "contentId",
                    contentId
                )

                put(
                    "manifestUrl",
                    localManifestUrl
                )

                /*
                 * Também devolvemos a URL direta para facilitar
                 * diagnóstico no log/interface.
                 */
                put(
                    "packageUrl",
                    directPkgUrl
                )

                if (result.isFailure) {

                    put(
                        "error",
                        result.exceptionOrNull()?.message
                            ?: "Falha desconhecida na injeção."
                    )
                }
            }.toString()

        fileLog(
            "Resposta enviada à interface: $responseJson"
        )

        fileLog("FIM DA INJEÇÃO")
        fileLog("========================================")

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
     * SERVIR MANIFESTO
     * ================================================================
     *
     * Rota principal:
     *
     * /json/1.json
     *
     * Também aceitamos:
     *
     * /manifest/1.json
     * /local-manifest.json
     *
     * para compatibilidade.
     */
    private fun handleServeLocalManifest(
        session: IHTTPSession
    ): Response {

        val uri = session.uri

        /*
         * ------------------------------------------------------------
         * /json/1.json
         * ------------------------------------------------------------
         */
        val jsonIndex =
            if (uri.startsWith("/json/")) {

                uri
                    .removePrefix("/json/")
                    .removeSuffix(".json")

            } else {
                null
            }

        /*
         * ------------------------------------------------------------
         * /manifest/1.json
         * ------------------------------------------------------------
         */
        val legacyIndex =
            if (uri.startsWith("/manifest/")) {

                uri
                    .removePrefix("/manifest/")
                    .removeSuffix(".json")

            } else {
                null
            }

        /*
         * ------------------------------------------------------------
         * Determina o índice solicitado
         * ------------------------------------------------------------
         */
        val requestIndex =
            jsonIndex
                ?: legacyIndex
                ?: session.parameters["id"]
                    ?.firstOrNull()
                ?: lastCatalogIndex

        fileLog(
            "PS4 solicitou manifesto: $requestIndex"
        )

        /*
         * ------------------------------------------------------------
         * Procura manifesto
         * ------------------------------------------------------------
         */
        val manifest =
            if (!requestIndex.isNullOrBlank()) {

                val normalized =
                    requestIndex
                        .toLongOrNull()
                        ?.toString()
                        ?: requestIndex

                manifestCache[normalized]

            } else {

                null
            }

        /*
         * ------------------------------------------------------------
         * Entrega
         * ------------------------------------------------------------
         */
        return if (manifest != null) {

            fileLog(
                "Manifesto entregue com sucesso: $requestIndex"
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
                "Manifesto inexistente para: $requestIndex"
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

    /*
     * ================================================================
     * INTERFACE WEB
     * ================================================================
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
                    response.body?.string()
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

    /*
     * ================================================================
     * PROXY DA INTERFACE WEB
     * ================================================================
     *
     * IMPORTANTE:
     *
     * Este proxy continua existindo para a interface web.
     *
     * Ele NÃO é usado para transportar PKG.
     */
    private fun handleProxyForward(
        session: IHTTPSession
    ): Response {

        val queryString =
            if (!session.queryParameterString.isNullOrBlank()) {

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
            .newCall(requestBuilder.build())
            .execute()
            .use { response ->

                val bytes =
                    response.body?.bytes()
                        ?: ByteArray(0)

                val contentType =
                    response.header("Content-Type")
                        ?: "application/octet-stream"

                val status =
                    Response.Status.lookup(response.code)
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

    /*
     * ================================================================
     * DETECÇÃO DO IP WI-FI
     * ================================================================
     */
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
                            networkInterface.name.lowercase()

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

            for (networkInterface in validInterfaces) {

                for (address in networkInterface.inetAddresses) {

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

    /*
     * ================================================================
     * CORS
     * ================================================================
     */
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

    /*
     * ================================================================
     * ERRO JSON
     * ================================================================
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



