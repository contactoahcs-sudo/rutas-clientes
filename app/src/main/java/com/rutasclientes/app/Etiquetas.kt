package com.rutasclientes.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Etiqueta(val id: String = UUID.randomUUID().toString(), var texto: String, var color: Int) {
    fun toJson() = JSONObject().put("id", id).put("texto", texto).put("color", color)
    companion object {
        fun fromJson(o: JSONObject) = Etiqueta(o.optString("id"), o.optString("texto"), o.optInt("color", Color.GRAY))
    }
}

/** Etiquetas de color con texto propio (ej. "Crédito", "Cobrar"), se le ponen a cada cliente. */
object Etiquetas {
    val COLORES = listOf("#E53935", "#FB8C00", "#FDD835", "#43A047", "#00ACC1", "#1E88E5", "#8E24AA", "#EC407A", "#6D4C41", "#757575")
        .map { Color.parseColor(it) }

    private var cache: MutableList<Etiqueta>? = null
    private fun file(ctx: Context) = File(ctx.filesDir, "etiquetas.json")

    fun todas(ctx: Context): MutableList<Etiqueta> {
        cache?.let { return it }
        val f = file(ctx)
        val lista = if (f.exists()) runCatching {
            val a = JSONArray(f.readText()); (0 until a.length()).map { Etiqueta.fromJson(a.getJSONObject(it)) }.toMutableList()
        }.getOrElse { mutableListOf() } else mutableListOf(  // sugerencias para empezar (se pueden borrar)
            Etiqueta(texto = "Crédito", color = COLORES[1]),
            Etiqueta(texto = "Cobrar", color = COLORES[0]),
            Etiqueta(texto = "Paga de contado", color = COLORES[3]),
            Etiqueta(texto = "Pedido grande", color = COLORES[6]),
            Etiqueta(texto = "Horario especial", color = COLORES[5]),
        )
        cache = lista
        if (!f.exists()) guardar(ctx)
        return lista
    }

    fun guardar(ctx: Context) = file(ctx).writeText(aJson(ctx).toString())
    fun aJson(ctx: Context) = JSONArray().apply { todas(ctx).forEach { put(it.toJson()) } }
    fun de(ctx: Context, p: Parada) = p.etiquetas.mapNotNull { id -> todas(ctx).firstOrNull { it.id == id } }

    /** Suma etiquetas recibidas (de otro teléfono o un respaldo) que no existan. */
    fun mezclar(ctx: Context, a: JSONArray?) {
        a ?: return
        val mias = todas(ctx)
        for (i in 0 until a.length()) {
            val e = Etiqueta.fromJson(a.getJSONObject(i))
            if (mias.none { it.id == e.id }) mias.add(e)
        }
        guardar(ctx)
    }

    // ---------------- vistas ----------------
    fun chip(ctx: Context, e: Etiqueta, grande: Boolean = false): TextView {
        val d = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            text = e.texto
            textSize = if (grande) 15f else 11f
            setTextColor(if (esClaro(e.color)) Color.BLACK else Color.WHITE)
            setPadding((8 * d).toInt(), (2 * d).toInt(), (8 * d).toInt(), (2 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 10 * d; setColor(e.color) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = (4 * d).toInt(); topMargin = (3 * d).toInt() }
        }
    }

    private fun esClaro(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 > 170

    fun pintarEn(ctx: Context, cont: LinearLayout, p: Parada) {
        cont.removeAllViews()
        val es = de(ctx, p)
        es.forEach { cont.addView(chip(ctx, it)) }
        cont.visibility = if (es.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Elegir etiquetas de un cliente (varias). */
    fun elegir(ctx: Context, p: Parada, alTerminar: () -> Unit) {
        val todas = todas(ctx)
        val d = ctx.resources.displayMetrics.density
        val lista = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0) }
        val elegidas = p.etiquetas.toMutableSet()
        fun llenar() {
            lista.removeAllViews()
            if (todas.isEmpty()) lista.addView(TextView(ctx).apply { text = "Aún no tienes etiquetas. Toca \"Nueva\"." })
            todas.forEach { e ->
                val fila = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
                }
                val cb = android.widget.CheckBox(ctx).apply { isChecked = e.id in elegidas }
                cb.setOnCheckedChangeListener { _, on -> if (on) elegidas.add(e.id) else elegidas.remove(e.id) }
                fila.addView(cb); fila.addView(chip(ctx, e, grande = true))
                fila.setOnClickListener { cb.isChecked = !cb.isChecked }
                fila.setOnLongClickListener { editar(ctx, e) { llenar() }; true }
                lista.addView(fila)
            }
            lista.addView(TextView(ctx).apply {
                text = "Mantén presionada una etiqueta para cambiarle texto o color."
                textSize = 12f; setTextColor(Color.GRAY); setPadding(0, (8 * d).toInt(), 0, 0)
            })
        }
        llenar()
        val dlg = AlertDialog.Builder(ctx).setTitle("🏷 Etiquetas de ${p.nombre}")
            .setView(android.widget.ScrollView(ctx).apply { addView(lista) })
            .setPositiveButton("Guardar") { _, _ ->
                p.etiquetas.clear(); p.etiquetas.addAll(todas.filter { it.id in elegidas }.map { it.id }); alTerminar()
            }
            .setNeutralButton("+ Nueva", null)
            .setNegativeButton("Cancelar", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                editar(ctx, null) { nueva -> nueva?.let { elegidas.add(it.id) }; llenar() }
            }
        }
        dlg.show()
    }

    /** Crear (e == null) o editar una etiqueta: texto + color. */
    fun editar(ctx: Context, e: Etiqueta?, alTerminar: (Etiqueta?) -> Unit) {
        val d = ctx.resources.displayMetrics.density
        var color = e?.color ?: COLORES[0]
        val caja = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0) }
        val texto = EditText(ctx).apply {
            hint = "Texto (ej. Crédito, Cobrar el viernes, Dueño: Don Pepe)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(e?.texto ?: "")
        }
        val vista = TextView(ctx)
        fun actualizarVista() {
            val tmp = Etiqueta(texto = texto.text.toString().ifBlank { "Vista previa" }, color = color)
            val c = chip(ctx, tmp, grande = true)
            vista.text = c.text; vista.background = c.background; vista.setTextColor(c.currentTextColor)
            vista.setPadding(c.paddingLeft, c.paddingTop, c.paddingRight, c.paddingBottom); vista.textSize = 15f
        }
        texto.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = actualizarVista()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val paleta = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt()) }
        val puntos = COLORES.map { c ->
            View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, (30 * d).toInt(), 1f).apply { marginEnd = (4 * d).toInt() }
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
            }
        }
        fun marcar() = puntos.forEachIndexed { i, v ->
            (v.background as GradientDrawable).setStroke(if (COLORES[i] == color) (3 * d).toInt() else 0, Color.WHITE)
        }
        puntos.forEachIndexed { i, v -> v.setOnClickListener { color = COLORES[i]; marcar(); actualizarVista() }; paleta.addView(v) }
        marcar(); actualizarVista()
        caja.addView(texto); caja.addView(paleta); caja.addView(vista)
        val b = AlertDialog.Builder(ctx).setTitle(if (e == null) "Nueva etiqueta" else "Editar etiqueta").setView(caja)
            .setPositiveButton("Guardar") { _, _ ->
                val t = texto.text.toString().trim()
                if (t.isBlank()) { alTerminar(null); return@setPositiveButton }
                val res = if (e == null) Etiqueta(texto = t, color = color).also { todas(ctx).add(it) }
                    else e.also { it.texto = t; it.color = color }
                guardar(ctx); alTerminar(res)
            }
            .setNegativeButton("Cancelar", null)
        if (e != null) b.setNeutralButton("Borrar") { _, _ ->
            AlertDialog.Builder(ctx).setMessage("¿Borrar la etiqueta \"${e.texto}\"? Se quita de todos los clientes.")
                .setPositiveButton("Borrar") { _, _ ->
                    todas(ctx).remove(e); guardar(ctx)
                    Almacen.rutas(ctx).forEach { r -> r.paradas.forEach { it.etiquetas.remove(e.id) } }
                    Almacen.guardar(ctx); alTerminar(null)
                }.setNegativeButton("Cancelar", null).show()
        }
        b.show()
    }

    /** Administrar todas las etiquetas (desde el menú principal). */
    fun administrar(ctx: Context) {
        val todas = todas(ctx)
        val nombres = (todas.map { "● ${it.texto}" } + "+ Nueva etiqueta").toTypedArray()
        AlertDialog.Builder(ctx).setTitle("🏷 Mis etiquetas")
            .setItems(nombres) { _, w ->
                if (w == todas.size) editar(ctx, null) { administrar(ctx) } else editar(ctx, todas[w]) { administrar(ctx) }
            }.setNegativeButton("Cerrar", null).show()
    }
}
