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
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.HashMap
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

    // Inicializa o Payloader com o contexto para carregar assets/payload.bin
    private val payloader = Ps4Payloader(context)

    // Cache em memória do último manifesto sincronizado
    @Volatile
    private var cachedManifestJson: String? = null

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        if (method == Method.OPTIONS) {
            return addCors(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""))
        }

        return try {
            when {
                // Rota local exclusiva acionada pela interface web para injeção
                method == Method.POST && uri == "/api/local/inject" -> handleLocalInject(session)

                // Rota local para entregar o manifesto JSON diretamente ao PS4 via Wi-Fi
                method == Method.GET && uri == "/local-manifest.json" -> handleServeLocalManifest()

                // Proxy do index.html originário do servidor Administrador na nuvem
                uri == "/" || uri == "/index.html" -> handleProxyStatic("$adminHost/index.html", "text/html")

                // Encaminhamento transparente de todas as outras rotas e recursos
                else -> handleProxyForward(session)
            }
        } catch (e: Exception) {
            Log.e(tag, "Erro no processamento da rota $uri: ${e.message}")
            val err = JSONObject().put("error", e.message ?: "Erro interno no LocalBridgeServer").toString()
            addCors(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", err))
        }
    }

    private fun handleLocalInject(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val json = JSONObject(map["postData"] ?: "{}")

        val ps4Ip = json.getString("ps4Ip")
        val rawManifestUrl = if (json.has("manifestUrl")) json.getString("manifestUrl") else json.optString("pkgUrl", "")
        val title = json.optString("title", "Jogo PS4")
        val contentId = json.optString("contentId", "CUSA00000")
        val category = json.optString("category", "gd")
        val size = json.optLong("size", 0L)

        // 1. Resolve e busca a cópia atualizada do manifesto no Admin
        val remoteManifestUrl = when {
            rawManifestUrl.startsWith("http://") || rawManifestUrl.startsWith("https://") -> rawManifestUrl
            rawManifestUrl.startsWith("/") -> "$adminHost$rawManifestUrl"
            else -> "$adminHost/json/$rawManifestUrl.json"
        }

        Log.i(tag, "Buscando cópia atualizada do manifesto em: $remoteManifestUrl")

        try {
            val req = Request.Builder().url(remoteManifestUrl).build()
            val resp = proxyClient.newCall(req).execute()

            if (resp.isSuccessful) {
                cachedManifestJson = resp.body?.string()
                Log.i(tag, "Manifesto obtido e armazenado em cache local com sucesso.")
            } else {
                Log.w(tag, "Servidor retornou HTTP ${resp.code} ao buscar manifesto.")
                return addCors(
                    newFixedLengthResponse(
                        Response.Status.BAD_REQUEST,
                        "application/json",
                        JSONObject().apply {
                            put("success", false)
                            put("error", "Não foi possível obter os dados do jogo no servidor (HTTP ${resp.code})")
                        }.toString()
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "Falha ao conectar com o servidor Admin para obter o JSON: ${e.message}")
            return addCors(
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    JSONObject().apply {
                        put("success", false)
                        put("error", "Falha de rede ao sincronizar com o Admin: ${e.message}")
                    }.toString()
                )
            )
        }

        // 2. Determina o IP Wi-Fi local do telemóvel para a consola aceder
        val localIp = getLocalWifiAddress() ?: "127.0.0.1"
        val localManifestUrl = "http://$localIp:$port/local-manifest.json"
        Log.i(tag, "URL local fornecida ao PS4: $localManifestUrl")

        // 3. Disparo integral via payload.bin com a URL local HTTP (sem SSL, na rede local)
        val result = runBlocking {
            payloader.injectDpiPayload(
                ps4Ip = ps4Ip,
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

        return addCors(newFixedLengthResponse(Response.Status.OK, "application/json", resJson))
    }

    private fun handleServeLocalManifest(): Response {
        val manifest = cachedManifestJson
        return if (manifest != null) {
            Log.i(tag, "PS4 solicitou e recebeu o manifesto local via Wi-Fi.")
            addCors(newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", manifest))
        } else {
            Log.w(tag, "PS4 solicitou manifesto, mas nenhum estava em cache.")
            addCors(newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error": "Manifesto ausente"}"""))
        }
    }

    private fun handleProxyStatic(targetUrl: String, mime: String): Response {
        val req = Request.Builder().url(targetUrl).build()
        val resp = proxyClient.newCall(req).execute()
        val body = resp.body?.string() ?: "Falha ao carregar a interface remota."
        return addCors(newFixedLengthResponse(Response.Status.OK, mime, body))
    }

    private fun handleProxyForward(session: IHTTPSession): Response {
        val queryString = if (session.queryParameterString != null) "?${session.queryParameterString}" else ""
        val targetUrl = "$adminHost${session.uri}$queryString"
        val reqBuilder = Request.Builder().url(targetUrl)

        if (session.method == Method.POST) {
            val map = HashMap<String, String>()
            session.parseBody(map)
            val bodyText = map["postData"] ?: ""
            reqBuilder.post(bodyText.toRequestBody("application/json".toMediaTypeOrNull()))
        }

        val resp = proxyClient.newCall(reqBuilder.build()).execute()
        val bytes = resp.body?.bytes() ?: ByteArray(0)
        val contentType = resp.header("Content-Type") ?: "application/octet-stream"

        return addCors(
            newFixedLengthResponse(
                Response.Status.lookup(resp.code),
                contentType,
                ByteArrayInputStream(bytes),
                bytes.size.toLong()
            )
        )
    }

    private fun getLocalWifiAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun addCors(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Range")
        return response
    }
}
