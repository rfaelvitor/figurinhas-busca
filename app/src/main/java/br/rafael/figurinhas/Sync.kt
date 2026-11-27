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
// e o app copia as figurinhas da aba Favoritas. so le, nunca manda nada.
class Sync(private val a: Activity) {

    enum class Estado { Parado, Conectando, Conectado, Sincronizando, Processando, Pronto, Desconectado, Erro }

    val estado = mutableStateOf(Estado.Parado)
    val feitas = mutableIntStateOf(0)
    val total = mutableIntStateOf(0)
    val novas = mutableIntStateOf(0)
    val removidas = mutableIntStateOf(0)
    val mensagem = mutableStateOf("")
    var aoTerminar: (() -> Unit)? = null

    private val io = Executors.newSingleThreadExecutor()
    private val pasta get() = File(a.filesDir, "novas").apply { mkdirs() }
    private var conhecidos: Set<String> = emptySet()
    private var falhas = 0
    private var modo = ""

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
                    if (modo == "sincronizar") { modo = "rodando"; view.evaluateJavascript(JS_COLETA, null) }
                }
            }
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
        pasta.listFiles()?.forEach { it.delete() }
        estado.value = Estado.Sincronizando
        mensagem.value = "Abrindo o WhatsApp Web…"
        modo = "sincronizar"
        Store.log(a, "sync: comecou")
        web.loadUrl("https://web.whatsapp.com/send?phone=$num")
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
        @JavascriptInterface fun log(m: String) = Store.log(a, "sync: $m")
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
                    else -> { mensagem.value = "Não consegui abrir o painel de figurinhas ($s)."; estado.value = Estado.Erro }
                }
                aoTerminar?.invoke()
            }
        }
        @JavascriptInterface fun mensagem(m: String) { a.runOnUiThread { mensagem.value = m } }
        @JavascriptInterface fun total(n: Int) { a.runOnUiThread { total.intValue = n } }
        @JavascriptInterface fun conhece(h: String): Boolean = h in conhecidos
        @JavascriptInterface fun existe(h: String) { a.runOnUiThread { feitas.intValue++ } }
        @JavascriptInterface fun falhou() { falhas++; a.runOnUiThread { feitas.intValue++ } }
        @JavascriptInterface fun nova(h: String, b64: String) {
            File(pasta, "$h.webp").writeBytes(Base64.decode(b64, Base64.DEFAULT))
            a.runOnUiThread { feitas.intValue++; novas.intValue++ }
        }
        @JavascriptInterface fun fim(lista: String) {
            val vistas = lista.split(',').filter { it.isNotBlank() }.toSet()
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
            // so apaga as que sumiram se a leitura foi completa
            var tiradas = 0
            if (falhas == 0 && vistas.isNotEmpty()) {
                val antes = atuais.size
                atuais.filter { it.id.isNotBlank() && it.id !in vistas }.forEach { File(Store.dir(a), it.arquivo).delete() }
                atuais.removeAll { it.id.isNotBlank() && it.id !in vistas }
                tiradas = antes - atuais.size
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
        private const val JS_COLETA = """
(async function(){
  var A = window.FigAndroid;
  var dormir = function(ms){ return new Promise(function(r){ setTimeout(r, ms); }); };
  async function esperar(fn, ms){ var t0 = Date.now(); while (Date.now() - t0 < ms){ try { var v = fn(); if (v) return v; } catch(e){} await dormir(400); } return null; }
  function porRotulo(re, raiz){ return Array.prototype.slice.call((raiz||document).querySelectorAll('[aria-label]')).find(function(e){ return re.test(e.getAttribute('aria-label')); }); }
  try {
    A.mensagem('Entrando no WhatsApp Web…');
    var rodape = await esperar(function(){ return document.querySelector('footer'); }, 90000);
    if (!rodape) {
      if (document.querySelector('canvas') || !document.querySelector('#pane-side')) { A.estado('desconectado'); return; }
      A.estado('sem_conversa'); return;
    }
    A.mensagem('Abrindo suas favoritas…');
    var emoji = await esperar(function(){ return porRotulo(/emoji/i, rodape); }, 20000);
    if (!emoji) { A.estado('sem_botao_emoji'); return; }
    emoji.click(); await dormir(1500);
    var aba = await esperar(function(){ return Array.prototype.slice.call(document.querySelectorAll('[role="tab"]')).find(function(e){ return /sticker|figurinha|pegatina/i.test((e.getAttribute('aria-label')||'') + e.textContent); }); }, 20000);
    if (!aba) { A.estado('sem_aba_figurinhas'); return; }
    aba.click(); await dormir(2500);
    var fav = await esperar(function(){ return Array.prototype.slice.call(document.querySelectorAll('button[aria-label]')).find(function(e){ return /^(favorite|favorites|favorita|favoritas|favorito|favoritos)$/i.test(e.getAttribute('aria-label').trim()); }); }, 20000);
    if (!fav) { A.estado('sem_favoritas'); return; }
    fav.click(); await dormir(3000);
    var itens = function(){ return Array.prototype.slice.call(document.querySelectorAll('[data-testid="sticker-item"]')); };
    var lista = itens(), antes = -1;
    for (var k = 0; k < 10 && lista.length !== antes; k++){ antes = lista.length; await dormir(800); lista = itens(); }
    A.total(lista.length);
    A.mensagem('Copiando favoritas…');
    var vistas = {};
    for (var i = 0; i < lista.length; i++){
      var it = lista[i];
      it.scrollIntoView({block: 'center'});
      var img = await esperar(function(){ var x = it.querySelector('img'); return (x && x.complete && x.naturalWidth > 0 && x.src.indexOf('blob:') === 0) ? x : null; }, 8000);
      if (!img){ A.falhou(); continue; }
      var b = await (await fetch(img.src)).blob();
      var u8 = new Uint8Array(await b.arrayBuffer());
      var dg = new Uint8Array(await crypto.subtle.digest('SHA-256', u8));
      var h = Array.prototype.slice.call(dg, 0, 8).map(function(x){ return x.toString(16).padStart(2, '0'); }).join('');
      if (vistas[h]) { A.existe(h); continue; }
      vistas[h] = 1;
      if (A.conhece(h)) { A.existe(h); continue; }
      var bin = ''; for (var j = 0; j < u8.length; j += 32768) bin += String.fromCharCode.apply(null, u8.subarray(j, j + 32768));
      A.nova(h, btoa(bin));
    }
    A.fim(Object.keys(vistas).join(','));
  } catch(e) { A.erro(String(e)); }
})();
"""
    }
}
