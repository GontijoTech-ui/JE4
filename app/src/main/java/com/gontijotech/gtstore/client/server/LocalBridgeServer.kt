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

    private val proxyClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val payloader = Ps4Payloader(context)

    // Cache concorrente chaveado por ID do pacote/jogo
    private val manifestCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var lastContentId: String? = null

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        if (method == Method.OPTIONS) {
            return addCors(session, newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""))
        }

        return try {
            when {
                // Rota local para injeção via interface web
                method == Method.POST && uri == "/api/local/inject" -> handleLocalInject(session)

                // Rota limpa para servir o manifesto BGFT ao PS4 (ex: /manifest/CUSA00184.json ou /local-manifest.json)
                method == Method.GET && (uri.startsWith("/manifest/") || uri == "/local-manifest.json") -> 
                    handleServeLocalManifest(session)

                // Proxy da interface web (HTML)
                uri == "/" || uri == "/index.html" -> handleProxyStatic("$adminHost/index.html", "text/html", session)

                // Encaminhamento de assets e APIs remotas
                else -> handleProxyForward(session)
            }
        } catch (e: Exception) {
            Log.e(tag, "Erro no processamento da rota $uri: ${e.message}", e)
            val err = JSONObject().put("error", e.message ?: "Erro interno no LocalBridgeServer").toString()
            addCors(session, newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", err))
        }
    }

    private fun handleLocalInject(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val json = JSONObject(map["postData"] ?: "{}")

        val ps4Ip = json.optString("ps4Ip").trim()
        if (ps4Ip.isEmpty()) {
            return addCors(
                session,
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    """{"success":false,"error":"O campo 'ps4Ip' é obrigatório."}"""
                )
            )
        }

        val title = json.optString("title", "Jogo PS4")
        val contentId = json.optString("contentId", "CUSA00000").trim()
        val rawCategory = json.optString("category", "gd").trim()

        // Padrão do projeto antigo: PS4 + CATEGORIA em maiúsculo (ex: PS4GD)
        val bgftCategory = if (rawCategory.startsWith("PS4", ignoreCase = true)) {
            rawCategory.uppercase()
        } else {
            "PS4" + rawCategory.uppercase()
        }

        // Resgata o link direto com fallbacks
        val directPkgUrl = (json.optString("packageUrl").ifBlank {
            json.optString("pkgUrl").ifBlank {
                json.optString("url").ifBlank {
                    json.optString("directUrl")
                }
            }
        }).trim()

        val rawManifestUrl = json.optString("manifestUrl", "").trim()

        val manifestJsonString: String
        var realFileSize = json.optLong("size", 0L)

        if (directPkgUrl.isNotEmpty() && (directPkgUrl.startsWith("http://") || directPkgUrl.startsWith("https://"))) {
            var realDigest = "0".repeat(64)

            // 1. Extrai o Digest real e o tamanho exato através de um pedido rápido aos primeiros 4KB do PKG
            try {
                val rangeReq = Request.Builder()
                    .url(directPkgUrl)
                    .addHeader("Range", "bytes=0-4095")
                    .build()

                proxyClient.newCall(rangeReq).execute().use { resp ->
                    // Resgata o tamanho real total pelo Content-Range (ex: bytes 0-4095/1532493824)
                    val cr = resp.header("Content-Range")
                    val totalLength = cr?.substringAfterLast('/')?.toLongOrNull()
                        ?: resp.header("Content-Length")?.toLongOrNull()
                        ?: 0L

                    if (totalLength > 0L) {
                        realFileSize = totalLength
                        Log.i(tag, "Tamanho exato do PKG remoto: $realFileSize bytes")
                    }

                    val headerBytes = resp.body?.bytes()
                    if (headerBytes != null && headerBytes.size >= 0x1000) {
                        // Extrai exatamente os 32 bytes do offset 0xFE0 a 0x1000 (SHA-256 do pacote)
                        realDigest = headerBytes.copyOfRange(0xFE0, 0x1000).joinToString("") { "%02X".format(it) }
                        Log.i(tag, "Digest real do PKG extraído com sucesso: $realDigest")
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Não foi possível extrair os primeiros 4KB do PKG: ${e.message}")
            }

            if (realFileSize <= 0L) {
                realFileSize = 1024L * 1024L * 500L // Fallback de segurança
            }

            // 2. Monta o manifesto no padrão aceito pelo DirectPackageInstaller
            manifestJsonString = JSONObject().apply {
                put("originalFileSize", realFileSize)
                put("packageDigest", realDigest)
                put("numberOfSplitFiles", 1)
                put("pieces", JSONArray().apply {
                    put(JSONObject().apply {
                        put("url", directPkgUrl)
                        put("fileOffset", 0L)
                        put("fileSize", realFileSize)
                        put("hashValue", "0000000000000000000000000000000000000000") // 40 zeros exigidos pelo BGFT
                    })
                })
            }.toString()

        } else if (rawManifestUrl.isNotEmpty()) {
            val remoteManifestUrl = when {
                rawManifestUrl.startsWith("http://") || rawManifestUrl.startsWith("https://") -> rawManifestUrl
                rawManifestUrl.startsWith("/") -> "$adminHost$rawManifestUrl"
                else -> "$adminHost/json/$rawManifestUrl.json"
            }

            Log.i(tag, "Buscando manifesto remoto em: $remoteManifestUrl")

            val req = Request.Builder().url(remoteManifestUrl).build()
            try {
                proxyClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return addCors(
                            session,
                            newFixedLengthResponse(
                                Response.Status.BAD_REQUEST,
                                "application/json",
                                """{"success":false,"error":"Servidor remoto retornou HTTP ${resp.code} ao buscar manifesto."}"""
                            )
                        )
                    }
                    manifestJsonString = resp.body?.string() ?: ""
                }
            } catch (e: Exception) {
                return addCors(
                    session,
                    newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR,
                        "application/json",
                        """{"success":false,"error":"Falha de rede ao buscar manifesto: ${e.message}"}"""
                    )
                )
            }
        } else {
            return addCors(
                session,
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    """{"success":false,"error":"Nenhum link direto (packageUrl) ou manifesto configurado."}"""
                )
            )
        }

        // Salva no cache
        val safeKey = contentId.ifBlank { "default" }
        manifestCache[safeKey] = manifestJsonString
        lastContentId = safeKey

        // 3. Obtém o IP físico do celular
        val localIp = getLocalWifiAddress()
        if (localIp == null) {
            return addCors(
                session,
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    """{"success":false,"error":"O aparelho não está conectado a uma rede Wi-Fi local válida."}"""
                )
            )
        }

        val localManifestUrl = "http://$localIp:$port/manifest/$safeKey.json"
        Log.i(tag, "Manifesto local fornecido ao PS4: $localManifestUrl")

        // 4. Disparo via Payloader
        val result = runBlocking {
            payloader.injectDpiPayload(
                ps4Ip = ps4Ip,
                localIp = localIp,
                manifestUrl = localManifestUrl,
                itemTitle = title,
                contentId = contentId,
                category = bgftCategory,
                fileSize = realFileSize,
                iconBytes = null
            )
        }

        val resJson = JSONObject().apply {
            put("success", result.isSuccess)
            if (result.isFailure) {
                put("error", result.exceptionOrNull()?.message)
            }
        }.toString()

        return addCors(session, newFixedLengthResponse(Response.Status.OK, "application/json", resJson))
    }

    private fun handleServeLocalManifest(session: IHTTPSession): Response {
        val uri = session.uri
        val idFromPath = if (uri.startsWith("/manifest/")) {
            uri.removePrefix("/manifest/").removeSuffix(".json")
        } else {
            null
        }

        val reqId = idFromPath ?: session.parameters["id"]?.firstOrNull() ?: lastContentId
        val manifest = if (!reqId.isNullOrBlank()) manifestCache[reqId] else manifestCache.values.lastOrNull()

        return if (manifest != null) {
            Log.i(tag, "Manifesto entregue com sucesso ao BGFT do PS4 para: $reqId")
            addCors(session, newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", manifest))
        } else {
            Log.w(tag, "PS4 solicitou manifesto inexistente ou expirado no cache.")
            addCors(session, newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"Manifesto ausente"}"""))
        }
    }

    private fun handleProxyStatic(targetUrl: String, mime: String, session: IHTTPSession): Response {
        val req = Request.Builder().url(targetUrl).build()
        return proxyClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: "Falha ao carregar interface remota."
            addCors(session, newFixedLengthResponse(Response.Status.OK, mime, body))
        }
    }

    private fun handleProxyForward(session: IHTTPSession): Response {
        val queryString = if (!session.queryParameterString.isNullOrBlank()) "?${session.queryParameterString}" else ""
        val targetUrl = "$adminHost${session.uri}$queryString"
        val reqBuilder = Request.Builder().url(targetUrl)

        when (session.method) {
            Method.POST -> {
                val map = HashMap<String, String>()
                session.parseBody(map)
                val bodyText = map["postData"] ?: ""
                val contentType = session.headers["content-type"] ?: "application/json"
                reqBuilder.post(bodyText.toRequestBody(contentType.toMediaTypeOrNull()))
            }
            Method.PUT -> {
                val map = HashMap<String, String>()
                session.parseBody(map)
                val bodyText = map["postData"] ?: ""
                val contentType = session.headers["content-type"] ?: "application/json"
                reqBuilder.put(bodyText.toRequestBody(contentType.toMediaTypeOrNull()))
            }
            Method.DELETE -> reqBuilder.delete()
            Method.HEAD -> reqBuilder.head()
            else -> reqBuilder.get()
        }

        return proxyClient.newCall(reqBuilder.build()).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            val contentType = resp.header("Content-Type") ?: "application/octet-stream"

            val status = Response.Status.lookup(resp.code) ?: object : Response.IStatus {
                override fun getRequestStatus(): Int = resp.code
                override fun getDescription(): String = resp.message
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

    private fun getLocalWifiAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null

            val validInterfaces = interfaces.filter { intf ->
                val name = intf.name.lowercase()
                intf.isUp && !intf.isLoopback &&
                        !name.contains("tun") &&
                        !name.contains("tap") &&
                        !name.contains("rmnet") &&
                        !name.contains("pdp") &&
                        !name.contains("dummy")
            }

            val sorted = validInterfaces.sortedByDescending {
                it.name.startsWith("wlan") || it.name.startsWith("ap")
            }

            for (intf in sorted) {
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address && addr.isSiteLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun addCors(session: IHTTPSession, response: Response): Response {
        val requestHeaders = session.headers["access-control-request-headers"] ?: "Content-Type, Authorization, Range, X-Requested-With"
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, DELETE")
        response.addHeader("Access-Control-Allow-Headers", requestHeaders)
        response.addHeader("Access-Control-Max-Age", "86400")
        return response
    }
}
