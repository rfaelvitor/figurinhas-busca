package br.rafael.figurinhas

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

// confirma o "Update" do pacote que o whatsapp mostra quando o app reenvia o pacote
// so age nos segundos logo depois que o proprio app pediu
class AutoConfirma : AccessibilityService() {

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e?.packageName != "com.whatsapp") return
        if (System.currentTimeMillis() - pedidoEm > 12_000) return
        val raiz = rootInActiveWindow ?: return
        for (t in TEXTOS) {
            for (n in raiz.findAccessibilityNodeInfosByText(t)) {
                if (n.text?.toString()?.trim()?.equals(t, ignoreCase = true) != true) continue
                var c: AccessibilityNodeInfo? = n
                while (c != null && !c.isClickable) c = c.parent
                if (c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                    pedidoEm = 0
                    Store.log(this, "confirmacao automatica: tocou em \"$t\"")
                    return
                }
            }
        }
    }

    override fun onInterrupt() {}

    companion object {
        @Volatile var pedidoEm = 0L
        private val TEXTOS = listOf("Update", "Atualizar", "Actualizar", "Add", "Adicionar", "Agregar", "Add to WhatsApp", "Adicionar ao WhatsApp")

        fun ativo(ctx: Context): Boolean {
            val lista = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val eu = ComponentName(ctx, AutoConfirma::class.java)
            return lista.split(':').any { ComponentName.unflattenFromString(it) == eu }
        }
    }
}
