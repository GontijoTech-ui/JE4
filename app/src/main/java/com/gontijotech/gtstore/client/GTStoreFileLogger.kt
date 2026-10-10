package com.gontijotech.gtstore.client

import android.content.Context

/**
 * Versão definitiva (Produção): 
 * Criação de arquivos de log em disco desativada para poupar armazenamento.
 */
object GTStoreFileLogger {

    @Synchronized
    fun log(context: Context, tag: String, message: String) {
        // Ignorado na versão final. Nenhum arquivo será gerado.
    }

    @Synchronized
    fun clear(context: Context) {
        // Ignorado na versão final.
    }
}
