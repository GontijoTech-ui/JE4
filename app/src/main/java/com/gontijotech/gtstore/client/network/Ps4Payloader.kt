package com.gontijotech.gtstore.client.network

import android.content.Context
import com.gontijotech.gtstore.client.server.LocalBridgeServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class Ps4Payloader(private val context: Context) {

    /**
     * Executa a injeção DPI completa via porta 9090 e handshake de retorno.
     */
    suspend fun injectDpiPayload(
        ps4Ip: String,
        manifestUrl: String,
        itemTitle: String,
        contentId: String,
        category: String,
        fileSize: Long,
        iconBytes: ByteArray?
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // 1. Carrega o payload base dos assets
            val payloadTemplate = loadPayload("payload.bin") ?: loadPayload("direct-installer.bin")
                ?: return@withContext Result.failure(Exception("Payload DPI (payload.bin) ausente em assets/."))

            val payload = payloadTemplate.copyOf()

            // 2. Localiza a assinatura padrão de 5 bytes 0xB4
            val hookPattern = byteArrayOf(0xB4.toByte(), 0xB4.toByte(), 0xB4.toByte(), 0xB4.toByte(), 0xB4.toByte())
            val offset = indexOf(payload, hookPattern)

            if (offset < 0) {
                return@withContext Result.failure(Exception("Assinatura do payload incompatível (offset não encontrado)."))
            }

            // 3. Determina o IP do celular na rede local
            val localIpAddress = getLocalWifiAddress()
                ?: return@withContext Result.failure(Exception("Não foi possível identificar o IP Wi-Fi local do celular."))

            val localAddr = InetAddress.getByName(localIpAddress)

            // 4. Cria socket temporário para receber a resposta do PS4
            ServerSocket(0, 5, localAddr).use { tempServer ->
                tempServer.soTimeout = 15_000
                val callbackPort = tempServer.localPort

                // Grava IP e porta de retorno no payload binário
                localAddr.address.copyInto(payload, offset)
                payload[offset + 4] = (callbackPort ushr 8).toByte()
                payload[offset + 5] = callbackPort.toByte()

                // 5. Envia o binário para o BinLoader do PS4 (porta 9090)
                val socketResult = sendToBinLoader(ps4Ip, payload)
                if (!socketResult) {
                    return@withContext Result.failure(Exception("Falha ao conectar no BinLoader do PS4 (porta 9090)."))
                }

                // 6. Aguarda o PS4 conectar de volta e envia os metadados binários (buildDpiInfo)
                tempServer.accept().use { ps4Client ->
                    ps4Client.soTimeout = 10_000
                    val output = ps4Client.getOutputStream()
                    output.write(buildDpiInfo(manifestUrl, itemTitle, contentId, category, fileSize, iconBytes))
                    output.flush()
                }
            }

            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildDpiInfo(
        url: String,
        title: String,
        contentId: String,
        category: String,
        size: Long,
        icon: ByteArray?
    ): ByteArray {
        val out = ByteArrayOutputStream()

        fun i32(v: Int) {
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        }

        fun i64(v: Long) {
            out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        }

        fun str(s: String) {
            val b = s.toByteArray(StandardCharsets.UTF_8)
            i32(b.size)
            out.write(b)
        }

        i32(1)
        str(url)
        str(title)
        str(contentId)

        val bgftType = "PS4" + category.uppercase()
        str(bgftType)
        i64(size)

        if (icon == null || icon.isEmpty()) {
            i32(0)
        } else {
            i32(icon.size)
            out.write(icon)
        }

        return out.toByteArray()
    }

    private fun sendToBinLoader(ip: String, payload: ByteArray): Boolean {
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.soTimeout = 8000
                socket.connect(InetSocketAddress(ip, 9090), 5000)
                val out = socket.getOutputStream()
                out.write(payload)
                out.flush()
                try { socket.shutdownOutput() } catch (_: Exception) {}
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun loadPayload(name: String): ByteArray? {
        val targets = listOf("payloads/$name", name)
        for (target in targets) {
            try {
                context.assets.open(target).use { return it.readBytes() }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        if (pattern.isEmpty() || pattern.size > data.size) return -1
        for (i in 0..data.size - pattern.size) {
            var match = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    private fun getLocalWifiAddress(): String? {
        try {
            val en = java.net.NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val addr = enumIpAddr.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
