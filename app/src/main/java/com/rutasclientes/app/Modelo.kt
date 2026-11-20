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
) {
    /** false = cliente importado sin coordenadas: se ubica al llegar ("Ubicar aquí"). */
    val ubicada get() = !lat.isNaN() && !lng.isNaN()

    fun toJson() = JSONObject()
        .put("id", id).put("nombre", nombre)
        .apply { if (ubicada) put("lat", lat).put("lng", lng) }
        .put("direccion", direccion).put("telefono", telefono).put("nota", nota)
        .put("estado", estado.name).put("marcadaEn", marcadaEn)

    companion object {
        fun fromJson(o: JSONObject) = Parada(
            id = o.optString("id", UUID.randomUUID().toString()),
            nombre = o.optString("nombre"),
            lat = o.optDouble("lat"), lng = o.optDouble("lng"),
            direccion = o.optString("direccion"), telefono = o.optString("telefono"),
            nota = o.optString("nota"),
            estado = runCatching { Estado.valueOf(o.optString("estado")) }.getOrDefault(Estado.PENDIENTE),
            marcadaEn = o.optLong("marcadaEn"),
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

    fun restaurar(ctx: Context, texto: String): Int {
        val arr = JSONArray(texto)
        val nuevas = (0 until arr.length()).map { Ruta.fromJson(arr.getJSONObject(it)) }
        val lista = rutas(ctx)
        nuevas.forEach { n -> lista.removeAll { it.id == n.id }; lista.add(n) }
        guardar(ctx)
        return nuevas.size
    }
}
