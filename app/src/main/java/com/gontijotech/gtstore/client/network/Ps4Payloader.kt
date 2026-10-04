package com.gontijotech.gtstore.client.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

class Ps4Payloader(private val context: Context) {

    // Placeholder padrão dentro do binário (caso o payload tenha uma string para substituição)
    private val URL_PLACEHOLDER = "__GTSTORE_PKG_URL_PLACEHOLDER____________________________________________________________________"

    /**
     * Injeta 100% via payload.bin na porta 9090 do PS4.
     * 
     * @param ps4Ip IP do console na rede local (ex: 192.168.1.15)
     * @param pkgUrl URL pública do jogo (ex: https://loja.gontijotech.com.br/download/jogo.pkg)
     */
    suspend fun injectBinaryPayload(ps4Ip: String, pkgUrl: String): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            var socket: Socket? = null
            var out: OutputStream? = null
            try {
                // 1. Carrega o payload.bin dos assets
                val rawBytes = readAssetBinary("payload.bin")
                    ?: return@withContext Result.failure(Exception("Arquivo payload.bin não encontrado em assets/"))

                // 2. Patcheia a URL dentro do binário se o placeholder existir
                val finalPayload = patchPayloadUrl(rawBytes, pkgUrl)

                // 3. Conecta no BinLoader do PS4 na porta 9090
                socket = Socket()
                socket.connect(InetSocketAddress(ps4Ip, 9090), 6000)
                out = socket.getOutputStream()

                // 4. Envia o binário bruto
                out.write(finalPayload)
                out.flush()

                Result.success(true)
            } catch (e: Exception) {
                Result.failure(Exception("Falha ao injetar payload.bin na porta 9090: ${e.message}"))
            } finally {
                try { out?.close() } catch (_: Exception) {}
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Lê o arquivo binário da pasta assets/
     */
    private fun readAssetBinary(fileName: String): ByteArray? {
        return try {
            context.assets.open(fileName).use { input ->
                val buffer = ByteArrayOutputStream()
                val data = ByteArray(4096)
                var count: Int
                while (input.read(data).also { count = it } != -1) {
                    buffer.write(data, 0, count)
                }
                buffer.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Substitui o marcador de texto no binário pela URL real do jogo (terminada em byte nulo \0)
     */
    private fun patchPayloadUrl(payload: ByteArray, url: String): ByteArray {
        val searchBytes = URL_PLACEHOLDER.toByteArray(StandardCharsets.US_ASCII)
        val index = indexOf(payload, searchBytes)

        if (index == -1) {
            // Se não tem placeholder, envia o binário original intocado
            return payload
        }

        val patched = payload.clone()
        val urlBytes = url.toByteArray(StandardCharsets.US_ASCII)

        // Limpa a área com zeros
        for (i in 0 until searchBytes.size) {
            patched[index + i] = 0
        }

        // Escreve a nova URL
        val lenToWrite = minOf(urlBytes.size, searchBytes.size - 1)
        System.arraycopy(urlBytes, 0, patched, index, lenToWrite)
        patched[index + lenToWrite] = 0 // Null-byte term

        return patched
    }

    private fun indexOf(source: ByteArray, target: ByteArray): Int {
        if (target.isEmpty() || source.size < target.size) return -1
        for (i in 0..source.size - target.size) {
            var found = true
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }
}
