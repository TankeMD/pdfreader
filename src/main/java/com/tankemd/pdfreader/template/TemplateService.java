package com.tankemd.pdfreader.template;

import static com.tankemd.pdfreader.template.Template.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

/** Orquesta los analizadores y produce maestras, contenido refluible, recursos y avisos de revisión. */
@Service
public final class TemplateService {
    /** Controla si se omite, se selecciona automáticamente o se fuerza la lectura OCR. */
    public enum OcrMode { AUTO, ALWAYS, OFF }
    /** LOCAL usa geometría/OCR; HYBRID exige además una respuesta válida de Docling. */
    public enum Engine { LOCAL, HYBRID }
    private final OcrReader ocr;
    private final DoclingClient docling;
    private final Semaphore capacity=new Semaphore(1);
    /** Construye el servicio con OCR y, cuando se inyecta, cliente Docling; la sobrecarga de pruebas no requiere el servicio semántico. */
    TemplateService(OcrReader ocr) { this(ocr,null); }
    /** Construye el servicio con OCR y, cuando se inyecta, cliente Docling; la sobrecarga de pruebas no requiere el servicio semántico. */
    @Autowired TemplateService(OcrReader ocr,DoclingClient docling) { this.ocr=ocr;this.docling=docling; }

    /** Controla el acceso al análisis y devuelve el modelo con el motor solicitado. La sobrecarga sin motor utiliza LOCAL. */
    public Template analyze(byte[] bytes, OcrMode mode) throws IOException {
        return analyze(bytes,mode,Engine.LOCAL);
    }

    /** Controla el acceso al análisis y devuelve el modelo con el motor solicitado. La sobrecarga sin motor utiliza LOCAL. */
    public Template analyze(byte[] bytes, OcrMode mode,Engine engine) throws IOException {
        // El semáforo limita el coste de memoria y OCR a un documento simultáneo; el finally libera la plaza incluso si falla.
        if (bytes.length==0) throw new PdfProblem("EMPTY_FILE","El archivo está vacío.");
        if (!capacity.tryAcquire()) throw new PdfProblem("BUSY","Ya hay un documento en proceso. Reintenta más tarde.");
        try { return extract(bytes,mode,engine); }
        finally { capacity.release(); }
    }

    /** Orquesta extracción nativa, OCR selectivo, reconstrucción geométrica, combinación semántica opcional y detección de maestras. */
    private Template extract(byte[] bytes,OcrMode mode,Engine engine) throws IOException {
        List<SourcePage> pages=new ArrayList<>();List<Element> flow=new ArrayList<>(),objects=new ArrayList<>();
        List<Asset> assets=new ArrayList<>();List<Notice> notices=new ArrayList<>();
        List<FontInfo> fontInfos=new ArrayList<>();
        // Se valida el documento antes de renderizarlo; el cierre automático libera los recursos de PDFBox.
        try (var document=Loader.loadPDF(bytes)) {
            if (!document.getCurrentAccessPermission().canExtractContent()) throw new PdfProblem("EXTRACTION_DENIED","El PDF no permite extraer contenido.");
            if (document.getNumberOfPages()<1 || document.getNumberOfPages()>20) throw new PdfProblem("PAGE_LIMIT","Se admiten entre 1 y 20 páginas por documento.");
            var fonts=FontCatalog.from(document);fontInfos.addAll(fonts.infos());
            var renderer=new PDFRenderer(document);
            for (int index=0;index<document.getNumberOfPages();index++) {
                var page=document.getPage(index);int number=index+1;
                // La rotación intercambia ancho y alto visibles. El límite de píxeles se calcula para el render a 144 dpi.
                boolean rotated=Math.floorMod(page.getRotation(),180)!=0;
                double width=rotated?page.getCropBox().getHeight():page.getCropBox().getWidth();
                double height=rotated?page.getCropBox().getWidth():page.getCropBox().getHeight();
                if (width<=0 || height<=0 || width*height*4>12_000_000) throw new PdfProblem("PAGE_TOO_LARGE","Una página supera el límite de 12 megapíxeles a 144 dpi.");
                if (page.getUserUnit()!=1) throw new PdfProblem("UNSUPPORTED_USER_UNIT","Esta versión requiere páginas PDF con UserUnit=1.");
                // Primero se conserva la evidencia gráfica y textual, antes de asignarla a tablas, párrafos o maestras.
                var graphics=new PdfObjects(page,number);graphics.processPage(page);
                assets.addAll(graphics.assets);objects.addAll(graphics.elements);
                var text=new PdfObjects.Text(graphics);text.setStartPage(number);text.setEndPage(number);text.getText(document);
                List<TextRun> runs=new ArrayList<>(text.runs);
                boolean hasImages=graphics.elements.stream().anyMatch(e->e.type().equals("IMAGE"));
                // AUTO contempla imágenes y páginas con texto convertido en curvas; OFF mantiene únicamente la extracción nativa.
                boolean doOcr=mode==OcrMode.ALWAYS || mode==OcrMode.AUTO && (hasImages || text.runs.isEmpty() && !graphics.elements.isEmpty());
                List<Structure.Segment> segments=new ArrayList<>(Structure.segments(graphics.elements));
                Map<String,Element> imageRegions=Map.of();
                boolean addedOcr=false;
                if (doOcr) {
                    // Las regiones, los cuatro giros y la lectura de página comparten el mismo plazo, no 45 segundos por pasada.
                    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
                    var image=renderer.renderImageWithDPI(index,144,ImageType.RGB);
                    var regions=ImageRegions.analyze(image,width,height,graphics.elements,ocr,deadline,fonts);
                    imageRegions=regions.replacements();assets.addAll(regions.assets());notices.addAll(regions.notices());
                    // Con texto nativo se leen regiones de imagen; la página completa solo se analiza si falta texto, hay un escaneo grande o se fuerza OCR.
                    boolean fullPageOcr=mode==OcrMode.ALWAYS || text.runs.isEmpty() || graphics.elements.stream().anyMatch(e->e.type().equals("IMAGE")
                            && e.bounds().width()*e.bounds().height()>width*height*.65);
                    var words=fullPageOcr?ocr.read(regions.masked(),width,height,deadline):List.<TextRun>of();
                    words=FontEstimator.estimate(words,regions.masked(),width,height,fonts);
                    // El solapamiento con runs nativos evita insertar una segunda copia de las palabras leídas por OCR.
                    for (var word:words) if (text.runs.stream().noneMatch(r->r.bounds().overlap(word.bounds())>.5)) {
                        runs.add(word);addedOcr=true;
                    }
                    if (addedOcr) {
                        segments.addAll(Structure.rasterLines(regions.masked(),width,height));
                        notices.add(new Notice("OCR_ESTIMATED",number,"Texto OCR: revisa errores, fuentes, estilos y orden de lectura. Los colores y estilos originales no se recuperan del escaneo."));
                    }
                }
                // Se registra el método realmente utilizado y se emiten avisos sin convertir ausencia de texto en una falsa detección.
                boolean regionOcr=imageRegions.values().stream().anyMatch(e->!e.imageAnalysis().runs().isEmpty());
                String method=text.runs.isEmpty() && doOcr?"OCR":addedOcr || regionOcr?"MIXED":"NATIVE";
                pages.add(new SourcePage(number,width,height,method));
                if (runs.isEmpty() && !regionOcr) notices.add(new Notice("NO_TEXT",number,"No se encontró texto. La página puede estar vacía o requerir OCR u orientación manual."));
                if (!doOcr && (hasImages || runs.isEmpty() && !graphics.elements.isEmpty())) notices.add(new Notice("OCR_NOT_RUN",number,"El OCR está desactivado: las imágenes o los trazados pueden contener texto no extraído."));
                if (graphics.clipping) notices.add(new Notice("GRAPHICS_REVIEW",number,"Hay recortes o sombreados: los objetos aislados pueden diferir de la apariencia compuesta."));
                // El inventario conserva líneas originales aunque luego sus palabras pertenezcan a una tabla o desaparezcan del flow.
                for (var line:Structure.lines(runs)) objects.add(new Element("p"+number+"-text-"+objects.size(),"TEXT",number,
                        Structure.union(line),1,false,Structure.join(line),List.copyOf(line),null,null,null,List.of()));
                // La cuadrícula tiene prioridad; la segunda pasada busca tablas con bordes parciales que aún no se hayan asignado.
                var tables=Structure.tables(number,segments,runs,graphics.elements);
                tables=Structure.partialTables(number,segments,runs,graphics.elements,tables);
                flow.addAll(tables.elements());
                if (!tables.elements().isEmpty()) notices.add(new Notice("TABLE_REVIEW",number,"Tablas inferidas por cuadrículas o bordes parciales con columnas alineadas. Revisa celdas, spans, zonas ausentes de subtotales, bordes y rellenos."));
                Set<TextRun> tableText=tables.text();
                // Los glifos dibujados como formas se conservan en objects, pero no deben renderizarse además del texto OCR.
                var glyphs=outlinedGlyphs(graphics.elements,runs);
                if(!glyphs.isEmpty()) notices.add(new Notice("OUTLINED_TEXT_REMOVED_FROM_FLOW",number,
                        /** Genera una clave estable de tamaño en puntos, independiente de la configuración regional del proceso. */
                        glyphs.size()+" trazados compatibles con letras OCR se conservan en objects y se excluyen del flow para evitar duplicados. Revisa esta inferencia."));
                // Solo el texto no consumido por tablas se agrupa en párrafos. Se enlazan los trazados identificados como letras.
                for(var paragraph:Structure.paragraphs(number,runs.stream().filter(r->!tableText.contains(r)).toList())) {
                    var sources=graphics.elements.stream().filter(e->glyphs.contains(e.id()) && paragraph.bounds().overlap(e.bounds())>.5).map(Element::id).toList();
                    flow.add(new Element(paragraph.id(),paragraph.type(),number,paragraph.bounds(),paragraph.confidence(),true,
                            paragraph.text(),paragraph.runs(),null,null,null,sources));
                }
                // Se conserva lo no asignado. Un escaneo completo no se añade otra vez detrás del texto OCR recuperado.
                for (var object:graphics.elements) {
                    if (tables.borders().contains(object.id()) || glyphs.contains(object.id())) continue;
                    if (addedOcr && object.type().equals("IMAGE") && object.bounds().width()*object.bounds().height()>width*height*.65) {
                        notices.add(new Notice("SCAN_BACKGROUND_REMOVED_FROM_FLOW",number,"Imagen de escaneo conservada en objects/assets, excluida del flow para evitar duplicar el texto OCR."));
                        continue;
                    }
                    flow.add(imageRegions.getOrDefault(object.id(),object));
                }
            }
        } catch (InvalidPasswordException e) { throw new PdfProblem("PASSWORD_REQUIRED","No se admiten PDF protegidos con contraseña."); }
        flow.sort(Comparator.comparingInt(Element::sourcePage).thenComparingDouble(e->e.bounds().y()).thenComparingDouble(e->e.bounds().x()));
        // HYBRID exige Docling y respeta su orden de lectura; cualquier error se propaga, sin fallback implícito.
        String doclingVersion=null;
        if(engine==Engine.HYBRID) {
            if(docling==null) throw new PdfProblem("DOCLING_UNAVAILABLE","El cliente Docling no está configurado.");
            var result=docling.analyze(bytes,mode);doclingVersion=result.version();
            DoclingMerger.merge(result,pages,flow,objects,notices);
        }
        // Las maestras se deducen después de la estructura del cuerpo para no mover tablas a encabezados por proximidad.
        var masters=masters(pages,flow,assets,notices);
        notices.add(new Notice("LAYOUT_REVIEW",null,"Modelo intermedio: flow es una secuencia editable; bounds conserva solo la referencia original. La paginación requiere un motor CCM. Revisa columnas, párrafos, tablas sin bordes y formas rasterizadas."));
        return new Template("1.2","pt",List.copyOf(pages),masters,List.copyOf(flow),List.copyOf(objects),List.copyOf(assets),List.copyOf(notices),new Analysis(engine.name(),doclingVersion),List.copyOf(fontInfos));
    }

    /** Identifica trazados pequeños compatibles con letras OCR para retirarlos del flow sin borrar su evidencia original. */
    private static Set<String> outlinedGlyphs(List<Element> objects,List<TextRun> runs) {
        Set<String> ids=new HashSet<>();
        var ocrLines=Structure.lines(runs.stream().filter(r->r.origin().equals("OCR")).toList());
        for(var line:ocrLines) {
            var box=Structure.union(line);double font=line.stream().mapToDouble(TextRun::fontSize).max().orElse(10);
            // El margen de dos puntos tolera pequeñas diferencias entre la caja OCR y el contorno de una letra; es una heurística revisable.
            var expanded=new Bounds(box.x()-2,box.y()-2,box.width()+4,box.height()+4);
            for(var object:objects) {
                if(object.shape()==null || object.shape().paint().fill()==null || object.shape().paint().fill().equals("#FFFFFF")) continue;
                var b=object.bounds();
                if(b.width()<=font*2 && b.height()<=font*1.8 && b.height()>0 && b.width()>0
                        && expanded.overlap(b)>.8) ids.add(object.id());
            }
        }
        return ids;
    }

    /** Infiere elementos repetidos por tamaño y paridad. Mueve al contenido estático solo candidatos confirmados en al menos dos páginas. */
    private static List<PageMaster> masters(List<SourcePage> pages,List<Element> flow,List<Asset> assets,List<Notice> notices) {
        // Se crean variantes pares e impares por tamaño, incluso cuando no hay muestra suficiente de una paridad.
        Map<String,List<SourcePage>> groups=new LinkedHashMap<>();
        for (var page:pages) {
            for (String parity:List.of("ODD","EVEN")) groups.computeIfAbsent(size(page)+"-"+parity,k->new ArrayList<>());
            groups.get(size(page)+"-"+(page.number()%2==0?"EVEN":"ODD")).add(page);
        }
        // El hash permite comparar imágenes repetidas sin depender de sus identificadores distintos por página.
        Map<String,String> fingerprints=new HashMap<>();
        for (var a:assets) {
            try { fingerprints.put(a.id(),java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(a.base64().getBytes(java.nio.charset.StandardCharsets.US_ASCII)))); }
            catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        }
        List<PageMaster> masters=new ArrayList<>();Set<String> moved=new HashSet<>();
        for (var entry:groups.entrySet()) {
            var sample=entry.getValue().isEmpty()?pages.stream().filter(p->entry.getKey().startsWith(size(p)+"-")).findFirst().orElseThrow():entry.getValue().get(0);
            Set<Integer> numbers=new HashSet<>();for (var p:entry.getValue()) numbers.add(p.number());
            // Solo se proponen objetos en franjas superior/inferior o fondos grandes; el contenido único permanece en el flow.
            Map<String,List<Element>> candidates=new LinkedHashMap<>();
            for (var element:flow) {
                if (!numbers.contains(element.sourcePage()) || element.type().equals("TABLE")) continue;
                String zone=element.bounds().bottom()<=sample.height()*.14?"HEADER":element.bounds().y()>=sample.height()*.86?"FOOTER":null;
                boolean background=element.type().equals("SHAPE") && element.bounds().width()>sample.width()*.9 && element.bounds().height()>sample.height()*.9;
                if (zone==null && !background) continue;
                if (background) zone="BACKGROUND";
                String value=element.text()!=null?pageNumberPattern(element,pages.size()):element.assetId()!=null?fingerprints.get(element.assetId()):String.valueOf(element.shape());
                // Los runs e IDs cambian por página; se comparan contenido y geometría aproximada.
                var b=element.bounds();
                String key=zone+"|"+element.type()+"|"+value+"|"+Math.round(b.x()/4)+"|"+Math.round(b.y()/4)+"|"+Math.round(b.width()/4)+"|"+Math.round(b.height()/4);
                candidates.computeIfAbsent(key,k->new ArrayList<>()).add(element);
            }
            List<Element> header=new ArrayList<>(),footer=new ArrayList<>(),background=new ArrayList<>();
            // Se exige repetición en todas las páginas observadas de esa variante y al menos dos muestras para confirmarla.
            for (var candidate:candidates.entrySet()) {
                if (candidate.getValue().stream().map(Element::sourcePage).distinct().count()<2
                        || candidate.getValue().stream().map(Element::sourcePage).distinct().count()!=numbers.size()) continue;
                var first=candidate.getValue().get(0);
                if (first.text()!=null && !pageNumberPattern(first,pages.size()).equals(first.text())) {
                    first=new Element(first.id(),"PAGE_NUMBER",first.sourcePage(),first.bounds(),.8,true,
                            pageNumberPattern(first,pages.size()),first.runs(),null,null,null,first.sourceObjectIds());
                }
                if (candidate.getKey().startsWith("HEADER")) header.add(first);
                else if (candidate.getKey().startsWith("FOOTER")) footer.add(first);
                else background.add(first);
                for (var e:candidate.getValue()) moved.add(e.id());
            }
            // Los márgenes son estimaciones editables; 54 puntos es el valor inicial cuando no se detectan elementos estáticos.
            double top=header.stream().mapToDouble(e->e.bounds().bottom()+12).max().orElse(54);
            double bottom=footer.stream().mapToDouble(e->sample.height()-e.bounds().y()+12).max().orElse(54);
            masters.add(new PageMaster(entry.getKey(),entry.getKey().endsWith("EVEN")?"EVEN":"ODD",sample.width(),sample.height(),
                    new Margins(top,54,bottom,54),true,header,footer,background));
            if (numbers.size()<2) notices.add(new Notice("MASTER_NEEDS_REVIEW",null,"Maestra "+entry.getKey()+": faltan al menos dos páginas de esta paridad para confirmar elementos repetidos; no se retira contenido único del flow."));
        }
        flow.removeIf(e->moved.contains(e.id()));
        return List.copyOf(masters);
    }
    /** Genera una clave estable de tamaño en puntos, independiente de la configuración regional del proceso. */
    private static String size(SourcePage p) { return String.format(java.util.Locale.ROOT,"%.2fx%.2f",p.width(),p.height()); }
    /** Convierte números en campos dinámicos solo si coinciden con la página de origen y, cuando aparece, con el total de páginas. */
    private static String pageNumberPattern(Element element,int totalPages) {
        String text=element.text();
        if (text.matches("(?iu)^(?:(?:página|pagina|page|pág\\.?)\\s*)?\\d+(?:\\s*(?:de|of|/)\\s*\\d+)?$")) {
            // Comprobar el valor de página evita transformar años, referencias u otros números fijos en campos dinámicos.
            var numbers=java.util.regex.Pattern.compile("\\d+").matcher(text);
            if (!numbers.find() || !numbers.group().equals(Integer.toString(element.sourcePage()))) return text;
            if (numbers.find() && !numbers.group().equals(Integer.toString(totalPages))) return text;
            return text.replaceFirst("\\d+","\\${pageNumber}").replaceFirst("\\d+","\\${pageCount}");
        }
        return text;
    }
}
