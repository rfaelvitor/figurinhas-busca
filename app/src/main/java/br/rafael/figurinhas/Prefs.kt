package br.rafael.figurinhas

import android.content.Context
import android.net.Uri

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("figurinhas", Context.MODE_PRIVATE)

    fun configurado(ctx: Context) = sp(ctx).getBoolean("configurado", false)
    fun marcarConfigurado(ctx: Context) = sp(ctx).edit().putBoolean("configurado", true).apply()

    fun recentes(ctx: Context): List<String> =
        sp(ctx).getString("recentes", "")!!.split("\n").filter { it.isNotBlank() }

    fun guardarBusca(ctx: Context, q: String) {
        val t = q.trim()
        if (t.length < 2) return
        val nova = (listOf(t) + recentes(ctx).filter { !it.equals(t, true) }).take(8)
        sp(ctx).edit().putString("recentes", nova.joinToString("\n")).apply()
    }

    // figurinhas mais mandadas pelo app
    fun registrarUso(ctx: Context, arquivo: String) {
        val o = org.json.JSONObject(sp(ctx).getString("usos", "{}")!!)
        o.put(arquivo, o.optInt(arquivo, 0) + 1)
        sp(ctx).edit().putString("usos", o.toString()).apply()
    }
    fun maisUsadas(ctx: Context): List<String> {
        val o = org.json.JSONObject(sp(ctx).getString("usos", "{}")!!)
        return o.keys().asSequence().map { it to o.getInt(it) }.sortedByDescending { it.second }.map { it.first }.toList()
    }

    // buscas que nao acharam nada, so no celular, pra melhorar o dicionario
    fun registrarSemResultado(ctx: Context, q: String) {
        val l = (listOf(q.trim()) + semResultado(ctx).filter { !it.equals(q.trim(), true) }).take(30)
        sp(ctx).edit().putString("sem_resultado", l.joinToString("\n")).apply()
    }
    fun semResultado(ctx: Context): List<String> = sp(ctx).getString("sem_resultado", "")!!.split("\n").filter { it.isNotBlank() }

    fun ultimaSync(ctx: Context) = sp(ctx).getLong("ultima_sync", 0)
    fun marcarSync(ctx: Context) = sp(ctx).edit().putLong("ultima_sync", System.currentTimeMillis()).apply()
    fun meuNumero(ctx: Context) = sp(ctx).getString("meu_numero", "")!!
    fun salvarNumero(ctx: Context, n: String) = sp(ctx).edit().putString("meu_numero", n).apply()
    fun conectado(ctx: Context) = sp(ctx).getBoolean("conectado", false)
    fun marcarConectado(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("conectado", v).apply()

    fun limparHistorico(ctx: Context) = sp(ctx).edit().remove("recentes").apply()

    // o whatsapp responde se o pacote ja foi adicionado
    fun pacoteNoWhatsApp(ctx: Context, id: String): Boolean = try {
        val uri = Uri.parse("content://com.whatsapp.provider.sticker_whitelist_check/is_whitelisted").buildUpon()
            .appendQueryParameter("authority", Store.AUTH).appendQueryParameter("identifier", id).build()
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow("result")) == 1
        } ?: false
    } catch (e: Exception) { false }
}
