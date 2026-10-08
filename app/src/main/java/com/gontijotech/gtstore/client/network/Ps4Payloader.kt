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

        /*
         * PRIMEIRA VERSÃO DE CORREÇÃO
         *
         * Reproduzimos o comportamento do HttpServer antigo,
         * que funcionava:
         *
         * PS4 BinLoader -> porta 9090
         */
        private const val BINLOADER_PORT = 9090

        /*
         * Timeout usado pelo projeto antigo.
         */
        private const val CALLBACK_TIMEOUT_MS = 15_000
        private const val CONNECT_TIMEOUT_MS = 5_000
    }

    /**
     * Loga simultaneamente no Logcat e em:
     *
     * Downloads/GTSTORE-log.txt
     */
    private fun fileLog(message: String) {
        Log.i(TAG, message)

        GTStoreFileLogger.log(
            context,
            TAG,
            message
        )
    }

    private fun fileWarn(message: String) {
        Log.w(TAG, message)

        GTStoreFileLogger.log(
            context,
            TAG,
            "WARN: $message"
        )
    }

    private fun fileError(
        message: String,
        throwable: Throwable? = null
    ) {
        Log.e(
            TAG,
            message,
            throwable
        )

        GTStoreFileLogger.log(
            context,
            TAG,
            "ERROR: $message" +
                (
                    throwable?.message
                        ?.let { " | $it" }
                        ?: ""
                )
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

            fileLog(
                "========================================"
            )

            fileLog(
                "INÍCIO DA INJEÇÃO"
            )

            fileLog(
                "PS4       : $ps4Ip"
            )

            fileLog(
                "Android   : $localIp"
            )

            fileLog(
                "Manifesto : $manifestUrl"
            )

            fileLog(
                "Título    : $itemTitle"
            )

            fileLog(
                "ContentID : $contentId"
            )

            fileLog(
                "Categoria : $category"
            )

            fileLog(
                "Tamanho   : $fileSize"
            )

            fileLog(
                "========================================"
            )

            // =========================================================
            // 1. CARREGA PAYLOAD
            // =========================================================

            fileLog(
                "Carregando payload..."
            )

            val payloadTemplate =
                loadPayload("payload.bin")
                    ?: loadPayload("direct-installer.bin")
                    ?: return@withContext Result.failure(
                        Exception(
                            "Arquivo de payload ausente. " +
                                "Coloque payload.bin ou direct-installer.bin em assets."
                        )
                    )

            fileLog(
                "Payload carregado: ${payloadTemplate.size} bytes"
            )

            val payload =
                payloadTemplate.copyOf()

            // =========================================================
            // 2. LOCALIZA HOOK
            // =========================================================
            //
            // IMPORTANTE:
            //
            // O projeto antigo procurava SOMENTE 5 bytes B4.
            //
            // Depois disso ele utilizava:
            //
            // offset + 0..3 = IP
            // offset + 4..5 = porta
            //
            // Portanto NÃO procuramos 6 B4 consecutivos aqui.
            // =========================================================

            val hookPattern =
                byteArrayOf(
                    0xB4.toByte(),
                    0xB4.toByte(),
                    0xB4.toByte(),
                    0xB4.toByte(),
                    0xB4.toByte()
                )

            fileLog(
                "Procurando marcador:"
            )

            fileLog(
                "B4 B4 B4 B4 B4"
            )

            val offset =
                indexOf(
                    payload,
                    hookPattern
                )

            if (
                offset < 0 ||
                offset + 6 > payload.size
            ) {

                fileError(
                    "Marcador B4 B4 B4 B4 B4 não encontrado."
                )

                return@withContext Result.failure(
                    Exception(
                        "Marcador B4 B4 B4 B4 B4 não encontrado no payload."
                    )
                )
            }

            fileLog(
                "HOOK ENCONTRADO!"
            )

            fileLog(
                "Offset decimal: $offset"
            )

            fileLog(
                "Offset hexadecimal: " +
                    "0x${offset.toString(16).uppercase()}"
            )

            // Mostra os 6 bytes que serão utilizados.
            fileLog(
                "Bytes antes do patch: " +
                    payload[offset].toUByte().toString(16).padStart(2, '0') +
                    " " +
                    payload[offset + 1].toUByte().toString(16).padStart(2, '0') +
                    " " +
                    payload[offset + 2].toUByte().toString(16).padStart(2, '0') +
                    " " +
                    payload[offset + 3].toUByte().toString(16).padStart(2, '0') +
                    " " +
                    payload[offset + 4].toUByte().toString(16).padStart(2, '0') +
                    " " +
                    payload[offset + 5].toUByte().toString(16).padStart(2, '0')
            )

            // =========================================================
            // 3. RESOLVE IP LOCAL
            // =========================================================

            fileLog(
                "Resolvendo endereço local: $localIp"
            )

            val localAddr =
                try {

                    InetAddress.getByName(
                        localIp
                    )

                } catch (e: Exception) {

                    fileError(
                        "IP local do Android inválido: $localIp",
                        e
                    )

                    return@withContext Result.failure(
                        Exception(
                            "IP local do Android inválido: $localIp",
                            e
                        )
                    )
                }

            val ipBytes =
                localAddr.address

            fileLog(
                "Bytes de endereço encontrados: ${ipBytes.size}"
            )

            if (ipBytes.size != 4) {

                fileError(
                    "O endereço local não é IPv4: $localIp"
                )

                return@withContext Result.failure(
                    Exception(
                        "O endereço local precisa ser IPv4. Obtido: $localIp"
                    )
                )
            }

            fileLog(
                "IPv4 confirmado: $localIp"
            )

            // =========================================================
            // 4. CRIA SOCKET DE CALLBACK
            // =========================================================
            //
            // O callback precisa existir ANTES de enviar o payload.
            //
            // Isso permanece igual ao projeto antigo.
            // =========================================================

            ServerSocket(
                0,
                5,
                localAddr
            ).use { tempServer ->

                tempServer.soTimeout =
                    CALLBACK_TIMEOUT_MS

                val callbackPort =
                    tempServer.localPort

                fileLog(
                    "========================================"
                )

                fileLog(
                    "CALLBACK SERVER CRIADO"
                )

                fileLog(
                    "IP callback   : $localIp"
                )

                fileLog(
                    "Porta callback: $callbackPort"
                )

                fileLog(
                    "Timeout       : ${CALLBACK_TIMEOUT_MS}ms"
                )

                fileLog(
                    "========================================"
                )

                // =====================================================
                // 5. PATCH DO PAYLOAD
                // =====================================================
                //
                // EXATAMENTE como no HttpServer antigo:
                //
                // offset + 0..3 = IPv4
                // offset + 4..5 = porta big-endian
                // =====================================================

                ipBytes.copyInto(
                    payload,
                    offset
                )

                payload[offset + 4] =
                    (callbackPort ushr 8)
                        .toByte()

                payload[offset + 5] =
                    (callbackPort and 0xFF)
                        .toByte()

                fileLog(
                    "PAYLOAD PATCHADO"
                )

                fileLog(
                    "IP inserido: $localIp"
                )

                fileLog(
                    "Porta inserida: $callbackPort"
                )

                fileLog(
                    "Porta HIGH: " +
                        ((callbackPort ushr 8) and 0xFF)
                )

                fileLog(
                    "Porta LOW: " +
                        (callbackPort and 0xFF)
                )

                fileLog(
                    "Bytes após patch: " +
                        payload[offset].toUByte().toString(16).padStart(2, '0') +
                        " " +
                        payload[offset + 1].toUByte().toString(16).padStart(2, '0') +
                        " " +
                        payload[offset + 2].toUByte().toString(16).padStart(2, '0') +
                        " " +
                        payload[offset + 3].toUByte().toString(16).padStart(2, '0') +
                        " " +
                        payload[offset + 4].toUByte().toString(16).padStart(2, '0') +
                        " " +
                        payload[offset + 5].toUByte().toString(16).padStart(2, '0')
                )

                fileLog(
                    "Payload preparado para envio."
                )

                // =====================================================
                // 6. ENVIA PARA BINLOADER
                // =====================================================

                try {

                    sendToBinLoader(
                        ps4Ip = ps4Ip,
                        payload = payload
                    )

                } catch (e: ConnectException) {

                    fileError(
                        "Não foi possível conectar ao BinLoader.",
                        e
                    )

                    return@withContext Result.failure(
                        Exception(
                            "Não foi possível conectar ao BinLoader " +
                                "$ps4Ip:$BINLOADER_PORT. " +
                                "Verifique se o GoldHEN > BinLoader está ativado.",
                            e
                        )
                    )

                } catch (e: SocketTimeoutException) {

                    fileError(
                        "Tempo limite ao conectar ao BinLoader.",
                        e
                    )

                    return@withContext Result.failure(
                        Exception(
                            "Tempo limite ao conectar ao BinLoader " +
                                "$ps4Ip:$BINLOADER_PORT.",
                            e
                        )
                    )

                } catch (e: Exception) {

                    fileError(
                        "Erro ao enviar payload ao PS4: ${e.message}",
                        e
                    )

                    return@withContext Result.failure(
                        Exception(
                            "Erro ao enviar payload ao PS4: ${e.message}",
                            e
                        )
                    )
                }

                fileLog(
                    "========================================"
                )

                fileLog(
                    "PAYLOAD ENVIADO AO BINLOADER"
                )

                fileLog(
                    "Destino: $ps4Ip:$BINLOADER_PORT"
                )

                fileLog(
                    "Tamanho: ${payload.size} bytes"
                )

                fileLog(
                    "========================================"
                )

                fileLog(
                    "Aguardando execução do payload..."
                )

                fileLog(
                    "Aguardando callback do PS4..."
                )

                // =====================================================
                // 7. AGUARDA CALLBACK
                // =====================================================

                try {

                    tempServer.accept().use { ps4Client ->

                        ps4Client.soTimeout =
                            10_000

                        val remoteAddress =
                            ps4Client
                                .inetAddress
                                ?.hostAddress
                                ?: "desconhecido"

                        fileLog(
                            "========================================"
                        )

                        fileLog(
                            "CALLBACK RECEBIDO!"
                        )

                        fileLog(
                            "IP remoto: $remoteAddress"
                        )

                        fileLog(
                            "Porta local: $callbackPort"
                        )

                        fileLog(
                            "========================================"
                        )

                        // =================================================
                        // 8. BUILD INFO
                        // =================================================

                        fileLog(
                            "Construindo buildInfo..."
                        )

                        val info =
                            buildDpiInfo(
                                url = manifestUrl,
                                title = itemTitle,
                                contentId = contentId,
                                category = category,
                                size = fileSize,
                                icon = iconBytes
                            )

                        fileLog(
                            "buildInfo criado: ${info.size} bytes"
                        )

                        fileLog(
                            "Enviando buildInfo ao PS4..."
                        )

                        val output =
                            ps4Client.getOutputStream()

                        output.write(
                            info
                        )

                        output.flush()

                        fileLog(
                            "buildInfo enviado com sucesso."
                        )
                    }

                } catch (e: SocketTimeoutException) {

                    fileError(
                        "CALLBACK NÃO RECEBIDO."
                    )

                    fileError(
                        "O payload foi enviado ao BinLoader, " +
                            "mas o PS4 não conectou de volta em " +
                            "${CALLBACK_TIMEOUT_MS}ms."
                    )

                    fileError(
                        "Callback esperado em: " +
                            "$localIp:$callbackPort"
                    )

                    return@withContext Result.failure(
                        Exception(
                            "O payload foi enviado ao BinLoader, " +
                                "mas o PS4 não retornou conexão para " +
                                "$localIp:$callbackPort.",
                            e
                        )
                    )
                }
            }

            fileLog(
                "========================================"
            )

            fileLog(
                "INJEÇÃO CONCLUÍDA"
            )

            fileLog(
                "========================================"
            )

            Result.success(true)

        } catch (e: Exception) {

            fileError(
                "FALHA GERAL NA INJEÇÃO: ${e.message}",
                e
            )

            Result.failure(e)
        }
    }

    /**
     * Envia o payload para o BinLoader.
     *
     * PRIMEIRA VERSÃO:
     *
     * Reproduz exatamente o comportamento do
     * HttpServer antigo:
     *
     * PS4:9090
     *
     * write()
     * flush()
     * shutdownOutput()
     * close()
     */
    private fun sendToBinLoader(
        ps4Ip: String,
        payload: ByteArray
    ) {

        fileLog(
            "========================================"
        )

        fileLog(
            "ENVIO PARA BINLOADER"
        )

        fileLog(
            "Destino: $ps4Ip:$BINLOADER_PORT"
        )

        fileLog(
            "Payload: ${payload.size} bytes"
        )

        fileLog(
            "Connect timeout: ${CONNECT_TIMEOUT_MS}ms"
        )

        fileLog(
            "========================================"
        )

        try {

            Socket().use { socket ->

                socket.tcpNoDelay =
                    true

                socket.keepAlive =
                    false

                socket.soTimeout =
                    8_000

                fileLog(
                    "Conectando ao BinLoader..."
                )

                socket.connect(
                    InetSocketAddress(
                        ps4Ip,
                        BINLOADER_PORT
                    ),
                    CONNECT_TIMEOUT_MS
                )

                fileLog(
                    "CONEXÃO COM BINLOADER ESTABELECIDA."
                )

                val output =
                    socket.getOutputStream()

                fileLog(
                    "Enviando ${payload.size} bytes..."
                )

                output.write(
                    payload
                )

                fileLog(
                    "write() concluído."
                )

                output.flush()

                fileLog(
                    "flush() concluído."
                )

                /*
                 * IMPORTANTE:
                 *
                 * O projeto antigo fazia shutdownOutput()
                 * depois do flush().
                 *
                 * Isso sinaliza ao receptor que não existem
                 * mais bytes sendo enviados nesta conexão.
                 */
                try {

                    socket.shutdownOutput()

                    fileLog(
                        "shutdownOutput() concluído."
                    )

                } catch (e: Exception) {

                    fileWarn(
                        "shutdownOutput() falhou: " +
                            "${e.message}"
                    )
                }

                fileLog(
                    "Payload entregue ao socket do BinLoader."
                )
            }

            fileLog(
                "Socket do BinLoader fechado."
            )

            fileLog(
                "Envio concluído sem erro."
            )

        } catch (e: ConnectException) {

            fileError(
                "CONEXÃO RECUSADA pelo BinLoader $ps4Ip:$BINLOADER_PORT",
                e
            )

            throw e

        } catch (e: SocketTimeoutException) {

            fileError(
                "TIMEOUT conectando ao BinLoader " +
                    "$ps4Ip:$BINLOADER_PORT",
                e
            )

            throw e

        } catch (e: Exception) {

            fileError(
                "ERRO durante envio ao BinLoader " +
                    "$ps4Ip:$BINLOADER_PORT",
                e
            )

            throw e
        }
    }

    /**
     * Estrutura de informações enviada pelo payload.
     *
     * Compatível com o protocolo do projeto antigo:
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
                    .order(
                        ByteOrder.LITTLE_ENDIAN
                    )
                    .putInt(value)
                    .array()
            )
        }

        fun i64(value: Long) {

            out.write(
                ByteBuffer
                    .allocate(8)
                    .order(
                        ByteOrder.LITTLE_ENDIAN
                    )
                    .putLong(value)
                    .array()
            )
        }

        fun str(value: String) {

            val bytes =
                value.toByteArray(
                    StandardCharsets.UTF_8
                )

            i32(
                bytes.size
            )

            out.write(
                bytes
            )
        }

        // =============================================================
        // VERSION
        // =============================================================

        i32(1)

        fileLog(
            "buildInfo.version = 1"
        )

        // =============================================================
        // MANIFEST URL
        // =============================================================

        str(url)

        fileLog(
            "buildInfo.manifestUrl = $url"
        )

        // =============================================================
        // TITLE
        // =============================================================

        str(title)

        fileLog(
            "buildInfo.title = $title"
        )

        // =============================================================
        // CONTENT ID
        // =============================================================

        str(contentId)

        fileLog(
            "buildInfo.contentId = $contentId"
        )

        // =============================================================
        // BGFT TYPE
        // =============================================================

        val bgftType =
            normalizeBgftType(
                category
            )

        fileLog(
            "BGFT Type enviado: $bgftType"
        )

        str(
            bgftType
        )

        // =============================================================
        // FILE SIZE
        // =============================================================

        i64(
            size
        )

        fileLog(
            "buildInfo.fileSize = $size"
        )

        // =============================================================
        // ICON
        // =============================================================

        if (
            icon == null ||
            icon.isEmpty()
        ) {

            i32(0)

            fileLog(
                "buildInfo.iconSize = 0"
            )

        } else {

            i32(
                icon.size
            )

            out.write(
                icon
            )

            fileLog(
                "buildInfo.iconSize = ${icon.size}"
            )
        }

        val result =
            out.toByteArray()

        fileLog(
            "buildInfo.totalSize = ${result.size}"
        )

        return result
    }

    /**
     * Normalização da categoria.
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

            var match =
                true

            for (
                j in pattern.indices
            ) {

                if (
                    data[i + j] !=
                    pattern[j]
                ) {

                    match =
                        false

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



