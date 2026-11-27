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
    @Volatile var ne: String? = null
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

    fun limparCache() { cache = null; Vetores.limpar() }

    // calibrado com as figurinhas reais (tools/avaliar_busca.py)
    var SEM_MIN = .45
    var SEM_PESO = 160.0
    val PARADAS = setOf("de", "da", "do", "um", "uma", "com", "to", "ta", "eu", "me", "que", "pra", "para", "ser", "tipo", "o", "a", "e", "no", "na", "em")

    @Volatile private var grupos: List<List<String>>? = null
    private var rotulos: List<String> = emptyList()

    private fun grupos(ctx: Context): List<List<String>> {
        grupos?.let { return it }
        val arr = JSONArray(ctx.assets.open("sinonimos.json").bufferedReader().readText())
        val g = (0 until arr.length()).map { i -> val a = arr.getJSONArray(i); (0 until a.length()).map { norm(a.getString(it)) } }
        rotulos = (0 until arr.length()).map { arr.getJSONArray(it).getString(0) }
        grupos = g
        return g
    }

    // palavras "parentes" das que ja estao na descricao, pra busca por sentido
    private fun expandir(ctx: Context, f: Fig): String {
        f.ne?.let { return it }
        val base = " ${f.nt} ${f.ng} "
        val extra = grupos(ctx).filter { g -> g.any { base.contains(" $it ") } }.flatten().toSet().joinToString(" ")
        f.ne = extra
        return extra
    }

    // o que a pessoa quer passar na resposta, com palavras que puxam o sentido certo
    val INTENCOES = linkedMapOf(
        "Ironia" to "ironia sarcasmo supostamente claro debochado desconfiado",
        "Rir junto" to "rindo risada kkkk engracado gargalhada",
        "Concordar" to "ok joinha positivo concordo certo isso",
        "Discordar" to "nao discordo negativo nao autorizo nem pensar",
        "Consolar" to "abraco carinho amor fofo triste consolo",
        "Zoar" to "zoando debochado lingua careta provocando",
        "Surpresa" to "surpreso chocado choque espantado minha nossa",
        "Preguiça" to "preguica cansado sono dormindo nao quero trabalhar",
        "Raiva" to "bravo raiva irritado puto nervoso",
        "Amor" to "amor coracao apaixonado carinho beijo",
        "Tanto faz" to "indiferente tanto faz nem ligo por mim sei la"
    )

    // sugere figurinhas pra responder uma conversa colada, puxando pro que a pessoa quer passar
    fun sugerir(ctx: Context, conversa: String, intencao: String?): List<Fig> {
        val todas = figs(ctx)
        if (todas.isEmpty() || !Vetores.carregar(ctx)) return emptyList()
        val vc = if (conversa.isBlank()) null else Vetores.vetorTexto(norm(conversa), PARADAS)
        val pal = intencao?.let { INTENCOES[it] }
        val vi = pal?.let { Vetores.vetorTexto(norm(it), PARADAS) }
        if (vc == null && vi == null) return emptyList()
        val dim = (vc ?: vi)!!.size
        val v = FloatArray(dim) { (vc?.get(it) ?: 0f) * .6f + (vi?.get(it) ?: 0f) }
        val n = kotlin.math.sqrt(v.fold(0f) { s, x -> s + x * x }); if (n > 0) for (i in v.indices) v[i] /= n
        val chaves = pal?.let { norm(it).split(" ").toSet() } ?: emptySet()
        return todas.map { f ->
            var s = Vetores.vetorFig(f)?.let { Vetores.cos(v, it) } ?: 0f
            // bonus quando a descricao ja fala exatamente do sentimento escolhido
            if (chaves.isNotEmpty()) { val b = " ${f.nt} ${f.ng} ${expandir(ctx, f)} "; if (chaves.any { b.contains(" $it ") }) s += .15f }
            f to s
        }.sortedByDescending { it.second }.take(24).filter { it.second > .2f }.map { it.first }
    }

    // temas mais comuns nas figurinhas da pessoa viram sugestao de busca
    fun sugestoes(ctx: Context): List<String> {
        val figs = figs(ctx)
        if (figs.isEmpty()) return emptyList()
        val gs = grupos(ctx)
        return gs.mapIndexed { i, g -> rotulos[i] to figs.count { f -> val b = " ${f.nt} ${f.ng} "; g.any { b.contains(" $it ") } } }
            .filter { it.second >= 2 }.sortedByDescending { it.second }.take(10).map { it.first }
    }

    private fun alternativas(ctx: Context, t: String): Set<String> {
        val r = mutableSetOf(t)
        for (g in grupos(ctx)) if (g.any { it == t || (t.length >= 4 && it.startsWith(t)) || dice(t, it) >= .8 }) r += g
        return r
    }

    private fun bigr(w: String): Set<String> = (0 until (w.length - 1).coerceAtLeast(0)).map { w.substring(it, it + 2) }.toSet()
    private fun dice(a: String, b: String): Double {
        if (a.length < 2 || b.length < 2) return if (a == b) 1.0 else 0.0
        val x = bigr(a); val y = bigr(b)
        return 2.0 * x.count { it in y } / (x.size + y.size)
    }
    private fun semLigacao(s: String) = " $s ".replace(Regex(" (a|o|e|de|da|do) "), " ").replace(" ", "")

    private fun nota(ctx: Context, f: Fig, q: String): Double {
        val tudo = f.nt + " " + f.ng
        val ext = " " + expandir(ctx, f) + " "
        val palavras = tudo.split(" ")
        var s = 0.0
        if (f.nt.isNotEmpty() && f.nt.contains(q)) s += 120
        else if (tudo.contains(q)) s += 100
        else if (q.length > 3 && semLigacao(tudo).contains(semLigacao(q))) s += 90
        else if (ext.contains(" $q ")) s += 60
        val termos = q.split(" ").filter { it.length > 1 && it !in PARADAS }
        var acertos = 0
        for (t in termos) {
            val melhor = palavras.maxOfOrNull { w -> if (w.startsWith(t)) 1.0 else dice(t, w) } ?: 0.0
            if (melhor >= .7) { acertos++; s += melhor * 20; continue }
            // nao achou a palavra, tenta pelos sinonimos
            if (alternativas(ctx, t).any { a -> a != t && (" $tudo ".contains(" $a") || ext.contains(" $a ")) }) { acertos++; s += 12 }
        }
        val n = termos.size.coerceAtLeast(1)
        if (s < 60 && acertos < Math.ceil(n * .66)) return 0.0
        return s
    }

    fun buscar(ctx: Context, consulta: String): List<Fig> {
        val todas = figs(ctx)
        val q = norm(consulta)
        if (q.isEmpty()) return todas
        // parte de sentido (ia): parecido mesmo sem palavra em comum
        val vq = if (Vetores.carregar(ctx)) Vetores.vetorTexto(q, PARADAS) else null
        val r = todas.map { f ->
            var s = nota(ctx, f, q)
            if (vq != null) {
                val vf = Vetores.vetorFig(f)
                if (vf != null) { val c = Vetores.cos(vq, vf); if (c > SEM_MIN) s += (c - SEM_MIN) * SEM_PESO }
            }
            f to s
        }.filter { it.second > 0 }.sortedByDescending { it.second }
        val topo = r.firstOrNull()?.second ?: return emptyList()
        return r.filter { it.second >= topo * .15 }.map { it.first }
    }

    // coloca os resultados nos dois pacotes e troca a versao pro whatsapp recarregar
    // devolve quais pacotes mudaram
    fun preencherPacotes(ctx: Context, resultado: List<Fig>): List<String> {
        val todas = figs(ctx)
        fun montar(anim: Boolean): List<String> {
            val r = resultado.filter { it.animada == anim }.take(MAX).map { it.arquivo }.toMutableList()
            // pacote precisa de no minimo 3, completa com figurinha vazia (ou favorita se o zip for antigo)
            for (k in 1..3) { if (r.size >= 3) break; val v = "vazio_${if (anim) "a" else "s"}$k.webp"; if (File(dir(ctx), v).exists()) r.add(v) }
            for (f in todas) { if (r.size >= 3) break; if (f.animada == anim && f.arquivo !in r) r.add(f.arquivo) }
            return r
        }
        val antigo = lerEstado(ctx)
        val o = JSONObject()
        val mudou = mutableListOf<String>()
        for ((pack, anim) in listOf(PACK_EST to false, PACK_ANIM to true)) {
            val novo = montar(anim)
            val velho = arquivosDoPacote(ctx, pack)
            // so troca a versao do pacote que mudou
            if (novo != velho) mudou += pack
            val v = antigo.optInt("v_$pack", 0) + if (novo != velho) 1 else 0
            o.put(pack, JSONArray(novo)).put("v_$pack", v)
        }
        estado(ctx).writeText(o.toString())
        log(ctx, "pacotes atualizados: ${resultado.size} resultados, v_busca=${o.getInt("v_$PACK_EST")} v_gif=${o.getInt("v_$PACK_ANIM")}")
        ctx.contentResolver.notifyChange(android.net.Uri.parse("content://$AUTH/metadata"), null)
        return mudou
    }

    fun versao(ctx: Context, pack: String) = lerEstado(ctx).optInt("v_$pack", 0)

    private val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    @Synchronized fun log(ctx: Context, msg: String) {
        val f = File(ctx.filesDir, "diag.log")
        val linhas = (if (f.exists()) f.readLines() else emptyList()).takeLast(150) + "${fmt.format(java.util.Date())} $msg"
        f.writeText(linhas.joinToString("\n"))
    }
    fun lerLog(ctx: Context): String {
        val f = File(ctx.filesDir, "diag.log")
        return if (f.exists()) f.readLines().reversed().joinToString("\n") else "(vazio)"
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
