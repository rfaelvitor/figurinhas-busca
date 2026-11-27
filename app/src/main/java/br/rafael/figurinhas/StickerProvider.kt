package br.rafael.figurinhas

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

// segue o contrato do app de exemplo do whatsapp (github.com/WhatsApp/stickers)
class StickerProvider : ContentProvider() {

    private val colsPack = arrayOf(
        "sticker_pack_identifier", "sticker_pack_name", "sticker_pack_publisher", "sticker_pack_icon",
        "android_play_store_link", "ios_app_download_link", "sticker_pack_publisher_email",
        "sticker_pack_publisher_website", "sticker_pack_privacy_policy_website",
        "sticker_pack_license_agreement_website", "image_data_version",
        "whatsapp_will_not_cache_stickers", "animated_sticker_pack"
    )
    private val colsSticker = arrayOf("sticker_file_name", "sticker_emoji", "sticker_accessibility_text")

    override fun onCreate() = true

    private fun linhaPack(c: MatrixCursor, id: String) {
        val ctx = context!!
        val versao = Store.versao(ctx, id).toString()
        val anim = id == Store.PACK_ANIM
        c.addRow(arrayOf<Any>(
            id, if (anim) "🔍 Busca GIF" else "🔍 Busca", "Figurinhas", "tray_$id.png",
            "", "", "", "", "", "", versao, 1, if (anim) 1 else 0
        ))
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val seg = uri.pathSegments
        Store.log(context!!, "query ${uri.path} por ${callingPackage ?: "?"}")
        return when {
            seg.size == 1 && seg[0] == "metadata" -> MatrixCursor(colsPack).also { c ->
                linhaPack(c, Store.PACK_EST); linhaPack(c, Store.PACK_ANIM)
            }.also { it.setNotificationUri(context!!.contentResolver, uri) }
            seg.size == 2 && seg[0] == "metadata" -> MatrixCursor(colsPack).also { c ->
                if (seg[1] == Store.PACK_EST || seg[1] == Store.PACK_ANIM) linhaPack(c, seg[1])
            }.also { it.setNotificationUri(context!!.contentResolver, uri) }
            seg.size == 2 && seg[0] == "stickers" -> MatrixCursor(colsSticker).also { c ->
                val figs = Store.figs(context!!).associateBy { it.arquivo }
                for (arq in Store.arquivosDoPacote(context!!, seg[1])) {
                    val f = figs[arq]
                    c.addRow(arrayOf<Any>(arq, "😀", (f?.texto?.ifBlank { f.tags } ?: "figurinha").take(120)))
                }
            }.also { it.setNotificationUri(context!!.contentResolver, uri) }
            else -> throw IllegalArgumentException("uri desconhecida: $uri")
        }
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val seg = uri.pathSegments
        if (seg.size != 3 || seg[0] != "stickers_asset") return null
        val ctx = context!!
        val nome = seg[2]
        // cada arquivo lido pelo whatsapp enchia o diagnostico e empurrava o resto pra fora
        val arq = if (nome.startsWith("tray_")) File(ctx.filesDir, nome) else File(Store.dir(ctx), nome)
        if (!arq.exists() || arq.parentFile?.canonicalPath !in listOf(ctx.filesDir.canonicalPath, Store.dir(ctx).canonicalPath)) return null
        val pfd = ParcelFileDescriptor.open(arq, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, arq.length())
    }

    override fun getType(uri: Uri): String {
        val seg = uri.pathSegments
        return when {
            seg.size == 1 && seg[0] == "metadata" -> "vnd.android.cursor.dir/vnd.${Store.AUTH}.metadata"
            seg.size == 2 && seg[0] == "metadata" -> "vnd.android.cursor.item/vnd.${Store.AUTH}.metadata"
            seg.size == 2 && seg[0] == "stickers" -> "vnd.android.cursor.dir/vnd.${Store.AUTH}.stickers"
            seg.size == 3 && seg[2].startsWith("tray_") -> "image/png"
            seg.size == 3 -> "image/webp"
            else -> throw IllegalArgumentException("uri desconhecida: $uri")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
}
