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
            Log.e(tag, "Erro no processamento da rota $uri:${e.message}", e)
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
            json.optString
