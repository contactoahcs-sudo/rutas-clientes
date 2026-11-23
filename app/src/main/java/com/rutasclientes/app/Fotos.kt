package com.rutasclientes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

/** Fotos de fachada: una por cliente/prospecto, guardadas en el teléfono como <id>.jpg. */
object Fotos {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "fotos").apply { mkdirs() }
    fun archivo(ctx: Context, id: String) = File(dir(ctx), "$id.jpg")
    fun tiene(ctx: Context, id: String) = archivo(ctx, id).exists()

    fun borrar(ctx: Context, id: String) {
        archivo(ctx, id).delete(); limpiarCache(id)
    }

    private fun limpiarCache(id: String) {
        cache.snapshot().keys.filter { it.startsWith("$id:") }.forEach { cache.remove(it) }
    }

    /** Copia la foto (cámara o galería), la endereza y la reduce para no llenar el teléfono. */
    fun guardarDesde(ctx: Context, uri: Uri, id: String): Boolean = runCatching {
        val bmp = leerReducida(ctx, uri, 1280) ?: return false
        archivo(ctx, id).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        limpiarCache(id)
        true
    }.getOrDefault(false)

    private fun leerReducida(ctx: Context, uri: Uri, max: Int): Bitmap? {
        val cr = ctx.contentResolver
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0) return null
        var muestra = 1
        while (maxOf(o.outWidth, o.outHeight) / (muestra * 2) >= max) muestra *= 2
        val bmp = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = muestra })
        } ?: return null
        val giro = cr.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val escala = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
        if (giro == 0f && escala >= 1f) return bmp
        val m = Matrix().apply { postRotate(giro); postScale(escala, escala) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /** Foto reducida a [lado] px (para miniatura, mapa o pantalla completa). */
    fun cargar(ctx: Context, id: String, lado: Int): Bitmap? {
        val f = archivo(ctx, id)
        if (!f.exists()) return null
        val k = "$id:$lado"
        cache.get(k)?.let { return it }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var muestra = 1
        while (minOf(o.outWidth, o.outHeight) / (muestra * 2) >= lado) muestra *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = muestra }) ?: return null
        cache.put(k, bmp)
        return bmp
    }

    // ---- para compartir la ruta con otro teléfono (fotos más chicas, en texto) ----
    fun aTexto(ctx: Context, id: String): String? {
        val f = archivo(ctx, id)
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var muestra = 1
        while (maxOf(o.outWidth, o.outHeight) / (muestra * 2) >= 640) muestra *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = muestra }) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun desdeTexto(ctx: Context, id: String, b64: String) {
        runCatching { archivo(ctx, id).writeBytes(Base64.decode(b64, Base64.DEFAULT)); limpiarCache(id) }
    }
}
