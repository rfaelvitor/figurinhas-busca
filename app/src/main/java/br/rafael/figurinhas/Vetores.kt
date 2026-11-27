package br.rafael.figurinhas

import android.content.Context
import java.io.BufferedInputStream
import java.io.DataInputStream
import kotlin.math.sqrt

// vetores de palavras gerados no build com um modelo de IA de frases
// a busca compara o sentido da pesquisa com o sentido da descricao de cada figurinha
object Vetores {
    private var indice: HashMap<String, Int>? = null
    private var porPrefixo: HashMap<String, MutableList<Int>> = HashMap()
    private lateinit var palavras: Array<String>
    private lateinit var escala: FloatArray
    private lateinit var dados: ByteArray
    private var dim = 0
    private val cacheFig = HashMap<String, FloatArray?>()

    @Synchronized fun carregar(ctx: Context): Boolean {
        if (indice != null) return true
        return try {
            DataInputStream(BufferedInputStream(ctx.assets.open("vetores.bin"), 1 shl 16)).use { ins ->
                val mag = ByteArray(4); ins.readFully(mag)
                val n = ins.readInt(); dim = ins.readInt()
                palavras = Array(n) { val b = ByteArray(ins.readUnsignedShort()); ins.readFully(b); String(b, Charsets.UTF_8) }
                escala = FloatArray(n) { ins.readFloat() }
                dados = ByteArray(n * dim); ins.readFully(dados)
            }
            val m = HashMap<String, Int>(palavras.size * 2)
            palavras.forEachIndexed { i, p -> m[p] = i; if (p.length >= 3) porPrefixo.getOrPut(p.take(3)) { mutableListOf() }.add(i) }
            indice = m
            true
        } catch (e: Exception) { false }
    }

    private fun linha(i: Int): FloatArray = FloatArray(dim) { dados[i * dim + it] * escala[i] }

    private fun achar(p: String): Int? {
        val m = indice ?: return null
        m[p]?.let { return it }
        for (s in listOf("s", "es", "inho", "inha", "zinho")) if (p.endsWith(s)) m[p.removeSuffix(s)]?.let { return it }
        // erro de digitacao: palavra mais parecida com o mesmo comeco
        val cand = porPrefixo[p.take(3)] ?: return null
        var melhor = -1; var nota = 0.0
        for (i in cand) { val d = parecido(p, palavras[i]); if (d > nota) { nota = d; melhor = i } }
        return if (nota >= .75) melhor else null
    }

    private fun parecido(a: String, b: String): Double {
        if (a.length < 2 || b.length < 2) return 0.0
        val x = (0 until a.length - 1).map { a.substring(it, it + 2) }.toSet()
        val y = (0 until b.length - 1).map { b.substring(it, it + 2) }.toSet()
        return 2.0 * x.count { it in y } / (x.size + y.size)
    }

    fun vetorTexto(texto: String, ignorar: Set<String> = emptySet()): FloatArray? {
        if (indice == null) return null
        val soma = FloatArray(dim); var k = 0
        for (t in texto.split(" ")) {
            if (t.length < 2 || t in ignorar) continue
            val i = achar(t) ?: continue
            // palavra rara pesa mais que palavra comum (hoje, bora, vamo)
            val w = (kotlin.math.ln(i + 2.0) / kotlin.math.ln(palavras.size.toDouble())).toFloat()
            val v = linha(i); for (j in 0 until dim) soma[j] += v[j] * w; k++
        }
        if (k == 0) return null
        var n = 0f; for (v in soma) n += v * v
        n = sqrt(n); if (n == 0f) return null
        for (j in 0 until dim) soma[j] /= n
        return soma
    }

    @Synchronized fun vetorFig(f: Fig): FloatArray? =
        cacheFig.getOrPut(f.arquivo) { vetorTexto(f.nt + " " + f.ng) }

    fun cos(a: FloatArray, b: FloatArray): Float { var s = 0f; for (j in a.indices) s += a[j] * b[j]; return s }

    @Synchronized fun limpar() = cacheFig.clear()
}
