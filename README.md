# CampoAR

App Android para ubicar puntos de un **KMZ/KML** en el terreno con la cámara, GPS y ARCore Geospatial, y para **ver IFC en 3D**.

## Qué hace

1. Importa KMZ, KML o **IFC** (selector de archivos o “Compartir con CampoAR”).
2. En la lista: botón **Ver IFC 3D** (visor con órbita) y **Mapa / AR** si el IFC está georreferenciado.
3. Muestra puntos, líneas y polígonos en un mapa.
4. Descarga esa zona **antes de ir a campo**, para usarla sin red.
5. Abre la cámara AR: flecha de rumbo, distancia y sólido anclado.
6. Si hay internet, cobertura Street View y una clave de ARCore, ancla con **Geospatial (VPS)**.

## IFC

El lector acepta, entre otros:

- `IfcExtrudedAreaSolid` con perfil `Polyline`, `IndexedPolyCurve`, rectángulo o círculo
- Edificios de Revit (IFC2X3): muros, losas, columnas y vigas con `IfcLocalPlacement`, `IfcBooleanClippingResult` y `IfcMappedItem`
- `IfcTriangulatedFaceSet` / `IfcPolygonalFaceSet` / `IfcPolyLoop`
- Con o **sin** georreferencia (`IfcSite` / `IfcMapConversion`)

Sin georreferencia igual puedes abrir el visor 3D. Para mapa/AR el IFC debe traer coordenadas geográficas.

En la cámara AR se dibuja la misma malla del visor, orientada a norte (incluye la convergencia de meridianos de UTM) y anclada en su coordenada real: de lejos se ve pequeña y de cerca grande.

### Modelos grandes

El archivo se recorre en streaming, sin cargarlo entero en memoria, y se descartan propiedades, cantidades y relaciones. Aun así la geometría se acota para que el teléfono pueda dibujarla: hasta 150.000 vértices repartidos en grupos de 30.000. El tope de tamaño del IFC depende de la memoria del equipo (unos 100 MB en un teléfono con heap de 256 MB); si te pasas, la app lo dice en vez de cerrarse.

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
