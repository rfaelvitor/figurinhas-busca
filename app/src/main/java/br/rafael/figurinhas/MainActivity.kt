package br.rafael.figurinhas

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

class MainActivity : Activity() {

    private val io = Executors.newFixedThreadPool(3)
    private val ui = Handler(Looper.getMainLooper())
    private val cacheImg = LruCache<String, Drawable>(90)
    private var lista: List<Fig> = emptyList()

    private lateinit var busca: EditText
    private lateinit var status: TextView
    private lateinit var grade: GridView
    private val adapter = Grade()

    private val atualizarPacotes = Runnable { io.execute { Store.preencherPacotes(this, lista) } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val dp = resources.displayMetrics.density
        val raiz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), 0) }

        busca = EditText(this).apply {
            hint = "buscar figurinha (esqueleto, cala a boca…)"; textSize = 17f; setSingleLine()
        }
        val botoes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun botao(t: String, acao: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { acao() } }
            .also { botoes.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        botao("Importar") { escolherZip() }
        botao("Atualizar WA") { atualizarNoWhatsApp() }
        botao("Add pacote") { adicionar(Store.PACK_EST, "🔍 Busca") }
        botao("Add GIF") { adicionar(Store.PACK_ANIM, "🔍 Busca GIF") }
        botao("WhatsApp") { abrirWhatsApp() }
        botao("Diag") { diagnostico() }

        status = TextView(this).apply { setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt()); setTextColor(Color.GRAY) }
        grade = GridView(this).apply {
            numColumns = GridView.AUTO_FIT; columnWidth = (100 * dp).toInt(); stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = (6 * dp).toInt(); horizontalSpacing = (6 * dp).toInt(); adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> detalhe(lista[pos]) }
        }

        raiz.addView(busca); raiz.addView(botoes); raiz.addView(status)
        raiz.addView(grade, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(raiz)

        busca.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filtrar() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        filtrar()
    }

    private fun filtrar() {
        val q = busca.text.toString()
        io.execute {
            val r = Store.buscar(this, q)
            ui.post {
                lista = r
                adapter.notifyDataSetChanged()
                val total = Store.figs(this).size
                status.text = when {
                    total == 0 -> "Nenhum dado ainda. Toque em \"Importar dados\" e escolha o dados.zip."
                    q.isBlank() -> "$total favoritas. Digite pra buscar."
                    else -> "${r.size} resultados. Os primeiros já estão nos pacotes 🔍 Busca e 🔍 Busca GIF."
                }
                ui.removeCallbacks(atualizarPacotes)
                if (q.isNotBlank()) ui.postDelayed(atualizarPacotes, 500)
            }
        }
    }

    private fun detalhe(f: Fig) {
        AlertDialog.Builder(this)
            .setTitle("#${f.n}${if (f.animada) " (animada)" else ""}")
            .setMessage("Texto: ${f.texto.ifBlank { "(sem texto)" }}\n\nDescrição: ${f.tags}")
            .setPositiveButton("Ok", null).show()
    }

    private fun escolherZip() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        startActivityForResult(i, 10)
    }

    @Deprecated("api antiga mas resolve")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 10 && res == RESULT_OK) data?.data?.let { importar(it) }
        if (req in 200..202) {
            val extras = data?.extras?.keySet()?.joinToString { k -> "$k=${data.extras?.get(k)}" } ?: "-"
            Store.log(this, "retorno add pacote req=$req res=$res extras=$extras")
            if (res == RESULT_CANCELED && data?.getStringExtra("validation_error") != null) {
                status.text = "WhatsApp recusou o pacote: ${data.getStringExtra("validation_error")}"
            }
            // depois do pacote normal, pede o de gif
            if (req == 201) adicionar(Store.PACK_ANIM, "🔍 Busca GIF", 202)
        }
    }

    private fun importar(uri: android.net.Uri) {
        status.text = "Importando…"
        io.execute {
            try {
                val dir = Store.dir(this); dir.mkdirs()
                var n = 0
                contentResolver.openInputStream(uri)!!.use { ins ->
                    ZipInputStream(ins).use { z ->
                        while (true) {
                            val e = z.nextEntry ?: break
                            if (e.isDirectory) continue
                            val nome = e.name.substringAfterLast('/')
                            val destino = if (e.name == "indice.json") File(filesDir, "indice.json") else File(dir, nome)
                            destino.outputStream().use { z.copyTo(it) }
                            n++
                        }
                    }
                }
                Store.limparCache(); cacheImg.evictAll()
                Store.gerarIcones(this)
                Store.preencherPacotes(this, Store.figs(this))
                ui.post { Toast.makeText(this, "$n arquivos importados", Toast.LENGTH_SHORT).show(); filtrar() }
            } catch (e: Exception) {
                ui.post { status.text = "Erro ao importar: ${e.message}" }
            }
        }
    }

    // hipotese: o whatsapp so rele as figurinhas quando o pacote e "adicionado" de novo
    private fun atualizarNoWhatsApp() {
        ui.removeCallbacks(atualizarPacotes)
        io.execute {
            Store.preencherPacotes(this, lista)
            ui.post { adicionar(Store.PACK_EST, "🔍 Busca", 201) }
        }
    }

    private fun adicionar(id: String, nome: String, req: Int = 200) {
        val i = Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK").apply {
            putExtra("sticker_pack_id", id); putExtra("sticker_pack_authority", Store.AUTH); putExtra("sticker_pack_name", nome)
        }
        Store.log(this, "pedindo add/atualizar do pacote $id")
        try { startActivityForResult(i, req) } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "WhatsApp não encontrado", Toast.LENGTH_LONG).show()
        }
    }

    private fun diagnostico() {
        val txt = TextView(this).apply { text = Store.lerLog(this@MainActivity); textSize = 11f; setTextIsSelectable(true); setPadding(30, 20, 30, 20) }
        AlertDialog.Builder(this).setTitle("Diagnóstico (mais recente em cima)")
            .setView(ScrollView(this).apply { addView(txt) })
            .setPositiveButton("Fechar", null)
            .setNeutralButton("Copiar") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("diag", txt.text))
            }.show()
    }

    private fun abrirWhatsApp() {
        packageManager.getLaunchIntentForPackage("com.whatsapp")?.let { startActivity(it) }
    }

    inner class Grade : BaseAdapter() {
        override fun getCount() = lista.size
        override fun getItem(p: Int) = lista[p]
        override fun getItemId(p: Int) = lista[p].n.toLong()
        override fun getView(p: Int, conv: View?, parent: ViewGroup): View {
            val dp = resources.displayMetrics.density
            val iv = (conv as? ImageView) ?: ImageView(this@MainActivity).apply {
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (100 * dp).toInt())
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val f = lista[p]
            iv.tag = f.arquivo
            val c = cacheImg.get(f.arquivo)
            if (c != null) { iv.setImageDrawable(c); (c as? AnimatedImageDrawable)?.start(); return iv }
            iv.setImageDrawable(null)
            io.execute {
                try {
                    val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(Store.dir(this@MainActivity), f.arquivo))) { dec, _, _ ->
                        dec.setTargetSize(256, 256)
                    }
                    cacheImg.put(f.arquivo, d)
                    ui.post { if (iv.tag == f.arquivo) { iv.setImageDrawable(d); (d as? AnimatedImageDrawable)?.start() } }
                } catch (_: Exception) {}
            }
            return iv
        }
    }
}
