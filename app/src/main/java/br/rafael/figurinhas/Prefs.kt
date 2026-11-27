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
