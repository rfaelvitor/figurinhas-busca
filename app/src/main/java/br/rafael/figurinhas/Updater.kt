package br.rafael.figurinhas

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// confere a ultima release no github e instala por cima (o android pede 1 toque pra confirmar)
object Updater {
    private const val API = "https://api.github.com/repos/rfaelvitor/figurinhas-busca/releases/latest"

    data class Versao(val numero: Int, val url: String)

    fun versaoAtual(ctx: Context): Int =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()

    // roda fora da thread principal
    fun buscarNova(ctx: Context): Versao? = try {
        val c = URL(API).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 8000; c.readTimeout = 8000
        if (c.responseCode != 200) null else {
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            val n = o.getString("tag_name").removePrefix("build-").toIntOrNull() ?: 0
            val assets = o.getJSONArray("assets")
            val url = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }?.getString("browser_download_url")
            if (url != null && n > versaoAtual(ctx)) Versao(n, url) else null
        }
    } catch (e: Exception) {
        Store.log(ctx, "atualizador: falhou ao checar (${e.message})"); null
    }

    fun podeInstalar(a: Activity): Boolean {
        if (a.packageManager.canRequestPackageInstalls()) return true
        Toast.makeText(a, "Permita \"Instalar apps desconhecidos\" pro Figurinhas e toque em Atualizar de novo", Toast.LENGTH_LONG).show()
        a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
        return false
    }

    // roda fora da thread principal
    fun baixarEInstalar(ctx: Context, v: Versao) {
        val apk = File(ctx.cacheDir, "atualizacao.apk")
        // https -> https, o HttpURLConnection segue o redirecionamento do github sozinho
        val c = URL(v.url).openConnection() as HttpURLConnection
        c.inputStream.use { ins -> apk.outputStream().use { ins.copyTo(it) } }

        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("app.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val p = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstalouReceiver::class.java), flags)
            s.commit(p.intentSender)
        }
        Store.log(ctx, "atualizador: enviando versao ${v.numero} pro instalador")
    }
}

class InstalouReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmar = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirmar?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirmar != null) ctx.startActivity(confirmar)
            }
            PackageInstaller.STATUS_SUCCESS -> Store.log(ctx, "atualizador: instalado")
            else -> Store.log(ctx, "atualizador: status $st ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
