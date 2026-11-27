package br.rafael.figurinhas

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.os.Build
import android.util.Base64
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

// sincroniza as favoritas: o whatsapp web roda escondido dentro do app (como mais um aparelho conectado)
// e o app copia as figurinhas da aba Favoritas. So manda alguma coisa quando a pessoa pede pra mandar
// uma figurinha com som: ai ele abre a conversa escolhida e toca na figurinha, como no computador.
class Sync(private val a: Activity) {

    enum class Estado { Parado, Conectando, Conectado, Sincronizando, Processando, Pronto, Desconectado, Erro, Preparando, Enviando }

    val estado = mutableStateOf(Estado.Parado)
    val feitas = mutableIntStateOf(0)
    val total = mutableIntStateOf(0)
    val novas = mutableIntStateOf(0)
    val removidas = mutableIntStateOf(0)
    val mensagem = mutableStateOf("")
    // a aba de favoritas veio vazia: a pessoa precisa favoritar uma figurinha com o app rodando
    val esperandoFavoritar = mutableStateOf(false)
    // quem pediu: true = pela pessoa (mostra tela de espera), false = automatico (so a barrinha)
    private val manualEstado = mutableStateOf(false)
    var manual: Boolean get() = manualEstado.value; set(v) { manualEstado.value = v }
    var aoTerminar: (() -> Unit)? = null

    private val io = Executors.newSingleThreadExecutor()
    private val pasta get() = File(a.filesDir, "novas").apply { mkdirs() }
    private var conhecidos: Set<String> = emptySet()
    private var falhas = 0
    private var modo = ""
    @Volatile private var ultimoSinal = 0L
    @Volatile private var ultimoPasso = ""
    private val vigia = android.os.Handler(android.os.Looper.getMainLooper())
    private val checar = object : Runnable {
        override fun run() {
            if (estado.value != Estado.Sincronizando) return
            if (System.currentTimeMillis() - ultimoSinal > 150_000) {
                Store.log(a, "sync: travou em '$ultimoPasso'")
                estado.value = Estado.Erro
                mensagem.value = "A sincronização travou ($ultimoPasso). Toque em tentar de novo."
                modo = ""
                aoTerminar?.invoke()
                return
            }
            vigia.postDelayed(this, 15_000)
        }
    }
    private fun sinal(passo: String? = null) { ultimoSinal = System.currentTimeMillis(); if (passo != null) ultimoPasso = passo }

    val web: WebView by lazy { criarWeb() }

    @SuppressLint("SetJavaScriptEnabled")
    private fun criarWeb(): WebView =
        WebView(a).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            // pagina de computador num celular: comeca afastada pra caber o QR e o link do numero
            setInitialScale(60)
            // o whatsapp web so abre em navegador de computador
            val chrome = Regex("Chrome/[\\d.]+").find(WebSettings.getDefaultUserAgent(a))?.value ?: "Chrome/126.0.0.0"
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
            CookieManager.getInstance().setAcceptCookie(true)
            addJavascriptInterface(Ponte(), "FigAndroid")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (modo == "conectar") view.evaluateJavascript(JS_ESPERA_LOGIN, null)
                    if (modo == "sincronizar") { modo = "rodando"; view.evaluateJavascript(JS_COLETA.replace("@GANCHO@", JS_GANCHO), null) }
                    if (modo == "preparar" && url.startsWith("https://web.whatsapp.com")) view.evaluateJavascript(JS_PRONTO.replace("@GANCHO@", JS_GANCHO), null)
                    if (modo == "direto") {
                        modo = "enviando"
                        view.evaluateJavascript(JS_ENVIAR.replace("@GANCHO@", JS_GANCHO).replace("@ALVO@", "'@direto'").replace("@ID@", org.json.JSONObject.quote(idEnvio)), null)
                    }
                }
            }
        }

    // ---------- mandar figurinha com som pelo whatsapp web ----------
    val enviandoPara = mutableStateOf("")
    private var webPronto = false
    private var envioPendente: (() -> Unit)? = null
    private var aoEnviar: ((Boolean, String) -> Unit)? = null
    private val tempoEnvio = Runnable { terminarEnvio(false, "O WhatsApp Web demorou demais pra responder.") }

    init { a.runOnUiThread { listaConversas.value = conversasRecentes() } }

    fun webAtivo() = rodando() || estado.value == Estado.Preparando || estado.value == Estado.Enviando

    // abre o whatsapp web por tras assim que a pessoa toca numa figurinha com som, pra ganhar tempo
    fun preparar() {
        if (webAtivo() || Prefs.meuNumero(a).isBlank()) return
        webPronto = false
        estado.value = Estado.Preparando
        modo = "preparar"
        web.loadUrl("https://web.whatsapp.com/")
    }

    fun largar() {
        if (estado.value != Estado.Preparando) return
        modo = ""; webPronto = false; envioPendente = null
        web.loadUrl("about:blank")
        estado.value = Estado.Parado
    }

    val listaConversas = mutableStateOf<List<String>>(emptyList())

    fun conversasRecentes(): List<String> = try {
        val arr = org.json.JSONArray(a.getSharedPreferences("p", Activity.MODE_PRIVATE).getString("conversas", "[]"))
        (0 until arr.length()).map { arr.getString(it) }
    } catch (e: Exception) { emptyList() }

    // alvo vazio = conversa com voce mesmo
    fun enviarComSom(f: Fig, alvo: String, fim: (Boolean, String) -> Unit) {
        if (rodando()) { fim(false, "Espere a sincronização terminar."); return }
        if (Prefs.meuNumero(a).isBlank()) { fim(false, "Conecte o WhatsApp nos Ajustes primeiro."); return }
        aoEnviar = fim
        enviandoPara.value = alvo.ifBlank { "você mesmo" }
        Store.log(a, "envio com som: ${f.id} pra ${if (alvo.isBlank()) "mim" else "uma conversa"}")
        val js = JS_ENVIAR.replace("@GANCHO@", JS_GANCHO).replace("@ALVO@", org.json.JSONObject.quote(alvo)).replace("@ID@", org.json.JSONObject.quote(f.id))
        val rodar = { modo = "enviando"; web.evaluateJavascript(js, null); Unit }
        val estava = estado.value
        estado.value = Estado.Enviando
        vigia.removeCallbacks(tempoEnvio); vigia.postDelayed(tempoEnvio, 120_000)
        if (webPronto) rodar()
        else {
            envioPendente = rodar
            if (estava != Estado.Preparando) { webPronto = false; modo = "preparar"; web.loadUrl("https://web.whatsapp.com/") }
        }
        idEnvio = f.id
    }
    private var idEnvio = ""

    private fun terminarEnvio(ok: Boolean, msg: String) {
        vigia.removeCallbacks(tempoEnvio)
        if (estado.value != Estado.Enviando) return
        Store.log(a, "envio com som: ${if (ok) "ok" else "falhou"} $msg")
        modo = ""; webPronto = false; envioPendente = null
        web.loadUrl("about:blank")
        estado.value = Estado.Parado
        val cb = aoEnviar; aoEnviar = null
        cb?.invoke(ok, msg)
        Ouvir.pendentes(a)
    }

    fun soltar() { (web.parent as? ViewGroup)?.removeView(web) }

    fun rodando() = estado.value in setOf(Estado.Conectando, Estado.Sincronizando, Estado.Processando)

    // tela de conexao: a pessoa ve o whatsapp web e conecta com o codigo por numero de telefone
    fun conectar() {
        estado.value = Estado.Conectando
        mensagem.value = ""
        modo = "conectar"
        web.loadUrl("https://web.whatsapp.com/")
    }

    fun sincronizar() {
        val num = Prefs.meuNumero(a)
        if (num.isBlank()) { conectar(); return }
        conhecidos = Store.figs(a).map { it.id }.filter { it.isNotBlank() }.toSet()
        feitas.intValue = 0; total.intValue = 0; novas.intValue = 0; removidas.intValue = 0; falhas = 0
        esperandoFavoritar.value = false
        pasta.listFiles()?.forEach { it.delete() }
        estado.value = Estado.Sincronizando
        mensagem.value = "Abrindo o WhatsApp Web…"
        modo = "sincronizar"
        Store.log(a, "sync: comecou")
        sinal("abrindo o WhatsApp Web"); vigia.removeCallbacks(checar); vigia.postDelayed(checar, 15_000)
        web.loadUrl("https://web.whatsapp.com/send?phone=$num")
    }

    fun cancelar() {
        modo = ""
        vigia.removeCallbacks(checar)
        web.loadUrl("about:blank")
        esperandoFavoritar.value = false
        estado.value = Estado.Parado
        mensagem.value = ""
        Store.log(a, "sync: cancelada")
    }

    fun desconectar() {
        modo = ""
        web.loadUrl("about:blank")
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
        Prefs.marcarConectado(a, false); Prefs.salvarNumero(a, "")
        estado.value = Estado.Parado
    }

    inner class Ponte {
        @JavascriptInterface fun log(m: String) { sinal(m); Store.log(a, "sync: $m") }
        @JavascriptInterface fun conectou(jid: String) {
            // jid vem tipo 554797048612:12@c.us
            val num = jid.trim('"').substringBefore(':').substringBefore('@').filter { it.isDigit() }
            Store.log(a, "sync: conectado")
            Prefs.salvarNumero(a, num); Prefs.marcarConectado(a, true)
            a.runOnUiThread { estado.value = Estado.Conectado; sincronizar() }
        }
        @JavascriptInterface fun estado(s: String) {
            Store.log(a, "sync: estado $s")
            a.runOnUiThread {
                when (s) {
                    "desconectado" -> { Prefs.marcarConectado(a, false); estado.value = Estado.Desconectado; mensagem.value = "O WhatsApp Web desconectou. Conecte de novo." }
                    "sem_favoritas" -> { mensagem.value = "Não achei a aba de favoritas. Favorite uma figurinha no celular e tente de novo."; estado.value = Estado.Erro }
                    "favoritas_vazias" -> { mensagem.value = "O WhatsApp ainda não mandou suas favoritas pro app. Com a sincronização rodando, favorite uma figurinha no WhatsApp e volte."; estado.value = Estado.Erro }
                    else -> { mensagem.value = "Não consegui abrir o painel de figurinhas ($s)."; estado.value = Estado.Erro }
                }
                aoTerminar?.invoke()
            }
        }
        @JavascriptInterface fun pronto(conversas: String) {
            Store.log(a, "whatsapp web pronto pra mandar")
            a.getSharedPreferences("p", Activity.MODE_PRIVATE).edit().putString("conversas", conversas).apply()
            a.runOnUiThread {
                listaConversas.value = conversasRecentes()
                webPronto = true
                envioPendente?.let { envioPendente = null; it() }
            }
        }
        @JavascriptInterface fun envio(r: String) {
            a.runOnUiThread {
                if (estado.value == Estado.Preparando) { if (r == "desconectado") Prefs.marcarConectado(a, false); largar(); return@runOnUiThread }
                when {
                    r == "ok" -> terminarEnvio(true, "")
                    r == "abrir_eu" -> {
                        // a conversa com voce mesmo nao estava na lista: abre direto pelo numero
                        modo = "direto"
                        web.loadUrl("https://web.whatsapp.com/send?phone=${Prefs.meuNumero(a)}")
                    }
                    r == "desconectado" -> { Prefs.marcarConectado(a, false); terminarEnvio(false, "O WhatsApp Web desconectou. Conecte de novo nos Ajustes.") }
                    r == "sem_conversa" -> terminarEnvio(false, "Não achei essa conversa no WhatsApp Web.")
                    r == "conversa_errada" -> terminarEnvio(false, "Abriu outra conversa, então não mandei nada.")
                    r == "nao_achei" -> terminarEnvio(false, "Essa figurinha ainda não está nas favoritas do WhatsApp Web. No WhatsApp, desfavorite e favorite ela de novo uma vez, e tente outra vez.")
                    else -> terminarEnvio(false, "Não deu pra mandar ($r).")
                }
            }
        }
        // o whatsapp web mostrou uma versao maior da figurinha do que a guardada no app
        @JavascriptInterface fun trocar(h: String, b64: String) {
            val f = Store.figs(a).firstOrNull { it.id == h } ?: return
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val dest = File(Store.dir(a), f.arquivo)
            if (bytes.size <= dest.length()) return
            if (f.animada == animada(bytes)) {
                dest.writeBytes(if (f.animada) bytes else paraPacote(bytes) ?: bytes)
                Store.log(a, "sync: troquei $h por uma versao maior (${bytes.size}B)")
                a.runOnUiThread { ImgCache.limpar() }
            }
        }
        @JavascriptInterface fun vazio() { sinal("esperando favoritas"); a.runOnUiThread { esperandoFavoritar.value = true; mensagem.value = "Esperando suas favoritas chegarem…" } }
        @JavascriptInterface fun mensagem(m: String) { sinal(m); Store.log(a, "sync: $m"); a.runOnUiThread { mensagem.value = m } }
        @JavascriptInterface fun total(n: Int) { sinal("copiando"); a.runOnUiThread { esperandoFavoritar.value = false; total.intValue = n } }
        @JavascriptInterface fun conhece(h: String): Boolean = h in conhecidos
        @JavascriptInterface fun existe(h: String) { sinal(); a.runOnUiThread { feitas.intValue++ } }
        @JavascriptInterface fun falhou() { sinal(); falhas++; a.runOnUiThread { feitas.intValue++ } }
        @JavascriptInterface fun nova(h: String, b64: String) {
            sinal()
            File(pasta, "$h.webp").writeBytes(Base64.decode(b64, Base64.DEFAULT))
            a.runOnUiThread { feitas.intValue++; novas.intValue++ }
        }
        @JavascriptInterface fun marcaSom(h: String) { sinal(); Store.marcarSom(a, h) }
        @JavascriptInterface fun precisaSom(h: String): Boolean = !File(Store.dir(a), "$h.som").exists()
        @JavascriptInterface fun som(h: String, b64: String, tipo: String) {
            sinal()
            File(Store.dir(a), "$h.som").writeBytes(Base64.decode(b64, Base64.DEFAULT))
            Store.marcarSom(a, h)
            Store.log(a, "sync: audio de $h salvo ($tipo)")
        }
        @JavascriptInterface fun fim(lista: String) {
            val ordem = lista.split(',').filter { it.isNotBlank() }
            if (ordem.isNotEmpty()) File(a.filesDir, "ordem_favoritas.txt").writeText(ordem.joinToString("\n"))
            val vistas = ordem.toSet()
            a.runOnUiThread { estado.value = Estado.Processando; mensagem.value = "Descrevendo as figurinhas novas…" }
            io.execute { processar(vistas) }
        }
        @JavascriptInterface fun erro(e: String) {
            Store.log(a, "sync: erro $e")
            a.runOnUiThread { estado.value = Estado.Erro; mensagem.value = "A sincronização parou: $e"; aoTerminar?.invoke() }
        }
    }

    private fun processar(vistas: Set<String>) {
        try {
            val atuais = Store.figs(a).toMutableList()
            var prox = (atuais.maxOfOrNull { it.n } ?: 0) + 1
            val novasFiles = pasta.listFiles()?.filter { it.name.endsWith(".webp") } ?: emptyList()
            for ((i, f) in novasFiles.withIndex()) {
                a.runOnUiThread { mensagem.value = "Descrevendo figurinha ${i + 1} de ${novasFiles.size}…" }
                val id = f.nameWithoutExtension
                val bytes = f.readBytes()
                val anim = animada(bytes)
                val nome = "${id}_${if (anim) "a" else "s"}.webp"
                val destino = File(Store.dir(a), nome)
                if (anim) destino.writeBytes(bytes) else destino.writeBytes(paraPacote(bytes) ?: bytes)
                val bmp = primeiroQuadro(destino)
                val (texto, tags) = if (bmp != null) Descrever.descrever(bmp) else ("" to "")
                atuais += Fig(prox++, nome, anim, texto, tags.ifBlank { "figurinha" } + if (anim) " animada" else "", id, "celular")
                f.delete()
            }
            // o whatsapp web de um aparelho recem conectado so conhece as favoritas mexidas depois da conexao,
            // entao a lista dele nao serve pra apagar o resto. So sai daqui o que ja apareceu no web antes e sumiu,
            // e mesmo assim vai pra lixeira (da pra restaurar nos ajustes)
            var tiradas = 0
            val arqVistos = File(a.filesDir, "vistos_no_web.txt")
            val jaVistos = if (arqVistos.exists()) arqVistos.readLines().filter { it.isNotBlank() }.toMutableSet() else mutableSetOf()
            if (falhas == 0 && vistas.isNotEmpty()) {
                val sairiam = atuais.filter { it.id.isNotBlank() && it.id in jaVistos && it.id !in vistas }
                if (sairiam.size > 3 && sairiam.size > atuais.size * 0.3) {
                    Store.log(a, "sync: ${sairiam.size} sairiam de uma vez, nao removi nada por seguranca")
                } else if (sairiam.isNotEmpty()) {
                    Store.paraLixeira(a, sairiam)
                    atuais.removeAll { f -> sairiam.any { it.n == f.n } }
                    tiradas = sairiam.size
                }
                jaVistos.addAll(vistas)
                arqVistos.writeText(jaVistos.joinToString("\n"))
            }
            Store.salvarIndice(a, atuais)
            Prefs.marcarSync(a)
            Store.log(a, "sync: fim, ${novasFiles.size} novas, $tiradas removidas, $falhas falhas")
            a.runOnUiThread {
                removidas.intValue = tiradas
                estado.value = Estado.Pronto
                mensagem.value = when {
                    novasFiles.isEmpty() && tiradas == 0 -> "Tudo em dia."
                    else -> "${novasFiles.size} nova${if (novasFiles.size == 1) "" else "s"}, $tiradas removida${if (tiradas == 1) "" else "s"}."
                }
                aoTerminar?.invoke()
                Ouvir.pendentes(a)
            }
        } catch (e: Exception) {
            Store.log(a, "sync: erro ao processar ${e.message}")
            a.runOnUiThread { estado.value = Estado.Erro; mensagem.value = "Erro ao guardar as figurinhas novas."; aoTerminar?.invoke() }
        }
        modo = ""
    }

    private fun animada(b: ByteArray): Boolean = String(b, 0, minOf(b.size, 200), Charsets.ISO_8859_1).contains("ANIM")

    private fun primeiroQuadro(f: File): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
    } catch (e: Exception) { null }

    // figurinha estatica fora do padrao (tamanho ou peso) e refeita em 512x512 ate 100KB
    private fun paraPacote(b: ByteArray): ByteArray? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        if (o.outWidth == 512 && o.outHeight == 512 && b.size <= 100_000) return null
        val src = BitmapFactory.decodeByteArray(b, 0, b.size) ?: return null
        val esc = 512f / maxOf(src.width, src.height)
        val w = (src.width * esc).toInt().coerceAtLeast(1); val h = (src.height * esc).toInt().coerceAtLeast(1)
        val tela = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        Canvas(tela).drawBitmap(Bitmap.createScaledBitmap(src, w, h, true), (512 - w) / 2f, (512 - h) / 2f, null)
        @Suppress("DEPRECATION")
        val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        for (q in listOf(90, 75, 60, 45, 30)) {
            val out = ByteArrayOutputStream(); tela.compress(fmt, q, out)
            if (out.size() <= 100_000) return out.toByteArray()
        }
        return null
    }

    companion object {
        // espera o login na tela de conexao e pega o proprio numero salvo pelo whatsapp web
        private const val JS_ESPERA_LOGIN = """
(function(){
  if (window.__figEspera) return; window.__figEspera = true;
  var t = setInterval(function(){
    var lista = document.querySelector('#pane-side');
    if (!lista) return;
    var jid = localStorage.getItem('last-wid-md') || localStorage.getItem('last-wid') || '';
    if (!jid) return;
    clearInterval(t); FigAndroid.conectou(jid);
  }, 1500);
})();
"""

        // abre o painel de figurinhas na conversa consigo mesmo, vai na aba Favoritas e copia cada uma
        private const val JS_GANCHO = """
if (!window.__figGancho) {
  window.__figGancho = true; window.__figBlobs = [];
  var criar = URL.createObjectURL;
  URL.createObjectURL = function(b){ var u = criar.apply(this, arguments); try { if (b && b.size) { window.__figBlobs.push({u: u, b: b, t: Date.now()}); if (window.__figBlobs.length > 500) window.__figBlobs.shift(); } } catch(e){} return u; };
  var tocar = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function(){ try { this.muted = true; this.volume = 0; } catch(e){} return tocar.apply(this, arguments); };
}
window.__figDesde = function(t, re){ return window.__figBlobs.filter(function(x){ return x.t >= t && re.test(x.b.type || ''); }); };
window.__figB64 = async function(b){ var u8 = new Uint8Array(await b.arrayBuffer()), s = ''; for (var j = 0; j < u8.length; j += 32768) s += String.fromCharCode.apply(null, u8.subarray(j, j + 32768)); return btoa(s); };
window.__figHash = async function(u8){ var dg = new Uint8Array(await crypto.subtle.digest('SHA-256', u8)); return Array.prototype.slice.call(dg, 0, 8).map(function(x){ return x.toString(16).padStart(2, '0'); }).join(''); };
window.__figRotulos = function(el){ return Array.prototype.slice.call(el.querySelectorAll('[aria-label],[data-icon],[title],img[alt]')).map(function(e){ return (e.getAttribute('aria-label') || e.getAttribute('data-icon') || e.getAttribute('title') || e.getAttribute('alt') || '').slice(0, 40); }).filter(function(x){ return x; }).join('|'); };
// toca o som de uma mensagem (mudo) e devolve o audio que o whatsapp web decifrou
window.__figSomDaMensagem = async function(msg){
  var bt = Array.prototype.slice.call(msg.querySelectorAll('[aria-label],[data-icon]')).find(function(e){ return /som|sound|audio|áudio|play|reproduz|tocar|mute|mudo|volume|speaker/i.test((e.getAttribute('aria-label') || '') + ' ' + (e.getAttribute('data-icon') || '')); });
  var t0 = Date.now();
  if (bt) { (bt.closest('button,[role=button]') || bt).click(); } else { var im = msg.querySelector('img'); if (im) im.click(); }
  await new Promise(function(r){ setTimeout(r, 3000); });
  var achados = window.__figDesde(t0, /audio|video|ogg|mpeg|mp4|opus|aac/i);
  var el = msg.querySelector('audio[src],video[src]');
  if (!achados.length && el && el.src.indexOf('blob:') === 0) { return await (await fetch(el.src)).blob(); }
  if (!achados.length) return null;
  achados.sort(function(x, y){ return y.b.size - x.b.size; });
  return achados[0].b;
};
"""

        private const val JS_PRONTO = """
(async function(){
  var A = window.FigAndroid;
  @GANCHO@
  var dormir = function(ms){ return new Promise(function(r){ setTimeout(r, ms); }); };
  for (var i = 0; i < 180; i++) {
    if (document.querySelector('#pane-side')) break;
    if (i > 20 && document.querySelector('canvas') && !document.querySelector('#pane-side')) { A.envio('desconectado'); return; }
    await dormir(500);
  }
  await dormir(1500);
  var nomes = [];
  Array.prototype.slice.call(document.querySelectorAll('#pane-side [role="row"], #pane-side [role="listitem"]')).forEach(function(r){
    var s = r.querySelector('span[title]'); var n = s && s.getAttribute('title');
    if (n && nomes.indexOf(n) < 0 && nomes.length < 12) nomes.push(n);
  });
  A.pronto(JSON.stringify(nomes));
})();
"""

        private const val JS_ENVIAR = """
(async function(){
  var A = window.FigAndroid, ALVO = @ALVO@, ID = @ID@;
  @GANCHO@
  var dormir = function(ms){ return new Promise(function(r){ setTimeout(r, ms); }); };
  async function esperar(fn, ms){ var t0 = Date.now(); while (Date.now() - t0 < ms){ try { var v = fn(); if (v) return v; } catch(e){} await dormir(300); } return null; }
  function porRotulo(re, raiz){ return Array.prototype.slice.call((raiz||document).querySelectorAll('[aria-label]')).find(function(e){ return re.test(e.getAttribute('aria-label')); }); }
  try {
    if (ALVO !== '@direto') {
      var achar = function(){ return Array.prototype.slice.call(document.querySelectorAll('#pane-side span[title]')).find(function(s){ var t = s.getAttribute('title'); return ALVO === '' ? /\((you|você|voce)\)/i.test(t) : t === ALVO; }); };
      var nome = await esperar(achar, 15000);
      if (!nome) { A.envio(ALVO === '' ? 'abrir_eu' : 'sem_conversa'); return; }
      var linha = nome.closest('[role="row"],[role="listitem"]') || nome;
      ['mousedown', 'mouseup', 'click'].forEach(function(ev){ linha.dispatchEvent(new MouseEvent(ev, {bubbles: true, cancelable: true, view: window})); });
    }
    var rodape = await esperar(function(){ return document.querySelector('#main footer'); }, 40000);
    if (!rodape) { A.envio('sem_rodape'); return; }
    await dormir(900);
    if (ALVO && ALVO !== '@direto') {
      var cab = document.querySelector('#main header');
      if (!cab || cab.textContent.indexOf(ALVO) < 0) { A.envio('conversa_errada'); return; }
    }
    A.log('envio: conversa aberta');
    var emoji = await esperar(function(){ return porRotulo(/emoji/i, rodape); }, 15000);
    if (!emoji) { A.envio('sem_botao_emoji'); return; }
    emoji.click(); await dormir(1200);
    var aba = await esperar(function(){ return Array.prototype.slice.call(document.querySelectorAll('[role="tab"]')).find(function(e){ return /sticker|figurinha|pegatina/i.test((e.getAttribute('aria-label')||'') + e.textContent); }); }, 15000);
    if (!aba) { A.envio('sem_aba_figurinhas'); return; }
    aba.click(); await dormir(1800);
    var fav = await esperar(function(){ return Array.prototype.slice.call(document.querySelectorAll('button[aria-label]')).find(function(e){ return /^(favorite|favorites|favorita|favoritas|favorito|favoritos)$/i.test(e.getAttribute('aria-label').trim()); }); }, 15000);
    if (!fav) { A.envio('sem_favoritas'); return; }
    fav.click(); await dormir(2000);
    var itens = await esperar(function(){ var l = document.querySelectorAll('[data-testid="sticker-item"]'); return l.length ? Array.prototype.slice.call(l) : null; }, 20000);
    if (!itens) { A.envio('nao_achei'); return; }
    var antes = document.querySelectorAll('#main [data-id]').length;
    for (var i = 0; i < itens.length; i++) {
      var it = itens[i];
      it.scrollIntoView({block: 'center'});
      var img = await esperar(function(){ var x = it.querySelector('img'); return (x && x.complete && x.naturalWidth > 0 && x.src.indexOf('blob:') === 0) ? x : null; }, 6000);
      if (!img) continue;
      var u8 = new Uint8Array(await (await (await fetch(img.src)).blob()).arrayBuffer());
      if (await window.__figHash(u8) !== ID) continue;
      A.log('envio: achei a figurinha (' + (i + 1) + ' de ' + itens.length + '), mandando');
      (img.closest('[data-testid="sticker-item"]') || it).click();
      var foi = await esperar(function(){ return document.querySelectorAll('#main [data-id]').length > antes; }, 8000);
      A.log('envio: ' + (foi ? 'mensagem apareceu na conversa' : 'nao vi a mensagem aparecer'));
      // aproveita a mensagem que acabou de sair pra pegar o audio (fica mudo) e entender o que e falado
      try {
        var msgs = document.querySelectorAll('#main [data-id]'), ult = msgs[msgs.length - 1];
        if (ult) {
          A.log('envio: rotulos da mensagem ' + window.__figRotulos(ult));
          if (A.precisaSom(ID)) {
            var som = await window.__figSomDaMensagem(ult);
            if (som) { A.som(ID, await window.__figB64(som), som.type || ''); A.marcaSom(ID); }
            else A.log('envio: nao achei o audio na mensagem');
          }
        }
      } catch(e3) { A.log('envio: audio falhou ' + e3); }
      A.envio(foi ? 'ok' : 'ok');
      return;
    }
    A.envio('nao_achei');
  } catch(e) { A.envio('erro: ' + e); }
})();
"""

        private const val JS_COLETA = """
(async function(){
  var A = window.FigAndroid;
  @GANCHO@
  var dormir = function(ms){ return new Promise(function(r){ setTimeout(r, ms); }); };
  async function esperar(fn, ms){ var t0 = Date.now(); while (Date.now() - t0 < ms){ try { var v = fn(); if (v) return v; } catch(e){} await dormir(400); } return null; }
  function porRotulo(re, raiz){ return Array.prototype.slice.call((raiz||document).querySelectorAll('[aria-label]')).find(function(e){ return re.test(e.getAttribute('aria-label')); }); }
  try {
    A.mensagem('Entrando no WhatsApp Web…');
    A.log('esperando a conversa abrir');
    var rodape = await esperar(function(){ return document.querySelector('footer'); }, 90000);
    if (!rodape) {
      if (document.querySelector('canvas') || !document.querySelector('#pane-side')) { A.estado('desconectado'); return; }
      A.estado('sem_conversa'); return;
    }
    A.mensagem('Abrindo suas favoritas…');
    var emoji = await esperar(function(){ return porRotulo(/emoji/i, rodape); }, 20000);
    if (!emoji) { A.estado('sem_botao_emoji'); return; }
    // aparelho recem conectado pode demorar pra receber a lista de favoritas: tenta algumas vezes
    var fav = null;
    for (var tent = 0; tent < 5 && !fav; tent++) {
      if (tent > 0) { A.mensagem('Esperando o WhatsApp mandar suas favoritas… (' + (tent + 1) + '/5)'); emoji.click(); await dormir(4000); }
      emoji.click(); await dormir(1500);
      var aba = await esperar(function(){ return Array.prototype.slice.call(document.querySelectorAll('[role="tab"]')).find(function(e){ return /sticker|figurinha|pegatina/i.test((e.getAttribute('aria-label')||'') + e.textContent); }); }, 20000);
      if (!aba) { A.estado('sem_aba_figurinhas'); return; }
      aba.click(); await dormir(2500);
      A.log('procurando favoritas, tentativa ' + (tent + 1));
      fav = await esperar(function(){ return Array.prototype.slice.call(document.querySelectorAll('button[aria-label]')).find(function(e){ return /^(favorite|favorites|favorita|favoritas|favorito|favoritos)$/i.test(e.getAttribute('aria-label').trim()); }); }, 15000);
    }
    if (!fav) { A.estado('sem_favoritas'); return; }
    fav.click(); await dormir(3000);
    var itens = function(){ return Array.prototype.slice.call(document.querySelectorAll('[data-testid="sticker-item"]')); };
    var lista = itens();
    // aparelho novo pode abrir com a aba vazia ate o whatsapp mandar a lista; espera e pede pra favoritar uma
    var inicio = Date.now(), avisou = false;
    while (lista.length === 0 && Date.now() - inicio < 300000) {
      var passou = Math.round((Date.now() - inicio) / 1000);
      if (passou > 25 && !avisou) { avisou = true; A.vazio(); }
      A.log('favoritas ainda vazias (' + passou + 's)');
      await dormir(5000);
      if (passou % 30 < 5) { fav.click(); await dormir(1500); }
      lista = itens();
    }
    if (lista.length === 0) { A.estado('favoritas_vazias'); return; }
    var antes = -1;
    for (var k = 0; k < 10 && lista.length !== antes; k++){ antes = lista.length; await dormir(800); lista = itens(); }
    A.total(lista.length);
    A.mensagem('Copiando favoritas…');
    var vistas = {}, amostras = 0;
    for (var i = 0; i < lista.length; i++){
      var it = lista[i];
      it.scrollIntoView({block: 'center'});
      var img = await esperar(function(){ var x = it.querySelector('img'); return (x && x.complete && x.naturalWidth > 0 && x.src.indexOf('blob:') === 0) ? x : null; }, 8000);
      if (!img){ A.log('figurinha ' + (i + 1) + ' nao carregou'); A.falhou(); continue; }
      var b = await (await fetch(img.src)).blob();
      var u8 = new Uint8Array(await b.arrayBuffer());
      var dg = new Uint8Array(await crypto.subtle.digest('SHA-256', u8));
      var h = Array.prototype.slice.call(dg, 0, 8).map(function(x){ return x.toString(16).padStart(2, '0'); }).join('');
      var rot = window.__figRotulos(it);
      var midia = it.querySelector('audio[src],video[src],source[src]');
      var comSom = !!(midia || /som|sound|audio|áudio|volume|speaker/i.test(rot + ' ' + (it.getAttribute('aria-label') || '')));
      var pequena = img.naturalWidth < 400;
      if (comSom || pequena) {
        // passa o mouse por cima: o whatsapp web pode carregar a versao completa (e o audio) so nessa hora
        var t0 = Date.now();
        ['mouseover', 'mouseenter', 'pointerover', 'pointerenter'].forEach(function(ev){ img.dispatchEvent(new MouseEvent(ev, {bubbles: true})); });
        await dormir(1800);
        var novosB = window.__figDesde(t0, /./);
        var dentro = it.querySelector('audio[src],video[src],source[src]');
        if (!midia && dentro) midia = dentro;
        var maior = window.__figDesde(t0, /image|webp/i).sort(function(x, y){ return y.b.size - x.b.size; })[0];
        if (maior && maior.b.size > u8.length) A.trocar(h, await window.__figB64(maior.b));
        var sons = window.__figDesde(t0, /audio|video|ogg|mpeg|mp4|opus|aac/i);
        if (sons.length && A.precisaSom(h)) { A.som(h, await window.__figB64(sons[0].b), sons[0].b.type); comSom = true; }
        if (amostras < 8) { amostras++; A.log('item ' + (i + 1) + (comSom ? ' COM SOM' : '') + ' ' + img.naturalWidth + 'x' + img.naturalHeight + ' ' + u8.length + 'B rotulos[' + rot + '] blobs depois do mouse: ' + novosB.map(function(x){ return (x.b.type || '?') + ':' + x.b.size; }).join(',') + ' html: ' + it.outerHTML.slice(0, 700)); }
      } else if (i === 0 && amostras < 8) { amostras++; A.log('item 1 ' + img.naturalWidth + 'x' + img.naturalHeight + ' rotulos[' + rot + ']'); }
      if (comSom) A.marcaSom(h);
      if (midia && A.precisaSom(h)) {
        try {
          var bs = await (await fetch(midia.src)).blob();
          var a8 = new Uint8Array(await bs.arrayBuffer()), bs64 = '';
          for (var q = 0; q < a8.length; q += 32768) bs64 += String.fromCharCode.apply(null, a8.subarray(q, q + 32768));
          A.som(h, btoa(bs64), bs.type || '');
        } catch(e2) { A.log('audio da ' + (i + 1) + ' falhou: ' + e2); }
      }
      if (vistas[h]) { A.existe(h); continue; }
      vistas[h] = 1;
      if (A.conhece(h)) { A.existe(h); continue; }
      var bin = ''; for (var j = 0; j < u8.length; j += 32768) bin += String.fromCharCode.apply(null, u8.subarray(j, j + 32768));
      A.nova(h, btoa(bin));
    }
    try {
      var msgs = Array.prototype.slice.call(document.querySelectorAll('#main [data-id]')).slice(-40);
      var comSomMsg = msgs.filter(function(m){ return m.querySelector('img') && /som|sound|audio|áudio|volume|speaker/i.test(window.__figRotulos(m)); });
      A.log('conversa: ' + msgs.length + ' mensagens olhadas, ' + comSomMsg.length + ' com som');
      for (var m = 0; m < comSomMsg.length && m < 6; m++) {
        var mi = comSomMsg[m].querySelector('img');
        if (!mi || mi.src.indexOf('blob:') !== 0) continue;
        var mh = await window.__figHash(new Uint8Array(await (await (await fetch(mi.src)).blob()).arrayBuffer()));
        A.marcaSom(mh);
        if (!A.precisaSom(mh)) continue;
        A.log('conversa: rotulos ' + window.__figRotulos(comSomMsg[m]));
        var sm = await window.__figSomDaMensagem(comSomMsg[m]);
        if (sm) A.som(mh, await window.__figB64(sm), sm.type || ''); else A.log('conversa: audio de ' + mh + ' nao veio');
      }
      var tipos = {}; window.__figBlobs.forEach(function(x){ var k = (x.b.type || '?').split(';')[0]; tipos[k] = (tipos[k] || 0) + 1; });
      A.log('blobs criados: ' + JSON.stringify(tipos));
    } catch(e4) { A.log('conversa: falhou ' + e4); }
    A.fim(Object.keys(vistas).join(','));
  } catch(e) { A.erro(String(e)); }
})();
"""
    }
}
