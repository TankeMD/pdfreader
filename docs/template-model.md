# Modelo de plantilla CCM, versión 2.0

`POST /api/templates/analyze` recibe multipart con `file` y devuelve JSON. El modelo es independiente del proveedor CCM; su consumidor deberá implementar maquetación y edición.

## Colecciones

| Campo | Significado |
| --- | --- |
| `schemaVersion` | `2.0`; cambia las unidades públicas a pulgadas e incorpora clasificación del papel y evidencia tipográfica. |
| `analysis` | `{engine, doclingVersion}` identifica `LOCAL` o `HYBRID`. En modo local la versión Docling es null; no se presenta una extracción local como híbrida. |
| `units` | `inches`. Todas las longitudes del JSON, incluido `fontSize`, usan pulgadas. |
| `sourcePages` | Páginas de entrada con `number`, `width`, `height`, método `NATIVE`, `OCR` o `MIXED`, `paper` y `orientation`. No son páginas de salida. |
| `fonts` | Recursos reales del PDF: nombre original, familia normalizada, peso, estilo, incrustación y páginas de procedencia. No incluye candidatos de sistema que no están en el documento. |
| `masters` | Papel y márgenes inferidos, paridad `ODD`/`EVEN`, contenido estático `header`, `footer`, `background` y `reviewRequired`. |
| `flow` | Secuencia de elementos editables; no contiene saltos por cada página de entrada. |
| `objects` | Inventario de imágenes, trazados y líneas de texto, para inspección y reclasificación. Excluye formas totalmente invisibles por recorte. No se renderiza adicionalmente al flow. |
| `assets` | Recursos `{id, mediaType, base64, pixelWidth, pixelHeight}`; `assetId` los referencia. |
| `notices` | Diagnósticos `{code, sourcePage, message}`. `sourcePage=null` afecta al documento. |

Los identificadores de maestra son opacos: no se deben interpretar como medidas aunque contengan cifras del tamaño original. Se generan variantes para ambos lados de cada tamaño detectado, incluso si no hay muestra de una paridad. La paridad de salida se cuenta desde 1. Al maquetar una sección del mismo tamaño, el consumidor debe elegir por **paridad de la página de salida**, nunca por `sourcePage`. Los cambios de tamaño requieren que el consumidor forme una sección a partir de `sourcePages`; no se crean saltos obligatorios entre páginas consecutivas del mismo tamaño.

Los márgenes laterales y, si no hay detecciones, los verticales parten de 0.75 pulgadas. Son valores editables por revisar, no mediciones garantizadas del PDF. No se asignan elementos únicos de una sola página a maestras sin evidencia de repetición.

## Papel, unidades y precisión

`paper` compara ambos lados del papel visible con un catálogo: ISO A0–A10, B0–B10, sobres C0–C10, JIS B0–B10, RA/SRA0–4 y Letter, Legal, Tabloid, Executive, Statement y Folio. Acepta hasta 0.5 puntos de diferencia por lado para tolerar el redondeo habitual del PDF; no elige el estándar más cercano fuera de esa tolerancia. Tabloid y Ledger comparten tamaño: se usa Tabloid como nombre canónico. Las dimensiones solas no permiten determinar el nombre comercial de cualquier papel posible.

`orientation` es `vertical` cuando alto >= ancho, u `horizontal` en el caso contrario, después de aplicar el giro de página. Un papel cuadrado se clasifica como vertical por convenio. El tamaño de 555 × 802 puntos del informe anual no coincide con el catálogo: su salida es `paper: "custom"`, `width: 7.7083`, `height: 11.1389`.

Todos los valores numéricos se serializan con **como máximo cuatro decimales**, redondeo HALF_UP y sin ceros fraccionarios obligatorios. La conversión afecta también a `objects`, márgenes, celdas, runs, `imageAnalysis`, anchos/alturas de tabla, curvas, grosores y patrón discontinuo. Los índices, cantidades de filas/columnas, ángulos y dimensiones `pixelWidth`/`pixelHeight` siguen sin escalar; las confianzas y opacidades solo se redondean. Una fuente de 12 puntos se exporta como `fontSize: 0.1667` pulgadas.

Las heurísticas Java y el contrato interno Python de Docling siguen usando puntos y precisión completa. La conversión ocurre una sola vez, en la frontera de la API; los cálculos no se realizan sobre valores redondeados.

## Elementos

Todo elemento tiene `id`, `type`, `sourcePage`, `bounds`, `confidence`, `reviewRequired` y `sourceObjectIds`. `bounds={x,y,width,height}` usa origen superior izquierdo del CropBox visible, ajustado a su rotación. Estos bounds son **metadatos del origen**: no fijan la posición del bloque en el flujo de salida.

`confidence` es una puntuación heurística de 0 a 1, no una probabilidad calibrada. En `objects` la confianza de existencia de un objeto no garantiza su clasificación semántica. No se deben tomar decisiones destructivas solo con esa puntuación.

| Tipo | Contenido |
| --- | --- |
| `PARAGRAPH` | `text` une líneas visuales con espacios; `runs` preserva texto, fuente, tamaño, color, bounds, origen y confianza. |
| `TABLE` | `table` contiene `rows`, `columns`, anchos y alturas originales orientativos, `cells` y referencias a objetos de borde. |
| `IMAGE` | `assetId`; puede incluir `imageAnalysis` con OCR de la región. Cuando se analiza la región, el recurso compuesto respeta su apariencia en la página. |
| `SHAPE` | `shape.kind`: `LINE`, `RECTANGLE`, `ELLIPSE` o `PATH`; `path` y `paint`. |
| `PAGE_NUMBER` | En maestras: `text` contiene `${pageNumber}` y opcionalmente `${pageCount}`. El consumidor calcula ambos tras paginar. Los runs conservan el valor original para inspección. |
| `TEXT` | Solo en el inventario `objects`: una línea de texto original con sus runs. |

Los campos que no corresponden al tipo se omiten. El texto sin saltos de un párrafo es el contenido de salida; las coordenadas de sus runs no deben impedir que se redistribuya. Los colores OCR desconocidos permanecen null y su tamaño se estima por la caja visual.

## Fuentes identificadas y estimadas

Cada run incluye `font`, `fontOriginal`, `fontWeight`, `fontStyle`, `fontEstimated`, `fontEstimationMethod`, `fontConfidence` y `fontCandidates`.

- Texto nativo: `fontOriginal` conserva, por ejemplo, `VSZKRH+Arial-BoldItalicMT`; `font` se normaliza a `Arial`, peso `bold`, estilo `italic`, `fontEstimated: false` y método `PDF_TEXT_STATE`. La fuente viene del estado de ese fragmento, no de elegir una fuente de la lista global.
- OCR: `fontEstimated: true`. Se comparan siluetas de hasta dos palabras representativas por grupo de altura, con un máximo de ocho grupos por página o región. Primero se usan exclusivamente las fuentes incrustadas que permiten renderizar esas letras. Si el PDF no tiene fuentes incrustadas, se comparan hasta 40 ficheros TTF realmente instalados. No se usa una familia por indicación del usuario ni un valor fijo como Arial/Calibri.
- `fontCandidates` contiene hasta tres alternativas con familia, peso, estilo, `source: embedded|installed` y `similarity`. El método es `EMBEDDED_FONT_VISUAL_MATCH`, `INSTALLED_FONT_VISUAL_MATCH` o `UNRESOLVED`. `fontConfidence` es la similitud de la muestra, no una probabilidad calibrada ni una garantía individual por fragmento.
- Si la mejor similitud es inferior a 0.75, o apenas se separa del siguiente candidato (menos de 0.008), se conserva `font: "unknown"` con las alternativas disponibles. Sin muestras o sin glifos compatibles puede no haber candidatos. La ausencia de una fuente del conjunto comparado impide identificarla: una fuente instalada parecida puede ser solo una sustituta visual.

La etiqueta Amazon no declara recursos tipográficos en el PDF original: `fonts` queda vacío. Cualquier familia propuesta allí es una comparación visual con fuentes locales, nunca una fuente incrustada confirmada. El informe anual sí declara recursos Arial incrustados y conserva su evidencia nativa.

Una celda tiene `row`, `column` (base cero), `rowSpan`, `columnSpan`, `sourceBounds`, `text`, `runs`, `backgroundObjectIds`. Los spans son enteros positivos, normalmente 1. Una celda combinada se representa una sola vez; no se incluyen copias por cada posición que ocupa. En una fila de subtotal pueden existir posiciones sin celda: no deben rellenarse con bordes inventados.

`table.detection` puede ser `GRID`, `GRID_IRREGULAR`, `PARTIAL_BORDERS` o `DOCLING`. Las cuadrículas irregulares agrupan posiciones vecinas sin borde interno y solo aceptan grupos rectangulares con perímetro comprobable; las zonas abiertas del subtotal no se convierten en celdas. En `PARTIAL_BORDERS` se deducen columnas a partir del encabezado, alineación de datos, contorno cerrado y separadores horizontales. Sus divisiones son hipótesis, no bordes encontrados; no se deben dibujar bordes verticales que no estén en `borderObjectIds`. Las líneas envueltas de una descripción permanecen dentro de su celda. Un simple recuadro sin esa evidencia no se convierte en tabla.

En modo `HYBRID`, las regiones de Docling se relacionan con los runs originales por posición, conservando sus fuentes y colores. Se eliminan del flow los párrafos y bordes asignados a las nuevas tablas, pero no los objetos originales. Se conservan las tablas geométricas que Docling no sustituye. Los textos semánticos sin correspondencia con el origen no se incorporan ciegamente; se avisa y se mantiene la extracción local. Los solapamientos con imágenes preservadas no generan tablas adicionales encima de la etiqueta. Los cambios de dimensiones de tabla entre motores producen `TABLE_ENGINE_DISAGREEMENT`; el consumidor debe revisarlos. Las medidas de columnas/filas se deducen de las cajas de las celdas, con interpolación de bordes no observados; no son reglas de paginación.

Los IDs de fondos y `borderObjectIds` se resuelven en `objects`; allí están colores, rellenos, grosores y trazados originales. En tablas escaneadas estos IDs pueden estar vacíos: las líneas se detectan en píxeles y sus estilos son desconocidos. Las alturas originales no deben impedir que una fila crezca al editarla.

`imageAnalysis={rotationClockwise,text,runs}` expresa el giro aplicado **solo para leer** la región (0, 90, 180 o 270 grados). No ordena girar el recurso al renderizarlo. `text` está en el orden de lectura de la orientación elegida; los bounds de `runs` se transforman de vuelta a las coordenadas de la página de origen. El texto es una anotación consultable; la región sigue siendo una imagen, con códigos y gráficos intactos. No es una reconstrucción de sus campos editables ni un decodificador de códigos de barras. Los recursos originales siguen en `objects`/`assets`; el flow puede referenciar un recurso nuevo con sufijo `-composed`.

Los trazados pequeños compatibles con letras OCR se mantienen en `objects` pero no se añaden otra vez al flow. Los párrafos los relacionan mediante `sourceObjectIds`; esta clasificación es heurística y se avisa con `OUTLINED_TEXT_REMOVED_FROM_FLOW`. Los recortes no equivalen a una máscara semántica: los objetos parcialmente visibles pueden conservar geometría fuera de su zona visible.

Los trazados conservan comandos `M` (move), `L` (line), `C` (curva cúbica, dos controles y destino) y `Z` (close), con coordenadas en pulgadas. `paint` contiene stroke/fill, grosor, patrón discontinuo, extremos, uniones, opacidades y regla de relleno. Los trazados extraídos no reconstruyen todos los efectos de composición del PDF.

## Reglas de consumo

1. Revisar `notices` y los elementos con `reviewRequired` antes de usar la plantilla en producción.
2. Renderizar los objetos estáticos de la maestra una vez por página, fuera del flow.
3. Distribuir `flow` según el orden recibido. Párrafos y tablas deben poder crecer y desplazar los elementos posteriores.
4. Usar imágenes y formas en posición de flujo por defecto. Sus anclajes y posibles superposiciones requieren revisión.
5. No insertar automáticamente `objects` ni el escaneo completo como fondo: duplicaría contenido que ya pertenece a tablas, maestras o párrafos.
6. Conservar IDs para permitir correcciones humanas: convertir una tabla en forma, editar celdas o mover un bloque entre el flow y una maestra.

La API no distingue automáticamente datos variables de texto literal ni crea reglas de negocio CCM. Tampoco calcula las páginas finales: éstas dependerán del contenido editado, las fuentes disponibles y el motor de salida.
