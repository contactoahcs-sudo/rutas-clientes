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
    private var ubicando: Parada? = null
    private var filtro: String? = null      // id de etiqueta resaltada (las demás se ven tenues)
    private val esProspectos get() = ruta.id == Almacen.PROSPECTOS
    private val MORADO = Color.parseColor("#8E24AA")   // cliente que se está ubicando tocando el mapa
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
    // ---- fotos de fachada ----
    private var fotoPara: String? = null          // id del cliente/prospecto al que va la foto
    private var fotoTemporal: Uri? = null
    private val tomarFotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = fotoTemporal
        if (ok && u != null) guardarFoto(u)
    }
    private val elegirFotoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { u -> u?.let { guardarFoto(it) } }

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
            m.setOnMapClickListener { p ->
                if (!esperandoToque) return@setOnMapClickListener
                esperandoToque = false; b.ayudaMapa.visibility = View.GONE
                val u = ubicando
                if (u != null) { ubicando = null; fijarUbicacion(u, p) } else nuevaParadaEn(p)
            }
            m.setOnMarkerClickListener { mk ->
                when (val t = mk.tag) {
                    is Int -> { b.lista.smoothScrollToPosition(t); false }
                    is Parada -> { mk.showInfoWindow(); opcionesProspecto(t); true }
                    else -> false
                }
            }
            m.setInfoWindowAdapter(VentanaConFoto())
            m.setOnInfoWindowClickListener { mk ->
                val p = (mk.tag as? Parada) ?: (mk.tag as? Int)?.let { ruta.paradas.getOrNull(it) }
                p?.let { if (Fotos.tiene(this, it.id)) verFoto(it) else pedirFoto(it) }
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
        if (esProspectos) { b.toolbar.title = "★ Prospectos por convencer"; return }
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
        if (esProspectos) b.conteo.text = "★ PROSPECTOS ($total)   ✓ = \"pasé hoy\"   ·   Mantén presionado → Ya es cliente" else
        b.conteo.text = "PARADAS ($total)   ✓ ${ruta.visitadas}   ✕ ${ruta.noVisitadas}   Faltan ${ruta.pendientes}" +
            (if (ruta.sinUbicar > 0) "   📍 Sin ubicar ${ruta.sinUbicar}" else "") +
            if (total > 0) "   ·  ${"%.1f".format(Optimizador.longitudKm(ruta.paradas, null))} km" else ""
        val m = mapa ?: return
        m.clear()
        // Prospectos (tiendas por convencer) en morado en el mapa de cada ruta
        if (!esProspectos) Almacen.prospectos(this).paradas.filter { it.ubicada }.forEach { p ->
            m.addMarker(MarkerOptions().position(LatLng(p.lat, p.lng)).title("★ ${p.nombre} (prospecto)")
                .snippet("Visitas: ${p.visitas}" + if (p.nota.isNotBlank()) " · ${p.nota}" else "")
                .icon(icono(0, Estado.PENDIENTE, prospecto = true)))?.tag = p
        }
        val puntos = ruta.paradas.filter { it.ubicada }.map { LatLng(it.lat, it.lng) }
        if (puntos.isEmpty()) return
        if (!esProspectos) m.addPolyline(PolylineOptions().addAll(puntos).color(Color.argb(150, 242, 140, 40)).width(6f))
        ruta.paradas.forEachIndexed { i, p ->
            if (p.ubicada) m.addMarker(MarkerOptions().position(LatLng(p.lat, p.lng)).title("${i + 1}. ${p.nombre}")
                .snippet(p.direccion.ifBlank { p.nota }).icon(icono(i + 1, p.estado, esProspectos))
                .alpha(if (filtro != null && filtro !in p.etiquetas) 0.25f else 1f))?.tag = i
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

    private fun icono(n: Int, e: Estado, prospecto: Boolean = false): BitmapDescriptor {
        val d = resources.displayMetrics.density
        val s = (34 * d).toInt()
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE; c.drawCircle(s / 2f, s / 2f, s / 2f, p)
        p.color = if (prospecto) MORADO else color(e); c.drawCircle(s / 2f, s / 2f, s / 2f - 2 * d, p)
        p.color = Color.WHITE; p.textSize = (if (n > 99) 11 else 14) * d; p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true
        c.drawText(if (prospecto && n == 0) "★" else n.toString(), s / 2f, s / 2f - (p.descent() + p.ascent()) / 2, p)
        return BitmapDescriptorFactory.fromBitmap(bmp)
    }

    private inner class Adaptador : RecyclerView.Adapter<Adaptador.VH>() {
        inner class VH(val v: ItemParadaBinding) : RecyclerView.ViewHolder(v.root)
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(ItemParadaBinding.inflate(LayoutInflater.from(p.context), p, false))
        override fun getItemCount() = ruta.paradas.size
        override fun onBindViewHolder(h: VH, i: Int) {
            val p = ruta.paradas[i]
            h.v.numero.text = (i + 1).toString()
            h.v.numero.background.mutate().setTint(if (esProspectos) MORADO else color(p.estado))
            h.v.no.visibility = if (esProspectos) View.GONE else View.VISIBLE
            Etiquetas.pintarEn(this@RutaActivity, h.v.etiquetas, p)
            val mini = Fotos.cargar(this@RutaActivity, p.id, 120)
            h.v.foto.setImageBitmap(mini)
            h.v.foto.visibility = if (mini != null) View.VISIBLE else View.GONE
            h.v.foto.setOnClickListener { verFoto(p) }
            h.v.nombre.text = p.nombre
            h.v.detalle.text = (if (p.ubicada) "" else "📍 SIN UBICAR: toca para ubicarlo · ") +
                (if (esProspectos) "Visitas: ${p.visitas}" + (if (p.marcadaEn > 0) " (última ${fecha(p.marcadaEn)})" else "") + " · " else "") +
                (if (!esProspectos) Visitas.ultima(p)?.let { "🔁 ${it.resumen()} · " } ?: "" else "") +
                listOf(p.direccion, p.telefono, p.nota).filter { it.isNotBlank() }.joinToString(" · ")
            h.v.detalle.visibility = if (h.v.detalle.text.isBlank()) View.GONE else View.VISIBLE
            h.v.root.alpha = if (filtro != null && filtro !in p.etiquetas) 0.25f
                else if (p.estado == Estado.PENDIENTE || esProspectos) 1f else 0.6f
            h.v.etiquetas.setOnClickListener { Etiquetas.elegir(this@RutaActivity, p) { Almacen.guardar(this@RutaActivity); pintar() } }
            h.v.si.setOnClickListener { if (esProspectos) paseHoy(p) else marcar(p, Estado.VISITADA) }
            h.v.no.setOnClickListener { marcar(p, Estado.NO_VISITADA) }
            h.v.ir.setOnClickListener { navegar(p) }
            h.v.root.setOnClickListener {
                if (p.ubicada) mapa?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lng), 17f))
                else preguntarUbicacion(p)
            }
            h.v.root.setOnLongClickListener { opcionesParada(p); true }
            h.v.numero.setOnLongClickListener { moverConNumero(h); true } // mantener el número para arrastrar
        }
    }

    private fun fecha(t: Long) = java.text.SimpleDateFormat("d MMM", Locale("es", "MX")).format(java.util.Date(t))

    /** Prospecto: registra una visita de seguimiento. */
    private fun paseHoy(p: Parada) {
        p.visitas++; p.marcadaEn = System.currentTimeMillis()
        Almacen.guardar(this); pintar()
        Snackbar.make(b.root, "${p.nombre}: visita #${p.visitas} registrada", Snackbar.LENGTH_LONG)
            .setAction("Ya es cliente") { elegirRutaPara(p) }.show()
    }

    /** Opciones de un prospecto tocado en el mapa de una ruta. */
    private fun opcionesProspecto(p: Parada) {
        val tieneFoto = Fotos.tiene(this, p.id)
        val ops = arrayOf("✓ Pasé hoy (visitas: ${p.visitas})", "✅ Ya es cliente de esta ruta", "➤ Ir",
            if (tieneFoto) "🖼 Ver foto de la fachada" else "📷 Tomar foto de la fachada")
        AlertDialog.Builder(this).setTitle("★ ${p.nombre}").setItems(ops) { _, w ->
            when (w) {
                0 -> paseHoy(p)
                1 -> {
                    Almacen.hacerCliente(this, p, ruta); pintar()
                    Toast.makeText(this, "${p.nombre} ahora es cliente #${ruta.paradas.size} de esta ruta", Toast.LENGTH_LONG).show()
                }
                2 -> navegar(p)
                3 -> if (tieneFoto) verFoto(p) else pedirFoto(p)
            }
        }.show()
    }

    /** Desde la lista de prospectos: elegir a qué ruta pasa como cliente. */
    private fun elegirRutaPara(p: Parada) {
        val rutas = Almacen.rutasDelDia(this)
        if (rutas.isEmpty()) { Toast.makeText(this, "Primero crea una ruta", Toast.LENGTH_SHORT).show(); return }
        val nombres = rutas.map { it.nombre + if (it.dia > 0) " / ${Ruta.DIAS[it.dia]}" else "" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("¿A qué ruta pasa ${p.nombre}?").setItems(nombres) { _, w ->
            Almacen.hacerCliente(this, p, rutas[w]); pintar()
            Toast.makeText(this, "¡${p.nombre} ya es cliente de la ruta ${rutas[w].nombre}!", Toast.LENGTH_LONG).show()
        }.show()
    }

    private fun marcar(p: Parada, e: Estado) {
        if (p.estado == e) {   // tocar otra vez lo desmarca (y quita el registro de hoy)
            val hoy = java.text.SimpleDateFormat("yyyyMMdd", Locale.US)
            val ult = p.historial.lastOrNull()
            if (ult != null && hoy.format(java.util.Date(ult.fecha)) == hoy.format(java.util.Date()) &&
                ult.resultado == e.name) p.historial.removeAt(p.historial.size - 1)
            p.estado = Estado.PENDIENTE; p.marcadaEn = 0
            Almacen.guardar(this); pintar(); return
        }
        if (e == Estado.VISITADA) {
            Visitas.dialogo(this, p) { v -> v?.let { p.historial.add(it) }; aplicarMarca(p, e) }
            return
        }
        p.historial.add(Visita(resultado = "NO_VISITADA"))
        aplicarMarca(p, e)
        Snackbar.make(b.root, "✕ ${p.nombre} sin visita", Snackbar.LENGTH_LONG).setAction("Agregar motivo") {
            val et = EditText(this).apply { hint = "Cerrado, no estaba el encargado…"; setPadding(48, 32, 48, 32) }
            AlertDialog.Builder(this).setTitle("Motivo").setView(et).setPositiveButton("Guardar") { _, _ ->
                val i = p.historial.size - 1
                if (i >= 0) p.historial[i] = p.historial[i].copy(comentario = et.text.toString().trim())
                Almacen.guardar(this); pintar()
            }.setNegativeButton("Cancelar", null).show()
        }.show()
    }

    private fun aplicarMarca(p: Parada, e: Estado) {
        p.estado = e; p.marcadaEn = System.currentTimeMillis()
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

    // ---------------- fotos de fachada ----------------
    private fun buscarParada(id: String) =
        ruta.paradas.firstOrNull { it.id == id } ?: Almacen.prospectos(this).paradas.firstOrNull { it.id == id }

    private fun pedirFoto(p: Parada) {
        fotoPara = p.id
        AlertDialog.Builder(this).setTitle("📷 Fachada de ${p.nombre}")
            .setItems(arrayOf("Tomar foto con la cámara", "Elegir de la galería")) { _, w ->
                if (w == 0) {
                    val dir = java.io.File(cacheDir, "camara").apply { mkdirs() }
                    val f = java.io.File(dir, "foto-${System.currentTimeMillis()}.jpg")
                    fotoTemporal = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                    runCatching { tomarFotoLauncher.launch(fotoTemporal) }
                        .onFailure { Toast.makeText(this, "No encontré la cámara", Toast.LENGTH_SHORT).show() }
                } else elegirFotoLauncher.launch("image/*")
            }.show()
    }

    private fun guardarFoto(u: Uri) {
        val id = fotoPara ?: return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { Fotos.guardarDesde(this@RutaActivity, u, id) }
            if (ok) {
                pintar()
                Toast.makeText(this@RutaActivity, "📷 Foto guardada" + (buscarParada(id)?.let { " para ${it.nombre}" } ?: ""), Toast.LENGTH_SHORT).show()
            } else Toast.makeText(this@RutaActivity, "No se pudo guardar la foto", Toast.LENGTH_SHORT).show()
        }
    }

    /** Foto grande de la fachada con opciones. */
    private fun verFoto(p: Parada) {
        val bmp = Fotos.cargar(this, p.id, 1080) ?: return pedirFoto(p)
        val img = android.widget.ImageView(this).apply {
            setImageBitmap(bmp); adjustViewBounds = true; scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        AlertDialog.Builder(this).setTitle(p.nombre).setView(img)
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("Cambiar foto") { _, _ -> pedirFoto(p) }
            .setNegativeButton("Quitar") { _, _ -> Fotos.borrar(this, p.id); pintar() }
            .show()
    }

    /** Ventanita del mapa: nombre, datos y la foto de la fachada si la tiene. */
    private inner class VentanaConFoto : GoogleMap.InfoWindowAdapter {
        override fun getInfoWindow(mk: com.google.android.gms.maps.model.Marker): View? = null
        override fun getInfoContents(mk: com.google.android.gms.maps.model.Marker): View {
            val p = (mk.tag as? Parada) ?: (mk.tag as? Int)?.let { ruta.paradas.getOrNull(it) }
            val d = resources.displayMetrics.density
            return android.widget.LinearLayout(this@RutaActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                val foto = p?.let { Fotos.cargar(this@RutaActivity, it.id, 400) }
                if (foto != null) addView(android.widget.ImageView(this@RutaActivity).apply {
                    setImageBitmap(foto); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    layoutParams = android.widget.LinearLayout.LayoutParams((220 * d).toInt(), (140 * d).toInt())
                })
                addView(android.widget.TextView(this@RutaActivity).apply {
                    text = mk.title; setTextColor(Color.BLACK); setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, (4 * d).toInt(), 0, 0)
                })
                p?.let { Etiquetas.de(this@RutaActivity, it) }?.takeIf { it.isNotEmpty() }?.let { es ->
                    addView(android.widget.LinearLayout(this@RutaActivity).apply { es.forEach { e -> addView(Etiquetas.chip(this@RutaActivity, e)) } })
                }
                mk.snippet?.takeIf { it.isNotBlank() }?.let { s ->
                    addView(android.widget.TextView(this@RutaActivity).apply { text = s; setTextColor(Color.DKGRAY); maxWidth = (220 * d).toInt() })
                }
                addView(android.widget.TextView(this@RutaActivity).apply {
                    text = if (foto != null) "Toca para ver la foto grande" else "📷 Toca para agregar foto de la fachada"
                    setTextColor(Color.parseColor("#F28C28")); textSize = 12f
                })
            }
        }
    }

    private fun preguntarUbicacion(p: Parada) {
        AlertDialog.Builder(this).setTitle("Ubicar: ${p.nombre}")
            .setItems(arrayOf("📍 Estoy aquí (usar mi ubicación)", "👆 Tocar el lugar en el mapa")) { _, w ->
                if (w == 0) conUbicacionActual { pos ->
                    if (pos == null) Toast.makeText(this, "Todavía no tengo tu ubicación. Revisa el GPS.", Toast.LENGTH_LONG).show()
                    else fijarUbicacion(p, pos)
                } else { ubicando = p; esperandoToque = true; b.ayudaMapa.visibility = View.VISIBLE }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun fijarUbicacion(p: Parada, pos: LatLng) {
        p.lat = pos.latitude; p.lng = pos.longitude
        p.nota = p.nota.replace(Regex("\\s*\\(ubicación aproximada\\)"), "").trim()
        Almacen.guardar(this); pintar()
        Toast.makeText(this, "${p.nombre} quedó ubicado", Toast.LENGTH_SHORT).show()
    }

    private fun navegar(p: Parada) {
        if (!p.ubicada) {
            val q = Uri.encode("${p.nombre}, ${p.direccion.ifBlank { "Xalapa" }}")
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q"))) }
            return
        }
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
            val pendientes = ruta.paradas.filter { it.estado == Estado.PENDIENTE && it.ubicada }
            val sinUbicar = ruta.paradas.filter { it.estado == Estado.PENDIENTE && !it.ubicada }
            val antesKm = Optimizador.longitudKm(pendientes, inicio)
            val nuevas = Optimizador.optimizar(pendientes, inicio)
            val despuesKm = Optimizador.longitudKm(nuevas, inicio)
            val respaldo = ruta.paradas.toList()
            ruta.paradas.clear(); ruta.paradas.addAll(hechas + nuevas + sinUbicar)
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
        val tipo = v.findViewById<android.widget.RadioGroup>(R.id.tipo)
        val rCliente = v.findViewById<android.widget.RadioButton>(R.id.tipoCliente)
        if (esNueva && !esProspectos) rCliente.text = "Cliente de esta ruta (#${ruta.paradas.size + 1})"
        tipo.check(if (esProspectos) R.id.tipoProspecto else R.id.tipoCliente)
        if (!esNueva || esProspectos) tipo.visibility = View.GONE
        AlertDialog.Builder(this).setTitle(if (esNueva) (if (esProspectos) "Nuevo prospecto" else "Agregar punto") else "Editar")
            .setView(v)
            .setPositiveButton("Guardar") { _, _ ->
                val comoProspecto = tipo.checkedRadioButtonId == R.id.tipoProspecto
                val destino = if (comoProspecto) Almacen.prospectos(this) else ruta
                p.nombre = nom.text.toString().trim().ifBlank { (if (comoProspecto) "Prospecto " else "Cliente ") + (destino.paradas.size + 1) }
                p.direccion = dir.text.toString().trim(); p.telefono = tel.text.toString().trim(); p.nota = nota.text.toString().trim()
                if (esNueva) destino.paradas.add(p)
                Almacen.guardar(this); pintar()
                if (esNueva && destino === ruta) b.lista.smoothScrollToPosition(ruta.paradas.size - 1)
                if (esNueva) Snackbar.make(b.root,
                    if (comoProspecto && !esProspectos) "★ ${p.nombre} guardado como prospecto" else "${p.nombre} guardado",
                    Snackbar.LENGTH_LONG).setAction("📷 Foto fachada") { pedirFoto(p) }.show()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun opcionesParada(p: Parada) {
        val i = ruta.paradas.indexOf(p)
        val base = arrayOf("Editar datos", "Ubicar aquí / en el mapa", "Cambiar número (orden)", "Llamar", "Eliminar",
            if (Fotos.tiene(this, p.id)) "🖼 Foto de la fachada" else "📷 Tomar foto de la fachada", "🏷 Etiquetas", "📋 Historial de visitas")
        val ops = if (esProspectos) base + "✅ Ya es cliente → pasar a una ruta" else base + "★ Regresar a prospecto"
        AlertDialog.Builder(this).setTitle("${i + 1}. ${p.nombre}").setItems(ops) { _, w ->
            when (w) {
                5 -> if (Fotos.tiene(this, p.id)) verFoto(p) else pedirFoto(p)
                6 -> Etiquetas.elegir(this, p) { Almacen.guardar(this); pintar() }
                7 -> Visitas.historial(this, p)
                8 -> if (esProspectos) elegirRutaPara(p) else {
                    ruta.paradas.remove(p); p.estado = Estado.PENDIENTE; Almacen.prospectos(this).paradas.add(p)
                    Almacen.guardar(this); pintar()
                }
                0 -> dialogoParada(p, false)
                1 -> preguntarUbicacion(p)
                2 -> cambiarNumero(p)
                3 -> if (p.telefono.isNotBlank()) startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${p.telefono}")))
                     else Toast.makeText(this, "Este cliente no tiene teléfono", Toast.LENGTH_SHORT).show()
                4 -> AlertDialog.Builder(this).setMessage("¿Eliminar ${p.nombre}?")
                    .setPositiveButton("Eliminar") { _, _ -> ruta.paradas.remove(p); Fotos.borrar(this, p.id); Almacen.guardar(this); pintar() }
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
            R.id.compartir -> compartir()
            R.id.filtrar -> {
                val es = Etiquetas.todas(this)
                val nombres = (listOf("Todas (sin resaltar)") + es.map { "● ${it.texto} (${ruta.paradas.count { p -> it.id in p.etiquetas }})" }).toTypedArray()
                AlertDialog.Builder(this).setTitle("Resaltar clientes con…").setItems(nombres) { _, w ->
                    filtro = if (w == 0) null else es[w - 1].id
                    b.toolbar.subtitle = filtro?.let { id -> "🏷 " + es.first { it.id == id }.texto }
                    pintar()
                }.show()
            }
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

    /** Manda la ruta como archivo que Mis Rutas abre en otro teléfono. */
    private fun compartir() {
        val dir = java.io.File(cacheDir, "compartir").apply { mkdirs() }
        val nombre = (if (esProspectos) "prospectos" else "ruta-${ruta.nombre}-${Ruta.DIAS[ruta.dia]}")
            .replace(Regex("[^\\w\\-]+"), "_") + ".misrutas.json"
        val f = java.io.File(dir, nombre).apply { writeText(Almacen.paquete(this@RutaActivity, ruta)) }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val texto = "Ruta ${b.toolbar.title} (${ruta.paradas.size} clientes). Ábrela con la app Mis Rutas."
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_TEXT, texto)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Compartir ruta"))
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
        val sinCoord = filas.count { it.lat == null && it.direccion.isNotBlank() }
        if (sinCoord > 0) Toast.makeText(this, "Buscando $sinCoord direcciones en el mapa…", Toast.LENGTH_LONG).show()
        lifecycleScope.launch {
            val (todas, sinUbicar) = withContext(Dispatchers.IO) { Archivos.aParadas(this@RutaActivity, filas) }
            if (reemplazar) ruta.paradas.clear()
            ruta.paradas.addAll(todas); primerEncuadre = true
            Almacen.guardar(this@RutaActivity); pintar()
            val msg = "Se agregaron ${todas.size} clientes." + if (sinUbicar.isNotEmpty())
                "\n\n${sinUbicar.size} quedaron SIN UBICAR. Cuando llegues a cada uno toca su nombre → \"Estoy aquí\" y queda guardado con tu GPS exacto:\n• " + sinUbicar.joinToString("\n• ") else ""
            AlertDialog.Builder(this@RutaActivity).setTitle("Importación").setMessage(msg).setPositiveButton("OK", null).show()
        }
    }
}
