
package com.gontijotech.gtstore.client.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class Ps4Payloader {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Injeta comando de instalação no Remote Package Installer (Porta 12800).
     */
    suspend fun injectRpi(ps4Ip: String, packageUrls: List<String>): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val jsonPayload = JSONObject().apply {
                    put("type", "direct")
                    put("packages", JSONArray(packageUrls))
                }.toString()

                val body = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url("http://$ps4Ip:12800/api/install")
                    .post(body)
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("PS4 retornou código HTTP ${response.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Injeta payload binário bruto na porta 9090 (BinLoader / GoldHEN).
     */
    suspend fun injectBinPayload(ps4Ip: String, payloadStream: InputStream): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            var socket: Socket? = null
            var out: OutputStream? = null
            try {
                socket = Socket()
                socket.connect(InetSocketAddress(ps4Ip, 9090), 5000)
                out = socket.getOutputStream()

                val buffer = ByteArray(4096)
                var bytesRead: Int
                while (payloadStream.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                }
                out.flush()
                Result.success(true)
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                out?.close()
                socket?.close()
            }
        }
    }
}
