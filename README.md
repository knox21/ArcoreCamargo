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

- `IfcExtrudedAreaSolid` y `IfcRevolvedAreaSolid` con perfil `Polyline`, `IndexedPolyCurve`, rectángulo, círculo, hueco o sección de acero (I, T, L, U, C, Z), además de perfiles derivados y compuestos
- Edificios de Revit (IFC2X3 / IFC4): muros, losas, columnas y vigas con `IfcLocalPlacement`, `IfcBooleanClippingResult`, `IfcCsgSolid` y `IfcMappedItem`
- `IfcFacetedBrep`, `IfcAdvancedBrep` y superficies por caras, con `IfcPolyLoop` o `IfcEdgeLoop`
- `IfcTriangulatedFaceSet` / `IfcPolygonalFaceSet`
- Con o **sin** georreferencia (`IfcSite` / `IfcMapConversion`)

Sin georreferencia igual puedes abrir el visor 3D. Para mapa/AR el IFC debe traer coordenadas geográficas.

Al importar, el documento guarda un resumen de lo leído (sólidos, elementos, recortes y tipos sin soporte) que se muestra bajo el visor. Si un archivo no deja nada dibujable, el error nombra los tipos de geometría que encontró.

El visor encuadra el modelo según su tamaño real: la cámara, sus planos de recorte y la órbita del gesto se calculan cuando ya se conocen las medidas del edificio. Con los valores por omisión de SceneView (plano lejano a 30 m) cualquier modelo de más de unos 12 m quedaba recortado por completo y la pantalla salía vacía, y volvía a vaciarse al primer arrastre.

### Aristas y colores

Cada cara sale del parser dibujada dos veces, una por lado, para que se vea desde dentro y desde fuera. Al promediar las normales por vértice las dos copias se anulaban y todas acababan apuntando hacia arriba, así que el edificio entero se iluminaba igual y se veía de un solo tono. Las normales se sacan ahora de una sola copia de cada cara, girada hacia fuera según el volumen que encierra el sólido.

El botón **Ver aristas / colores**, en el visor y en la cámara, añade encima el contorno del modelo y pinta las caras por orientación (muros, losas altas y losas bajas). El contorno son los bordes abiertos y las aristas donde dos caras se cruzan en ángulo: dibujar todos los lados de los triángulos también dibujaría la diagonal que parte cada rectángulo en dos, y una fachada se convierte en ruido. Las caras se separan por posición antes de compararlas, porque cada una trae su propia copia de las esquinas.

Al abrir la cámara el sólido está encendido. **Traer aquí** lo enciende si estaba apagado. **Anclar GPS** ya no borra el modelo: después de traerlo, el ancla lo deja fijo y **GPS real** vuelve a su sitio. Las aristas se muestran u ocultan sin reconstruir la malla (eso era el temblor del botón).

En la cámara AR se dibuja la misma malla del visor, orientada a norte (incluye la convergencia de meridianos de UTM) y anclada en su coordenada real. A más de 100 m el modelo se acerca sobre la línea que lo une contigo, conservando rumbo y orientación, y la pantalla avisa de la distancia real; al acercarte a menos de 80 m vuelve a escala real. Esto vale igual en modo GPS y en Geospatial.

El sólido se apoya en el suelo que ves. Una altitud desconocida ya no se toma como nivel del mar: antes se restaba la altitud del GPS a un modelo sin ella, y el sólido acababa tantos kilómetros bajo tierra como alto esté el sitio.

Una malla solo se coloca por GPS si el IFC trajo origen georreferenciado. Los documentos importados con versiones anteriores no lo tienen, así que se dibuja su contorno (que sí está georreferenciado) y la cámara pide reimportar el IFC. Un IFC sin ninguna georreferencia se dibuja delante de ti, avisando de que no es su sitio, en vez de quedar centrado en la cámara.

### Modelos grandes

El archivo se recorre en streaming, sin cargarlo entero en memoria y sin crear una cadena por sentencia; se descartan propiedades, cantidades y relaciones. Indexar un IFC cuesta unas 4 veces su tamaño en memoria, así que el tope depende del equipo: unos 56 MB con heap de 256 MB y 112 MB con heap de 512 MB. Si te pasas, la app lo dice en vez de cerrarse.

La geometría también se acota para que el teléfono pueda dibujarla: hasta 6.000 elementos y 150.000 vértices repartidos en grupos de 30.000. Los sólidos con coordenadas imposibles (placements roscos) se descartan para que el encuadre del visor no se vaya al infinito.

## Geospatial no sustituye el modo offline

ARCore Geospatial de Google necesita **red** para Visual Positioning System. En monte, predio o zona sin Street View no basta. Por eso la app es híbrida:

| Situación | Cómo localiza | Precisión típica |
|---|---|---|
| Ciudad, de día, con red y Street View | ARCore Geospatial (VPS) | ~1 m o mejor |
| Campo sin red, o sin Street View | GPS + brújula + ARCore local | ~3–10 m (GPS del teléfono) |
| Mapa | Teselas OpenFreeMap descargadas | Funciona sin red después de descargar |

No uses solo Geospatial si tu trabajo es en campo sin línea.

En modo GPS el ancla y el origen se renuevan siempre juntos, y solo cuando el GPS trae algo nuevo: 8 m de camino, 8° de brújula o entrar y salir de la vista lejana. Entre renovaciones es el seguimiento de ARCore el que sostiene el sólido, que es bastante más estable que el GPS. Mover el origen dejando el ancla vieja contaba tu caminata dos veces y arrastraba el modelo.

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

Los controles de la cámara van en dos filas cortas sobre la imagen; la segunda se desliza de lado para que no crezcan hacia abajo y tapen la vista.

## Requisitos del teléfono

- Android 8+ (API 26)
- Cámara y GPS
- Google Play Services for AR
