# InDriveInspector

Widget flotante que se dibuja sobre inDrive y te dice, en verde / amarillo / rojo,
si la oferta que tienes delante es rentable.

**Todo se lee con OCR**: la app captura la pantalla y reconoce el precio, los
globos del mapa (tiempo y distancia) y los precios alternativos. No usa la
accesibilidad (que llegaba tarde).

## Uso (2 pasos)

1. `.\gradlew.bat :app:installDebug`
2. Abre la app y **mantén pulsado el botón del centro** hasta que se llene de
   agua (1,6 s). Si lo sueltas antes, se vacía y no pasa nada.

   El botón **no activa nada sin el consentimiento de captura de pantalla**:
   - primero pide el permiso de superposición (para dibujar el widget),
   - después pide el consentimiento de captura,
   - y **sólo si lo concedes** arrancan el OCR, el widget y la captura. Si lo
     deniegas, no se activa nada.

Cuando el inspector está activo, el botón se rellena **lime** y pone
**"Detener"**: con **un solo toque** se para todo.

Para cambiar los parámetros de rentabilidad, el icono de engranaje arriba a la
derecha abre **Ajustes**.

Para detenerlo desde fuera de la app, usa el botón **Detener** de la
notificación del sistema.

## El widget

```
(◯64) HNL126 ↓mín   14,5/km   291/h
```

- Indicador horizontal pequeño, semitransparente, arriba a la izquierda (bajo
  la barra de estado), fuera de la zona del mapa y de los botones.
- El **anillo circular** de la izquierda muestra el porcentaje de rentabilidad
  (el peor de los indicadores) y toma el color del semáforo.
- **Arrástralo** para moverlo, **tócalo** para desplegar los precios
  alternativos, cada uno con su propio anillo y su porcentaje.
- Se oculta solo cuando no hay ninguna carrera en pantalla.

### Semáforo

Se compara contra tus tres objetivos (no hay costo por minuto):

| Indicador | Configuración | Significado |
|---|---|---|
| precio por km | `cost_per_km` | lo que necesitas por km para no perder |
| precio por hora | `target_price_per_hour` | tu objetivo por hora |
| precio del viaje | `minimum_trip_price` | tarifa mínima que aceptas (0 = sin mínimo) |

| Color | Condición |
|---|---|
| 🟢 Verde | los tres indicadores llegan al 100% |
| 🟡 Amarillo | el peor está entre 85% y 100% (hasta 15% por debajo) |
| 🔴 Rojo | el peor está por debajo del 85% |

Se toma **el peor** de los indicadores. Si el precio queda por debajo del
mínimo, el widget lo marca con `↓mín` además del color.

Con la oferta de ejemplo (8,7 km / 26 min, mínimo 120):

| Precio | HNL/km | HNL/h | Mínimo | Estado |
|---|---|---|---|---|
| HNL126 | 14,5 | 291 | ✅ | 🟢 |
| HNL116 | 13,3 | 268 | ❌ | 🟡 |
| HNL100 | 11,5 | 231 | ❌ | 🟡 |
| HNL80 | 9,2 | 185 | ❌ | 🔴 |

### Configuración

Pestaña **Ajustes** (engranaje arriba a la derecha):

| Ajuste | Default | Significado |
|---|---|---|
| `cost_per_km` | 10 | Precio por km que necesitas para no perder |
| `target_price_per_hour` | 250 | Precio por hora que consideras rentable |
| `minimum_trip_price` | 0 | Tarifa mínima que aceptas (0 = sin mínimo) |
| `include_deadhead` | true | Contar el trayecto hasta el punto A |
| `always_visible` | false | Dejar el widget en pantalla aunque no haya oferta |

Con **"Widget siempre visible"** el indicador no se oculta: cuando no hay
carrera en pantalla muestra `—` en lugar de desaparecer.

## Compilar para producción

```bash
.\gradlew.bat :app:assembleRelease
```

El release va con **R8 + shrink de recursos** (54 MB debug → 42,8 MB release).
El modelo de ML Kit (~20 MB) es la mayor parte del peso; el resto es Compose y
la fuente Inter.

Para firmarlo, añade en `local.properties`:

```properties
RELEASE_STORE_FILE=rutas/mi-keystore.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

Si no están, sale un APK *unsigned* (hay que firmarlo a mano con `apksigner`).

Las reglas de ofuscación están en `app/proguard-rules.pro` (ML Kit y el
receptor de la notificación).

## Cómo se lee la pantalla

| Dato | Cómo se consigue |
|---|---|
| Es una carrera | ver abajo |
| Precio de la oferta | el precio del botón "Aceptar por HNL126" |
| Precios alternativos | los precios por debajo de ese botón (HNL116, HNL124) |
| Distancia hasta A | la línea "~1,6 km" junto al precio grande del panel |
| Minutos publicados | la línea "6 min." del panel |
| Globos del mapa | texto en color sólido (verde/azul) dentro de burbujas |

### Detección de "es una carrera"

La pantalla de la **lista** de ofertas (`Solicitudes de viaje`, la pestaña
inferior) tiene un botón "Aceptar" por tarjeta, muchos precios y distancias
"~895 metro" por cada una. Para que el widget no se ponga a tontear con esa
pantalla, sólo se analiza si se cumple **todo** esto:

1. Aparece **"Ofrece tu tarifa"** (sólo existe en la pantalla de una carrera)
   **o** hay un botón **"Aceptar"**.
2. Y la pantalla tiene **≤ 6 precios distintos** (una carrera: el precio de la
   oferta + un par de alternativas; una lista: uno por tarjeta).

**No se usa el título "Solicitud de viaje"** como señal: el mapa puede
taparlo y entonces el OCR no lo leería. La detección funciona igual con el
título tapado.

Si no es una carrera, el widget se oculta (o muestra `—` si activaste "widget
siempre visible").

ML Kit devuelve cada palabra suelta, así que el parser empareja número y
unidad **por posición** ("3" + "min.", "~1,6" + "km"). Las distancias se
normalizan a km: `735 metro` → 0,735 km.

Los globos se distinguen del resto por el **color del fondo**: el texto es
blanco sobre verde o azul sólido; las etiquetas del mapa van sobre fondos
claros y se descartan.

## Rendimiento y estabilidad

El objetivo es que el widget reaccione en **menos de un segundo** a un cambio
de pantalla.

**Detección de cambios (lo más lento antes)**
- Antes: se decodificaba la imagen entera (10 MB) cada 1,5 s y se comparaban
  miniaturas → hasta 1,8 s de retraso.
- Ahora: por cada frame se **muestrea una rejilla de 6.480 píxeles** (menos de
  1 ms) y se cuenta el porcentaje que ha cambiado (>1,2% ⇒ pantalla distinta).
  La imagen completa **sólo se decodifica cuando el cambio es real** y sólo
  cuando el OCR la va a usar. Detección a nivel de frame: ~50 ms.

**OCR en dos fases**
- La **mitad inferior** (panel con el precio y los botones) se analiza primero:
  dice si es una carrera y cuánto vale. Se publica el resultado **al instante**,
  así que el widget ya muestra el precio.
- La **mitad superior** (el mapa) se analiza después y añade los globos de
  distancia y tiempo.
- Antes era un único OCR de la pantalla completa (2,6 MP) y nada se publicaba
  hasta que terminaba.

**Ocultar el widget**
- Antes había que acumular **3 lecturas** fallidas, pero el OCR sólo corría al
  cambiar la pantalla: si la pantalla se quedaba quieta, el widget **no se
  ocultaba nunca**.
- Ahora basta **una** lectura limpia para ocultarlo, y si el OCR no lee nada
  (frame en transición, pantalla en negro) no se oculta: no dice nada.

| Momento | Antes | Ahora |
|---|---|---|
| Detectar el cambio | 1,5–1,8 s | ~50–130 ms |
| Saber si es carrera + precio | ~2,5 s | ~0,4–0,6 s |
| Widget con km/h completo | ~3 s | ~0,8–1 s |
| Ocultar el widget | 3 cambios de pantalla (o nunca) | 1 lectura (~0,5 s) |

**OCR a resolución reducida**
- El OCR se hace sobre una imagen **reducida a 0,7x** (756x1680 en vez de
  1080x2400): la mitad de píxeles, ~2 veces más rápido, y el texto de inDrive
  (botones y precios, tipografía grande) se lee igual de bien.
- Si aun así **no** se encuentra ningún globo (el texto del mapa es el más
  pequeño), se **reintenta a resolución completa**: ese reintento sólo ocurre
  cuando hace falta, así que el caso normal va siempre rápido.
- Para compensar la reducción, el parser tolera las confusiones típicas del
  OCR: `O`→0, `l`/`I`→1, `S`→5, `B`→8. "HNL1O5" se lee como 105 y "73S
  metro" como 735 m.

**Otros**
- El árbol de accesibilidad se publica sólo cuando el contenido cambia.
- Los fotogramas se decodifican en un `Bitmap` reutilizable.
- La copia del fotograma se hace dentro del lock: si no, el OCR leería una
  imagen a medias.

## Diseño

- **Home minimalista**: un único botón circular en el centro, con ondas que
  invitan a pulsarlo, un **patrón de puntos** (halftone) de fondo y un relleno
  de "agua" que sube mientras lo mantienes.
  La secuencia es: primero se llena el **líquido** y después se llena el
  **borde circular**; al completarse el borde **se activa sin esperar a
  soltar el dedo**. Si sueltas antes, se vacía.
  El texto tiene dos capas: la clara (siempre) y un **duplicado oscuro** que
  va apareciendo conforme el líquido lo cubre.
  Cuando está activo, el botón se rellena lime, pone **"Detener"** y también
  emite ondas (un toque lo para).
- **Cabecera**: "InDrive" en blanco translúcido y "Inspector" en lime, más
  grande, arriba a la izquierda. El engranaje de Ajustes, en blanco, arriba a
  la derecha. Todo con `windowInsetsPadding(systemBars)` para que no choque
  con la status bar.
- **Navegador de rutas** (Navigation Compose) con transiciones: las páginas
  entran desde la derecha con un *ease* suave y vuelven al revés.
- **Fondo con degradado**: manchas difuminadas azul → verde → lima sobre el
  negro (#06070A).
- **Tipografía Inter** (fuente variable, un solo archivo ~870 KB) en todos los
  pesos.
- **Anillos de progreso circulares** como elemento gráfico principal (en la
  app y en el widget).
- **Una sola notificación** del sistema para los tres servicios, con el botón
  **Detener**.

## Notificaciones

Captura, OCR y widget comparten **una única notificación** (mismo canal y mismo
ID), con un botón **Detener** que apaga todo de un golpe.

## Tests

`.\gradlew.bat :app:testDebugUnitTest`

- `MapBadgeParserTest`: parser contra el OCR real de inDrive (emparejamiento
  de palabras sueltas, metros).
- `ScreenOcrTest`: lectura de la pantalla (detección de carrera, precio,
  alternativas, distancia y minutos del panel).
- `ProfitWidgetTest`: métricas y semáforo del widget (verde / amarillo al
  85-100% / rojo).

## Notas

- `com.google.mlkit:text-recognition` (modelo empaquetado, no requiere Google
  Play Services, ~20 MB extra en el APK).
- Logcat: tag `InDriveInspector` (cada análisis escribe su duración).
