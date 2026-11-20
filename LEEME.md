# Mis Rutas

App Android para organizar las rutas de clientes de cada día (prevendedor Danone y Bonafont, Xalapa), sin publicidad.

## Qué hace
- Mapa de Google con tu ubicación en vivo.
- Rutas por día (ej. "38 / Jueves"). En la pantalla principal sale primero la ruta de hoy.
- Agregar cliente: **aquí donde estoy**, **tocando el mapa** (o mantener presionado) o **buscando la dirección**.
- Clientes numerados con conteo: ✓ visitado, ✕ no visitado, pendientes y km aproximados.
- **OPTIMIZAR**: ordena los pendientes desde donde estás (vecino más cercano + 2-opt, en el teléfono, gratis). Se puede deshacer.
- **Ir a la siguiente**: abre Google Maps navegando al siguiente pendiente. ➤ en cada cliente hace lo mismo.
- Reordenar: mantener presionado el número y arrastrar, o "Cambiar número".
- Menú de la ruta: Nuevo día (borra marcas), Importar CSV / KML / GPX, Exportar a Excel (CSV).
- Menú principal: respaldo completo y restaurar (archivo .json) y buscar actualización.

## Formato para importar (CSV, se puede guardar desde Excel)
Encabezados reconocidos (en cualquier orden): `nombre, direccion, lat, lng, telefono, nota`.
Si no hay `lat`/`lng`, la app busca la dirección en Xalapa (necesita internet).

## Publicar una versión
Igual que Wifi Manager: subir `versionCode` en `app/build.gradle.kts` y hacer push a `main`.
GitHub Actions arma `MisRutas.apk`, lo publica como Release `vN` y la app avisa "Nueva versión".

## Llave de Google Maps (una sola vez)
1. https://console.cloud.google.com → crear proyecto "Mis Rutas" (pide tarjeta; el mapa en el celular no tiene costo).
2. APIs y servicios → Biblioteca → activar **Maps SDK for Android**.
3. Credenciales → Crear credencial → Clave de API → Restringir: Apps de Android,
   paquete `com.rutasclientes.app`, SHA-1 `2C:AC:C6:26:A5:C3:FE:64:A8:F7:6D:38:7A:26:3D:82:6D:6F:1F:18`.
4. En GitHub: Settings → Secrets and variables → Actions → New secret `MAPS_API_KEY` con la clave.
