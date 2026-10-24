package com.tankemd.pdfreader.template;

import static com.tankemd.pdfreader.template.Template.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Combina semántica Docling y evidencia PDFBox sin ocultar discrepancias ni borrar el inventario original. */
final class DoclingMerger {
    /** Impide instanciar esta utilidad de combinación de resultados. */
    private DoclingMerger() {}

    /** Asocia regiones semánticas a objetos originales y actualiza flow y notices. Mantiene el inventario original y señala los desacuerdos entre motores. */
    static void merge(DoclingClient.Result result,List<SourcePage> pages,List<Element> flow,List<Element> objects,List<Notice> notices) {
        // Los conjuntos de asignación evitan consumir dos veces el mismo texto o renderizar otra vez bordes ya asociados.
        List<Element> semantic=new ArrayList<>();Set<TextRun> consumed=new HashSet<>();Set<String> assigned=new HashSet<>();Map<String,Integer> order=new HashMap<>();
        var sourceText=objects.stream().filter(e->e.type().equals("TEXT")).toList();
        var images=flow.stream().filter(e->e.type().equals("IMAGE")).toList();
        // Se valida cada tabla antes de tocar el flow; la respuesta semántica no se considera fiable solo por venir de Docling.
        for(var candidate:result.tables()) {
            checkBox(candidate.page(),candidate.bounds(),pages);
            if(candidate.rows()<1 || candidate.rows()>500 || candidate.columns()<1 || candidate.columns()>100 || candidate.cells()==null || candidate.cells().size()>10_000)
                throw invalid();
            // Una etiqueta conservada como imagen sigue siendo un bloque; una tabla detectada dentro no debe superponerse a ella.
            if(images.stream().anyMatch(e->e.sourcePage()==candidate.page() && e.bounds().overlap(candidate.bounds())>.5)) {
                notices.add(new Notice("DOCLING_IMAGE_OVERLAP",candidate.page(),"Tabla propuesta dentro de una imagen conservada: se mantiene la imagen para evitar duplicar su contenido."));continue;
            }
            if(candidate.cells().isEmpty()) throw invalid();
            if(semantic.stream().anyMatch(e->e.type().equals("TABLE") && e.sourcePage()==candidate.page() && e.bounds().overlap(candidate.bounds())>.5)) {
                notices.add(new Notice("DOCLING_TABLE_CONFLICT",candidate.page(),"Dos tablas semánticas se solapan; la segunda se deja pendiente de revisión."));continue;
            }
            // La matriz de ocupación detecta spans incompatibles; una posición sin ocupar puede ser una zona ausente del subtotal.
            List<Cell> cells=new ArrayList<>();boolean[][] occupied=new boolean[candidate.rows()][candidate.columns()];
            List<String> borderIds=objects.stream().filter(e->e.sourcePage()==candidate.page() && borderWithin(e,candidate.bounds())).map(Element::id).toList();
            Set<String> backgrounds=new HashSet<>();
            // Cada celda debe caber en la topología declarada y no invadir posiciones ya ocupadas por otra celda combinada.
            for(var cell:candidate.cells()) {
                if(cell.row()<0 || cell.column()<0 || cell.rowSpan()<1 || cell.columnSpan()<1
                        || cell.row()+cell.rowSpan()>candidate.rows() || cell.column()+cell.columnSpan()>candidate.columns()) throw invalid();
                for(int row=cell.row();row<cell.row()+cell.rowSpan();row++) for(int col=cell.column();col<cell.column()+cell.columnSpan();col++) {
                    if(occupied[row][col]) throw invalid();occupied[row][col]=true;
                }
                // Si falta la caja de una celda se aproxima con la malla uniforme; su pertenencia a página y tabla se comprueba igualmente.
                Bounds box=cell.bounds()!=null?cell.bounds():new Bounds(candidate.bounds().x()+candidate.bounds().width()*cell.column()/candidate.columns(),
                        candidate.bounds().y()+candidate.bounds().height()*cell.row()/candidate.rows(),candidate.bounds().width()*cell.columnSpan()/candidate.columns(),candidate.bounds().height()*cell.rowSpan()/candidate.rows());
                checkBox(candidate.page(),box,pages);
                if(!inside(candidate.bounds(),box,5)) throw invalid();
                // Se prioriza texto de origen con estilos conservados. Los fondos se asocian por cobertura, no por mera proximidad.
                var runs=runsWithin(sourceText,candidate.page(),box,consumed);
                var fills=objects.stream().filter(e->e.sourcePage()==candidate.page() && e.shape()!=null && e.shape().paint().fill()!=null
                        && inside(candidate.bounds(),e.bounds(),2) && e.bounds().width()>=box.width()*.8 && e.bounds().height()>=box.height()*.8
                        && e.bounds().contains(box.x()+box.width()/2,box.y()+box.height()/2)).map(Element::id).toList();
                backgrounds.addAll(fills);consumed.addAll(runs);
                String text=runs.isEmpty()?cell.text():Structure.join(runs);
                cells.add(new Cell(cell.row(),cell.column(),box,text==null?"":text,runs,fills,cell.rowSpan(),cell.columnSpan()));
            }
            List<String> ids=new ArrayList<>(borderIds);ids.addAll(backgrounds);assigned.addAll(ids);
            String id="docling-p"+candidate.page()+"-table-"+semantic.size();
            semantic.add(new Element(id,"TABLE",candidate.page(),candidate.bounds(),.85,true,null,null,null,null,
                    new Table(candidate.rows(),candidate.columns(),axisSizes(candidate,true),axisSizes(candidate,false),cells,borderIds,"DOCLING"),ids));
            order.put(id,candidate.order());
            // Un desacuerdo de dimensiones entre motores se hace visible para la revisión en lugar de ocultarse.
            if(flow.stream().anyMatch(e->e.type().equals("TABLE") && e.sourcePage()==candidate.page() && e.bounds().overlap(candidate.bounds())>.5
                    && (e.table().rows()!=candidate.rows() || e.table().columns()!=candidate.columns())))
                notices.add(new Notice("TABLE_ENGINE_DISAGREEMENT",candidate.page(),"Docling y la geometría proponen distintas dimensiones de tabla; se conserva la interpretación semántica con revisión obligatoria."));
        }
        var tables=semantic.stream().filter(e->e.type().equals("TABLE")).toList();
        // Los párrafos semánticos solo se incorporan si pueden asociarse a texto original y no duplican tablas o imágenes.
        for(var block:result.blocks()) {
            checkBox(block.page(),block.bounds(),pages);
            if(block.text()==null || block.text().isBlank() || tables.stream().anyMatch(t->t.sourcePage()==block.page() && t.bounds().overlap(block.bounds())>.5)
                    || images.stream().anyMatch(e->e.sourcePage()==block.page() && e.bounds().overlap(block.bounds())>.5)) continue;
            // Las tablas geométricas no sustituidas por Docling conservan su región de contenido.
            if(flow.stream().anyMatch(e->e.type().equals("TABLE") && e.sourcePage()==block.page() && e.bounds().overlap(block.bounds())>.5)) continue;
            var runs=runsWithin(sourceText,block.page(),block.bounds(),consumed);
            if(runs.isEmpty()) { notices.add(new Notice("DOCLING_TEXT_UNMATCHED",block.page(),"Bloque semántico sin texto de origen asociado; se conserva la extracción local para evitar inventar o duplicar contenido."));continue; }
            String id="docling-p"+block.page()+"-block-"+semantic.size();
            var sources=sourceText.stream().filter(e->e.sourcePage()==block.page() && e.runs().stream().anyMatch(runs::contains)).map(Element::id).toList();
            semantic.add(new Element(id,"PARAGRAPH",block.page(),block.bounds(),.85,true,block.text(),runs,null,null,null,sources));
            consumed.addAll(runs);order.put(id,block.order());
        }
        // Se retiran únicamente los runs y objetos consumidos; los fragmentos restantes siguen disponibles en el flujo.
        List<Element> retained=new ArrayList<>();
        for(var element:flow) {
            if(assigned.contains(element.id())) continue;
            if(element.type().equals("TABLE") && tables.stream().anyMatch(t->t.sourcePage()==element.sourcePage() && t.bounds().overlap(element.bounds())>.5)) continue;
            if(element.type().equals("PARAGRAPH") && element.runs()!=null) {
                var remaining=element.runs().stream().filter(r->!consumed.contains(r)).toList();
                if(remaining.size()==element.runs().size()) retained.add(element);
                else if(!remaining.isEmpty()) retained.add(new Element(element.id(),"PARAGRAPH",element.sourcePage(),Structure.union(remaining),element.confidence(),true,
                        Structure.join(remaining).replace('\n',' '),remaining,null,null,null,element.sourceObjectIds()));
            } else retained.add(element);
        }
        retained.addAll(semantic);
        // Los bloques semánticos siguen su orden Docling; los elementos conservados se intercalan mediante una aproximación geométrica.
        retained.sort(Comparator.comparingInt(Element::sourcePage).thenComparingDouble(e->readingOrder(e,semantic,order)).thenComparingDouble(e->e.bounds().x()));
        flow.clear();flow.addAll(retained);
        notices.add(new Notice("DOCLING_APPLIED",null,"Análisis semántico Docling "+result.version()+" combinado con objetos PDFBox y OCR local. Las celdas combinadas y el orden de lectura requieren revisión."));
    }

    /** Usa el orden semántico cuando existe y aproxima la posición relativa de los elementos conservados por su altura en la página. */
    private static double readingOrder(Element e,List<Element> semantic,Map<String,Integer> order) {
        if(order.containsKey(e.id())) return order.get(e.id());
        return semantic.stream().filter(s->s.sourcePage()==e.sourcePage() && s.bounds().y()<=e.bounds().y())
                .mapToDouble(s->order.get(s.id())+.5).max().orElse(-.5);
    }
    /** Estima anchos o alturas a partir de bordes de celdas observados; interpola los límites que no aportan las celdas combinadas. */
    private static List<Double> axisSizes(DoclingClient.TableResult table,boolean horizontal) {
        int count=horizontal?table.columns():table.rows();double total=horizontal?table.bounds().width():table.bounds().height();
        double origin=horizontal?table.bounds().x():table.bounds().y();double[] sums=new double[count+1];int[] samples=new int[count+1];
        for(var cell:table.cells()) if(cell.bounds()!=null) {
            int start=horizontal?cell.column():cell.row(),end=start+(horizontal?cell.columnSpan():cell.rowSpan());
            sums[start]+=(horizontal?cell.bounds().x():cell.bounds().y())-origin;samples[start]++;
            sums[end]+=(horizontal?cell.bounds().right():cell.bounds().bottom())-origin;samples[end]++;
        }
        // Se promedian los bordes observados. Los huecos debidos a spans se interpolan entre límites conocidos.
        double[] edges=new double[count+1];edges[0]=0;edges[count]=total;
        for(int i=1;i<count;i++) edges[i]=samples[i]>0?Math.max(0,Math.min(total,sums[i]/samples[i])):Double.NaN;
        for(int i=1;i<count;i++) if(Double.isNaN(edges[i])) {
            int end=i+1;while(Double.isNaN(edges[end])) end++;
            int start=i-1;for(int j=i;j<end;j++) edges[j]=edges[start]+(edges[end]-edges[start])*(j-start)/(end-start);
        }
        var out=new ArrayList<Double>();for(int i=0;i<count;i++) out.add(edges[i+1]-edges[i]);
        // Si los límites observados son incompatibles se usan tamaños uniformes de referencia, sujetos a revisión.
        if(out.stream().anyMatch(size->size<=0)) { out.clear();for(int i=0;i<count;i++) out.add(total/count); }
        return out;
    }
    /** Selecciona texto original aún no asignado cuyo centro cae dentro de la región de la página indicada. */
    private static List<TextRun> runsWithin(List<Element> source,int page,Bounds box,Set<TextRun> consumed) {
        return source.stream().filter(e->e.sourcePage()==page).flatMap(e->e.runs().stream()).filter(r->!consumed.contains(r)
                && box.contains(r.bounds().x()+r.bounds().width()/2,r.bounds().y()+r.bounds().height()/2)).toList();
    }
    /** Identifica trazos y rellenos estrechos contenidos en la tabla que podrían corresponder a sus bordes. */
    private static boolean borderWithin(Element e,Bounds box) {
        return e.shape()!=null && inside(box,e.bounds(),3) && (e.shape().paint().stroke()!=null
                || e.shape().paint().fill()!=null && (e.bounds().height()<=2 && e.bounds().width()>20 || e.bounds().width()<=2 && e.bounds().height()>20));
    }
    /** Comprueba contención geométrica admitiendo pequeñas diferencias entre las cajas de los dos analizadores. */
    private static boolean inside(Bounds outer,Bounds inner,double tolerance) {
        return inner.x()>=outer.x()-tolerance && inner.y()>=outer.y()-tolerance && inner.right()<=outer.right()+tolerance && inner.bottom()<=outer.bottom()+tolerance;
    }
    /** Rechaza páginas inexistentes, valores no finitos y cajas fuera del papel antes de consumir una respuesta externa. */
    private static void checkBox(int page,Bounds box,List<SourcePage> pages) {
        if(page<1 || page>pages.size() || box==null || !Double.isFinite(box.x()+box.y()+box.width()+box.height()) || box.width()<0 || box.height()<0) throw invalid();
        var source=pages.get(page-1);if(!inside(new Bounds(0,0,source.width(),source.height()),box,5)) throw invalid();
    }
    /** Construye el error de contrato que impide aceptar una estructura semántica incoherente. */
    private static PdfProblem invalid() { return new PdfProblem("DOCLING_INVALID_RESPONSE","Geometría o estructura de celdas no válida en la respuesta de Docling."); }
}
