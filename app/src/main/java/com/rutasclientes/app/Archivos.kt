package com.rutasclientes.app

import android.content.Context
import android.location.Geocoder
import java.util.Locale

/** Importar paradas desde CSV / KML / GPX y exportar a CSV (se abre en Excel). */
object Archivos {

    data class Fila(
        val nombre: String, val direccion: String, val lat: Double?, val lng: Double?,
        val telefono: String, val nota: String,
    )

    // ---------- Exportar ----------
    fun csv(ruta: Ruta): String {
        val sb = StringBuilder("﻿") // BOM para que Excel lea acentos
        sb.append("orden,nombre,direccion,lat,lng,telefono,nota,estado\n")
        ruta.paradas.forEachIndexed { i, p ->
            sb.append(listOf((i + 1).toString(), p.nombre, p.direccion, p.lat.toString(), p.lng.toString(),
                p.telefono, p.nota, p.estado.name).joinToString(",") { esc(it) }).append('\n')
        }
        return sb.toString()
    }

    private fun esc(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' || it == ';' })
        "\"" + s.replace("\"", "\"\"") + "\"" else s

    // ---------- Importar ----------
    fun leer(texto: String, nombreArchivo: String): List<Fila> {
        val t = texto.removePrefix("﻿")
        val low = nombreArchivo.lowercase()
        return when {
            low.endsWith(".kml") || t.contains("<kml", true) -> kml(t)
            low.endsWith(".gpx") || t.contains("<gpx", true) -> gpx(t)
            else -> csvLeer(t)
        }
    }

    private fun kml(t: String): List<Fila> {
        val pm = Regex("<Placemark[\\s\\S]*?</Placemark>", RegexOption.IGNORE_CASE)
        return pm.findAll(t).mapNotNull { m ->
            val b = m.value
            val nombre = tag(b, "name") ?: "Parada"
            val coords = tag(b, "coordinates")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.split(",")
            val lng = coords?.getOrNull(0)?.toDoubleOrNull()
            val lat = coords?.getOrNull(1)?.toDoubleOrNull()
            val dir = tag(b, "address") ?: ""
            if (lat == null && dir.isBlank()) null
            else Fila(limpiar(nombre), limpiar(dir), lat, lng, "", limpiar(tag(b, "description") ?: ""))
        }.toList()
    }

    private fun gpx(t: String): List<Fila> {
        val wpt = Regex("<wpt[^>]*lat=\"([-0-9.]+)\"[^>]*lon=\"([-0-9.]+)\"[^>]*>([\\s\\S]*?)</wpt>", RegexOption.IGNORE_CASE)
        return wpt.findAll(t).map { m ->
            Fila(limpiar(tag(m.groupValues[3], "name") ?: "Parada"), "", m.groupValues[1].toDoubleOrNull(),
                m.groupValues[2].toDoubleOrNull(), "", limpiar(tag(m.groupValues[3], "desc") ?: ""))
        }.toList()
    }

    private fun tag(b: String, n: String) =
        Regex("<$n[^>]*>([\\s\\S]*?)</$n>", RegexOption.IGNORE_CASE).find(b)?.groupValues?.get(1)

    private fun limpiar(s: String) = s.replace(Regex("<!\\[CDATA\\[|]]>"), "").replace(Regex("<[^>]+>"), " ").trim()

    private fun csvLeer(t: String): List<Fila> {
        val lineas = filas(t).filter { r -> r.any { it.isNotBlank() } }
        if (lineas.isEmpty()) return emptyList()
        val cab = lineas[0].map { norm(it) }
        fun col(vararg nombres: String) = cab.indexOfFirst { c -> nombres.any { c == it || c.startsWith(it) } }
        val iNom = col("nombre", "name", "cliente", "tienda", "parada", "titulo", "title")
        val iDir = col("direccion", "address", "domicilio", "calle")
        val iLat = col("lat", "latitud", "latitude")
        val iLng = col("lng", "lon", "long", "longitud", "longitude")
        val iTel = col("telefono", "phone", "tel", "celular")
        val iNota = col("nota", "notas", "note", "notes", "comentario", "descripcion")
        val tieneCab = listOf(iNom, iDir, iLat, iLng).any { it >= 0 }
        val datos = if (tieneCab) lineas.drop(1) else lineas
        return datos.mapNotNull { r ->
            fun v(i: Int) = if (i >= 0) r.getOrNull(i)?.trim().orEmpty() else ""
            if (!tieneCab) { // sin encabezado: nombre, direccion
                Fila(r.getOrElse(0) { "" }.trim(), r.getOrElse(1) { "" }.trim(), null, null, "", "")
            } else {
                val lat = v(iLat).replace(',', '.').toDoubleOrNull()
                val lng = v(iLng).replace(',', '.').toDoubleOrNull()
                val nom = v(iNom).ifBlank { v(iDir) }
                if (nom.isBlank() && lat == null) null
                else Fila(nom.ifBlank { "Parada" }, v(iDir), lat, lng, v(iTel), v(iNota))
            }
        }
    }

    private fun norm(s: String) = s.trim().lowercase(Locale.ROOT)
        .replace("á", "a").replace("é", "e").replace("í", "i").replace("ó", "o").replace("ú", "u")

    /** Parte el CSV respetando comillas; acepta coma, punto y coma o tabulador. */
    private fun filas(t: String): List<List<String>> {
        val primera = t.lineSequence().firstOrNull().orEmpty()
        val sep = listOf(',', ';', '\t').maxBy { c -> primera.count { it == c } }
        val out = mutableListOf<List<String>>()
        var fila = mutableListOf<String>()
        val campo = StringBuilder()
        var comillas = false
        var i = 0
        while (i < t.length) {
            val c = t[i]
            when {
                comillas && c == '"' && t.getOrNull(i + 1) == '"' -> { campo.append('"'); i++ }
                c == '"' -> comillas = !comillas
                !comillas && c == sep -> { fila.add(campo.toString()); campo.clear() }
                !comillas && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && t.getOrNull(i + 1) == '\n') i++
                    fila.add(campo.toString()); campo.clear(); out.add(fila); fila = mutableListOf()
                }
                else -> campo.append(c)
            }
            i++
        }
        if (campo.isNotEmpty() || fila.isNotEmpty()) { fila.add(campo.toString()); out.add(fila) }
        return out
    }

    /** Convierte filas a paradas; las que no traen coordenadas se buscan por dirección (necesita internet). */
    @Suppress("DEPRECATION")
    fun aParadas(ctx: Context, filas: List<Fila>, ciudad: String = "Xalapa, Veracruz"): Pair<List<Parada>, List<String>> {
        val geo = if (Geocoder.isPresent()) Geocoder(ctx, Locale("es", "MX")) else null
        val ok = mutableListOf<Parada>()
        val fallo = mutableListOf<String>()
        for (f in filas) {
            var lat = f.lat; var lng = f.lng
            if (lat == null || lng == null) {
                val q = f.direccion.ifBlank { f.nombre }
                val r = runCatching { geo?.getFromLocationName("$q, $ciudad", 1)?.firstOrNull() }.getOrNull()
                lat = r?.latitude; lng = r?.longitude
            }
            if (lat != null && lng != null) ok.add(Parada(nombre = f.nombre, lat = lat, lng = lng,
                direccion = f.direccion, telefono = f.telefono, nota = f.nota))
            else fallo.add(f.nombre)
        }
        return ok to fallo
    }
}
