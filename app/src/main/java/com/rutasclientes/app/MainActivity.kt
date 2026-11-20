package com.rutasclientes.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rutasclientes.app.databinding.ActivityMainBinding
import com.rutasclientes.app.databinding.ItemRutaBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding

    private val guardarRespaldo = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.openOutputStream(uri)?.use { it.write(Almacen.respaldo(this).toByteArray()) }
        Toast.makeText(this, "Respaldo guardado", Toast.LENGTH_SHORT).show()
    }
    private val abrirRespaldo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            val txt = contentResolver.openInputStream(uri)!!.bufferedReader().readText()
            Almacen.restaurar(this, txt)
        }.onSuccess { Toast.makeText(this, "Se restauraron $it rutas", Toast.LENGTH_LONG).show(); refrescar() }
            .onFailure { Toast.makeText(this, "Ese archivo no es un respaldo de Mis Rutas", Toast.LENGTH_LONG).show() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.subtitle = "v${BuildConfig.VERSION_NAME}"
        b.toolbar.inflateMenu(R.menu.main)
        b.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.respaldo -> guardarRespaldo.launch("mis-rutas-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json")
                R.id.restaurar -> abrirRespaldo.launch(arrayOf("application/json", "text/*", "*/*"))
                R.id.actualizar -> buscarActualizacion(true)
            }
            true
        }
        b.lista.layoutManager = LinearLayoutManager(this)
        b.nueva.setOnClickListener { dialogoRuta(null) }
        buscarActualizacion(false)
    }

    override fun onResume() { super.onResume(); refrescar() }

    /** 1 = lunes … 7 = domingo */
    private fun diaHoy(): Int = ((Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1

    private fun refrescar() {
        val hoy = diaHoy()
        val rutas = Almacen.rutas(this).sortedWith(compareBy({ if (it.dia == hoy) 0 else 1 }, { if (it.dia == 0) 9 else it.dia }, { it.nombre }))
        val deHoy = rutas.firstOrNull { it.dia == hoy }
        b.hoy.text = if (deHoy != null) "Hoy es ${Ruta.DIAS[hoy]}: te toca la ruta ${deHoy.nombre} (${deHoy.paradas.size} clientes)"
        else "Hoy es ${Ruta.DIAS[hoy]}"
        b.vacio.visibility = if (rutas.isEmpty()) View.VISIBLE else View.GONE
        b.lista.adapter = Adaptador(rutas, hoy)
    }

    private inner class Adaptador(val rutas: List<Ruta>, val hoy: Int) : RecyclerView.Adapter<Adaptador.VH>() {
        inner class VH(val v: ItemRutaBinding) : RecyclerView.ViewHolder(v.root)
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(ItemRutaBinding.inflate(LayoutInflater.from(p.context), p, false))
        override fun getItemCount() = rutas.size
        override fun onBindViewHolder(h: VH, i: Int) {
            val r = rutas[i]
            h.v.nombre.text = (if (r.dia == hoy) "⭐ " else "") + r.nombre + if (r.dia > 0) " / ${Ruta.DIAS[r.dia]}" else ""
            h.v.detalle.text = "${r.paradas.size} clientes · ✓ ${r.visitadas} · ✕ ${r.noVisitadas} · faltan ${r.pendientes}"
            h.v.avance.max = maxOf(1, r.paradas.size)
            h.v.avance.progress = r.visitadas + r.noVisitadas
            h.v.root.setOnClickListener {
                startActivity(Intent(this@MainActivity, RutaActivity::class.java).putExtra("id", r.id))
            }
            h.v.root.setOnLongClickListener { dialogoRuta(r); true }
        }
    }

    fun dialogoRuta(r: Ruta?) {
        val v = layoutInflater.inflate(R.layout.dialog_ruta, null)
        val nombre = v.findViewById<EditText>(R.id.nombre)
        val dia = v.findViewById<Spinner>(R.id.dia)
        dia.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Ruta.DIAS)
        nombre.setText(r?.nombre ?: "")
        dia.setSelection(r?.dia ?: diaHoy())
        val d = AlertDialog.Builder(this)
            .setTitle(if (r == null) "Nueva ruta" else "Editar ruta")
            .setView(v)
            .setPositiveButton("Guardar") { _, _ ->
                val n = nombre.text.toString().trim().ifBlank { "Ruta" }
                if (r == null) Almacen.rutas(this).add(Ruta(nombre = n, dia = dia.selectedItemPosition))
                else { r.nombre = n; r.dia = dia.selectedItemPosition }
                Almacen.guardar(this); refrescar()
            }
            .setNegativeButton("Cancelar", null)
        if (r != null) d.setNeutralButton("Eliminar") { _, _ ->
            AlertDialog.Builder(this).setMessage("¿Eliminar la ruta ${r.nombre} con sus ${r.paradas.size} clientes?")
                .setPositiveButton("Eliminar") { _, _ -> Almacen.rutas(this).remove(r); Almacen.guardar(this); refrescar() }
                .setNegativeButton("Cancelar", null).show()
        }
        d.show()
    }

    // ---- actualización de la app (mismo sistema que Wifi Manager) ----
    private fun buscarActualizacion(avisarSiNoHay: Boolean) {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                try { Updater.check() } catch (e: Exception) { Updater.Result(false, 0, e.message ?: "Sin conexión") }
            }
            if (r.hayNueva) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Nueva versión")
                    .setMessage("Hay una versión nueva (v${r.versionRemota}). ¿Descargar e instalar? Tus rutas no se borran.")
                    .setPositiveButton("Actualizar") { _, _ -> descargarEInstalar() }
                    .setNegativeButton("Ahora no", null).show()
            } else if (avisarSiNoHay) Toast.makeText(this@MainActivity, r.nota, Toast.LENGTH_SHORT).show()
        }
    }

    private fun descargarEInstalar() {
        Toast.makeText(this, "Descargando…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                try { Result.success(Updater.download(this@MainActivity)) } catch (e: Exception) { Result.failure(e) }
            }
            res.onSuccess { instalar(it) }
                .onFailure { Toast.makeText(this@MainActivity, "No se pudo descargar: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun instalar(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Permiso para actualizar")
                .setMessage("Activa \"Permitir de esta fuente\" para Mis Rutas y vuelve a tocar Buscar actualización.")
                .setPositiveButton("Abrir ajustes") { _, _ ->
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Cancelar", null).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
