package com.gontijotech.gtstore.client.network

import android.content.Context
import android.util.Log
import com.gontijotech.gtstore.client.GTStoreFileLogger
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

class Ps4Payloader(
    private val context: Context
) {

    companion object {
        private const val TAG = "GTStore-Payload"

        private val BINLOADER_PORTS = intArrayOf(
            9090,
            9021,
            9020
        )

        private const val CALLBACK_TIMEOUT_MS = 15_000
        private const val CONNECT_TIMEOUT_MS = 3_000
    }

    private fun fileLog(message: String) {
        Log.i(TAG, message)
        GTStoreFileLogger.log(context, TAG, message)
    }

    private fun fileWarn(message: String) {
        Log.w(TAG, message)
        GTStoreFileLogger.log(context, TAG, "WARN: $message")
    }

    private fun fileError(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        GTStoreFileLogger.log(
            context,
            TAG,
            "ERROR: $message" + (throwable?.message?.let { " | $it" } ?: "")
        )
    }

    suspend fun injectDpiPayload(
        ps4Ip: String,
        localIp: String,
        downloadUrl: String, // A payload nativa foi feita para receber diretamente a URL de download (limpa ou do proxy)
        itemTitle: String,
        contentId: String,
        category: String,
        fileSize: Long,
        iconBytes: ByteArray? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {

        try {
            fileLog("========================================")
            fileLog("INÍCIO DA INJEÇÃO DE PAYLOAD")
            fileLog("PS4       : $ps4Ip")
            fileLog("Android   : $localIp")
            fileLog("Download  : $downloadUrl")
            fileLog("Título    : $itemTitle")
            fileLog("ContentID : $contentId")
            fileLog("Categoria : $category")
            fileLog("Tamanho   : $fileSize")
            fileLog("========================================")

            // 1. CARREGA PAYLOAD
            fileLog("Carregando payload...")

            val payloadTemplate = loadPayload("payload.bin")
                ?: loadPayload("direct-installer.bin")
                ?: return@withContext Result.failure(
                    Exception(
                        "Arquivo de payload ausente. " +
                            "Coloque payload.bin ou direct-installer.bin na pasta assets do aplicativo."
                    )
                )

            val payload = payloadTemplate.copyOf()

            // 2. LOCALIZA HOOK
            val hookPattern = ByteArray(6) { 0xB4.toByte() }
            val offset = indexOf(payload, hookPattern)

            if (offset < 0 || offset + 6 > payload.size) {
                fileError("Marcador B4 B4 B4 B4 B4 B4 não encontrado.")
                return@withContext Result.failure(
                    Exception("Marcador B4 B4 B4 B4 B4 B4 não encontrado no payload.bin. Arquivo corrompido ou incorreto.")
                )
            }

            // 3. RESOLVE IP
            val localAddr = try {
                InetAddress.getByName(localIp)
            } catch (e: Exception) {
                return@withContext Result.failure(Exception("IP local do Android inválido: $localIp", e))
            }

            val ipBytes = localAddr.address

            if (ipBytes.size != 4) {
                return@withContext Result.failure(Exception("O endereço local precisa ser IPv4. Obtido: $localIp"))
            }

            // 4. SOCKET CALLBACK
            ServerSocket(0, 5, localAddr).use { tempServer ->
                tempServer.soTimeout = CALLBACK_TIMEOUT_MS
                val callbackPort = tempServer.localPort

                fileLog("Socket de callback aberto: $localIp:$callbackPort")

                // 5. INJETA IP + PORTA NA PAYLOAD
                ipBytes.copyInto(payload, offset)
                // A porta DEVE ser inserida em Big-Endian no payload C/C++
                payload[offset + 4] = (callbackPort ushr 8).toByte()
                payload[offset + 5] = (callbackPort and 0xFF).toByte()

                fileLog("Payload patcheada com sucesso.")

                // 6. ENVIA PARA O BINLOADER NO PS4
                try {
                    sendToBinLoader(ps4Ip = ps4Ip, payload = payload)
                } catch (e: Exception) {
                    return@withContext Result.failure(
                        Exception("Falha de conexão com o BinLoader do PS4. Verifique se o GoldHEN e o BinLoader estão ativos (Porta 9090).", e)
                    )
                }

                fileLog("Aguardando callback do PS4 (Timeout de 15s)...")

                // 7. CALLBACK DO PS4 E ENVIO DOS METADADOS
                try {
                    tempServer.accept().use { ps4Client ->
                        ps4Client.soTimeout = 10_000
                        val remoteAddress = ps4Client.inetAddress?.hostAddress ?: "desconhecido"

                        fileLog("CALLBACK RECEBIDO DO PS4! IP: $remoteAddress")

                        // 8. CONSTROI A ESTRUTURA BINÁRIA (Em Little-Endian)
                        val info = buildDpiInfo(
                            url = downloadUrl,
                            title = itemTitle,
                            contentId = contentId,
                            category = category,
                            size = fileSize,
                            icon = iconBytes
                        )

                        fileLog("Enviando metadados de instalação (${info.size} bytes) ao PS4...")

                        val output = ps4Client.getOutputStream()
                        output.write(info)
                        output.flush()

                        fileLog("Metadados enviados com sucesso.")
                    }
                } catch (e: SocketTimeoutException) {
                    return@withContext Result.failure(
                        Exception("O PS4 não retornou a conexão para o aplicativo em $localIp:$callbackPort. Verifique isolamento de AP no seu roteador.", e)
                    )
                }
            }

            fileLog("========================================")
            fileLog("INJEÇÃO E INSTALAÇÃO CONCLUÍDAS!")
            fileLog("========================================")

            Result.success(true)

        } catch (e: Exception) {
            fileError("FALHA GERAL NA INJEÇÃO: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun sendToBinLoader(ps4Ip: String, payload: ByteArray) {
        var lastError: Exception? = null

        for (attempt in 0 until 2) {
            for (port in BINLOADER_PORTS) {
                try {
                    Socket().use { socket ->
                        socket.tcpNoDelay = true
                        socket.keepAlive = false
                        socket.soTimeout = 8_000
                        socket.connect(InetSocketAddress(ps4Ip, port), CONNECT_TIMEOUT_MS)

                        val output = socket.getOutputStream()
                        output.write(payload)
                        output.flush()
                    }
                    // Retorna imediatamente em caso de sucesso
                    return
                } catch (e: Exception) {
                    lastError = e
                }
            }

            if (attempt == 0) {
                try {
                    Thread.sleep(3_000)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }

        throw IllegalStateException("BinLoader inacessível em 9090/9021/9020.", lastError)
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

        fun i32(value: Int) {
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        }

        fun i64(value: Long) {
            out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())
        }

        fun str(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            i32(bytes.size)
            out.write(bytes)
        }

        i32(1) // 1 = Comando de "Novo Pacote"

        str(url) // A URL que o sistema BGFT vai tentar acessar
        str(title)
        str(contentId)
        str(normalizeBgftType(category))
        i64(size)

        if (icon == null || icon.isEmpty()) {
            i32(0)
        } else {
            i32(icon.size)
            out.write(icon)
        }

        return out.toByteArray()
    }

    private fun normalizeBgftType(category: String): String {
        val clean = category.trim().removePrefix("PS4").removePrefix("ps4").uppercase()
        return when (clean) {
            "GP", "PATCH", "UPDATE" -> "PS4GP" // Patches e Atualizações
            "AC", "DLC", "APP", "GAME" -> "PS4AC" // Jogos base e DLCs padrão no BGFT
            else -> "PS4GD"
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
