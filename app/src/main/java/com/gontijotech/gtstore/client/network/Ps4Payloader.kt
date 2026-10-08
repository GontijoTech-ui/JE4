package com.gontijotech.gtstore.client.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class Ps4Payloader(private val context: Context) {

    /**
     * Executa a injeção DPI completa via BinLoader (porta 9090) e handshake reverso.
     * @param localIp IP Wi-Fi desta máquina na rede local para retorno do PS4.
     */
    suspend fun injectDpiPayload(
        ps4Ip: String,
        localIp: String,
        manifestUrl: String,
        itemTitle: String,
        contentId: String,
        category: String,
        fileSize: Long,
        iconBytes: ByteArray? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // 1. Carrega o payload base dos assets
            val payloadTemplate = loadPayload("payload.bin") ?: loadPayload("direct-installer.bin")
                ?: return@withContext Result.failure(Exception("Arquivo de payload (payload.bin) ausente na pasta assets."))

            val payload = payloadTemplate.copyOf()

            // 2. Localiza a assinatura do hook (6 bytes 0xB4: 4 bytes IP + 2 bytes Porta)
            val hookPattern = ByteArray(6) { 0xB4.toByte() }
            val offset = indexOf(payload, hookPattern)

            if (offset < 0 || offset + 6 > payload.size) {
                return@withContext Result.failure(Exception("Assinatura do payload incompatível (offset 0xB4 não localizado)."))
            }

            val localAddr = InetAddress.getByName(localIp)

            // 3. Socket temporário para receber o callback de metadados do PS4
            ServerSocket(0, 5, localAddr).use { tempServer ->
                tempServer.soTimeout = 15_000
                val callbackPort = tempServer.localPort

                // Grava IP (4 bytes) e Porta (2 bytes em Big Endian / Network Byte Order)
                localAddr.address.copyInto(payload, offset)
                payload[offset + 4] = (callbackPort ushr 8).toByte()
                payload[offset + 5] = (callbackPort and 0xFF).toByte()

                // 4. Envia o binário para o BinLoader do PS4 (porta 9090)
                try {
                    sendToBinLoader(ps4Ip, payload)
                } catch (e: ConnectException) {
                    return@withContext Result.failure(Exception("Conexão recusada na porta 9090. Ative o BinLoader no GoldHEN do console."))
                } catch (e: SocketTimeoutException) {
                    return@withContext Result.failure(Exception("Tempo limite esgotado ao conectar ao PS4 ($ps4Ip:9090)."))
                } catch (e: Exception) {
                    return@withContext Result.failure(Exception("Erro ao enviar payload ao PS4: ${e.message}"))
                }

                // 5. Aguarda o PS4 conectar de volta para entregar os metadados
                try {
                    tempServer.accept().use { ps4Client ->
                        ps4Client.soTimeout = 10_000
                        val output = ps4Client.getOutputStream()
                        output.write(buildDpiInfo(manifestUrl, itemTitle, contentId, category, fileSize, iconBytes))
                        output.flush()
                    }
                } catch (e: SocketTimeoutException) {
                    return@withContext Result.failure(
                        Exception("O PS4 não retornou a conexão. Verifique se o roteador possui AP Isolation ativo ou firewall bloqueando.")
                    )
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

        i32(1) // Versão do protocolo DPI
        str(url)
        str(title)
        str(contentId)

        // Normalização estrita da categoria para o padrão esperado pelo BGFT
        val cleanCat = category.removePrefix("PS4").removePrefix("ps4").lowercase().trim()
        val bgftType = "PS4" + when (cleanCat) {
            "gp", "patch", "update" -> "gp"
            "ac", "dlc" -> "ac"
            else -> "gd"
        }
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

    private fun sendToBinLoader(ip: String, payload: ByteArray) {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = 8_000
            socket.connect(InetSocketAddress(ip, 9090), 5_000)
            val out = socket.getOutputStream()
            out.write(payload)
            out.flush()
            try { socket.shutdownOutput() } catch (_: Exception) {}
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
}
