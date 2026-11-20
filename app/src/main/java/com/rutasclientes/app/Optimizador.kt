package com.rutasclientes.app

import kotlin.math.*

/**
 * Ordena las paradas para recorrerlas con la menor distancia posible, sin internet.
 * 1) Vecino más cercano desde donde estás.  2) Mejora 2-opt (quita cruces).
 * Usa distancia en línea recta, que en una ciudad compacta da muy buen orden.
 */
object Optimizador {
    fun distanciaM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    /**
     * @param inicio lat/lng de partida (tu ubicación) o null para empezar en la primera parada.
     * @return la lista ordenada (no modifica la original).
     */
    fun optimizar(paradas: List<Parada>, inicio: Pair<Double, Double>?): List<Parada> {
        if (paradas.size < 3 && inicio == null) return paradas
        val pts = paradas.map { it.lat to it.lng }
        val n = pts.size
        val conInicio = inicio != null
        // nodo 0 = inicio (si hay), luego las paradas
        val nodos = if (conInicio) listOf(inicio!!) + pts else pts
        val m = nodos.size
        val d = Array(m) { i -> DoubleArray(m) { j -> distanciaM(nodos[i].first, nodos[i].second, nodos[j].first, nodos[j].second) } }

        // vecino más cercano
        val visit = BooleanArray(m)
        val orden = ArrayList<Int>(m)
        var actual = 0
        visit[0] = true; orden.add(0)
        repeat(m - 1) {
            var mejor = -1; var md = Double.MAX_VALUE
            for (j in 0 until m) if (!visit[j] && d[actual][j] < md) { md = d[actual][j]; mejor = j }
            visit[mejor] = true; orden.add(mejor); actual = mejor
        }

        // 2-opt sobre ruta abierta; el primer nodo queda fijo
        var mejoro = true
        var vueltas = 0
        while (mejoro && vueltas < 200) {
            mejoro = false; vueltas++
            for (i in 1 until m - 1) {
                for (k in i + 1 until m) {
                    val a = orden[i - 1]; val b = orden[i]; val c = orden[k]
                    val dd = if (k + 1 < m) orden[k + 1] else -1
                    val antes = d[a][b] + (if (dd >= 0) d[c][dd] else 0.0)
                    val despues = d[a][c] + (if (dd >= 0) d[b][dd] else 0.0)
                    if (despues + 1e-6 < antes) {
                        orden.subList(i, k + 1).reverse(); mejoro = true
                    }
                }
            }
        }
        val idx = if (conInicio) orden.drop(1).map { it - 1 } else orden
        return idx.map { paradas[it] }.also { check(it.size == n) }
    }

    fun longitudKm(paradas: List<Parada>, inicio: Pair<Double, Double>?): Double {
        var total = 0.0
        var prev = inicio
        for (p in paradas.filter { it.ubicada }) {
            prev?.let { total += distanciaM(it.first, it.second, p.lat, p.lng) }
            prev = p.lat to p.lng
        }
        return total / 1000.0
    }
}
