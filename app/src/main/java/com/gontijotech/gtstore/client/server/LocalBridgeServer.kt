package com.gontijotech.gtstore.client.server

import com.gontijotech.gtstore.client.network.Ps4Payloader
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.HashMap
import java.util.concurrent.TimeUnit

class LocalBridgeServer(
    port: Int = 8080,
    private val adminHost: String = "https://loja.gontijotech.com.br"
) : NanoHTTPD(port) {

    private val proxyClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val payloader = Ps4Payloader()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        if (method == Method.OPTIONS) {
            return addCors(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""))
        }

        return try {
            when {
                // Endpoint local exclusivo acionado pelo botão da interface web
                method == Method.POST && uri == "/api/local/inject" -> handleLocalInject(session)

                // Proxy do index.html vindo direto do servidor Admin na nuvem
                uri == "/" || uri == "/index.html" -> handleProxyStatic("$adminHost/index.html", "text/html")

                // Proxy transparente para todas as outras rotas e assets (/api/..., imagens, etc.)
                else -> handleProxyForward(session)
            }
        } catch (e: Exception) {
            val err = JSONObject().put("error", e.message ?: "Erro interno no LocalBridgeServer").toString()
            addCors(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", err))
        }
    }

    private fun handleLocalInject(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val json = JSONObject(map["postData"] ?: "{}")

        val ps4Ip = json.getString("ps4Ip")
        val manifestUrl = json.getString("manifestUrl")

        val result = runBlocking {
            payloader.injectRpi(ps4Ip, listOf(manifestUrl))
        }

        val resJson = JSONObject().apply {
            put("success", result.isSuccess)
            if (result.isFailure) {
                put("error", result.exceptionOrNull()?.message)
            }
        }.toString()

        return addCors(newFixedLengthResponse(Response.Status.OK, "application/json", resJson))
    }

    private fun handleProxyStatic(targetUrl: String, mime: String): Response {
        val req = Request.Builder().url(targetUrl).build()
        val resp = proxyClient.newCall(req).execute()
        val body = resp.body?.string() ?: "Falha ao carregar interface remota."
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

    private fun addCors(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Range")
        return response
    }
}
