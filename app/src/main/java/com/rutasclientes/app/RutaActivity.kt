package com.rutasclientes.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.material.snackbar.Snackbar
import com.rutasclientes.app.databinding.ActivityRutaBinding
import com.rutasclientes.app.databinding.ItemParadaBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.Locale

class RutaActivity : AppCompatActivity() {
    private lateinit var b: ActivityRutaBinding
    private lateinit var ruta: Ruta
    private var mapa: GoogleMap? = null
    private var miPos: LatLng? = null
    private var esperandoToque = false
    private var primerEncuadre = true
    private val adaptador = Adaptador()
    private val ubic by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private val callbackUbic = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            r.lastLocation?.let { miPos = LatLng(it.latitude, it.longitude) }
        }
    }

    private val pedirPermiso = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (tienePermiso()) activarUbicacion()
        else Toast.makeText(this, "Sin permiso de ubicación no puedo mostrar dónde estás", Toast.LENGTH_LONG).show()
    }

    private val importar = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importarDe(it) } }
    private val exportar = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.openOutputStream(uri)?.use { it.write(Archivos.csv(ruta).toByteArray()) }
        Toast.makeText(this, "Exportado. Se abre con Excel o Google Sheets.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val r = Almacen.ruta(this, intent.getStringExtra("id") ?: "")
        if (r == null) { finish(); return }
        ruta = r
        b = ActivityRutaBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.toolbar.inflateMenu(R.menu.ruta)
        b.toolbar.setOnMenuItemClickListener { menu(it.itemId); true }
        titulo()

        b.lista.layoutManager = LinearLayoutManager(this)
        b.lista.adapter = adaptador
        arrastrarParaReordenar()

        b.agregar.setOnClickListener { elegirComoAgregar() }
        b.optimizar.setOnClickListener { optimizar() }
        b.siguiente.setOnClickListener {
            val sig = ruta.paradas.firstOrNull { it.estado == Estado.PENDIENTE }
            if (sig == null) Toast.makeText(this, "¡Ya no quedan clientes pendientes!", Toast.LENGTH_SHORT).show()
            else navegar(sig)
        }

        (supportFragmentManager.findFragmentById(R.id.mapa) as SupportMapFragment).getMapAsync { m ->
            mapa = m
            m.uiSettings.isZoomControlsEnabled = true
            m.uiSettings.isMapToolbarEnabled = false
            m.setOnMapLongClickListener { p -> nuevaParadaEn(p) }
            m.setOnMapClickListener { p -> if (esperandoToque) { esperandoToque = false; b.ayudaMapa.visibility = View.GONE; nuevaParadaEn(p) } }
            m.setOnMarkerClickListener { mk ->
                val i = mk.tag as? Int ?: return@setOnMarkerClickListener false
                b.lista.smoothScrollToPosition(i); false
            }
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(19.5438, -96.9102), 13f)) // Xalapa
            if (tienePermiso()) activarUbicacion()
            else pedirPermiso.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            pintar()
        }
        pintar()
    }

    override fun onPause() { super.onPause(); ubic.removeLocationUpdates(callbackUbic) }
    override fun onResume() { super.onResume(); if (tienePermiso() && mapa != null) activarUbicacion() }

    private fun titulo() {
        b.toolbar.title = ruta.nombre + if (ruta.dia > 0) " / ${Ruta.DIAS[ruta.dia]}" else ""
    }

    private fun tienePermiso() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun activarUbicacion() {
        mapa?.isMyLocationEnabled = true
        ubic.lastLocation.addOnSuccessListener { it?.let { l -> if (miPos == null) miPos = LatLng(l.latitude, l.longitude) } }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).setMinUpdateDistanceMeters(5f).build()
        ubic.requestLocationUpdates(req, callbackUbic, Looper.getMainLooper())
    }

    @SuppressLint("MissingPermission")
    private fun conUbicacionActual(accion: (LatLng?) -> Unit) {
        if (!tienePermiso()) { accion(miPos); return }
        ubic.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { l -> if (l != null) miPos = LatLng(l.latitude, l.longitude); accion(miPos) }
            .addOnFailureListener { accion(miPos) }
    }

    // ---------------- mapa y lista ----------------
    private fun pintar() {
        adaptador.notifyDataSetChanged()
        val total = ruta.paradas.size
        b.conteo.text = "PARADAS ($total)   ✓ ${ruta.visitadas}   ✕ ${ruta.noVisitadas}   Faltan ${ruta.pendientes}" +
            if (total > 0) "   ·  ${"%.1f".format(Optimizador.longitudKm(ruta.paradas, null))} km" else ""
        val m = mapa ?: return
        m.clear()
        if (ruta.paradas.isEmpty()) return
        val puntos = ruta.paradas.map { LatLng(it.lat, it.lng) }
        m.addPolyline(PolylineOptions().addAll(puntos).color(Color.argb(150, 242, 140, 40)).width(6f))
        ruta.paradas.forEachIndexed { i, p ->
            m.addMarker(MarkerOptions().position(LatLng(p.lat, p.lng)).title("${i + 1}. ${p.nombre}")
                .snippet(p.direccion.ifBlank { p.nota }).icon(icono(i + 1, p.estado)))?.tag = i
        }
        if (primerEncuadre) {
            primerEncuadre = false
            b.root.post {
                runCatching {
                    if (puntos.size == 1) m.moveCamera(CameraUpdateFactory.newLatLngZoom(puntos[0], 16f))
                    else m.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.builder().apply { puntos.forEach { include(it) } }.build(), 80))
                }
            }
        }
    }

    private fun color(e: Estado) = when (e) {
        Estado.PENDIENTE -> Color.parseColor("#1E88E5")
        Estado.VISITADA -> Color.parseColor("#43A047")
        Estado.NO_VISITADA -> Color.parseColor("#E53935")
    }

    private fun icono(n: Int, e: Estado): BitmapDescriptor {
        val d = resources.displayMetrics.density
        val s = (34 * d).toInt()
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE; c.drawCircle(s / 2f, s / 2f, s / 2f, p)
        p.color = color(e); c.drawCircle(s / 2f, s / 2f, s / 2f - 2 * d, p)
        p.color = Color.WHITE; p.textSize = (if (n > 99) 11 else 14) * d; p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true
        c.drawText(n.toString(), s / 2f, s / 2f - (p.descent() + p.ascent()) / 2, p)
        return BitmapDescriptorFactory.fromBitmap(bmp)
    }

    private inner class Adaptador : RecyclerView.Adapter<Adaptador.VH>() {
        inner class VH(val v: ItemParadaBinding) : RecyclerView.ViewHolder(v.root)
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(ItemParadaBinding.inflate(LayoutInflater.from(p.context), p, false))
        override fun getItemCount() = ruta.paradas.size
        override fun onBindViewHolder(h: VH, i: Int) {
            val p = ruta.paradas[i]
            h.v.numero.text = (i + 1).toString()
            h.v.numero.background.mutate().setTint(color(p.estado))
            h.v.nombre.text = p.nombre
            h.v.detalle.text = listOf(p.direccion, p.telefono, p.nota).filter { it.isNotBlank() }.joinToString(" · ")
            h.v.detalle.visibility = if (h.v.detalle.text.isBlank()) View.GONE else View.VISIBLE
            h.v.root.alpha = if (p.estado == Estado.PENDIENTE) 1f else 0.6f
            h.v.si.setOnClickListener { marcar(p, Estado.VISITADA) }
            h.v.no.setOnClickListener { marcar(p, Estado.NO_VISITADA) }
            h.v.ir.setOnClickListener { navegar(p) }
            h.v.root.setOnClickListener {
                mapa?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lng), 17f))
            }
            h.v.root.setOnLongClickListener { opcionesParada(p); true }
            h.v.numero.setOnLongClickListener { moverConNumero(h); true } // mantener el número para arrastrar
        }
    }

    private fun marcar(p: Parada, e: Estado) {
        p.estado = if (p.estado == e) Estado.PENDIENTE else e   // tocar otra vez lo desmarca
        p.marcadaEn = if (p.estado == Estado.PENDIENTE) 0 else System.currentTimeMillis()
        Almacen.guardar(this); pintar()
        if (ruta.pendientes == 0 && ruta.paradas.isNotEmpty())
            Snackbar.make(b.root, "¡Ruta terminada! ✓ ${ruta.visitadas} visitados, ✕ ${ruta.noVisitadas} sin visita", Snackbar.LENGTH_LONG).show()
    }

    private fun arrastrarParaReordenar() {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(rv: RecyclerView, a: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder): Boolean {
                Collections.swap(ruta.paradas, a.bindingAdapterPosition, t.bindingAdapterPosition)
                adaptador.notifyItemMoved(a.bindingAdapterPosition, t.bindingAdapterPosition); return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
            override fun isLongPressDragEnabled() = false
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh); Almacen.guardar(this@RutaActivity); pintar()
            }
        }).also { helper ->
            helper.attachToRecyclerView(b.lista)
            moverConNumero = { vh -> helper.startDrag(vh) }
        }
    }
    private var moverConNumero: (RecyclerView.ViewHolder) -> Unit = {}

    private fun navegar(p: Parada) {
        val g = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${p.lat},${p.lng}")).setPackage("com.google.android.apps.maps")
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${p.lat},${p.lng}?q=${p.lat},${p.lng}(${Uri.encode(p.nombre)})"))
        runCatching { startActivity(g) }.recoverCatching { startActivity(geo) }
            .onFailure { Toast.makeText(this, "No encontré Google Maps ni Waze", Toast.LENGTH_SHORT).show() }
    }

    // ---------------- optimizar ----------------
    private fun optimizar() {
        if (ruta.paradas.size < 2) { Toast.makeText(this, "Agrega al menos 2 clientes", Toast.LENGTH_SHORT).show(); return }
        conUbicacionActual { pos ->
            val inicio = pos?.let { it.latitude to it.longitude }
            val hechas = ruta.paradas.filter { it.estado != Estado.PENDIENTE }
            val pendientes = ruta.paradas.filter { it.estado == Estado.PENDIENTE }
            val antesKm = Optimizador.longitudKm(pendientes, inicio)
            val nuevas = Optimizador.optimizar(pendientes, inicio)
            val despuesKm = Optimizador.longitudKm(nuevas, inicio)
            val respaldo = ruta.paradas.toList()
            ruta.paradas.clear(); ruta.paradas.addAll(hechas + nuevas)
            Almacen.guardar(this); pintar()
            val desde = if (inicio != null) "desde donde estás" else "sin tu ubicación"
            Snackbar.make(b.root, "Ruta optimizada $desde: ${"%.1f".format(antesKm)} → ${"%.1f".format(despuesKm)} km (línea recta)", Snackbar.LENGTH_LONG)
                .setAction("Deshacer") { ruta.paradas.clear(); ruta.paradas.addAll(respaldo); Almacen.guardar(this); pintar() }
                .show()
        }
    }

    // ---------------- agregar / editar ----------------
    private fun elegirComoAgregar() {
        val opciones = arrayOf("📍 Aquí donde estoy", "👆 Tocar el lugar en el mapa", "🔎 Buscar por dirección")
        AlertDialog.Builder(this).setTitle("Agregar cliente").setItems(opciones) { _, w ->
            when (w) {
                0 -> conUbicacionActual { p ->
                    if (p == null) Toast.makeText(this, "Todavía no tengo tu ubicación. Revisa que el GPS esté prendido.", Toast.LENGTH_LONG).show()
                    else nuevaParadaEn(p)
                }
                1 -> { esperandoToque = true; b.ayudaMapa.visibility = View.VISIBLE }
                2 -> buscarDireccion()
            }
        }.show()
    }

    @Suppress("DEPRECATION")
    private fun buscarDireccion() {
        val et = EditText(this).apply { hint = "Calle y número, colonia"; setPadding(48, 32, 48, 32) }
        AlertDialog.Builder(this).setTitle("Buscar dirección").setView(et)
            .setPositiveButton("Buscar") { _, _ ->
                val q = et.text.toString().trim()
                if (q.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching { Geocoder(this@RutaActivity, Locale("es", "MX")).getFromLocationName("$q, Xalapa, Veracruz", 1)?.firstOrNull() }.getOrNull()
                    }
                    if (r == null) Toast.makeText(this@RutaActivity, "No encontré esa dirección. Prueba tocando el mapa.", Toast.LENGTH_LONG).show()
                    else {
                        val p = LatLng(r.latitude, r.longitude)
                        mapa?.animateCamera(CameraUpdateFactory.newLatLngZoom(p, 17f))
                        nuevaParadaEn(p, r.getAddressLine(0) ?: q)
                    }
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    @Suppress("DEPRECATION")
    private fun nuevaParadaEn(p: LatLng, direccion: String? = null) {
        val nueva = Parada(nombre = "", lat = p.latitude, lng = p.longitude, direccion = direccion ?: "")
        if (direccion == null) lifecycleScope.launch {
            val dir = withContext(Dispatchers.IO) {
                runCatching { Geocoder(this@RutaActivity, Locale("es", "MX")).getFromLocation(p.latitude, p.longitude, 1)?.firstOrNull()?.getAddressLine(0) }.getOrNull()
            }
            dialogoParada(nueva, true, dir ?: "")
        } else dialogoParada(nueva, true, direccion)
    }

    private fun dialogoParada(p: Parada, esNueva: Boolean, dirSugerida: String = p.direccion) {
        val v = layoutInflater.inflate(R.layout.dialog_parada, null)
        val nom = v.findViewById<EditText>(R.id.nombre).apply { setText(p.nombre) }
        val dir = v.findViewById<EditText>(R.id.direccion).apply { setText(dirSugerida) }
        val tel = v.findViewById<EditText>(R.id.telefono).apply { setText(p.telefono) }
        val nota = v.findViewById<EditText>(R.id.nota).apply { setText(p.nota) }
        AlertDialog.Builder(this).setTitle(if (esNueva) "Cliente #${ruta.paradas.size + 1}" else "Editar cliente")
            .setView(v)
            .setPositiveButton("Guardar") { _, _ ->
                p.nombre = nom.text.toString().trim().ifBlank { "Cliente ${ruta.paradas.size + 1}" }
                p.direccion = dir.text.toString().trim(); p.telefono = tel.text.toString().trim(); p.nota = nota.text.toString().trim()
                if (esNueva) ruta.paradas.add(p)
                Almacen.guardar(this); pintar()
                if (esNueva) b.lista.smoothScrollToPosition(ruta.paradas.size - 1)
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun opcionesParada(p: Parada) {
        val i = ruta.paradas.indexOf(p)
        val ops = arrayOf("Editar datos", "Mover a mi ubicación actual", "Cambiar número (orden)", "Llamar", "Eliminar")
        AlertDialog.Builder(this).setTitle("${i + 1}. ${p.nombre}").setItems(ops) { _, w ->
            when (w) {
                0 -> dialogoParada(p, false)
                1 -> conUbicacionActual { pos -> pos?.let { p.lat = it.latitude; p.lng = it.longitude; Almacen.guardar(this); pintar() } }
                2 -> cambiarNumero(p)
                3 -> if (p.telefono.isNotBlank()) startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${p.telefono}")))
                     else Toast.makeText(this, "Este cliente no tiene teléfono", Toast.LENGTH_SHORT).show()
                4 -> AlertDialog.Builder(this).setMessage("¿Eliminar ${p.nombre}?")
                    .setPositiveButton("Eliminar") { _, _ -> ruta.paradas.remove(p); Almacen.guardar(this); pintar() }
                    .setNegativeButton("Cancelar", null).show()
            }
        }.show()
    }

    private fun cambiarNumero(p: Parada) {
        val et = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText((ruta.paradas.indexOf(p) + 1).toString()); setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this).setTitle("Nuevo número para ${p.nombre}").setView(et)
            .setPositiveButton("Mover") { _, _ ->
                val n = (et.text.toString().toIntOrNull() ?: return@setPositiveButton).coerceIn(1, ruta.paradas.size)
                ruta.paradas.remove(p); ruta.paradas.add(n - 1, p); Almacen.guardar(this); pintar()
            }.setNegativeButton("Cancelar", null).show()
    }

    // ---------------- menú ----------------
    private fun menu(id: Int) {
        when (id) {
            R.id.nuevoDia -> AlertDialog.Builder(this)
                .setMessage("¿Empezar de nuevo? Se quitan las marcas ✓ y ✕, los clientes y su orden se quedan.")
                .setPositiveButton("Sí") { _, _ ->
                    ruta.paradas.forEach { it.estado = Estado.PENDIENTE; it.marcadaEn = 0 }; Almacen.guardar(this); pintar()
                }.setNegativeButton("Cancelar", null).show()
            R.id.importar -> importar.launch(arrayOf("text/*", "application/*", "*/*"))
            R.id.exportar -> exportar.launch("ruta-${ruta.nombre}-${Ruta.DIAS[ruta.dia]}.csv".replace(Regex("[^\\w.\\-]+"), "_"))
            R.id.renombrar -> {
                val et = EditText(this).apply { setText(ruta.nombre); setPadding(48, 32, 48, 32) }
                AlertDialog.Builder(this).setTitle("Nombre de la ruta").setView(et)
                    .setPositiveButton("Siguiente") { _, _ ->
                        ruta.nombre = et.text.toString().trim().ifBlank { ruta.nombre }
                        AlertDialog.Builder(this).setTitle("Día").setItems(Ruta.DIAS.toTypedArray()) { _, d ->
                            ruta.dia = d; Almacen.guardar(this); titulo()
                        }.show()
                        Almacen.guardar(this); titulo()
                    }.setNegativeButton("Cancelar", null).show()
            }
            R.id.eliminar -> AlertDialog.Builder(this).setMessage("¿Eliminar la ruta ${ruta.nombre} con sus ${ruta.paradas.size} clientes?")
                .setPositiveButton("Eliminar") { _, _ -> Almacen.rutas(this).remove(ruta); Almacen.guardar(this); finish() }
                .setNegativeButton("Cancelar", null).show()
        }
    }

    private fun importarDe(uri: Uri) {
        val nombreArchivo = uri.lastPathSegment ?: ""
        val texto = runCatching { contentResolver.openInputStream(uri)!!.bufferedReader().readText() }.getOrNull()
        if (texto == null) { Toast.makeText(this, "No pude leer el archivo", Toast.LENGTH_SHORT).show(); return }
        if (texto.startsWith("PK")) {
            Toast.makeText(this, "Ese es un .xlsx: en Excel usa Guardar como → CSV y vuelve a importarlo", Toast.LENGTH_LONG).show(); return
        }
        val filas = Archivos.leer(texto, nombreArchivo)
        if (filas.isEmpty()) { Toast.makeText(this, "No encontré clientes en ese archivo", Toast.LENGTH_LONG).show(); return }
        AlertDialog.Builder(this).setTitle("Importar ${filas.size} clientes")
            .setMessage("¿Agregarlos al final de esta ruta o reemplazar los que ya tiene?")
            .setPositiveButton("Agregar") { _, _ -> procesarImport(filas, false) }
            .setNeutralButton("Reemplazar") { _, _ -> procesarImport(filas, true) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun procesarImport(filas: List<Archivos.Fila>, reemplazar: Boolean) {
        val sinCoord = filas.count { it.lat == null }
        if (sinCoord > 0) Toast.makeText(this, "Buscando $sinCoord direcciones en el mapa…", Toast.LENGTH_LONG).show()
        lifecycleScope.launch {
            val (ok, fallo) = withContext(Dispatchers.IO) { Archivos.aParadas(this@RutaActivity, filas) }
            if (reemplazar) ruta.paradas.clear()
            ruta.paradas.addAll(ok); primerEncuadre = true
            Almacen.guardar(this@RutaActivity); pintar()
            val msg = "Se agregaron ${ok.size} clientes." + if (fallo.isNotEmpty()) "\n\nNo encontré en el mapa (agrégalos tocando el mapa):\n• " + fallo.joinToString("\n• ") else ""
            AlertDialog.Builder(this@RutaActivity).setTitle("Importación").setMessage(msg).setPositiveButton("OK", null).show()
        }
    }
}
