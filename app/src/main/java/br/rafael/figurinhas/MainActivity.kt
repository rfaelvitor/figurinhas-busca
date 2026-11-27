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
    val maisUsadas = mutableStateOf<List<Fig>>(emptyList())
    val semResultado = mutableStateOf<List<String>>(emptyList())
    val conectado = mutableStateOf(false)
    lateinit var sync: Sync
    var telaAntesDeConectar = Tela.Busca

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

    // figurinha com som escolhida: pergunta se manda sem som ou se abre as favoritas do whatsapp
    val escolhaSom = mutableStateOf<Triple<String, List<Fig>, Fig>?>(null)
    // aviso rapido no topo com o resultado da sincronizacao
    val resultadoSync = mutableStateOf<Pair<String, Boolean>?>(null)

    private val pedirMicrofone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        Store.log(this, "permissao de audio: $ok")
        if (ok) Ouvir.pendentes(this)
    }

    fun pedirTranscricao() {
        if (!Ouvir.podeTranscrever(this) || Ouvir.faltando(this).isEmpty()) return
        if (Ouvir.temPermissao(this)) Ouvir.pendentes(this)
        else if (!getSharedPreferences("p", MODE_PRIVATE).getBoolean("pediu_mic", false)) {
            getSharedPreferences("p", MODE_PRIVATE).edit().putBoolean("pediu_mic", true).apply()
            aviso.value = "Algumas favoritas têm som. Pra busca achar pelo que é falado nelas, o Android vai pedir a permissão de áudio. O app não usa o microfone, só lê o som das figurinhas."
            pedirMicAoFecharAviso = true
        }
    }
    var pedirMicAoFecharAviso = false
    fun avisoFechado() { if (pedirMicAoFecharAviso) { pedirMicAoFecharAviso = false; pedirMicrofone.launch(android.Manifest.permission.RECORD_AUDIO) } }

    private val abrirZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importar(it) } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        enableEdgeToEdge()
        intent.getStringExtra("demo_importar")?.let { importarDe(File(filesDir, it).inputStream()) }
        intent.getStringExtra("demo_recentes")?.replace('_', ' ')?.split(",")?.reversed()?.forEach { Prefs.guardarBusca(this, it) }
        sync = Sync(this)
        sync.aoTerminar = {
            atualizarListas()
            val erro = sync.estado.value == Sync.Estado.Erro
            val mudou = sync.novas.intValue > 0 || sync.removidas.intValue > 0
            if (sync.manual || erro || mudou) resultadoSync.value = sync.mensagem.value.ifBlank { if (erro) "A sincronização parou." else "Favoritas em dia." } to erro
            sync.manual = false
            pedirTranscricao()
        }
        conectado.value = Prefs.conectado(this)
        lixeira.intValue = Store.naLixeira(this)
        total.intValue = Store.figs(this).size
        recentes.value = Prefs.recentes(this)
        sugestoes.value = Store.INTENCOES.keys.toList()
        pacotesOk.value = Prefs.pacoteNoWhatsApp(this, Store.PACK_EST) && Prefs.pacoteNoWhatsApp(this, Store.PACK_ANIM)
        // quem ja tinha tudo pronto antes dessa versao nao precisa passar pela configuracao
        if (!Prefs.configurado(this) && total.intValue > 0 && pacotesOk.value) Prefs.marcarConfigurado(this)
        tela.value = if (Prefs.configurado(this)) Tela.Busca else Tela.Configurar
        when (intent.getStringExtra("demo_tela")) {
            "configurar" -> { tela.value = Tela.Configurar; demoPasso = intent.getIntExtra("demo_passo", 0) }
            "conectar" -> { tela.value = Tela.Conectar; sync.conectar() }
            "sugerir" -> { tela.value = Tela.Sugerir; demoTexto = intent.getStringExtra("demo_texto")?.replace('_', ' ') ?: "" }
            "busca" -> { tela.value = Tela.Busca; demoBusca = intent.getStringExtra("demo_q")?.replace('_', ' ') ?: ""; demoAjustes = intent.getBooleanExtra("demo_ajustes", false) }
        }
        // telas de teste no emulador: espera da sincronizacao e escolha de figurinha com som
        when (intent.getStringExtra("demo_sync")) {
            "copiando" -> { sync.manual = true; sync.estado.value = Sync.Estado.Sincronizando; sync.total.intValue = 48; sync.feitas.intValue = 17; sync.mensagem.value = "Copiando favoritas…" }
            "esperando" -> { sync.manual = true; sync.estado.value = Sync.Estado.Sincronizando; sync.esperandoFavoritar.value = true; sync.mensagem.value = "Esperando suas favoritas chegarem…" }
            "fim" -> resultadoSync.value = "3 novas, 1 removida." to false
        }
        if (intent.getBooleanExtra("demo_som", false)) Store.figs(this).firstOrNull { it.animada }?.let { escolhaSom.value = Triple("", listOf(it), it) }
        setContent { App(this) }
        if (intent.getStringExtra("demo_tela") == null) io.execute { Updater.buscarNova(this)?.let { nova.value = it } }
        // carrega os vetores de ia em segundo plano pra primeira busca nao travar
        io.execute { Vetores.carregar(this) }
    }

    override fun onResume() {
        super.onResume()
        pacotesOk.value = Prefs.pacoteNoWhatsApp(this, Store.PACK_EST) && Prefs.pacoteNoWhatsApp(this, Store.PACK_ANIM)
        autoOk.value = AutoConfirma.ativo(this)
        atualizarListas()
        // sincroniza sozinho se faz mais de 6 horas, sem atrapalhar a busca
        if (conectado.value && tela.value == Tela.Busca && !sync.rodando() &&
            System.currentTimeMillis() - Prefs.ultimaSync(this) > 6 * 3600_000L) { sync.manual = false; sync.sincronizar() }
    }

    fun atualizarListas() {
        total.intValue = Store.figs(this).size
        sugestoes.value = Store.INTENCOES.keys.toList()
        semResultado.value = Prefs.semResultado(this)
        conectado.value = Prefs.conectado(this)
        lixeira.intValue = Store.naLixeira(this)
        val porArquivo = Store.figs(this).associateBy { it.arquivo }
        maisUsadas.value = Prefs.maisUsadas(this).mapNotNull { porArquivo[it] }.take(12)
        if (sync.estado.value == Sync.Estado.Pronto) io.execute { Store.preencherPacotes(this, Store.figs(this)) }
    }

    fun abrirConectar() {
        if (tela.value != Tela.Conectar) telaAntesDeConectar = tela.value
        tela.value = Tela.Conectar
        sync.conectar()
    }

    fun sairDeConectar() { tela.value = telaAntesDeConectar }

    fun sincronizarAgora() { if (!sync.rodando()) { sync.manual = true; sync.sincronizar() } else sync.manual = true }

    fun desconectarWhatsApp() { sync.desconectar(); conectado.value = false }

    fun registrarSemResultado(q: String) { Prefs.registrarSemResultado(this, q); semResultado.value = Prefs.semResultado(this) }

    fun abrirWhatsAppSo() { packageManager.getLaunchIntentForPackage("com.whatsapp")?.let { startActivity(it) } }

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
    val enviandoFig = mutableStateOf<Fig?>(null)

    fun enviar(q: String, resultado: List<Fig>, primeira: Fig?, conversa: Boolean = false, semSom: Boolean = false) {
        if (enviando.value) return
        if (primeira != null && !semSom && Store.temSom(this, primeira)) { escolhaSom.value = Triple(q, resultado, primeira); return }
        if (resultado.isEmpty() && primeira == null && q.isBlank()) return
        primeira?.let { Prefs.registrarUso(this, it.arquivo) }
        enviando.value = true
        enviandoFig.value = primeira ?: resultado.firstOrNull()
        if (!conversa) { Prefs.guardarBusca(this, q); recentes.value = Prefs.recentes(this) }
        io.execute {
            // refaz a busca na hora: se o enter vier antes da tela atualizar, nao manda o resultado antigo
            val base = if (!conversa && q.isNotBlank()) Store.buscar(this, q) else resultado
            val res = if (primeira == null) base else listOf(primeira) + base.filter { it.n != primeira.n }
            if (res.isEmpty()) { runOnUiThread { enviando.value = false; enviandoFig.value = null; aviso.value = "Nada encontrado com \"$q\"." }; return@execute }
            if (enviandoFig.value == null) runOnUiThread { enviandoFig.value = res.first() }
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
            enviandoFig.value = null
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

    fun abrirWhatsApp() = voltarProWhatsApp()
    // abre o whatsapp sem fechar o app, pra sincronizacao continuar rodando
    fun espiarWhatsApp() { packageManager.getLaunchIntentForPackage("com.whatsapp")?.let { startActivity(it) } }

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
                runOnUiThread { atualizarListas(); importando.value = false }
            } catch (e: Exception) {
                runOnUiThread { importando.value = false; aviso.value = "Não deu pra ler esse arquivo. Confira se é o .zip de figurinhas." }
            }
        }
    }

    private fun importarDe(entrada: java.io.InputStream) {
        val dir = Store.dir(this); dir.mkdirs()
        val antes = Store.figs(this)
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
        // o zip traz o indice dele: mantem as que ja estavam no app e nao vieram no zip
        val novas = Store.figs(this)
        val faltam = antes.filter { f -> novas.none { it.arquivo == f.arquivo || (f.id.isNotBlank() && it.id == f.id) } && File(dir, f.arquivo).exists() }
        if (faltam.isNotEmpty()) {
            var prox = (novas.maxOfOrNull { it.n } ?: 0) + 1
            Store.salvarIndice(this, novas + faltam.map { it.copy(n = prox++) })
        }
        Store.log(this, "importou: ${novas.size} do arquivo, ${faltam.size} mantidas")
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

    val lixeira = mutableIntStateOf(0)
    fun restaurarRemovidas() {
        io.execute {
            val n = Store.restaurarLixeira(this)
            runOnUiThread { atualizarListas(); aviso.value = if (n > 0) "$n figurinha${if (n == 1) "" else "s"} de volta." else "Nada pra restaurar." }
        }
    }

    fun diagnostico(): String = Store.lerLog(this)
}

enum class Tela { Configurar, Busca, Sugerir, Conectar }
