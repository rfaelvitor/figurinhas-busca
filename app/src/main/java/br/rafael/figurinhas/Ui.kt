package br.rafael.figurinhas

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.widget.ImageView
import androidx.compose.animation.*
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File

// cores tiradas do icone: roxo do fundo e amarelo da figurinha
private val Roxo = Color(0xFF5B2DBF)
private val Amarelo = Color(0xFFFFC83D)

private val Claro = lightColorScheme(
    primary = Roxo, onPrimary = Color.White, primaryContainer = Color(0xFFE9DDFF), onPrimaryContainer = Color(0xFF22104F),
    secondary = Amarelo, onSecondary = Color(0xFF3A2470), secondaryContainer = Color(0xFFFFF0C7), onSecondaryContainer = Color(0xFF3A2A00),
    background = Color(0xFFFBF8FF), onBackground = Color(0xFF1E1530),
    surface = Color(0xFFFBF8FF), onSurface = Color(0xFF1E1530),
    surfaceVariant = Color(0xFFEEE7FA), onSurfaceVariant = Color(0xFF5E5470),
    surfaceContainer = Color(0xFFF3EEFC), surfaceContainerHigh = Color(0xFFEDE6F8), outline = Color(0xFFCFC4E2)
)
private val Escuro = darkColorScheme(
    primary = Color(0xFFCBB8FF), onPrimary = Color(0xFF2A1560), primaryContainer = Color(0xFF41269A), onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Amarelo, onSecondary = Color(0xFF3A2470), secondaryContainer = Color(0xFF4A3B10), onSecondaryContainer = Color(0xFFFFE9A8),
    background = Color(0xFF15101E), onBackground = Color(0xFFECE4F7),
    surface = Color(0xFF15101E), onSurface = Color(0xFFECE4F7),
    surfaceVariant = Color(0xFF2A2236), onSurfaceVariant = Color(0xFFBFB4D2),
    surfaceContainer = Color(0xFF1E1729), surfaceContainerHigh = Color(0xFF261E33), outline = Color(0xFF4A405C)
)

@Composable
fun Tema(conteudo: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Escuro else Claro, content = conteudo)
}

@Composable
fun App(a: MainActivity) {
    Tema {
        Box(Modifier.fillMaxSize()) {
        val webNaTela = a.tela.value == Tela.Conectar && a.sync.estado.value in setOf(Sync.Estado.Conectando, Sync.Estado.Desconectado)
        // o whatsapp web roda por tras da interface, em tamanho real, coberto pelo app:
        // fica "visivel" pro android (nao e pausado) mas a pessoa nunca ve nem toca nele
        if (a.sync.webAtivo() && !webNaTela) {
            AndroidView(factory = { a.sync.soltar(); a.sync.web }, modifier = Modifier.fillMaxSize())
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Crossfade(targetState = a.tela.value, animationSpec = tween(250), label = "tela") { t ->
                when (t) {
                    Tela.Configurar -> Configurar(a)
                    Tela.Busca -> Busca(a)
                    Tela.Sugerir -> Sugerir(a)
                    Tela.Conectar -> Conectar(a)
                }
            }
            a.aviso.value?.let { msg ->
                AlertDialog(onDismissRequest = { a.aviso.value = null; a.avisoFechado() },
                    confirmButton = { TextButton(onClick = { a.aviso.value = null; a.avisoFechado() }) { Text("Ok") } },
                    text = { Text(msg) })
            }
            a.escolhaSom.value?.let { (q, res, f) -> EscolhaSom(a, q, res, f) }
        }
        AnimatedVisibility(a.sync.manual && a.sync.rodando() && !webNaTela, enter = fadeIn(tween(250)), exit = fadeOut(tween(300))) { EsperaSync(a) }
        ResultadoSync(a, Modifier.align(Alignment.TopCenter))
        }
    }
}

// ---------- sincronizacao por tras dos panos ----------

private val FRASES_ESPERA = listOf(
    "Separando os memes bons…", "Contando figurinhas de gato…", "Tirando a poeira das antigas…",
    "Conferindo quem tá de óculos escuros…", "Desamassando as figurinhas…", "Lendo o que tá escrito em cada uma…"
)

@Composable
private fun EsperaSync(a: MainActivity) {
    // fica fora do Surface: sem isso o texto sai preto no tema escuro
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) { EsperaSyncConteudo(a) }
}

@Composable
private fun EsperaSyncConteudo(a: MainActivity) {
    val ctx = LocalContext.current
    // uma fila embaralhada com todas as favoritas: cada uma que pula volta como outra
    val fila = remember { Store.figs(ctx).shuffled() }
    val vagas = minOf(4, fila.size)
    val nas = remember { mutableStateListOf<Fig>().apply { addAll(fila.take(vagas)) } }
    val pulos = remember { List(4) { androidx.compose.animation.core.Animatable(0f) } }
    LaunchedEffect(Unit) {
        if (vagas == 0) return@LaunchedEffect
        var prox = vagas; var k = 0
        while (true) {
            val i = k % vagas
            launch {
                pulos[i].animateTo(1f, tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                if (fila.size > vagas) { nas[i] = fila[prox % fila.size]; prox++ }
                pulos[i].animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = .42f, stiffness = 420f))
            }
            kotlinx.coroutines.delay(420); k++
        }
    }
    var frase by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2800); frase = (frase + 1) % FRASES_ESPERA.size } }
    val teclado = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val foco = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(Unit) { foco.clearFocus(); teclado?.hide() }
    val esperando = a.sync.esperandoFavoritar.value
    val total = a.sync.total.intValue
    val feitas = a.sync.feitas.intValue
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
            .systemBarsPadding().padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.verticalScroll(rememberScrollState())) {
            Row(Modifier.height(170.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                if (vagas == 0) Text("✨", fontSize = 64.sp)
                for (i in 0 until vagas) {
                    val f = nas[i]
                    val p = pulos[i].value
                    Box(
                        Modifier.size(74.dp).graphicsLayer {
                            translationY = -p * 190f; rotationZ = p * (if (i % 2 == 0) -16f else 16f)
                            scaleX = 1f + p * .2f; scaleY = 1f + p * .2f
                        }.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(6.dp)
                    ) { key(f.n) { Figura(f.arquivo, Modifier.fillMaxSize()) } }
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(if (esperando) "Falta só um toque seu" else "Buscando suas favoritas", fontSize = 27.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 32.sp)
            Spacer(Modifier.height(10.dp))
            Crossfade(targetState = if (esperando) -1 else frase, animationSpec = tween(300), label = "frase") { k ->
                Text(
                    when {
                        k == -1 -> "O WhatsApp ainda não mandou suas favoritas pra cá."
                        a.sync.estado.value == Sync.Estado.Processando -> a.sync.mensagem.value
                        else -> FRASES_ESPERA[k]
                    },
                    fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(22.dp))
            if (total > 0) {
                val prog by androidx.compose.animation.core.animateFloatAsState(feitas / total.toFloat(), label = "prog")
                LinearProgressIndicator(progress = { prog }, modifier = Modifier.fillMaxWidth(.8f).height(8.dp).clip(RoundedCornerShape(4.dp)))
                Spacer(Modifier.height(8.dp))
                Text("$feitas de $total", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (!esperando) {
                LinearProgressIndicator(Modifier.fillMaxWidth(.8f).height(8.dp).clip(RoundedCornerShape(4.dp)))
            }
            AnimatedVisibility(esperando, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(
                    Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("⭐", fontSize = 40.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Favorite uma figurinha agora", fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.height(6.dp))
                    Text("No WhatsApp, segure qualquer figurinha e toque na estrela. Depois volte aqui que eu continuo sozinho.",
                        fontSize = 16.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { a.espiarWhatsApp() }, Modifier.height(50.dp)) { Text("Abrir WhatsApp", fontSize = 16.sp) }
                }
            }
            Spacer(Modifier.height(26.dp))
            Text("Nada muda no seu WhatsApp. Isso roda por trás, só lendo as favoritas.", fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { a.sync.cancelar() }) { Text("Cancelar") }
                FilledTonalButton(onClick = { a.sync.manual = false }) { Text("Usar o app enquanto isso") }
            }
        }
    }
}

@Composable
private fun ResultadoSync(a: MainActivity, modifier: Modifier) {
    val r = a.resultadoSync.value
    var mostrado by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    if (r != null) mostrado = r
    LaunchedEffect(r) { if (r != null) { kotlinx.coroutines.delay(if (r.second) 9000 else 4500); a.resultadoSync.value = null } }
    AnimatedVisibility(r != null, modifier = modifier.statusBarsPadding().padding(16.dp, 10.dp),
        enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
        val (txt, erro) = mostrado ?: return@AnimatedVisibility
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(if (erro) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer)
                .clickable { a.resultadoSync.value = null }.padding(16.dp, 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (erro) "⚠️" else "✅", fontSize = 22.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (erro) "Não deu pra sincronizar" else "Favoritas sincronizadas", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    color = if (erro) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer)
                Text(txt, fontSize = 14.sp, color = if (erro) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer)
            }
            if (erro) TextButton(onClick = { a.resultadoSync.value = null; a.sincronizarAgora() }) { Text("Tentar de novo") }
        }
    }
}

// figurinha com som: o pacote nao leva o audio, entao o app manda pelo whatsapp web escondido,
// abrindo a conversa escolhida e tocando na figurinha das favoritas (vai como se fosse do computador)
@Composable
private fun EscolhaSom(a: MainActivity, q: String, res: List<Fig>, f: Fig) {
    val ctx = LocalContext.current
    val audio = remember(f) { Store.audio(ctx, f) }
    val conectado = a.conectado.value
    // ja vai abrindo o whatsapp web por tras enquanto a pessoa escolhe
    DisposableEffect(f) { a.sync.preparar(); onDispose { Ouvir.parar() } }
    val conversas = a.sync.listaConversas.value.filterNot { Regex("\\((you|você|voce)\\)", RegexOption.IGNORE_CASE).containsMatchIn(it) }.take(3)
    var outra by remember { mutableStateOf("") }
    fun fechar() { a.escolhaSom.value = null; a.sync.largar() }
    androidx.compose.ui.window.Dialog(onDismissRequest = { fechar() }) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(8.dp)) {
                Figura(f.arquivo, Modifier.fillMaxSize())
                Text("🔊", Modifier.align(Alignment.TopStart), fontSize = 16.sp)
            }
            if (audio != null) TextButton(onClick = { Ouvir.tocar(ctx, f) }) { Text("▶  Ouvir") }
            Spacer(Modifier.height(8.dp))
            Text("Mandar com som pra", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            if (!conectado) {
                Text("Conecte seu WhatsApp nos Ajustes primeiro.", fontSize = 16.sp, textAlign = TextAlign.Center)
            } else {
                conversas.forEachIndexed { i, c ->
                    if (i > 0) Spacer(Modifier.height(8.dp))
                    val cor = if (i == 0) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()
                    Button(onClick = { a.mandarComSom(f, c) }, Modifier.fillMaxWidth().height(50.dp), colors = cor) {
                        Text(c, fontSize = 16.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = outra, onValueChange = { outra = it }, singleLine = true,
                    placeholder = { Text("Outra conversa: nome do contato") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { if (outra.isNotBlank()) a.mandarComSom(f, outra.trim()) }),
                    trailingIcon = { if (outra.isNotBlank()) TextButton(onClick = { a.mandarComSom(f, outra.trim()) }) { Text("Mandar") } }
                )
            }
        }
    }
}

// ---------- configuracao inicial ----------

private val EXEMPLOS = listOf("gato comendo", "segunda-feira", "aguardando o pix", "tô com sono", "rindo de nervoso", "bom dia", "não sei")

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Configurar(a: MainActivity) {
    val total = 5
    val pager = rememberPagerState(initialPage = a.demoPasso.coerceIn(0, total - 1)) { total }
    val scope = rememberCoroutineScope()
    val atual = pager.currentPage
    val pode = when (atual) { 1 -> a.total.intValue > 0; 2 -> a.pacotesOk.value; else -> true }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        HorizontalPager(state = pager, modifier = Modifier.weight(1f), userScrollEnabled = pode || pager.currentPageOffsetFraction != 0f) { p ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp, 56.dp, 28.dp, 16.dp)) {
                when (p) {
                    0 -> PassoOla()
                    1 -> PassoFavoritas(a)
                    2 -> PassoPacotes(a)
                    3 -> PassoAuto(a)
                    else -> PassoAtalho(a)
                }
            }
        }
        AnimatedVisibility(!pode) {
            Text("Termine este passo pra seguir", Modifier.fillMaxWidth().padding(bottom = 4.dp), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(20.dp, 8.dp, 20.dp, 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(60.dp)) {
                if (atual > 0) OutlinedIconButton(onClick = { scope.launch { pager.animateScrollToPage(atual - 1) } }, Modifier.fillMaxSize()) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                repeat(total) { i ->
                    val largura by androidx.compose.animation.core.animateDpAsState(if (i == atual) 22.dp else 8.dp, label = "ponto")
                    Box(Modifier.padding(3.dp).height(8.dp).width(largura).clip(CircleShape)
                        .background(if (i == atual) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
                }
            }
            if (atual == total - 1) {
                Button(onClick = { a.concluirConfiguracao() }, Modifier.height(60.dp)) { Text("Começar", fontSize = 18.sp) }
            } else {
                FilledIconButton(onClick = { scope.launch { pager.animateScrollToPage(atual + 1) } }, enabled = pode, modifier = Modifier.size(60.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "Avançar")
                }
            }
        }
    }
}

@Composable
private fun TituloGrande(t: String, sub: String) {
    Text(t, fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold)
    Spacer(Modifier.height(12.dp))
    Text(sub, fontSize = 18.sp, lineHeight = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(32.dp))
}

@Composable
private fun PassoOla() {
    Box(Modifier.size(96.dp).clip(RoundedCornerShape(26.dp))) {
        Image(painterResource(R.drawable.ic_fundo), null, Modifier.fillMaxSize())
        Image(painterResource(R.drawable.ic_frente), null, Modifier.fillMaxSize())
    }
    Spacer(Modifier.height(32.dp))
    TituloGrande("Digitou, achou, mandou.", "Suas figurinhas favoritas pelo que elas mostram, dizem ou sentem.")
    // a busca de exemplo vai trocando sozinha pra mostrar o que da pra procurar
    var i by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1800); i = (i + 1) % EXEMPLOS.size } }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(18.dp, 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        AnimatedContent(targetState = EXEMPLOS[i], label = "exemplo",
            transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) }) { t ->
            Text(t, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }
    }
    Spacer(Modifier.height(36.dp))
    // tres "figurinhas" de enfeite pra dar o clima
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        listOf(Triple("😹", -8f, Color(0xFFFFC83D)), Triple("💸", 5f, Color(0xFF9B7BEA)), Triple("😴", -3f, Color(0xFF7BD3B5))).forEach { (e, giro, cor) ->
            Box(
                Modifier.padding(horizontal = 8.dp).size(84.dp).graphicsLayer { rotationZ = giro }
                    .clip(RoundedCornerShape(22.dp)).background(cor),
                contentAlignment = Alignment.Center
            ) { Text(e, fontSize = 44.sp) }
        }
    }
}

@Composable
private fun PassoFavoritas(a: MainActivity) {
    TituloGrande("Traga suas favoritas", "O app conecta no seu WhatsApp como mais um aparelho e copia a aba Favoritas. Só lê, nunca manda nada.")
    val ok = a.total.intValue > 0
    val st = a.sync.estado.value
    CartaoEstado(
        feito = ok, titulo = if (ok) "${a.total.intValue} figurinhas prontas" else "Conectar meu WhatsApp",
        texto = if (a.sync.rodando()) a.sync.mensagem.value.ifBlank { "Conectando…" } else if (ok) "Novas favoritas entram sozinhas daqui pra frente." else "Leva uns 2 minutos na primeira vez.",
        acao = if (ok && a.conectado.value) null else if (a.sync.rodando()) "Ver andamento" else "Conectar",
        carregando = false, onClick = { a.abrirConectar() }
    )
    if (st == Sync.Estado.Erro || st == Sync.Estado.Desconectado) {
        Spacer(Modifier.height(8.dp)); Text(a.sync.mensagem.value, color = MaterialTheme.colorScheme.error, fontSize = 15.sp)
    }
    Spacer(Modifier.height(12.dp))
    TextButton(onClick = { a.escolherArquivo() }) { Text(if (a.importando.value) "Importando…" else "Ou importar um arquivo .zip") }
}

@Composable
private fun PassoPacotes(a: MainActivity) {
    TituloGrande("Ganhe 2 pacotes 🔍", "É neles que a sua busca aparece, dentro do WhatsApp.")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SeloPacote(R.drawable.ic_tile, "Busca", MaterialTheme.colorScheme.primary)
        SeloPacote(R.drawable.ic_tile, "Busca GIF", Color(0xFF7A3FD1))
    }
    Spacer(Modifier.height(20.dp))
    CartaoEstado(
        feito = a.pacotesOk.value, titulo = if (a.pacotesOk.value) "Pacotes no WhatsApp" else "Adicionar ao WhatsApp",
        texto = if (a.pacotesOk.value) null else "Toque em \"Adicionar\" nas duas janelas.",
        acao = if (a.pacotesOk.value) null else "Adicionar pacotes", carregando = a.enviando.value, onClick = { a.adicionarPacotes {} }
    )
}

@Composable
private fun PassoAuto(a: MainActivity) {
    TituloGrande("Zero toques a mais", "O WhatsApp pede pra confirmar cada busca nova. O app pode tocar em \"Atualizar\" por você.")
    CartaoEstado(
        feito = a.autoOk.value, titulo = if (a.autoOk.value) "Confirmação automática ligada" else "Ligar confirmação automática",
        texto = if (a.autoOk.value) null else "Nas configurações, ative \"Figurinhas: confirmar pacote\". Opcional.",
        acao = if (a.autoOk.value) null else "Abrir configurações", carregando = false, onClick = { a.abrirAcessibilidade() }
    )
    if (!a.autoOk.value) {
        Spacer(Modifier.height(12.dp))
        Text("Se aparecer \"configuração restrita\": abra as informações do app, toque nos ⋮ e em \"Permitir configurações restritas\".",
            fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { a.abrirInfoDoApp() }) { Text("Abrir informações do app") }
    }
}

@Composable
private fun PassoAtalho(a: MainActivity) {
    TituloGrande("Busque de qualquer conversa", "Um atalho na barra de notificações abre a busca e te devolve pro chat.")
    CartaoEstado(
        feito = a.atalhoOk.value, titulo = if (a.atalhoOk.value) "Atalho adicionado" else "Adicionar atalho",
        texto = if (a.atalhoOk.value) null else "Opcional. O ícone do app também funciona.",
        acao = if (a.atalhoOk.value) null else "Adicionar atalho", carregando = false, onClick = { a.pedirAtalho() }
    )
}

@Composable
private fun SeloPacote(icone: Int, nome: String, cor: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(cor), contentAlignment = Alignment.Center) {
            Icon(painterResource(icone), null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(nome, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CartaoEstado(feito: Boolean, titulo: String, texto: String?, acao: String?, carregando: Boolean, onClick: () -> Unit) {
    val desativado = acao == null && !feito
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(if (feito) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (feito) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
            }
            Text(titulo, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = if (desativado) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        }
        if (texto != null) {
            Spacer(Modifier.height(6.dp))
            Text(texto, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (acao != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onClick, enabled = !carregando, modifier = Modifier.height(52.dp)) {
                if (carregando) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(10.dp)) }
                Text(acao, fontSize = 17.sp)
            }
        }
    }
}

// ---------- busca ----------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun Busca(a: MainActivity) {
    var q by rememberSaveable { mutableStateOf(a.demoBusca) }
    var res by remember { mutableStateOf<List<Fig>>(emptyList()) }
    var detalhe by remember { mutableStateOf<Fig?>(null) }
    var ajustes by remember { mutableStateOf(a.demoAjustes) }
    val foco = remember { FocusRequester() }

    // na primeira busca depois de abrir, a ia ainda pode estar carregando: nao mostra "nada" antes da hora
    var buscou by remember { mutableStateOf("") }
    LaunchedEffect(q, a.total.intValue) {
        delay(60)
        res = withContext(Dispatchers.Default) { a.buscar(q) }
        buscou = q
        // guarda a busca que nao achou nada (so depois de parar de digitar)
        if (res.isEmpty() && q.trim().length >= 3) { delay(1800); a.registrarSemResultado(q) }
    }
    LaunchedEffect(Unit) { foco.requestFocus() }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        AnimatedVisibility(a.nova.value != null) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp, 8.dp, 16.dp, 0.dp).clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer).padding(14.dp, 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Tem uma versão nova do app", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                TextButton(onClick = { a.atualizarApp() }, enabled = !a.baixandoUpdate.value) {
                    Text(if (a.baixandoUpdate.value) "Baixando…" else "Atualizar")
                }
            }
        }

        AnimatedVisibility(a.sync.estado.value == Sync.Estado.Sincronizando || a.sync.estado.value == Sync.Estado.Processando) {
            Column(Modifier.fillMaxWidth().padding(16.dp, 8.dp, 16.dp, 0.dp)) {
                Text(if (a.sync.total.intValue > 0) "Sincronizando favoritas… ${a.sync.feitas.intValue} de ${a.sync.total.intValue}" else "Sincronizando favoritas…",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                if (a.sync.total.intValue > 0) LinearProgressIndicator(progress = { a.sync.feitas.intValue / a.sync.total.intValue.toFloat() }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 8.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = q, onValueChange = { q = it },
                modifier = Modifier.weight(1f).focusRequester(foco),
                placeholder = {
                    var i by remember { mutableIntStateOf(0) }
                    LaunchedEffect(Unit) { while (true) { delay(2600); i = (i + 1) % EXEMPLOS.size } }
                    AnimatedContent(EXEMPLOS[i], label = "dica",
                        transitionSpec = { (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut()) }) { t ->
                        Text("Buscar \"$t\"", maxLines = 1)
                    }
                },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Default.Clear, "Limpar busca") } },
                singleLine = true, shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (q.isNotBlank()) a.enviar(q, res, null) })
            )
            IconButton(onClick = { ajustes = true }) { Icon(Icons.Default.Settings, "Ajustes") }
        }

        when {
            a.total.intValue == 0 -> Vazio("Nenhuma figurinha ainda", "Abra os ajustes e importe seu arquivo de figurinhas.")
            q.isBlank() -> {
                if (a.recentes.value.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(20.dp, 4.dp, 8.dp, 0.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Recentes", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { a.limparHistorico() }) { Text("Limpar") }
                    }
                    Chips(a.recentes.value, null) { q = it }
                }
                Row(
                    Modifier.fillMaxWidth().padding(16.dp, 6.dp).clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer).clickable { a.tela.value = Tela.Sugerir }.padding(16.dp, 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Sem ideia do que mandar?", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text("Cole a conversa e eu sugiro", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                if (a.maisUsadas.value.isNotEmpty()) {
                    Text("Mais usadas", Modifier.padding(20.dp, 10.dp, 20.dp, 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(a.maisUsadas.value, key = { it.n }) { f ->
                            Bloco(f, Modifier, { a.enviar("", listOf(f), f) }, { detalhe = it }, tamanho = 80.dp)
                        }
                    }
                }
                if (a.sugestoes.value.isNotEmpty()) {
                    Text("O que você quer passar?", Modifier.padding(20.dp, 10.dp, 20.dp, 2.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Chips(a.sugestoes.value, null) { q = it }
                }
                Legenda("Suas ${a.total.intValue} favoritas · toque pra mandar")
                Grade(res, { a.enviar("", res, it) }, { detalhe = it })
            }
            res.isEmpty() && buscou != q -> {
                Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.TopCenter) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                }
            }
            res.isEmpty() -> {
                Vazio("Nada com \"$q\"", "Tente o que aparece na figurinha ou o clima que você quer passar:")
                Chips(a.sugestoes.value, null) { q = it }
            }
            else -> {
                Legenda("${res.size} resultado${if (res.size == 1) "" else "s"} · toque pra mandar")
                Grade(res, { a.enviar(q, res, it) }, { detalhe = it })
            }
        }
    }

    // a figurinha escolhida "pula" pro centro enquanto vai pro pacote
    AnimatedVisibility(a.enviando.value, enter = fadeIn(tween(150)), exit = fadeOut(tween(200))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)), contentAlignment = Alignment.Center) {
            var cheio by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { cheio = true }
            val esc by androidx.compose.animation.core.animateFloatAsState(if (cheio) 1f else .3f,
                androidx.compose.animation.core.spring(dampingRatio = .45f, stiffness = 300f), label = "pulo")
            Column(Modifier.graphicsLayer { scaleX = esc; scaleY = esc }.clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                a.enviandoFig.value?.let { Figura(it.arquivo, Modifier.size(150.dp)) }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(if (a.sync.estado.value == Sync.Estado.Enviando) "Mandando com som pra ${a.sync.enviandoPara.value} 🔊" else "Indo pro pacote 🔍",
                        fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    detalhe?.let { f ->
        ModalBottomSheet(onDismissRequest = { detalhe = null }) {
            Column(Modifier.padding(24.dp, 0.dp, 24.dp, 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Figura(f.arquivo, Modifier.size(160.dp))
                val ctx = LocalContext.current
                if (Store.audio(ctx, f) != null) {
                    DisposableEffect(f) { onDispose { Ouvir.parar() } }
                    TextButton(onClick = { Ouvir.tocar(ctx, f) }) { Text("🔊  Ouvir o som") }
                } else if (Store.temSom(ctx, f)) {
                    Text("🔊 Tem som no WhatsApp", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
                Spacer(Modifier.height(16.dp))
                if (f.texto.isNotBlank()) {
                    Text("\"${f.texto}\"", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                }
                Text(f.tags, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Button(onClick = { detalhe = null; a.enviar(q, if (q.isBlank()) listOf(f) else res, f) }, Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Mandar no WhatsApp")
                }
                // se o app ainda nao sabe que ela tem som, da pra pedir o envio com som daqui
                TextButton(onClick = { detalhe = null; a.escolhaSom.value = Triple(q, if (q.isBlank()) listOf(f) else res, f) }) {
                    Text("🔊  Ela tem som? Mandar com som")
                }
            }
        }
    }

    if (ajustes) Ajustes(a) { ajustes = false }
}

@Composable
private fun Chips(itens: List<String>, icone: androidx.compose.ui.graphics.vector.ImageVector?, tocar: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        items(itens) { r ->
            AssistChip(onClick = { tocar(r) }, label = { Text(r, fontSize = 15.sp) }, shape = RoundedCornerShape(12.dp),
                leadingIcon = icone?.let { { Icon(it, null, Modifier.size(16.dp)) } })
        }
    }
}

@Composable
private fun Legenda(t: String) {
    Text(t, Modifier.padding(20.dp, 6.dp, 20.dp, 4.dp), style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Vazio(t: String, sub: String) {
    val giro = androidx.compose.animation.core.rememberInfiniteTransition(label = "giro")
    val ang by giro.animateFloat(-8f, 8f, androidx.compose.animation.core.infiniteRepeatable(tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "ang")
    Column(Modifier.fillMaxWidth().padding(32.dp, 40.dp, 32.dp, 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🤔", fontSize = 48.sp, modifier = Modifier.graphicsLayer { rotationZ = ang })
        Spacer(Modifier.height(10.dp))
        Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Grade(lista: List<Fig>, tocar: (Fig) -> Unit, segurar: (Fig) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(88.dp),
        contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 96.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(lista, key = { it.n }) { f -> Bloco(f, Modifier.animateItemPlacement(), tocar, segurar) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bloco(f: Fig, modifier: Modifier, tocar: (Fig) -> Unit, segurar: (Fig) -> Unit, tamanho: androidx.compose.ui.unit.Dp? = null) {
    val toque = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val apertado by toque.collectIsPressedAsState()
    val escala by androidx.compose.animation.core.animateFloatAsState(if (apertado) .9f else 1f,
        androidx.compose.animation.core.spring(dampingRatio = .5f, stiffness = 600f), label = "aperto")
    val vibra = androidx.compose.ui.platform.LocalHapticFeedback.current
    val ctx = LocalContext.current
    val comSom = remember(f.id) { Store.temSom(ctx, f) }
    Box(
        modifier.then(if (tamanho != null) Modifier.size(tamanho) else Modifier.aspectRatio(1f))
            .graphicsLayer { scaleX = escala; scaleY = escala }
            .clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(interactionSource = toque, indication = null,
                onClick = { vibra.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove); tocar(f) },
                onLongClick = { vibra.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); segurar(f) })
            .padding(6.dp)
    ) {
        Figura(f.arquivo, Modifier.fillMaxSize())
        if (comSom) {
            Text("🔊", Modifier.align(Alignment.TopStart).clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.secondary).padding(3.dp, 0.dp), fontSize = 11.sp)
        }
        if (f.animada) {
            Text("GIF", Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.secondary).padding(4.dp, 1.dp),
                fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Ajustes(a: MainActivity, fechar: () -> Unit) {
    var diag by remember { mutableStateOf<String?>(null) }
    var semRes by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val estado = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = fechar, sheetState = estado) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
            Text("Ajustes", Modifier.padding(24.dp, 0.dp, 24.dp, 4.dp), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text("${a.total.intValue} favoritas no app · versão ${Updater.versaoAtual(ctx)}", Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Grupo("Suas favoritas") {
                if (a.conectado.value) {
                    ItemAjuste(Icons.Default.Refresh, "Sincronizar agora",
                        if (a.sync.rodando()) "Sincronizando…" else "Automático a cada 6 horas") { fechar(); a.sincronizarAgora() }
                } else {
                    ItemAjuste(Icons.Default.Refresh, "Conectar meu WhatsApp", "Pra novas favoritas entrarem sozinhas") { fechar(); a.abrirConectar() }
                }
                ItemAjuste(Icons.Default.Add, "Importar arquivo .zip", "Junta com as que já estão no app") { a.escolherArquivo() }
                if (a.lixeira.intValue > 0) {
                    ItemAjuste(Icons.Default.Refresh, "Restaurar removidas", "${a.lixeira.intValue} saíram na sincronização, toque pra trazer de volta") { a.restaurarRemovidas() }
                }
            }
            Grupo("WhatsApp") {
                ItemAjuste(Icons.Default.Add, "Pacotes 🔍 no WhatsApp", if (a.pacotesOk.value) "Adicionados" else "Faltando, toque pra adicionar",
                    ok = a.pacotesOk.value) { a.adicionarPacotes {} }
                ItemAjuste(Icons.Default.ThumbUp, "Confirmação automática",
                    if (a.autoOk.value) "Ligada: o app toca em Atualizar por você" else "Desligada, toque pra ligar", ok = a.autoOk.value) { a.abrirAcessibilidade() }
                ItemAjuste(Icons.Default.Search, "Atalho na barra de notificações", "Abre a busca por cima de qualquer app") { a.pedirAtalho() }
                if (a.conectado.value) ItemAjuste(Icons.Default.Close, "Desconectar o WhatsApp Web", "Apaga a sessão guardada no app") { a.desconectarWhatsApp() }
            }
            Grupo("Busca") {
                ItemAjuste(Icons.Default.Delete, "Limpar histórico",
                    if (a.recentes.value.isEmpty()) "Nada salvo" else "${a.recentes.value.size} buscas salvas") { a.limparHistorico() }
                if (a.semResultado.value.isNotEmpty()) ItemAjuste(Icons.Default.Warning, "Buscas sem resultado", "${a.semResultado.value.size} guardadas só no celular") { semRes = true }
            }
            Grupo("Ajuda") {
                ItemAjuste(Icons.Default.PlayArrow, "Ver a configuração inicial", "O passo a passo de quem acabou de instalar") { fechar(); a.tela.value = Tela.Configurar }
                ItemAjuste(Icons.Default.Info, "Diagnóstico", "Registro técnico pra quando algo der errado") { diag = a.diagnostico() }
            }
        }
    }
    if (semRes) {
        AlertDialog(onDismissRequest = { semRes = false }, title = { Text("Buscas sem resultado") },
            text = { Text(a.semResultado.value.joinToString("\n"), Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { semRes = false }) { Text("Fechar") } })
    }
    diag?.let { d ->
        AlertDialog(onDismissRequest = { diag = null },
            title = { Text("Diagnóstico") },
            text = { Text(d, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), fontSize = 11.sp) },
            confirmButton = { TextButton(onClick = { diag = null }) { Text("Fechar") } },
            dismissButton = {
                TextButton(onClick = {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("diag", d))
                }) { Text("Copiar") }
            })
    }
}

@Composable
private fun Grupo(titulo: String, conteudo: @Composable ColumnScope.() -> Unit) {
    Text(titulo, Modifier.padding(24.dp, 20.dp, 24.dp, 6.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
        .padding(vertical = 4.dp), content = conteudo)
}

@Composable
private fun ItemAjuste(icone: androidx.compose.ui.graphics.vector.ImageVector, t: String, sub: String, ok: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center) {
            Icon(if (ok) Icons.Default.Check else icone, null, Modifier.size(20.dp),
                tint = if (ok) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(t, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------- conectar o whatsapp ----------

@Composable
private fun Conectar(a: MainActivity) {
    val st = a.sync.estado.value
    androidx.activity.compose.BackHandler { a.sairDeConectar() }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.padding(4.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { a.sairDeConectar() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
            Text("Conectar meu WhatsApp", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        if (st == Sync.Estado.Conectando || st == Sync.Estado.Desconectado) {
            Column(Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp).clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer).padding(16.dp)) {
                Text("1. Abaixo, toque em \"Entrar com número de telefone\" e digite seu número.", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("2. Guarde o código de 8 letras que aparecer.", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("3. No WhatsApp: ⋮ → Aparelhos conectados → Conectar aparelho → Conectar com número de telefone, e digite o código.", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = { a.abrirWhatsAppSo() }) { Text("Abrir WhatsApp") }
            }
            AndroidView(factory = { a.sync.soltar(); a.sync.web }, modifier = Modifier.weight(1f).fillMaxWidth())
        } else {
            Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                when (st) {
                    Sync.Estado.Pronto -> {
                        Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(36.dp))
                        }
                        Spacer(Modifier.height(20.dp))
                        Text("Favoritas sincronizadas", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text(a.sync.mensagem.value, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { a.sairDeConectar() }, Modifier.height(52.dp)) { Text("Continuar", fontSize = 17.sp) }
                    }
                    Sync.Estado.Erro -> {
                        Text("Não deu certo", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.height(8.dp))
                        Text(a.sync.mensagem.value, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { a.sincronizarAgora() }, Modifier.height(52.dp)) { Text("Tentar de novo", fontSize = 17.sp) }
                    }
                    else -> {
                        CircularProgressIndicator(Modifier.size(48.dp))
                        Spacer(Modifier.height(20.dp))
                        Text(a.sync.mensagem.value.ifBlank { "Conectado. Buscando suas favoritas…" }, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        if (a.sync.total.intValue > 0) {
                            Spacer(Modifier.height(16.dp))
                            LinearProgressIndicator(progress = { a.sync.feitas.intValue / a.sync.total.intValue.toFloat() }, modifier = Modifier.fillMaxWidth(.8f))
                            Spacer(Modifier.height(8.dp))
                            Text("${a.sync.feitas.intValue} de ${a.sync.total.intValue} · ${a.sync.novas.intValue} novas", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Pode deixar o app aberto. Na primeira vez leva uns 2 minutos.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

// ---------- sugestao pela conversa ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sugerir(a: MainActivity) {
    var texto by rememberSaveable { mutableStateOf(a.demoTexto) }
    var intencao by rememberSaveable { mutableStateOf<String?>(if (a.demoTexto.isNotEmpty()) "Ironia" else null) }
    var res by remember { mutableStateOf<List<Fig>>(emptyList()) }
    androidx.activity.compose.BackHandler { a.tela.value = Tela.Busca }
    LaunchedEffect(texto, intencao) {
        delay(250)
        res = withContext(Dispatchers.Default) { a.sugerir(texto, intencao) }
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        Row(Modifier.padding(4.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { a.tela.value = Tela.Busca }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
            Text("Me ajuda a responder", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedTextField(
            value = texto, onValueChange = { texto = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 110.dp, max = 180.dp),
            placeholder = { Text("Cole aqui a mensagem que você recebeu (pode ser a conversa toda)") },
            shape = RoundedCornerShape(20.dp)
        )
        Row(Modifier.padding(horizontal = 12.dp)) {
            TextButton(onClick = { texto = a.colarTexto() }) { Text("Colar do WhatsApp") }
            if (texto.isNotEmpty()) TextButton(onClick = { texto = "" }) { Text("Apagar") }
        }
        Text("O que você quer passar?", Modifier.padding(20.dp, 4.dp), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Store.INTENCOES.keys.toList()) { k ->
                FilterChip(selected = intencao == k, onClick = { intencao = if (intencao == k) null else k }, label = { Text(k, fontSize = 15.sp) })
            }
        }
        when {
            texto.isBlank() && intencao == null -> Vazio("Cole uma mensagem ou escolha um clima", "Eu procuro nas suas favoritas o que combina pra responder.")
            res.isEmpty() -> Vazio("Não achei nada que combine", "Tente escolher outro clima ou colar mais da conversa.")
            else -> {
                Legenda("Combinam com a conversa · toque pra mandar")
                Grade(res, { a.enviar(texto.take(40), res, it, conversa = true) }, {})
            }
        }
    }
}

// ---------- imagens (inclusive animadas) ----------

object ImgCache {
    private val cache = LruCache<String, Drawable>(120)
    private val emTela = java.util.IdentityHashMap<Drawable, Int>()
    fun get(k: String): Drawable? = cache.get(k)
    fun limpar() = cache.evictAll()
    fun carregar(ctx: Context, arquivo: String): Drawable? = try {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(Store.dir(ctx), arquivo))) { d, _, _ -> d.setTargetSize(256, 256) }
            .also { it.setBounds(0, 0, it.intrinsicWidth.coerceAtLeast(1), it.intrinsicHeight.coerceAtLeast(1)); cache.put(arquivo, it) }
    } catch (e: Exception) { null }

    // a mesma figurinha pode estar em dois lugares (mais usadas e grade): so para quando sair de todos
    fun apareceu(d: Drawable) { val n = (emTela[d] ?: 0) + 1; emTela[d] = n; if (n == 1) (d as? AnimatedImageDrawable)?.start() }
    fun sumiu(d: Drawable) { val n = (emTela[d] ?: 1) - 1; if (n <= 0) { emTela.remove(d); (d as? AnimatedImageDrawable)?.stop() } else emTela[d] = n }
}

@Composable
fun Figura(arquivo: String, modifier: Modifier) {
    val ctx = LocalContext.current
    var d by remember(arquivo) { mutableStateOf(ImgCache.get(arquivo)) }
    LaunchedEffect(arquivo) { if (d == null) d = withContext(Dispatchers.IO) { ImgCache.carregar(ctx, arquivo) } }
    val dr = d
    // so anima enquanto esta na tela; fora dela a grade descarta o item e a animacao para
    DisposableEffect(dr) {
        if (dr != null) ImgCache.apareceu(dr)
        onDispose { if (dr != null) ImgCache.sumiu(dr) }
    }
    val opac by androidx.compose.animation.core.animateFloatAsState(if (dr != null) 1f else 0f, tween(160), label = "opac")
    androidx.compose.foundation.Canvas(modifier.graphicsLayer { alpha = opac }) {
        val dd = dr ?: return@Canvas
        val w = dd.bounds.width().toFloat(); val h = dd.bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return@Canvas
        val esc = minOf(size.width / w, size.height / h)
        drawContext.canvas.nativeCanvas.apply {
            save()
            translate((size.width - w * esc) / 2f, (size.height - h * esc) / 2f)
            scale(esc, esc)
            dd.draw(this)
            restore()
        }
    }
}
