package com.gontijotech.gtstore.client.network

import android.content.Context
import android.util.Log
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

        /**
         * Mesmas portas utilizadas pelo projeto antigo.
         */
        private val BINLOADER_PORTS = intArrayOf(
            9090,
            9021,
            9020
        )

        private const val CALLBACK_TIMEOUT_MS = 15_000
        private const val CONNECT_TIMEOUT_MS = 3_000
    }

    /**
     * Executa a injeção DPI completa:
     *
     * Android
     *   ↓
     * cria ServerSocket temporário
     *   ↓
     * altera payload com IP + porta do Android
     *   ↓
     * envia payload ao BinLoader do PS4
     *   ↓
     * PS4 conecta de volta ao Android
     *   ↓
     * Android envia buildInfo()
     *   ↓
     * PS4 recebe manifesto
     *
     * @param ps4Ip IP do PS4
     * @param localIp IP Wi-Fi do Android
     * @param manifestUrl URL do manifesto servido pelo Android
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

            Log.i(TAG, "========================================")
            Log.i(TAG, "INÍCIO DA INJEÇÃO")
            Log.i(TAG, "PS4       : $ps4Ip")
            Log.i(TAG, "Android   : $localIp")
            Log.i(TAG, "Manifesto : $manifestUrl")
            Log.i(TAG, "Título    : $itemTitle")
            Log.i(TAG, "ContentID : $contentId")
            Log.i(TAG, "Categoria : $category")
            Log.i(TAG, "Tamanho   : $fileSize")
            Log.i(TAG, "========================================")

            // ---------------------------------------------------------
            // 1. Carrega o payload
            // ---------------------------------------------------------

            val payloadTemplate =
                loadPayload("payload.bin")
                    ?: loadPayload("direct-installer.bin")
                    ?: return@withContext Result.failure(
                        Exception(
                            "Arquivo de payload ausente. " +
                                "Coloque payload.bin ou direct-installer.bin em assets."
                        )
                    )

            Log.i(
                TAG,
                "Payload carregado: ${payloadTemplate.size} bytes"
            )

            val payload = payloadTemplate.copyOf()

            // ---------------------------------------------------------
            // 2. Localiza marcador B4 B4 B4 B4 B4 B4
            // ---------------------------------------------------------

            val hookPattern = ByteArray(6) {
                0xB4.toByte()
            }

            val offset = indexOf(
                payload,
                hookPattern
            )

            if (offset < 0 || offset + 6 > payload.size) {
                return@withContext Result.failure(
                    Exception(
                        "Marcador B4 B4 B4 B4 B4 B4 não encontrado no payload."
                    )
                )
            }

            Log.i(
                TAG,
                "Hook encontrado no offset: 0x${offset.toString(16).uppercase()}"
            )

            // ---------------------------------------------------------
            // 3. Resolve IP do Android
            // ---------------------------------------------------------

            val localAddr = try {
                InetAddress.getByName(localIp)
            } catch (e: Exception) {
                return@withContext Result.failure(
                    Exception(
                        "IP local do Android inválido: $localIp",
                        e
                    )
                )
            }

            val ipBytes = localAddr.address

            if (ipBytes.size != 4) {
                return@withContext Result.failure(
                    Exception(
                        "O endereço local precisa ser IPv4. Obtido: $localIp"
                    )
                )
            }

            // ---------------------------------------------------------
            // 4. Abre socket temporário para callback
            // ---------------------------------------------------------

            ServerSocket(
                0,
                5,
                localAddr
            ).use { tempServer ->

                tempServer.soTimeout = CALLBACK_TIMEOUT_MS

                val callbackPort = tempServer.localPort

                Log.i(
                    TAG,
                    "Socket de callback criado: $localIp:$callbackPort"
                )

                // -----------------------------------------------------
                // 5. Injeta IP + porta no payload
                // -----------------------------------------------------

                ipBytes.copyInto(
                    payload,
                    offset
                )

                // Porta em Big Endian / Network Byte Order
                payload[offset + 4] =
                    (callbackPort ushr 8).toByte()

                payload[offset + 5] =
                    (callbackPort and 0xFF).toByte()

                Log.i(
                    TAG,
                    "Payload preparado com callback $localIp:$callbackPort"
                )

                // -----------------------------------------------------
                // 6. Envia para BinLoader
                // -----------------------------------------------------

                try {

                    sendToBinLoader(
                        ps4Ip = ps4Ip,
                        payload = payload
                    )

                } catch (e: ConnectException) {

                    return@withContext Result.failure(
                        Exception(
                            "Não foi possível conectar ao BinLoader do PS4. " +
                                "Verifique se o GoldHEN > BinLoader está ativado " +
                                "nas portas 9090/9021/9020.",
                            e
                        )
                    )

                } catch (e: SocketTimeoutException) {

                    return@withContext Result.failure(
                        Exception(
                            "Tempo limite ao conectar ao BinLoader do PS4 $ps4Ip.",
                            e
                        )
                    )

                } catch (e: Exception) {

                    return@withContext Result.failure(
                        Exception(
                            "Erro ao enviar payload ao PS4: ${e.message}",
                            e
                        )
                    )
                }

                Log.i(
                    TAG,
                    "Payload enviado. Aguardando callback do PS4..."
                )

                // -----------------------------------------------------
                // 7. Aguarda conexão reversa
                // -----------------------------------------------------

                try {

                    tempServer.accept().use { ps4Client ->

                        ps4Client.soTimeout = 10_000

                        val remoteAddress =
                            ps4Client.inetAddress?.hostAddress
                                ?: "desconhecido"

                        Log.i(
                            TAG,
                            "Callback recebido do PS4: $remoteAddress"
                        )

                        val info = buildDpiInfo(
                            url = manifestUrl,
                            title = itemTitle,
                            contentId = contentId,
                            category = category,
                            size = fileSize,
                            icon = iconBytes
                        )

                        Log.i(
                            TAG,
                            "Enviando buildInfo: ${info.size} bytes"
                        )

                        val output =
                            ps4Client.getOutputStream()

                        output.write(info)
                        output.flush()

                        Log.i(
                            TAG,
                            "buildInfo enviado com sucesso."
                        )
                    }

                } catch (e: SocketTimeoutException) {

                    return@withContext Result.failure(
                        Exception(
                            "O PS4 não retornou a conexão para " +
                                "$localIp:$callbackPort. " +
                                "Verifique se o Android e o PS4 estão na mesma rede Wi-Fi " +
                                "e se o roteador não possui AP Isolation.",
                            e
                        )
                    )
                }
            }

            Log.i(TAG, "========================================")
            Log.i(TAG, "INJEÇÃO CONCLUÍDA")
            Log.i(TAG, "========================================")

            Result.success(true)

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Falha geral na injeção",
                e
            )

            Result.failure(e)
        }
    }

    /**
     * Envia o payload para o BinLoader.
     *
     * Lógica do projeto antigo:
     *
     * rodada 1:
     *   9090
     *   9021
     *   9020
     *
     * aguarda 3 segundos
     *
     * rodada 2:
     *   9090
     *   9021
     *   9020
     */
    private fun sendToBinLoader(
        ps4Ip: String,
        payload: ByteArray
    ) {

        var lastError: Exception? = null

        repeat(2) { attempt ->

            Log.i(
                TAG,
                "Rodada ${attempt + 1}/2 do BinLoader"
            )

            for (port in BINLOADER_PORTS) {

                try {

                    Log.i(
                        TAG,
                        "Tentando BinLoader $ps4Ip:$port"
                    )

                    Socket().use { socket ->

                        socket.tcpNoDelay = true
                        socket.keepAlive = false
                        socket.soTimeout = 8_000

                        socket.connect(
                            InetSocketAddress(
                                ps4Ip,
                                port
                            ),
                            CONNECT_TIMEOUT_MS
                        )

                        val output =
                            socket.getOutputStream()

                        output.write(payload)
                        output.flush()

                        Log.i(
                            TAG,
                            "Payload enviado para $ps4Ip:$port"
                        )
                    }

                    return

                } catch (e: Exception) {

                    lastError = e

                    Log.w(
                        TAG,
                        "Falha em $ps4Ip:$port -> ${e.message}"
                    )
                }
            }

            if (attempt == 0) {

                Log.i(
                    TAG,
                    "Nenhuma porta respondeu. Aguardando 3 segundos..."
                )

                try {

                    Thread.sleep(3_000)

                } catch (e: InterruptedException) {

                    Thread.currentThread().interrupt()

                    // IMPORTANTE:
                    // não usamos "break" aqui porque estamos
                    // dentro do lambda do repeat().
                    throw e
                }
            }
        }

        throw IllegalStateException(
            "BinLoader inacessível em 9090/9021/9020. " +
                "Verifique GoldHEN > BinLoader.",
            lastError
        )
    }

    /**
     * Estrutura de informações enviada pelo payload.
     *
     * Mantida compatível com o projeto antigo:
     *
     * int32 version
     * string manifestUrl
     * string title
     * string contentId
     * string bgftType
     * int64 fileSize
     * int32 iconSize
     * byte[] icon
     */
    private fun buildDpiInfo(
        url: String,
        title: String,
        contentId: String,
        category: String,
        size: Long,
        icon: ByteArray?
    ): ByteArray {

        val out =
            ByteArrayOutputStream()

        fun i32(value: Int) {

            out.write(
                ByteBuffer
                    .allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(value)
                    .array()
            )
        }

        fun i64(value: Long) {

            out.write(
                ByteBuffer
                    .allocate(8)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(value)
                    .array()
            )
        }

        fun str(value: String) {

            val bytes =
                value.toByteArray(
                    StandardCharsets.UTF_8
                )

            i32(bytes.size)
            out.write(bytes)
        }

        // Versão do protocolo
        i32(1)

        // URL do manifesto
        str(url)

        // Título
        str(title)

        // Content ID
        str(contentId)

        // Categoria BGFT
        val bgftType =
            normalizeBgftType(category)

        Log.i(
            TAG,
            "BGFT Type enviado: $bgftType"
        )

        str(bgftType)

        // Tamanho do PKG
        i64(size)

        // Ícone
        if (icon == null || icon.isEmpty()) {

            i32(0)

        } else {

            i32(icon.size)
            out.write(icon)
        }

        return out.toByteArray()
    }

    /**
     * Normalização da categoria.
     *
     * gd          -> PS4GD
     * gp          -> PS4GP
     * ac          -> PS4AC
     * patch       -> PS4GP
     * update      -> PS4GP
     * dlc         -> PS4AC
     */
    private fun normalizeBgftType(
        category: String
    ): String {

        val clean =
            category
                .trim()
                .removePrefix("PS4")
                .removePrefix("ps4")
                .uppercase()

        return when (clean) {

            "GP",
            "PATCH",
            "UPDATE" -> "PS4GP"

            "AC",
            "DLC" -> "PS4AC"

            else -> "PS4GD"
        }
    }

    /**
     * Carrega payload dos assets.
     */
    private fun loadPayload(
        name: String
    ): ByteArray? {

        val targets =
            listOf(
                "payloads/$name",
                name
            )

        for (target in targets) {

            try {

                context.assets
                    .open(target)
                    .use {
                        return it.readBytes()
                    }

            } catch (_: Exception) {
                // Tenta o próximo caminho.
            }
        }

        return null
    }

    /**
     * Procura uma sequência de bytes dentro do payload.
     */
    private fun indexOf(
        data: ByteArray,
        pattern: ByteArray
    ): Int {

        if (
            pattern.isEmpty() ||
            pattern.size > data.size
        ) {
            return -1
        }

        for (
            i in 0..data.size - pattern.size
        ) {

            var match = true

            for (j in pattern.indices) {

                if (data[i + j] != pattern[j]) {

                    match = false
                    break
                }
            }

            if (match) {
                return i
            }
        }

        return -1
    }
}
