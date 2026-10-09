package com.gontijotech.gtstore.client.network

import android.content.Context
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

class Ps4Payloader(private val context: Context) {

    private val tag = "Ps4Payloader"

    // [Mantenha o seu método injectDpiPayload original da porta 9090 com metadados e ícone]

    /**
     * Testa se uma porta TCP está ativa e a escutar na PS4.
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

    /**
     * Injeta o binário puro do payload via BinLoader (portas 9090 ou 9020)
     * para inicializar o daemon HTTP da porta 12800 em segundo plano.
     */
    fun startRpiServerDaemon(ps4Ip: String): Result<Unit> {
        val binLoaderPorts = listOf(9090, 9020)

        for (port in binLoaderPorts) {
            try {
                Log.i(tag, "Porta 12800 fechada. A acordar via BinLoader na porta $port...")
                
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ps4Ip, port), 2500)

                    val payloadStream: InputStream = context.assets.open("payload")
                    val out: OutputStream = socket.getOutputStream()

                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    while (payloadStream.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                    }

                    out.flush()
                    payloadStream.close()
                }

                Log.i(tag, "Payload enviado para a porta $port. A aguardar inicialização do serviço...")
                
                // Intervalo para o daemon do RPI subir e abrir o socket 12800
                Thread.sleep(1800)

                if (isPortOpen(ps4Ip, 12800, 2000)) {
                    Log.i(tag, "Porta 12800 ativa com sucesso!")
                    return Result.success(Unit)
                }
            } catch (e: Exception) {
                Log.w(tag, "Falha na injeção via porta $port: ${e.message}")
            }
        }

        return Result.failure(Exception("Não foi possível inicializar a porta 12800 via BinLoader (9090/9020)."))
    }
}
