package br.rafael.figurinhas

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder

// transcreve o audio das figurinhas com som pra busca achar pelo que e falado
// usa o reconhecimento de voz do proprio android, lendo o arquivo em vez do microfone (android 13+)
object Ouvir {
    private var rodando = false
    private var player: MediaPlayer? = null

    fun podeTranscrever(ctx: Context) = Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isRecognitionAvailable(ctx)
    fun temPermissao(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun faltando(ctx: Context): List<Fig> = Store.figs(ctx).filter { f ->
        Store.audio(ctx, f) != null && !File(Store.dir(ctx), "${f.id}.fala").exists()
    }

    // chamado no fim da sincronizacao e ao abrir o app
    fun pendentes(ctx: Context) {
        if (rodando || !podeTranscrever(ctx) || !temPermissao(ctx)) return
        val fila = faltando(ctx).toMutableList()
        if (fila.isEmpty()) return
        rodando = true
        fun proxima() {
            val f = fila.removeFirstOrNull() ?: run { rodando = false; return }
            transcrever(ctx, Store.audio(ctx, f)!!) { texto ->
                if (texto != null) {
                    Store.log(ctx, "ouvir: ${f.id} -> \"$texto\"")
                    Thread { Store.adicionarFala(ctx, f.id, texto); Handler(Looper.getMainLooper()).post { proxima() } }.start()
                } else proxima()
            }
        }
        proxima()
    }

    fun tocar(ctx: Context, f: Fig) {
        val arq = Store.audio(ctx, f) ?: return
        parar()
        player = try { MediaPlayer().apply { setDataSource(arq.path); prepare(); start(); setOnCompletionListener { parar() } } } catch (e: Exception) { null }
    }

    fun parar() { player?.runCatching { stop(); release() }; player = null }

    private fun transcrever(ctx: Context, arq: File, fim: (String?) -> Unit) {
        val pcm = File(ctx.cacheDir, "fala.pcm")
        Thread {
            val info = try { decodificar(arq, pcm) } catch (e: Exception) { Store.log(ctx, "ouvir: nao decodificou ${arq.name}: $e"); null }
            Handler(Looper.getMainLooper()).post {
                if (info == null) fim(null) else reconhecer(ctx, pcm, info, fim)
            }
        }.start()
    }

    // decodifica qualquer audio (opus, aac, mp3...) pra pcm 16 bits mono
    private fun decodificar(arq: File, destino: File): Int {
        val ex = MediaExtractor(); ex.setDataSource(arq.path)
        val faixa = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        ex.selectTrack(faixa)
        val fmt = ex.getTrackFormat(faixa)
        val cod = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        cod.configure(fmt, null, null, 0); cod.start()
        var taxa = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var canais = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        var acabouEntrada = false
        FileOutputStream(destino).use { out ->
            while (true) {
                if (!acabouEntrada) {
                    val i = cod.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(cod.getInputBuffer(i)!!, 0)
                        if (n < 0) { cod.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); acabouEntrada = true }
                        else { cod.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = cod.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    taxa = cod.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    canais = cod.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val buf = cod.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val s = buf.asShortBuffer()
                    val mono = ByteArray(s.remaining() / canais * 2)
                    var k = 0
                    while (s.remaining() >= canais) {
                        var soma = 0; repeat(canais) { soma += s.get() }
                        val v = soma / canais
                        mono[k++] = (v and 0xff).toByte(); mono[k++] = (v shr 8 and 0xff).toByte()
                    }
                    out.write(mono, 0, k)
                    cod.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        cod.stop(); cod.release(); ex.release()
        return taxa
    }

    private fun reconhecer(ctx: Context, pcm: File, taxa: Int, fim: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) { fim(null); return }
        val sr = SpeechRecognizer.createSpeechRecognizer(ctx)
        val pipe = ParcelFileDescriptor.createPipe()
        var terminou = false
        val h = Handler(Looper.getMainLooper())
        fun acabar(t: String?) { if (terminou) return; terminou = true; sr.destroy(); runCatching { pipe[0].close() }; fim(t) }
        h.postDelayed({ if (!terminou) Store.log(ctx, "ouvir: tempo esgotado"); acabar(null) }, 25_000)
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(r: Bundle?) { acabar(r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }) }
            override fun onError(e: Int) { Store.log(ctx, "ouvir: erro $e"); acabar(if (e == SpeechRecognizer.ERROR_NO_MATCH) "" else null) }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, taxa)
        }
        sr.startListening(intent)
        // manda o audio e depois um pouco de silencio pra ele entender que acabou
        Thread {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                    out.write(pcm.readBytes())
                    out.write(ByteArray(taxa * 2 * 2))
                }
            } catch (e: Exception) { }
        }.start()
    }
}
