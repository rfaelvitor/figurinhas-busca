package br.rafael.figurinhas

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

class MainActivity : ComponentActivity() {

    private val io = Executors.newFixedThreadPool(2)
    private val fila = ArrayDeque<String>()
    private var voltarDepois = false
    private var aoTerminar: (() -> Unit)? = null

    // estado que a tela observa
    val tela = mutableStateOf(Tela.Busca)
    val total = mutableIntStateOf(0)
    val enviando = mutableStateOf(false)
    val importando = mutableStateOf(false)
    val pacotesOk = mutableStateOf(false)
    val atalhoOk = mutableStateOf(false)
    val nova = mutableStateOf<Updater.Versao?>(null)
    val baixandoUpdate = mutableStateOf(false)
    val aviso = mutableStateOf<String?>(null)
    val recentes = mutableStateOf<List<String>>(emptyList())
    val sugestoes = mutableStateOf<List<String>>(emptyList())
    val autoOk = mutableStateOf(false)

    // usado pelo teste automatico no emulador pra abrir cada tela direto
    var demoPasso = 0
    var demoBusca = ""
    var demoAjustes = false
    var demoTexto = ""

    private val pedirPacote = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val extras = r.data?.extras?.keySet()?.joinToString { k -> "$k=${r.data?.extras?.get(k)}" } ?: "-"
        Store.log(this, "retorno add pacote res=${r.resultCode} extras=$extras")
        r.data?.getStringExtra("validation_error")?.let { aviso.value = "O WhatsApp recusou o pacote: $it" }
        proximoPacote()
    }

    private val abrirZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importar(it) } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        enableEdgeToEdge()
        intent.getStringExtra("demo_importar")?.let { importarDe(File(filesDir, it).inputStream()) }
        intent.getStringExtra("demo_recentes")?.split(",")?.reversed()?.forEach { Prefs.guardarBusca(this, it) }
        total.intValue = Store.figs(this).size
        recentes.value = Prefs.recentes(this)
        sugestoes.value = Store.sugestoes(this)
        pacotesOk.value = Prefs.pacoteNoWhatsApp(this, Store.PACK_EST) && Prefs.pacoteNoWhatsApp(this, Store.PACK_ANIM)
        // quem ja tinha tudo pronto antes dessa versao nao precisa passar pela configuracao
        if (!Prefs.configurado(this) && total.intValue > 0 && pacotesOk.value) Prefs.marcarConfigurado(this)
        tela.value = if (Prefs.configurado(this)) Tela.Busca else Tela.Configurar
        when (intent.getStringExtra("demo_tela")) {
            "configurar" -> { tela.value = Tela.Configurar; demoPasso = intent.getIntExtra("demo_passo", 0) }
            "sugerir" -> { tela.value = Tela.Sugerir; demoTexto = intent.getStringExtra("demo_texto") ?: "" }
            "busca" -> { tela.value = Tela.Busca; demoBusca = intent.getStringExtra("demo_q") ?: ""; demoAjustes = intent.getBooleanExtra("demo_ajustes", false) }
        }
        setContent { App(this) }
        if (intent.getStringExtra("demo_tela") == null) io.execute { Updater.buscarNova(this)?.let { nova.value = it } }
        // carrega os vetores de ia em segundo plano pra primeira busca nao travar
        io.execute { Vetores.carregar(this) }
    }

    override fun onResume() {
        super.onResume()
        pacotesOk.value = Prefs.pacoteNoWhatsApp(this, Store.PACK_EST) && Prefs.pacoteNoWhatsApp(this, Store.PACK_ANIM)
        autoOk.value = AutoConfirma.ativo(this)
    }

    fun limparHistorico() { Prefs.limparHistorico(this); recentes.value = emptyList() }

    fun abrirAcessibilidade() {
        Toast.makeText(this, "Procure \"Figurinhas: confirmar pacote\" e ative", Toast.LENGTH_LONG).show()
        startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun abrirInfoDoApp() {
        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    fun buscar(q: String): List<Fig> = Store.buscar(this, q)
    fun sugerir(texto: String, intencao: String?): List<Fig> = Store.sugerir(this, texto, intencao)
    fun colarTexto(): String = (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
        .primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""

    // salva a busca nos pacotes, pede pro whatsapp recarregar e volta pro chat
    fun enviar(q: String, resultado: List<Fig>, primeira: Fig?) {
        if (resultado.isEmpty() || enviando.value) return
        val res = if (primeira == null) resultado else listOf(primeira) + resultado.filter { it.n != primeira.n }
        enviando.value = true
        Prefs.guardarBusca(this, q); recentes.value = Prefs.recentes(this)
        io.execute {
            val mudou = Store.preencherPacotes(this, res)
            runOnUiThread {
                fila.clear(); fila += mudou
                voltarDepois = true
                aoTerminar = null
                proximoPacote()
            }
        }
    }

    fun adicionarPacotes(depois: () -> Unit) {
        fila.clear(); fila += listOf(Store.PACK_EST, Store.PACK_ANIM)
        voltarDepois = false
        aoTerminar = depois
        proximoPacote()
    }

    private fun proximoPacote() {
        val p = fila.removeFirstOrNull()
        if (p == null) {
            enviando.value = false
            pacotesOk.value = Prefs.pacoteNoWhatsApp(this, Store.PACK_EST) && Prefs.pacoteNoWhatsApp(this, Store.PACK_ANIM)
            aoTerminar?.invoke(); aoTerminar = null
            if (voltarDepois) voltarProWhatsApp()
            return
        }
        val i = Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK").apply {
            putExtra("sticker_pack_id", p); putExtra("sticker_pack_authority", Store.AUTH)
            putExtra("sticker_pack_name", if (p == Store.PACK_ANIM) "🔍 Busca GIF" else "🔍 Busca")
        }
        Store.log(this, "pedindo add/atualizar do pacote $p")
        AutoConfirma.pedidoEm = System.currentTimeMillis()
        try { pedirPacote.launch(i) } catch (e: Exception) {
            enviando.value = false
            aviso.value = "Não encontrei o WhatsApp neste celular."
        }
    }

    private fun voltarProWhatsApp() {
        packageManager.getLaunchIntentForPackage("com.whatsapp")?.let { startActivity(it) }
        finish()
    }

    fun escolherArquivo() = abrirZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))

    private fun importar(uri: Uri) {
        importando.value = true
        io.execute {
            try {
                importarDe(contentResolver.openInputStream(uri)!!)
                runOnUiThread { total.intValue = Store.figs(this).size; sugestoes.value = Store.sugestoes(this); importando.value = false }
            } catch (e: Exception) {
                runOnUiThread { importando.value = false; aviso.value = "Não deu pra ler esse arquivo. Confira se é o .zip de figurinhas." }
            }
        }
    }

    private fun importarDe(entrada: java.io.InputStream) {
        val dir = Store.dir(this); dir.mkdirs()
        entrada.use { ins ->
            ZipInputStream(ins).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    val nome = e.name.substringAfterLast('/')
                    val destino = if (e.name == "indice.json") File(filesDir, "indice.json") else File(dir, nome)
                    destino.outputStream().use { z.copyTo(it) }
                }
            }
        }
        Store.limparCache(); ImgCache.limpar()
        Store.gerarIcones(this)
        Store.preencherPacotes(this, Store.figs(this))
    }

    fun pedirAtalho() {
        if (Build.VERSION.SDK_INT >= 33) {
            val sbm = getSystemService(StatusBarManager::class.java)
            sbm.requestAddTileService(
                ComponentName(this, BuscaTile::class.java), "Buscar figurinha",
                Icon.createWithResource(this, R.drawable.ic_tile), mainExecutor
            ) { r -> atalhoOk.value = r == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || r == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED }
        } else {
            Toast.makeText(this, "Puxe a barra de notificações, toque no lápis e arraste \"Buscar figurinha\"", Toast.LENGTH_LONG).show()
            atalhoOk.value = true
        }
    }

    fun concluirConfiguracao() { Prefs.marcarConfigurado(this); tela.value = Tela.Busca }

    fun atualizarApp() {
        val v = nova.value ?: return
        if (!Updater.podeInstalar(this)) return
        baixandoUpdate.value = true
        io.execute {
            try { Updater.baixarEInstalar(this, v) } catch (e: Exception) {
                Store.log(this, "atualizador: erro ${e.message}")
                runOnUiThread { baixandoUpdate.value = false; aviso.value = "A atualização não baixou. Tente de novo com internet." }
            }
        }
    }

    fun diagnostico(): String = Store.lerLog(this)
}

enum class Tela { Configurar, Busca, Sugerir }
