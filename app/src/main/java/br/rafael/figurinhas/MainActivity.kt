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
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

class MainActivity : Activity() {

    private val io = Executors.newFixedThreadPool(3)
    private val ui = Handler(Looper.getMainLooper())
    private val cacheImg = LruCache<String, Drawable>(90)
    private var lista: List<Fig> = emptyList()
    private val fila = ArrayDeque<String>()

    private lateinit var busca: EditText
    private lateinit var status: TextView
    private lateinit var grade: GridView
    private lateinit var enviarBtn: Button
    private lateinit var aviso: Button
    private var nova: Updater.Versao? = null
    private var checou = false
    private val adapter = Grade()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val raiz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), px(12), px(12), 0) }

        aviso = Button(this).apply {
            isAllCaps = false; visibility = View.GONE
            setBackgroundColor(Color.parseColor("#0F8A4F")); setTextColor(Color.WHITE)
            setOnClickListener { atualizarApp() }
        }
        busca = EditText(this).apply {
            hint = "buscar figurinha (esqueleto, cala a boca…)"; textSize = 18f; setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, acao, ev ->
                if (acao == EditorInfo.IME_ACTION_SEND || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { enviar(null); true } else false
            }
        }
        enviarBtn = Button(this).apply {
            text = "Mandar pro WhatsApp"; isAllCaps = false; textSize = 16f
            setBackgroundColor(Color.parseColor("#25D366")); setTextColor(Color.parseColor("#0B2E1A"))
            setOnClickListener { enviar(null) }
        }
        status = TextView(this).apply { setPadding(0, px(6), 0, px(6)); setTextColor(Color.GRAY) }
        grade = GridView(this).apply {
            numColumns = GridView.AUTO_FIT; columnWidth = px(100); stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = px(6); horizontalSpacing = px(6); adapter = this@MainActivity.adapter
            // tocar numa figurinha manda ela como a primeira do pacote
            setOnItemClickListener { _, _, pos, _ -> enviar(lista[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> detalhe(lista[pos]); true }
        }
        val rodape = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun mini(t: String, acao: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; textSize = 12f; setOnClickListener { acao() } }
            .also { rodape.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        mini("Importar dados") { escolherZip() }
        mini("Adicionar pacotes") { fila.clear(); fila += listOf(Store.PACK_EST, Store.PACK_ANIM); proximoPacote(false) }
        mini("Diagnóstico") { diagnostico() }

        raiz.addView(aviso); raiz.addView(busca); raiz.addView(enviarBtn); raiz.addView(status)
        raiz.addView(grade, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        raiz.addView(rodape)
        setContentView(raiz)

        busca.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filtrar() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        filtrar()
    }

    override fun onResume() {
        super.onResume()
        busca.requestFocus()
        if (checou) return
        checou = true
        io.execute {
            val v = Updater.buscarNova(this) ?: return@execute
            nova = v
            ui.post { aviso.text = "Nova versão disponível (${v.numero}). Toque pra atualizar"; aviso.visibility = View.VISIBLE }
        }
    }

    private fun filtrar() {
        val q = busca.text.toString()
        io.execute {
            val r = Store.buscar(this, q)
            ui.post {
                if (q != busca.text.toString()) return@post
                lista = r
                adapter.notifyDataSetChanged()
                val total = Store.figs(this).size
                status.text = when {
                    total == 0 -> "Nenhum dado ainda. Toque em \"Importar dados\" e escolha o dados.zip."
                    q.isBlank() -> "$total favoritas. Digite pra buscar."
                    r.isEmpty() -> "Nada encontrado."
                    else -> "${r.size} resultado${if (r.size == 1) "" else "s"}. Toque numa figurinha ou em Mandar pro WhatsApp."
                }
            }
        }
    }

    // salva a busca nos pacotes, pede pro whatsapp recarregar e volta pro chat
    private fun enviar(primeira: Fig?) {
        val res = if (primeira == null) lista else listOf(primeira) + lista.filter { it.n != primeira.n }
        if (res.isEmpty()) { Toast.makeText(this, "Nenhuma figurinha pra mandar", Toast.LENGTH_SHORT).show(); return }
        enviarBtn.isEnabled = false
        io.execute {
            val mudou = Store.preencherPacotes(this, res)
            ui.post {
                fila.clear(); fila += mudou
                proximoPacote(true)
            }
        }
    }

    private fun proximoPacote(voltar: Boolean) {
        val p = fila.removeFirstOrNull()
        if (p == null) {
            enviarBtn.isEnabled = true
            if (voltar) voltarProWhatsApp()
            return
        }
        val i = Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK").apply {
            putExtra("sticker_pack_id", p); putExtra("sticker_pack_authority", Store.AUTH)
            putExtra("sticker_pack_name", if (p == Store.PACK_ANIM) "🔍 Busca GIF" else "🔍 Busca")
        }
        Store.log(this, "pedindo add/atualizar do pacote $p")
        try { startActivityForResult(i, if (voltar) 300 else 200) } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "WhatsApp não encontrado", Toast.LENGTH_LONG).show(); enviarBtn.isEnabled = true
        }
    }

    private fun voltarProWhatsApp() {
        packageManager.getLaunchIntentForPackage("com.whatsapp")?.let { startActivity(it) }
        finish()
    }

    @Deprecated("api antiga mas resolve")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 10 && res == RESULT_OK) { data?.data?.let { importar(it) }; return }
        if (req == 200 || req == 300) {
            val extras = data?.extras?.keySet()?.joinToString { k -> "$k=${data.extras?.get(k)}" } ?: "-"
            Store.log(this, "retorno add pacote req=$req res=$res extras=$extras")
            data?.getStringExtra("validation_error")?.let { status.text = "WhatsApp recusou o pacote: $it" }
            proximoPacote(req == 300)
        }
    }

    private fun detalhe(f: Fig) {
        AlertDialog.Builder(this)
            .setTitle("#${f.n}${if (f.animada) " (animada)" else ""}")
            .setMessage("Texto: ${f.texto.ifBlank { "(sem texto)" }}\n\nDescrição: ${f.tags}")
            .setPositiveButton("Ok", null).show()
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

    private fun atualizarApp() {
        val v = nova ?: return
        if (!Updater.podeInstalar(this)) return
        aviso.text = "Baixando atualização…"; aviso.isEnabled = false
        io.execute {
            try { Updater.baixarEInstalar(this, v) } catch (e: Exception) {
                Store.log(this, "atualizador: erro ${e.message}")
                ui.post { aviso.text = "Falhou, toque pra tentar de novo"; aviso.isEnabled = true }
            }
        }
    }

    private fun escolherZip() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        startActivityForResult(i, 10)
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
