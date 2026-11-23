package com.rutasclientes.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class Estado { PENDIENTE, VISITADA, NO_VISITADA }

data class Parada(
    val id: String = UUID.randomUUID().toString(),
    var nombre: String,
    var lat: Double,
    var lng: Double,
    var direccion: String = "",
    var telefono: String = "",
    var nota: String = "",
    var estado: Estado = Estado.PENDIENTE,
    var marcadaEn: Long = 0L,
    var visitas: Int = 0,               // prospectos: cuántas veces se le ha visitado
) {
    /** false = cliente importado sin coordenadas: se ubica al llegar ("Ubicar aquí"). */
    val ubicada get() = !lat.isNaN() && !lng.isNaN()

    fun toJson() = JSONObject()
        .put("id", id).put("nombre", nombre)
        .apply { if (ubicada) put("lat", lat).put("lng", lng) }
        .put("direccion", direccion).put("telefono", telefono).put("nota", nota)
        .put("estado", estado.name).put("marcadaEn", marcadaEn).put("visitas", visitas)

    companion object {
        fun fromJson(o: JSONObject) = Parada(
            id = o.optString("id", UUID.randomUUID().toString()),
            nombre = o.optString("nombre"),
            lat = o.optDouble("lat"), lng = o.optDouble("lng"),
            direccion = o.optString("direccion"), telefono = o.optString("telefono"),
            nota = o.optString("nota"),
            estado = runCatching { Estado.valueOf(o.optString("estado")) }.getOrDefault(Estado.PENDIENTE),
            marcadaEn = o.optLong("marcadaEn"),
            visitas = o.optInt("visitas"),
        )
    }
}

data class Ruta(
    val id: String = UUID.randomUUID().toString(),
    var nombre: String,
    var dia: Int,                       // 1 = lunes ... 7 = domingo, 0 = sin día
    val paradas: MutableList<Parada> = mutableListOf(),
) {
    val visitadas get() = paradas.count { it.estado == Estado.VISITADA }
    val noVisitadas get() = paradas.count { it.estado == Estado.NO_VISITADA }
    val pendientes get() = paradas.count { it.estado == Estado.PENDIENTE }
    val sinUbicar get() = paradas.count { !it.ubicada }

    fun toJson() = JSONObject().put("id", id).put("nombre", nombre).put("dia", dia)
        .put("paradas", JSONArray().apply { paradas.forEach { put(it.toJson()) } })

    companion object {
        val DIAS = listOf("Sin día", "Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo")
        fun fromJson(o: JSONObject): Ruta {
            val arr = o.optJSONArray("paradas") ?: JSONArray()
            return Ruta(
                id = o.optString("id"), nombre = o.optString("nombre"), dia = o.optInt("dia"),
                paradas = (0 until arr.length()).map { Parada.fromJson(arr.getJSONObject(it)) }.toMutableList(),
            )
        }
    }
}

/** Guarda todas las rutas en un archivo JSON dentro del teléfono (sin internet). */
object Almacen {
    private const val ARCHIVO = "rutas.json"
    /** Lista especial: tiendas por convencer. No es una ruta del día. */
    const val PROSPECTOS = "__prospectos__"

    fun prospectos(ctx: Context): Ruta = ruta(ctx, PROSPECTOS)
        ?: Ruta(id = PROSPECTOS, nombre = "Prospectos", dia = 0).also { rutas(ctx).add(it); guardar(ctx) }

    /** Rutas de verdad (sin la lista de prospectos). */
    fun rutasDelDia(ctx: Context) = rutas(ctx).filter { it.id != PROSPECTOS }

    /** Pasa un prospecto a una ruta como cliente (al final). */
    fun hacerCliente(ctx: Context, p: Parada, destino: Ruta) {
        prospectos(ctx).paradas.remove(p)
        p.estado = Estado.PENDIENTE; p.marcadaEn = 0
        destino.paradas.add(p)
        guardar(ctx)
    }
    private var cache: MutableList<Ruta>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, ARCHIVO)

    fun rutas(ctx: Context): MutableList<Ruta> {
        cache?.let { return it }
        val f = file(ctx)
        val lista = if (f.exists()) runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { Ruta.fromJson(arr.getJSONObject(it)) }.toMutableList()
        }.getOrElse { mutableListOf() } else mutableListOf()
        cache = lista
        return lista
    }

    fun ruta(ctx: Context, id: String) = rutas(ctx).firstOrNull { it.id == id }

    fun guardar(ctx: Context) {
        val arr = JSONArray().apply { rutas(ctx).forEach { put(it.toJson()) } }
        val tmp = File(ctx.filesDir, "$ARCHIVO.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file(ctx))
    }

    /** Respaldo completo (todas las rutas) como texto JSON. */
    fun respaldo(ctx: Context): String = JSONArray().apply { rutas(ctx).forEach { put(it.toJson()) } }.toString(2)

    // ---------- Compartir rutas entre teléfonos con Mis Rutas ----------
    /** Archivo que se manda por WhatsApp/Drive; Mis Rutas lo reconoce por "app":"MisRutas". */
    fun paquete(ctx: Context, r: Ruta): String {
        val fotos = JSONObject()
        r.paradas.forEach { p -> Fotos.aTexto(ctx, p.id)?.let { fotos.put(p.id, it) } }
        return JSONObject().put("app", "MisRutas").put("formato", 2)
            .put("rutas", JSONArray().put(r.toJson())).put("fotos", fotos).toString()
    }

    /** Lee un paquete compartido o un respaldo (y guarda sus fotos). Lanza error si no es de Mis Rutas. */
    fun leerPaquete(ctx: Context, texto: String): List<Ruta> {
        val t = texto.trim().removePrefix("\uFEFF")
        val arr = if (t.startsWith("[")) JSONArray(t) else {
            val o = JSONObject(t)
            require(o.optString("app") == "MisRutas") { "No es un archivo de Mis Rutas" }
            o.optJSONObject("fotos")?.let { f -> f.keys().forEach { id -> Fotos.desdeTexto(ctx, id, f.getString(id)) } }
            o.getJSONArray("rutas")
        }
        return (0 until arr.length()).map { Ruta.fromJson(arr.getJSONObject(it)) }
    }

    /**
     * Guarda una ruta recibida de otro teléfono. Las marcas ✓ ✕ se limpian.
     * @param comoCopia true = si ya existe, se guarda aparte en lugar de reemplazarla.
     */
    fun recibir(ctx: Context, r: Ruta, comoCopia: Boolean): Ruta {
        r.paradas.forEach { it.estado = Estado.PENDIENTE; it.marcadaEn = 0 }
        val lista = rutas(ctx)
        if (r.id == PROSPECTOS) {           // prospectos: se suman los que falten
            val mios = prospectos(ctx)
            r.paradas.filter { n -> mios.paradas.none { it.id == n.id } }.forEach { mios.paradas.add(it) }
            guardar(ctx); return mios
        }
        val final = if (comoCopia && lista.any { it.id == r.id })
            Ruta(nombre = r.nombre + " (recibida)", dia = r.dia, paradas = r.paradas) else r
        lista.removeAll { it.id == final.id }
        lista.add(final); guardar(ctx)
        return final
    }

    fun restaurar(ctx: Context, texto: String): Int {
        val nuevas = leerPaquete(ctx, texto)
        val lista = rutas(ctx)
        nuevas.forEach { n -> lista.removeAll { it.id == n.id }; lista.add(n) }
        guardar(ctx)
        return nuevas.size
    }
}
