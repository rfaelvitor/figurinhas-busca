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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Crossfade(targetState = a.tela.value, animationSpec = tween(250), label = "tela") { t ->
                when (t) {
                    Tela.Configurar -> Configurar(a)
                    Tela.Busca -> Busca(a)
                    Tela.Sugerir -> Sugerir(a)
                }
            }
            a.aviso.value?.let { msg ->
                AlertDialog(onDismissRequest = { a.aviso.value = null },
                    confirmButton = { TextButton(onClick = { a.aviso.value = null }) { Text("Ok") } },
                    text = { Text(msg) })
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
    TituloGrande("Traga suas favoritas", "O app guarda uma cópia delas pra buscar num piscar de olhos.")
    val ok = a.total.intValue > 0
    CartaoEstado(
        feito = ok, titulo = if (ok) "${a.total.intValue} figurinhas prontas" else "Importar arquivo",
        texto = if (ok) null else "O .zip de figurinhas que você recebeu.",
        acao = if (a.importando.value) "Importando…" else if (ok) "Trocar arquivo" else "Escolher arquivo",
        carregando = a.importando.value, onClick = { a.escolherArquivo() }
    )
    Spacer(Modifier.height(12.dp))
    CartaoEstado(feito = false, titulo = "Conectar meu WhatsApp", texto = "Em breve: puxa suas favoritas sozinho.", acao = null, carregando = false, onClick = {})
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

    LaunchedEffect(q, a.total.intValue) {
        delay(60)
        res = withContext(Dispatchers.Default) { a.buscar(q) }
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

        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 8.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = q, onValueChange = { q = it },
                modifier = Modifier.weight(1f).focusRequester(foco),
                placeholder = { Text("Buscar figurinha") },
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
                    Chips(a.recentes.value, Icons.Default.Refresh) { q = it }
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
                if (a.sugestoes.value.isNotEmpty()) {
                    Text("Sugestões", Modifier.padding(20.dp, 10.dp, 20.dp, 2.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Chips(a.sugestoes.value, null) { q = it }
                }
                Legenda("Suas ${a.total.intValue} favoritas · toque pra mandar")
                Grade(res, { a.enviar("", res, it) }, { detalhe = it })
            }
            res.isEmpty() -> {
                Vazio("Nada com \"$q\"", "Tente o que aparece na figurinha ou o que ela sente. Umas ideias:")
                Chips(a.sugestoes.value, null) { q = it }
            }
            else -> {
                Legenda("${res.size} resultado${if (res.size == 1) "" else "s"} · toque pra mandar")
                Grade(res, { a.enviar(q, res, it) }, { detalhe = it })
            }
        }
    }

    if (a.enviando.value) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)), contentAlignment = Alignment.Center) {
            Row(Modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(20.dp, 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.width(14.dp))
                Text("Colocando no pacote 🔍 do WhatsApp…")
            }
        }
    }

    detalhe?.let { f ->
        ModalBottomSheet(onDismissRequest = { detalhe = null }) {
            Column(Modifier.padding(24.dp, 0.dp, 24.dp, 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Figura(f.arquivo, Modifier.size(160.dp))
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
            }
        }
    }

    if (ajustes) Ajustes(a) { ajustes = false }
}

@Composable
private fun Chips(itens: List<String>, icone: androidx.compose.ui.graphics.vector.ImageVector?, tocar: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        items(itens) { r ->
            AssistChip(onClick = { tocar(r) }, label = { Text(r, fontSize = 15.sp) },
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
    Column(Modifier.fillMaxWidth().padding(32.dp, 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
        contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(lista, key = { it.n }) { f ->
            Box(
                Modifier.aspectRatio(1f).clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .combinedClickable(onClick = { tocar(f) }, onLongClick = { segurar(f) })
                    .padding(6.dp)
            ) {
                Figura(f.arquivo, Modifier.fillMaxSize())
                if (f.animada) {
                    Text("GIF", Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.secondary).padding(4.dp, 1.dp),
                        fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondary)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Ajustes(a: MainActivity, fechar: () -> Unit) {
    var diag by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    ModalBottomSheet(onDismissRequest = fechar) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text("Ajustes", Modifier.padding(24.dp, 0.dp, 24.dp, 8.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            ItemAjuste(Icons.Default.Refresh, "Importar figurinhas", "${a.total.intValue} no app agora") { a.escolherArquivo() }
            ItemAjuste(Icons.Default.Add, "Adicionar pacotes ao WhatsApp",
                if (a.pacotesOk.value) "Já adicionados" else "Faltando, toque pra adicionar") { a.adicionarPacotes {} }
            ItemAjuste(Icons.Default.Delete, "Limpar histórico de buscas",
                if (a.recentes.value.isEmpty()) "Nada salvo" else "${a.recentes.value.size} buscas salvas") { a.limparHistorico() }
            ItemAjuste(Icons.Default.CheckCircle, "Confirmação automática",
                if (a.autoOk.value) "Ligada: o app toca em Atualizar por você" else "Desligada, toque pra ligar") { a.abrirAcessibilidade() }
            ItemAjuste(Icons.Default.Search, "Atalho nas configurações rápidas", "Abre a busca por cima de qualquer app") { a.pedirAtalho() }
            ItemAjuste(Icons.Default.PlayArrow, "Ver a configuração inicial", "O passo a passo que um usuário novo vê") { fechar(); a.tela.value = Tela.Configurar }
            ItemAjuste(Icons.Default.Info, "Diagnóstico", "Registro do que o WhatsApp pediu ao app") { diag = a.diagnostico() }
            Text("Versão ${Updater.versaoAtual(ctx)}", Modifier.padding(24.dp, 12.dp), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
private fun ItemAjuste(icone: androidx.compose.ui.graphics.vector.ImageVector, t: String, sub: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(t) }, supportingContent = { Text(sub) },
        leadingContent = { Icon(icone, null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
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
                Grade(res, { a.enviar(texto.take(40), res, it) }, {})
            }
        }
    }
}

// ---------- imagens (inclusive animadas) ----------

object ImgCache {
    private val cache = LruCache<String, Drawable>(120)
    fun get(k: String): Drawable? = cache.get(k)
    fun limpar() = cache.evictAll()
    fun carregar(ctx: Context, arquivo: String): Drawable? = try {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(Store.dir(ctx), arquivo))) { d, _, _ -> d.setTargetSize(256, 256) }
            .also { cache.put(arquivo, it) }
    } catch (e: Exception) { null }
}

@Composable
fun Figura(arquivo: String, modifier: Modifier) {
    val ctx = LocalContext.current
    var d by remember(arquivo) { mutableStateOf(ImgCache.get(arquivo)) }
    LaunchedEffect(arquivo) { if (d == null) d = withContext(Dispatchers.IO) { ImgCache.carregar(ctx, arquivo) } }
    AnimatedVisibility(visible = d != null, enter = fadeIn(tween(160)), modifier = modifier) {
        AndroidView(
            factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
            update = { iv -> if (iv.drawable !== d) { iv.setImageDrawable(d); (d as? AnimatedImageDrawable)?.start() } },
            modifier = Modifier.fillMaxSize()
        )
    }
}
