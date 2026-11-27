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

@Composable
private fun Configurar(a: MainActivity) {
    var passo by rememberSaveable { mutableIntStateOf(0) }
    val ultimo = 3
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(ultimo + 1) { i ->
                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape)
                    .background(if (i <= passo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
            }
        }
        AnimatedContent(
            targetState = passo, modifier = Modifier.weight(1f),
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(280)) { it * dir / 4 } + fadeIn(tween(280))) togetherWith
                    (slideOutHorizontally(tween(200)) { -it * dir / 4 } + fadeOut(tween(200)))
            }, label = "passo"
        ) { p ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 40.dp)) {
                when (p) {
                    0 -> PassoBoasVindas()
                    1 -> PassoFavoritas(a)
                    2 -> PassoPacotes(a)
                    else -> PassoAtalho(a)
                }
            }
        }
        val podeSeguir = when (passo) { 1 -> a.total.intValue > 0; 2 -> a.pacotesOk.value; else -> true }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (passo > 0) TextButton(onClick = { passo-- }) { Text("Voltar") }
            Spacer(Modifier.weight(1f))
            if (passo == ultimo) {
                Button(onClick = { a.concluirConfiguracao() }, modifier = Modifier.height(52.dp)) { Text("Começar a buscar") }
            } else {
                Button(onClick = { passo++ }, enabled = podeSeguir, modifier = Modifier.height(52.dp)) {
                    Text(if (passo == 0) "Configurar" else "Continuar")
                }
            }
        }
    }
}

@Composable
private fun PassoBoasVindas() {
    // o icone adaptativo nao renderiza direto no compose, entao monta com as duas camadas
    Box(Modifier.size(112.dp).clip(RoundedCornerShape(30.dp))) {
        Image(painterResource(R.drawable.ic_fundo), null, Modifier.fillMaxSize())
        Image(painterResource(R.drawable.ic_frente), null, Modifier.fillMaxSize())
    }
    Spacer(Modifier.height(28.dp))
    Text("Ache qualquer figurinha favorita digitando", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    Text(
        "Escreva o que a figurinha mostra ou o que está escrito nela, tipo \"esqueleto\" ou \"cala a boca\". " +
            "Ela aparece num pacote próprio dentro do WhatsApp, pronta pra mandar, até as animadas.",
        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(24.dp))
    Text("A configuração leva uns 2 minutos e só é feita uma vez.", style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PassoFavoritas(a: MainActivity) {
    Titulo("Traga suas favoritas", "O app precisa de uma cópia das suas figurinhas, com a descrição de cada uma, pra conseguir buscar.")
    val ok = a.total.intValue > 0
    CartaoEstado(
        feito = ok,
        titulo = if (ok) "${a.total.intValue} figurinhas prontas pra busca" else "Importar arquivo de figurinhas",
        texto = if (ok) "Se quiser trocar, importe outro arquivo." else "Escolha o arquivo .zip de figurinhas que você recebeu.",
        acao = if (a.importando.value) "Importando…" else if (ok) "Importar outro" else "Escolher arquivo",
        carregando = a.importando.value, onClick = { a.escolherArquivo() }
    )
    Spacer(Modifier.height(12.dp))
    CartaoEstado(
        feito = false, titulo = "Conectar meu WhatsApp",
        texto = "Em breve: o app vai puxar e descrever suas favoritas sozinho, sem precisar de arquivo.",
        acao = null, carregando = false, onClick = {}
    )
}

@Composable
private fun PassoPacotes(a: MainActivity) {
    Titulo("Adicione os pacotes de busca", "Os resultados vão aparecer em dois pacotes na sua aba de figurinhas do WhatsApp: um pra figurinhas normais e outro pras animadas.")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SeloPacote(R.drawable.ic_tile, "🔍 Busca", MaterialTheme.colorScheme.primary)
        SeloPacote(R.drawable.ic_tile, "🔍 Busca GIF", Color(0xFF7A3FD1))
    }
    Spacer(Modifier.height(20.dp))
    CartaoEstado(
        feito = a.pacotesOk.value,
        titulo = if (a.pacotesOk.value) "Pacotes adicionados" else "Adicionar ao WhatsApp",
        texto = if (a.pacotesOk.value) "Tudo certo. Eles já aparecem no WhatsApp." else "O WhatsApp vai abrir duas vezes pra confirmar. Toque em \"Adicionar\" nas duas.",
        acao = if (a.pacotesOk.value) null else "Adicionar pacotes", carregando = a.enviando.value,
        onClick = { a.adicionarPacotes {} }
    )
}

@Composable
private fun PassoAtalho(a: MainActivity) {
    Titulo("Busque sem sair da conversa", "Um atalho nas configurações rápidas abre a busca por cima do WhatsApp. Depois de tocar na figurinha, você volta direto pro chat.")
    CartaoEstado(
        feito = a.atalhoOk.value,
        titulo = if (a.atalhoOk.value) "Atalho adicionado" else "Adicionar atalho \"Buscar figurinha\"",
        texto = if (a.atalhoOk.value) "Puxe a barra de notificações pra usar." else "Opcional. Dá pra abrir pelo ícone do app também.",
        acao = if (a.atalhoOk.value) null else "Adicionar atalho", carregando = false, onClick = { a.pedirAtalho() }
    )
}

@Composable
private fun Titulo(t: String, sub: String) {
    Text(t, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text(sub, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun SeloPacote(icone: Int, nome: String, cor: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp, 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(cor), contentAlignment = Alignment.Center) {
            Icon(painterResource(icone), null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(nome, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun CartaoEstado(feito: Boolean, titulo: String, texto: String, acao: String?, carregando: Boolean, onClick: () -> Unit) {
    val desativado = acao == null && !feito
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(if (feito) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (feito) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(10.dp))
            }
            Text(titulo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = if (desativado) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(6.dp))
        Text(texto, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (acao != null) {
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(onClick = onClick, enabled = !carregando) {
                if (carregando) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(acao)
            }
        }
    }
}

// ---------- busca ----------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun Busca(a: MainActivity) {
    var q by rememberSaveable { mutableStateOf("") }
    var res by remember { mutableStateOf<List<Fig>>(emptyList()) }
    var detalhe by remember { mutableStateOf<Fig?>(null) }
    var ajustes by remember { mutableStateOf(false) }
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
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 6.dp)) {
                        items(a.recentes.value) { r -> SuggestionChip(onClick = { q = r }, label = { Text(r) }) }
                    }
                }
                Legenda("Suas ${a.total.intValue} favoritas · toque pra mandar")
                Grade(res, { a.enviar("", res, it) }, { detalhe = it })
            }
            res.isEmpty() -> Vazio("Nada com \"$q\"", "Tente o que aparece na figurinha (gato, cachorro) ou o sentimento dela (triste, rindo).")
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
            ItemAjuste(Icons.Default.Search, "Atalho nas configurações rápidas", "Abre a busca por cima de qualquer app") { a.pedirAtalho() }
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
