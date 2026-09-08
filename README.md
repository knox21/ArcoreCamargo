# CampoAR

App Android para ubicar puntos de un **KMZ/KML** en el terreno con la cámara, GPS y ARCore Geospatial.

## Qué hace

1. Importa un KMZ o KML (selector de archivos o “Compartir con CampoAR”).
2. Muestra los puntos, líneas y polígonos en un mapa.
3. Descarga esa zona **antes de ir a campo**, para usarla sin red.
4. Abre la cámara AR: flecha de rumbo, distancia y pines sobre el paisaje.
5. Si hay internet, cobertura Street View y una clave de ARCore, ancla los puntos con **Geospatial (VPS)** para más precisión.

## Geospatial no sustituye el modo offline

ARCore Geospatial de Google necesita **red** para Visual Positioning System. En monte, predio o zona sin Street View no basta. Por eso la app es híbrida:

| Situación | Cómo localiza | Precisión típica |
|---|---|---|
| Ciudad, de día, con red y Street View | ARCore Geospatial (VPS) | ~1 m o mejor |
| Campo sin red, o sin Street View | GPS + brújula + ARCore local | ~3–10 m (GPS del teléfono) |
| Mapa | Teselas OpenFreeMap descargadas | Funciona sin red después de descargar |

No uses solo Geospatial si tu trabajo es en campo sin línea.

## Cómo generar el APK

1. Instala [Android Studio](https://developer.android.com/studio) (incluye JDK 17 y el SDK).
2. Copia `local.properties.example` a `local.properties`.
3. Al abrir el proyecto, Android Studio completa `sdk.dir`.
4. Opcional, para el modo VPS preciso: en [Google Cloud](https://console.cloud.google.com/) habilita **ARCore API** y pega la clave:

```
ARCORE_API_KEY=tu_clave
```

5. Teléfono con Google Play Services for AR, o genera el APK:

```
gradlew.bat assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

Sin clave de ARCore la app **sí corre**: KMZ, mapa offline y AR por GPS. El chip de la cámara dirá “GPS de campo”.

## Uso en campo

1. Con Wi‑Fi: importa el KMZ y pulsa **Descargar mapa para usar sin red**.
2. En el predio: **Abrir cámara AR**, acepta cámara y ubicación precisa.
3. Elige un punto abajo; la flecha indica hacia dónde caminar y la distancia.
4. Cuando estés cerca, el pin se proyecta sobre la cámara. Con Geospatial activo, el pin 3D queda anclado al suelo.

## Requisitos del teléfono

- Android 8+ (API 26)
- Cámara y GPS
- Google Play Services for AR
