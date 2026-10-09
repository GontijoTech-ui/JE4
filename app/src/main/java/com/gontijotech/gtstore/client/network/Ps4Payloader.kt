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

    /**
     * Loga simultaneamente no Logcat e em:
     *
     * Downloads/GTSTORE-log.txt
     */
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
        manifestUrl: String,
        itemTitle: String,
        contentId: String,
        category: String,
        fileSize: Long,
        iconBytes: ByteArray? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {

        try {
            fileLog("========================================")
            fileLog("INÍCIO DA INJEÇÃO")
            fileLog("PS4       : $ps4Ip")
            fileLog("Android   : $localIp")
            fileLog("Manifesto : $manifestUrl")
            fileLog("Título    : $itemTitle")
            fileLog("ContentID : $contentId")
            fileLog("Categoria : $category")
            fileLog("Tamanho   : $fileSize")
            fileLog("========================================")

            // ---------------------------------------------------------
            // 1. CARREGA PAYLOAD
            // ---------------------------------------------------------
            fileLog("Carregando payload...")

            val payloadTemplate = loadPayload("payload.bin")
                ?: loadPayload("direct-installer.bin")
                ?: return@withContext Result.failure(
                    Exception(
                        "Arquivo de payload ausente. " +
                            "Coloque payload.bin ou direct-installer.bin em assets."
                    )
                )

            fileLog("Payload carregado: ${payloadTemplate.size} bytes")
            val payload = payloadTemplate.copyOf()

            // ---------------------------------------------------------
            // 2. LOCALIZA HOOK
            // ---------------------------------------------------------
            val hookPattern = ByteArray(6) { 0xB4.toByte() }

            fileLog("Procurando marcador B4 B4 B4 B4 B4 B4...")

            val offset = indexOf(payload, hookPattern)

            if (offset < 0 || offset + 6 > payload.size) {
                fileError("Marcador B4 B4 B4 B4 B4 B4 não encontrado.")
                return@withContext Result.failure(
                    Exception("Marcador B4 B4 B4 B4 B4 B4 não encontrado no payload.")
                )
            }

            fileLog("Hook encontrado no offset: 0x${offset.toString(16).uppercase()}")

            // ---------------------------------------------------------
            // 3. RESOLVE IP
            // ---------------------------------------------------------
            fileLog("Resolvendo endereço local: $localIp")

            val localAddr = try {
                InetAddress.getByName(localIp)
            } catch (e: Exception) {
                fileError("IP local do Android inválido: $localIp", e)
                return@withContext Result.failure(
                    Exception("IP local do Android inválido: $localIp", e)
                )
            }

            val ipBytes = localAddr.address
            fileLog("Bytes IPv4 encontrados: ${ipBytes.size}")

            if (ipBytes.size != 4) {
                fileError("O endereço local não é IPv4: $localIp")
                return@withContext Result.failure(
                    Exception("O endereço local precisa ser IPv4. Obtido: $localIp")
                )
            }

            // ---------------------------------------------------------
            // 4. SOCKET CALLBACK
            // ---------------------------------------------------------
            ServerSocket(0, 5, localAddr).use { tempServer ->
                tempServer.soTimeout = CALLBACK_TIMEOUT_MS
                val callbackPort = tempServer.localPort

                fileLog("Socket de callback criado:")
                fileLog("$localIp:$callbackPort")

                // -----------------------------------------------------
                // 5. INJETA IP + PORTA
                // -----------------------------------------------------
                ipBytes.copyInto(payload, offset)
                payload[offset + 4] = (callbackPort ushr 8).toByte()
                payload[offset + 5] = (callbackPort and 0xFF).toByte()

                fileLog("IP do callback inserido: $localIp")
                fileLog("Porta do callback inserida: $callbackPort")
                fileLog("Payload preparado.")

                // -----------------------------------------------------
                // 6. ENVIA BINLOADER
                // -----------------------------------------------------
                try {
                    sendToBinLoader(ps4Ip = ps4Ip, payload = payload)
                } catch (e: ConnectException) {
                    fileError("Não foi possível conectar ao BinLoader.", e)
                    return@withContext Result.failure(
                        Exception(
                            "Não foi possível conectar ao BinLoader do PS4. " +
                                "Verifique se o GoldHEN > BinLoader está ativado nas portas 9090/9021/9020.",
                            e
                        )
                    )
                } catch (e: SocketTimeoutException) {
                    fileError("Tempo limite ao conectar ao BinLoader.", e)
                    return@withContext Result.failure(
                        Exception("Tempo limite ao conectar ao BinLoader do PS4 $ps4Ip.", e)
                    )
                } catch (e: Exception) {
                    fileError("Erro ao enviar payload ao PS4: ${e.message}", e)
                    return@withContext Result.failure(
                        Exception("Erro ao enviar payload ao PS4: ${e.message}", e)
                    )
                }

                fileLog("PAYLOAD ENVIADO COM SUCESSO.")
                fileLog("Aguardando callback do PS4...")

                // -----------------------------------------------------
                // 7. CALLBACK
                // -----------------------------------------------------
                try {
                    tempServer.accept().use { ps4Client ->
                        ps4Client.soTimeout = 10_000
                        val remoteAddress = ps4Client.inetAddress?.hostAddress ?: "desconhecido"

                        fileLog("CALLBACK RECEBIDO DO PS4!")
                        fileLog("IP remoto do callback: $remoteAddress")

                        // -------------------------------------------------
                        // 8. BUILD INFO
                        // -------------------------------------------------
                        fileLog("Construindo buildInfo...")

                        val info = buildDpiInfo(
                            url = manifestUrl,
                            title = itemTitle,
                            contentId = contentId,
                            category = category,
                            size = fileSize,
                            icon = iconBytes
                        )

                        fileLog("buildInfo criado: ${info.size} bytes")
                        fileLog("Enviando buildInfo ao PS4...")

                        val output = ps4Client.getOutputStream()
                        output.write(info)
                        output.flush()

                        fileLog("buildInfo enviado com sucesso.")
                    }
                } catch (e: SocketTimeoutException) {
                    fileError("PS4 não retornou conexão para $localIp:$callbackPort", e)
                    return@withContext Result.failure(
                        Exception(
                            "O PS4 não retornou a conexão para $localIp:$callbackPort. " +
                                "Verifique se o Android e o PS4 estão na mesma rede Wi-Fi e se o roteador não possui AP Isolation.",
                            e
                        )
                    )
                }
            }

            fileLog("========================================")
            fileLog("INJEÇÃO CONCLUÍDA")
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
            fileLog("Rodada ${attempt + 1}/2 do BinLoader")

            for (port in BINLOADER_PORTS) {
                try {
                    fileLog("Tentando BinLoader $ps4Ip:$port")

                    Socket().use { socket ->
                        socket.tcpNoDelay = true
                        socket.keepAlive = false
                        socket.soTimeout = 8_000
                        socket.connect(InetSocketAddress(ps4Ip, port), CONNECT_TIMEOUT_MS)

                        fileLog("Conectado ao BinLoader $ps4Ip:$port")

                        val output = socket.getOutputStream()
                        output.write(payload)
                        output.flush()

                        fileLog("Payload enviado para $ps4Ip:$port")
                    }

                    fileLog("BinLoader aceitou o envio na porta $port.")
                    return

                } catch (e: Exception) {
                    lastError = e
                    fileWarn("Falha em $ps4Ip:$port -> ${e.javaClass.simpleName}: ${e.message}")
                }
            }

            if (attempt == 0) {
                fileLog("Nenhuma porta respondeu na primeira rodada.")
                fileLog("Aguardando 3 segundos antes da segunda rodada...")
                try {
                    Thread.sleep(3_000)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    fileError("Thread interrompida durante espera do retry.", e)
                    throw e
                }
            }
        }

        fileError("BinLoader inacessível em 9090/9021/9020.")
        throw IllegalStateException(
            "BinLoader inacessível em 9090/9021/9020. Verifique GoldHEN > BinLoader.",
            lastError
        )
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

        i32(1)
        fileLog("buildInfo.version = 1")

        str(url)
        fileLog("buildInfo.manifestUrl = $url")

        str(title)
        fileLog("buildInfo.title = $title")

        str(contentId)
        fileLog("buildInfo.contentId = $contentId")

        val bgftType = normalizeBgftType(category)
        fileLog("BGFT Type enviado: $bgftType")
        str(bgftType)

        i64(size)
        fileLog("buildInfo.fileSize = $size")

        if (icon == null || icon.isEmpty()) {
            i32(0)
            fileLog("buildInfo.iconSize = 0")
        } else {
            i32(icon.size)
            out.write(icon)
            fileLog("buildInfo.iconSize = ${icon.size}")
        }

        val result = out.toByteArray()
        fileLog("buildInfo.totalSize = ${result.size}")

        return result
    }

    private fun normalizeBgftType(category: String): String {
        val clean = category.trim().removePrefix("PS4").removePrefix("ps4").uppercase()
        return when (clean) {
            "GP", "PATCH", "UPDATE" -> "PS4GP"
            "AC", "DLC" -> "PS4AC"
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

    /**
     * =========================================================================
     * FUNÇÕES NOVAS PARA A AUTO-ATIVAÇÃO DA PORTA 12800
     * =========================================================================
     */

    fun isPortOpen(ip: String, port: Int, timeoutMs: Int = 1200): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun startRpiServerDaemon(ps4Ip: String): Result<Unit> {
        // Aproveita o loadPayload nativo que já busca nos locais corretos ("payload.bin")
        val payloadBytes = loadPayload("payload.bin") 
            ?: loadPayload("direct-installer.bin")
            ?: return Result.failure(Exception("Arquivo de payload não encontrado nos assets."))

        fileLog("Tentando acordar porta 12800 via BinLoader...")
        
        try {
            // Reutiliza a função de socket nativa do seu projeto sem alterar o IP de callback
            // (Ao não alterar o callback, o payload assume o modo RPI 12800)
            sendToBinLoader(ps4Ip, payloadBytes)
            
            fileLog("Payload daemon enviado. Aguardando a inicialização do RPI na PS4 (1.8s)...")
            Thread.sleep(1800)

            if (isPortOpen(ps4Ip, 12800, 2000)) {
                fileLog("Porta 12800 ativada com sucesso!")
                return Result.success(Unit)
            }
        } catch (e: Exception) {
            fileWarn("Falha ao tentar ativar daemon RPI: ${e.message}")
        }

        return Result.failure(Exception("Não foi possível inicializar a porta 12800 via BinLoader."))
    }
}
