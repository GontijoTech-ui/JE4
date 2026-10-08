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

    // Inicializa o Payloader
    private val payloader = Ps4Payloader(context)

    // Cache em memória chaveado por contentId para evitar race conditions
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

                // Rota local para servir o manifesto BGFT ao PS4
                method == Method.GET && uri == "/local-manifest.json" -> handleServeLocalManifest(session)

                // Proxy da interface web (HTML)
                uri == "/" || uri == "/index.html" -> handleProxyStatic("$adminHost/index.html", "text/html", session)

                // Encaminhamento transparente de assets e APIs remotas
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
        val category = json.optString("category", "gd")
        val size = json.optLong("size", 0L)

        // Resgata link direto enviado pelo frontend
        val directPkgUrl = json.optString("packageUrl",
            json.optString("pkgUrl",
                json.optString("url",
                    json.optString("directUrl", "")
                )
            )
        ).trim()

        val rawManifestUrl = json.optString("manifestUrl", "").trim()

        // 1. Gera ou busca o manifesto JSON
        val manifestJsonString: String = if (directPkgUrl.isNotEmpty() && (directPkgUrl.startsWith("http://") || directPkgUrl.startsWith("https://"))) {
            JSONObject().apply {
                put("originalFileSize", size)
                put("packageDigest", "")
                put("numberOfSplitFiles", 1)
                put("pieces", JSONArray().apply {
                    put(JSONObject().apply {
                        put("url", directPkgUrl)
                        put("fileOffset", 0L)
                        put("fileSize", size)
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
                    resp.body?.string() ?: ""
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

        // Salva no cache concorrente
        manifestCache[contentId] = manifestJsonString
        lastContentId = contentId

        // 2. Determina o IP Wi-Fi local do celular
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

        val localManifestUrl = "http://$localIp:$port/local-manifest.json?id=$contentId"
        Log.i(tag, "Manifesto local disponível em: $localManifestUrl")

        // 3. Disparo via Payloader
        val result = runBlocking {
            payloader.injectDpiPayload(
                ps4Ip = ps4Ip,
                localIp = localIp,
                manifestUrl = localManifestUrl,
                itemTitle = title,
                contentId = contentId,
                category = category,
                fileSize = size,
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
        // NanoHTTPD mapeia parâmetros de consulta em session.parameters como List<String>
        val reqId = session.parameters["id"]?.firstOrNull() ?: lastContentId
        val manifest = if (!reqId.isNullOrBlank()) manifestCache[reqId] else null

        return if (manifest != null) {
            Log.i(tag, "Manifesto entregue ao PS4 para o ID: $reqId")
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

        if (session.method == Method.POST) {
            val map = HashMap<String, String>()
            session.parseBody(map)
            val bodyText = map["postData"] ?: ""
            val contentType = session.headers["content-type"] ?: "application/json"
            reqBuilder.post(bodyText.toRequestBody(contentType.toMediaTypeOrNull()))
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
            // Prioriza interfaces 'wlan' (Wi-Fi)
            val sorted = interfaces.sortedByDescending { it.name.startsWith("wlan") }

            for (intf in sorted) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
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
