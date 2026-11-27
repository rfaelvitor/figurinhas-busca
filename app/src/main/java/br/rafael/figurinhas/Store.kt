package br.rafael.figurinhas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

data class Fig(val n: Int, val arquivo: String, val animada: Boolean, val texto: String, val tags: String) {
    val nt = Store.norm(texto)
    val ng = Store.norm(tags)
}

object Store {
    const val AUTH = "br.rafael.figurinhas.stickercontentprovider"
    const val PACK_EST = "busca"
    const val PACK_ANIM = "busca_gif"
    private const val MAX = 30

    @Volatile private var cache: List<Fig>? = null

    fun dir(ctx: Context) = File(ctx.filesDir, "stickers")
    fun estado(ctx: Context) = File(ctx.filesDir, "packs.json")
    fun tray(ctx: Context, pack: String) = File(ctx.filesDir, "tray_$pack.png")

    fun norm(s: String): String {
        val sem = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return sem.replace(Regex("[^a-z0-9 ?!]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    fun figs(ctx: Context): List<Fig> {
        cache?.let { return it }
        val f = File(ctx.filesDir, "indice.json")
        if (!f.exists()) return emptyList()
        val arr = JSONObject(f.readText()).getJSONArray("itens")
        val lista = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Fig(o.getInt("n"), o.getString("arquivo"), o.getBoolean("animada"), o.optString("texto"), o.optString("tags"))
        }
        cache = lista
        return lista
    }

    fun limparCache() { cache = null }

    private fun bigr(w: String): Set<String> = (0 until (w.length - 1).coerceAtLeast(0)).map { w.substring(it, it + 2) }.toSet()
    private fun dice(a: String, b: String): Double {
        if (a.length < 2 || b.length < 2) return if (a == b) 1.0 else 0.0
        val x = bigr(a); val y = bigr(b)
        return 2.0 * x.count { it in y } / (x.size + y.size)
    }
    private fun semLigacao(s: String) = " $s ".replace(Regex(" (a|o|e|de|da|do) "), " ").replace(" ", "")

    private fun nota(f: Fig, q: String): Double {
        val tudo = f.nt + " " + f.ng
        val palavras = tudo.split(" ")
        var s = 0.0
        if (f.nt.isNotEmpty() && f.nt.contains(q)) s += 120
        else if (tudo.contains(q)) s += 100
        else if (q.length > 3 && semLigacao(tudo).contains(semLigacao(q))) s += 90
        val termos = q.split(" ").filter { it.length > 1 }
        var acertos = 0
        for (t in termos) {
            val melhor = palavras.maxOfOrNull { w -> if (w.startsWith(t)) 1.0 else dice(t, w) } ?: 0.0
            if (melhor >= .7) { acertos++; s += melhor * 20 }
        }
        val n = termos.size.coerceAtLeast(1)
        if (s < 90 && acertos < Math.ceil(n * .66)) return 0.0
        return s
    }

    fun buscar(ctx: Context, consulta: String): List<Fig> {
        val todas = figs(ctx)
        val q = norm(consulta)
        if (q.isEmpty()) return todas
        val r = todas.map { it to nota(it, q) }.filter { it.second > 0 }.sortedByDescending { it.second }
        val topo = r.firstOrNull()?.second ?: return emptyList()
        return r.filter { it.second >= topo * .1 }.map { it.first }
    }

    // coloca os resultados nos dois pacotes e troca a versao pro whatsapp recarregar
    fun preencherPacotes(ctx: Context, resultado: List<Fig>) {
        val todas = figs(ctx)
        fun montar(anim: Boolean): List<String> {
            val r = resultado.filter { it.animada == anim }.take(MAX).map { it.arquivo }.toMutableList()
            // pacote precisa de no minimo 3
            for (f in todas) { if (r.size >= 3) break; if (f.animada == anim && f.arquivo !in r) r.add(f.arquivo) }
            return r
        }
        val antigo = lerEstado(ctx)
        val versao = (antigo.optInt("versao", 0) + 1)
        val o = JSONObject()
            .put("versao", versao)
            .put(PACK_EST, JSONArray(montar(false)))
            .put(PACK_ANIM, JSONArray(montar(true)))
        estado(ctx).writeText(o.toString())
        ctx.contentResolver.notifyChange(android.net.Uri.parse("content://$AUTH/metadata"), null)
    }

    fun lerEstado(ctx: Context): JSONObject {
        val f = estado(ctx)
        return if (f.exists()) JSONObject(f.readText()) else JSONObject()
    }

    fun arquivosDoPacote(ctx: Context, pack: String): List<String> {
        val a = lerEstado(ctx).optJSONArray(pack) ?: return emptyList()
        return (0 until a.length()).map { a.getString(it) }
    }

    fun gerarIcones(ctx: Context) {
        for ((pack, cor) in listOf(PACK_EST to "#0F8A4F", PACK_ANIM to "#7A3FD1")) {
            val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = Color.parseColor(cor); c.drawCircle(48f, 48f, 44f, p)
            p.color = Color.WHITE; p.textSize = 34f; p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true
            c.drawText(if (pack == PACK_EST) "🔍" else "GIF", 48f, 60f, p)
            tray(ctx, pack).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
