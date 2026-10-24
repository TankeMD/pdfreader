# PDF Reader

Conversión de PDF a un modelo JSON de plantilla CCM: maestras pares/impares y contenido fluido. Java 17, Spring Boot 4.1.2, Spring Framework 7.0.10 y Apache PDFBox 3.0.8.

## Requisitos

JDK 17, acceso a Maven Central y Tesseract 5 con los idiomas `spa` y `eng`. No requiere base de datos, servicios de IA ni credenciales. El OCR se ejecuta localmente; los documentos no se envían a terceros.

En Debian/Ubuntu: instala `tesseract-ocr`, `tesseract-ocr-spa` y `tesseract-ocr-eng`. Si utilizas un directorio propio de modelos, exporta `TESSDATA_PREFIX` o configura `pdfreader.ocr.tessdata`. Comprueba la instalación con `tesseract --list-langs`.

## Desarrollo

```bash
./mvnw verify
./mvnw spring-boot:run
```

La aplicación escucha en el puerto 8080. `GET /api/health` devuelve `{"status":"UP"}`.

Para ejecutar el artefacto:

```bash
java -jar target/pdfreader-0.0.1-SNAPSHOT.jar
```

## Generar una plantilla

```bash
curl --fail-with-body -F 'file=@documento.pdf' \
  'http://localhost:8080/api/templates/analyze?ocr=AUTO' \
  -o plantilla.json
```

`ocr=AUTO` aplica OCR en páginas con imágenes o sin texto nativo pero con trazados visibles (por ejemplo, letras convertidas en curvas). Conserva el texto nativo sin duplicarlo. `ALWAYS` analiza todas las páginas; `OFF` solo extrae objetos originales. El enum distingue mayúsculas y minúsculas. Si la segmentación normal no encuentra palabras, intenta una pasada para texto disperso, como un número de página aislado.

La respuesta 2.0 incluye `analysis`, `sourcePages`, `masters`, `flow`, `objects`, `assets`, `fonts` y `notices`. Usa `units: "inches"`, longitudes en pulgadas (también tamaños de fuente), y como máximo cuatro decimales en todos los números. Cada página incluye `paper` (A4, A3, Letter, etc. o `custom`) y `orientation` (`vertical` u `horizontal`). Consulta el [contrato JSON](docs/template-model.md).

Las fuentes del texto nativo conservan su nombre original y se normalizan a familia, peso y estilo. Las de OCR se comparan visualmente con las incrustadas y, solo si no hay, con TTF locales. Se marcan como estimadas, con método, similitud y alternativas; los casos ambiguos quedan `unknown`. No se asigna Arial o Calibri por defecto. El catálogo `fonts` solo contiene recursos que realmente existen en el PDF. Para la comparación con fuentes de sistema se usan ficheros de `/usr/share/fonts/truetype` (p. ej., paquetes DejaVu/Liberation); sin candidatos compatibles se conserva el estado desconocido.

## Análisis híbrido con Docling

Se incluye un servicio Python local con Docling 2.142.0, modelos de layout y TableFormer Accurate sobre CPU. Java conserva texto, estilos, imágenes y trazados; Docling propone regiones semánticas, orden de lectura y tablas con celdas combinadas. Las discrepancias entre ambos motores se incluyen en `notices`. Las regiones de imagen conservadas, como una etiqueta, no se duplican como tablas o párrafos.

```bash
bash scripts/setup-docling.sh
bash scripts/start-docling.sh
```

La instalación necesita Python 3.12, `uv`, Tesseract con español/inglés y acceso a Hugging Face para descargar modelos. El script instala las versiones fijadas en `services/docling/requirements.lock` con PyTorch CPU. El servicio escucha solo en `127.0.0.1:8090`; los PDF se analizan localmente. El arranque utiliza los modelos descargados y desactiva descargas y telemetría.

Comprueba `GET http://127.0.0.1:8090/health`: debe devolver HTTP 200 y `ready=true`, lo que incluye inicializar el modelo de análisis. Un proceso iniciado o un puerto abierto no implican que estén disponibles los modelos.

```bash
curl --fail-with-body -F 'file=@documento.pdf' \
  'http://localhost:8080/api/templates/analyze?engine=HYBRID&ocr=AUTO' \
  -o plantilla-hibrida.json
```

`engine=LOCAL` sigue siendo el valor predeterminado. `engine=HYBRID` exige que Docling funcione: responde 503 si faltan sus modelos o el servicio, sin sustituirlo silenciosamente por el motor local. `ANALYSIS_ENGINE=HYBRID` cambia el valor predeterminado una vez validada su instalación. `DOCLING_URL` permite configurar su dirección; el tiempo máximo de la petición Java es 240 segundos y el de conversión Docling es 180 segundos.

**Estado de la validación en este entorno:** la instalación Python, el adaptador HTTP, la normalización, la combinación Java y el plugin real Tesseract de Docling se han probado. La descarga de los modelos de layout/TableFormer está bloqueada por HTTP 403 de la política de red hacia Hugging Face. La inferencia neuronal y su resultado sobre los PDF reales siguen pendientes; las muestras `*-local-v1.2.json` son resultados del motor local mejorado, no de Docling. Los dominios necesarios están guardados en el borrador de configuración del entorno.

- Detecta cuadrículas completas o irregulares, celdas combinadas y filas de subtotal con zonas ausentes. También detecta tablas delimitadas con separadores parciales y columnas alineadas. Conserva texto por celda y referencias a bordes y fondos. Un rectángulo aislado se conserva como forma. Las columnas deducidas de bordes parciales requieren revisión.
- Agrupa caracteres/palabras en párrafos y conserva fuente, tamaño, color y geometría cuando proceden del PDF.
- Extrae imágenes PNG en Base64 y trazados vectoriales con estilos de trazo/relleno.
- Infere encabezados, pies y fondos repetidos por tamaño de página y paridad. Requiere al menos dos páginas de cada paridad para confirmar cada maestra.
- Conserva los objetos originales, incluso los asignados a una tabla o maestra, para permitir revisión y reclasificación.
- Respeta los recortes del estado gráfico para excluir formas totalmente invisibles. Los pequeños trazados compatibles con letras reconocidas por OCR se conservan en `objects`, pero se excluyen del flujo; los párrafos los referencian mediante `sourceObjectIds`.
- Analiza por separado hasta seis regiones de imagen significativas por página. Prueba rotaciones de 0°, 90°, 180° y 270° y adjunta `imageAnalysis` al bloque de imagen. Su texto es una anotación de consulta: no se convierte además en párrafos. El recurso compuesto conserva la apariencia visible, incluidos códigos de barras, que no se decodifican.
- En escaneos extrae palabras mediante OCR y detecta cuadrículas por líneas de píxeles. La imagen de página completa se conserva como recurso, pero se excluye del flujo cuando se recupera texto para no duplicarlo.

El resultado es una **plantilla intermedia para revisión**, no una conversión perfecta ni un PDF de salida. No incluye editor visual, motor de paginación, exportador a un CCM comercial ni lectura de variables de negocio.

## Alcance y límites

Los márgenes y el orden de lectura son heurísticos. Se necesita revisión en columnas, encabezados variables, celdas combinadas, tablas sin bordes, rotaciones arbitrarias y documentos inclinados. Las fuentes OCR son estimaciones visuales limitadas al conjunto disponible y sus colores desconocidos permanecen null; las imágenes o formas dentro de un escaneo completo no se segmentan como objetos individuales. Las curvas se conservan como Bézier; las elipses de cuatro cuadrantes se reconocen de forma aproximada. Recortes parciales, patrones, sombreados y transformaciones de estilos pueden requerir reconstrucción adicional. No se identifica automáticamente una etiqueta cuando no existe una región de imagen independiente. Un PDF de una sola página no aporta evidencia suficiente para separar automáticamente el header/footer del cuerpo.

El servicio admite 20 MiB, de 1 a 20 páginas, hasta 12 megapíxeles por página renderizada a 144 dpi y 40 megapíxeles por imagen incrustada. Procesa un documento simultáneo por instancia. Todas las pasadas OCR de una página, incluidas sus regiones y rotaciones, comparten un límite de 45 segundos. Los PDF con contraseña o extracción prohibida se rechazan. Los temporales del OCR se eliminan al terminar, también si falla.

Errores JSON con `code` y `message`: PDF ilegible `400`; archivo demasiado grande `413`; restricciones del documento `422`; instancia ocupada `429`; OCR no disponible o mal configurado `503`; timeout OCR `504`. Un error de OCR no se presenta como conversión satisfactoria.

## Pruebas y ejemplos

`./mvnw verify` ejecuta pruebas de PDF digital, OCR escaneado, documento mixto, texto convertido en curvas, pie aislado, etiqueta girada sin texto duplicado, cuadrículas irregulares y subtotales, bordes parciales frente a rectángulos, maestras pares/impares, recortes y restauración del estado gráfico, PDF inválido/protegido, timeout de OCR y la API HTTP. También verifica el cliente Docling contra un servidor HTTP sintético y la combinación de regiones, estilos, orden de lectura y celdas combinadas. Estas pruebas de contrato no prueban inferencia neuronal. Tesseract y sus idiomas son necesarios; las pruebas OCR no se omiten silenciosamente.

`bash scripts/setup-docling.sh --dependencies-only` instala y prueba el adaptador Python sin descargar modelos. Su suite verifica el protocolo HTTP con inferencia simulada y el plugin real Tesseract de Docling con una imagen sintética. Para validar inferencia completa, instala los modelos, inicia ambos servicios y procesa un PDF mediante `engine=HYBRID`; comprueba `analysis.engine=HYBRID`, una versión Docling y `DOCLING_APPLIED`, además de revisar las celdas.

Las pruebas generan `target/samples/digital.pdf`, `target/samples/scanned.pdf` y `target/samples/digital-template.json` para experimentar con el endpoint.

## Entorno de nube preparado

```bash
export JAVA_HOME=/workspace/.tools/jdk-17.0.21+9
export PATH="$JAVA_HOME/bin:$PATH"
export MAVEN_USER_HOME=/workspace/.cache/maven-user
export MAVEN_OPTS="${MAVEN_OPTS:-} -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
export TESSDATA_PREFIX=/workspace/.tools/tessdata
./mvnw -s /workspace/.tools/maven-settings.xml -Dmaven.repo.local=/workspace/.cache/maven spring-boot:run
```

Los parámetros de proxy y certificados anteriores son específicos de este entorno; en una máquina con acceso directo basta Maven Wrapper y el JDK.
