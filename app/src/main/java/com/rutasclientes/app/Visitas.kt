package com.rutasclientes.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registro de cada visita: si pidió cambios (producto caducado/dañado) o no, y comentarios. */
data class Visita(
    val fecha: Long = System.currentTimeMillis(),
    val resultado: String = "VISITADA",   // VISITADA | NO_VISITADA
    val cambios: String = "",             // NO | SI | "" (no se registró)
    val detalle: String = "",             // qué cambió (ej. "3 Danonino fresa caducados")
    val comentario: String = "",
) {
    fun toJson() = JSONObject().put("fecha", fecha).put("resultado", resultado).put("cambios", cambios)
        .put("detalle", detalle).put("comentario", comentario)

    /** Una línea corta para la lista / el aviso. */
    fun resumen(): String {
        val f = Visitas.fecha(fecha)
        if (resultado == "NO_VISITADA") return "$f: ✕ no se visitó" + if (comentario.isNotBlank()) " ($comentario)" else ""
        val c = when (cambios) {
            "NO" -> "NO pidió cambios"
            "SI" -> "SÍ cambios" + if (detalle.isNotBlank()) ": $detalle" else ""
            else -> "visitado"
        }
        return "$f: $c" + if (comentario.isNotBlank()) " · $comentario" else ""
    }

    companion object {
        fun fromJson(o: JSONObject) = Visita(o.optLong("fecha"), o.optString("resultado", "VISITADA"),
            o.optString("cambios"), o.optString("detalle"), o.optString("comentario"))
    }
}

object Visitas {
    private val fmt = SimpleDateFormat("EEE d MMM", Locale("es", "MX"))
    private val fmtLargo = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale("es", "MX"))
    fun fecha(t: Long): String = fmt.format(Date(t))

    fun ultima(p: Parada) = p.historial.lastOrNull()
    /** Última visita en la que SÍ se registró si pidió cambios o no. */
    fun ultimaConRespuesta(p: Parada) = p.historial.lastOrNull { it.cambios.isNotBlank() }

    /**
     * Pantalla al marcar ✓: muestra lo que dijo la visita anterior y pide registrar la de hoy.
     * @param alGuardar recibe la visita (o null si solo se marca sin registrar).
     */
    fun dialogo(ctx: Context, p: Parada, alGuardar: (Visita?) -> Unit) {
        val d = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val caja = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(px(22), px(6), px(22), 0) }

        ultimaConRespuesta(p)?.let { prev ->
            val sinCambios = prev.cambios == "NO"
            caja.addView(TextView(ctx).apply {
                text = (if (sinCambios) "⚠ OJO: " else "ℹ Visita anterior: ") + prev.resumen() +
                    if (sinCambios) "\nSi hoy pide cambio de caducados, la semana pasada dijo que NO tenía." else ""
                setTextColor(Color.WHITE); textSize = 14f
                setPadding(px(10), px(8), px(10), px(8))
                setBackgroundColor(if (sinCambios) Color.parseColor("#B71C1C") else Color.parseColor("#37474F"))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(10) }
            })
        }

        caja.addView(TextView(ctx).apply { text = "¿Pidió cambios de producto hoy?"; textSize = 15f })
        val grupo = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL }
        val rNo = RadioButton(ctx).apply { id = View.generateViewId(); text = "No pidió" }
        val rSi = RadioButton(ctx).apply { id = View.generateViewId(); text = "Sí pidió" }
        grupo.addView(rNo); grupo.addView(rSi)
        caja.addView(grupo)
        val detalle = EditText(ctx).apply {
            hint = "¿Qué y cuánto? (ej. 4 Danonino fresa caducado)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            visibility = View.GONE
        }
        caja.addView(detalle)
        grupo.setOnCheckedChangeListener { _, id -> detalle.visibility = if (id == rSi.id) View.VISIBLE else View.GONE }
        val comentario = EditText(ctx).apply {
            hint = "Comentario (pedido, encargado, acuerdo…)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        caja.addView(comentario)

        AlertDialog.Builder(ctx).setTitle("✓ Visita a ${p.nombre}")
            .setView(android.widget.ScrollView(ctx).apply { addView(caja) })
            .setPositiveButton("Guardar visita") { _, _ ->
                val c = when (grupo.checkedRadioButtonId) { rNo.id -> "NO"; rSi.id -> "SI"; else -> "" }
                alGuardar(Visita(cambios = c, detalle = if (c == "SI") detalle.text.toString().trim() else "",
                    comentario = comentario.text.toString().trim()))
            }
            .setNeutralButton("Solo marcar") { _, _ -> alGuardar(Visita()) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Historial completo de un cliente, con opción de mandarlo por WhatsApp como evidencia. */
    fun historial(ctx: Context, p: Parada) {
        val texto = if (p.historial.isEmpty()) "Aún no hay visitas registradas."
            else p.historial.asReversed().joinToString("\n\n") { v ->
                fmtLargo.format(Date(v.fecha)) + "\n" + v.resumen().substringAfter(": ")
            }
        AlertDialog.Builder(ctx).setTitle("📋 Visitas de ${p.nombre}").setMessage(texto)
            .setPositiveButton("Cerrar", null)
            .apply {
                if (p.historial.isNotEmpty()) setNeutralButton("Compartir") { _, _ ->
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "Historial de visitas – ${p.nombre}\n\n$texto")
                    }, "Compartir historial"))
                }
            }.show()
    }
}
