package com.tankemd.pdfreader.template;

import static com.tankemd.pdfreader.template.Template.*;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reconstruye párrafos y tablas mediante geometría; las clasificaciones son heurísticas y revisables. */
final class Structure {
    /** Segmento candidato a borde con referencia al objeto gráfico del que procede. */
    record Segment(double x1,double y1,double x2,double y2,String objectId) {
        /** Admite pequeñas desviaciones al reconocer un segmento horizontal y descarta trazos muy cortos. */
        boolean horizontal() { return Math.abs(y2-y1)<1.5 && Math.abs(x2-x1)>15; }
        /** Admite pequeñas desviaciones al reconocer un segmento vertical y descarta trazos muy cortos. */
        boolean vertical() { return Math.abs(x2-x1)<1.5 && Math.abs(y2-y1)>15; }
        /** Devuelve el extremo izquierdo independientemente del sentido del segmento. */
        double left() { return Math.min(x1,x2); }
        /** Devuelve el extremo derecho independientemente del sentido del segmento. */
        double right() { return Math.max(x1,x2); }
        /** Devuelve el extremo superior del segmento en coordenadas de pantalla. */
        double top() { return Math.min(y1,y2); }
        /** Devuelve el extremo inferior del segmento en coordenadas de pantalla. */
        double bottom() { return Math.max(y1,y2); }
    }
    /** Tablas detectadas y conjuntos de texto/bordes consumidos para evitar duplicarlos en el flow. */
    record Tables(List<Element> elements, Set<TextRun> text, Set<String> borders) {}

    /** Descompone contornos vectoriales con trazo en segmentos candidatos a bordes; las curvas no se convierten en líneas. */
    static List<Segment> segments(List<Element> objects) {
        List<Segment> segments=new ArrayList<>();
        // Solo los trazados con contorno aportan segmentos vectoriales; se conserva el ID para asociar bordes a celdas.
        for (var object:objects) {
            if (object.shape()==null || object.shape().paint().stroke()==null) continue;
            double x=0,y=0,startX=0,startY=0;
            for (var c:object.shape().path()) {
                if (c.operator().equals("M")) { x=c.coordinates().get(0); y=c.coordinates().get(1); startX=x; startY=y; }
                else if (c.operator().equals("L")) {
                    double nx=c.coordinates().get(0),ny=c.coordinates().get(1);
                    segments.add(new Segment(x,y,nx,ny,object.id())); x=nx;y=ny;
                } else if (c.operator().equals("Z")) segments.add(new Segment(x,y,startX,startY,object.id()));
                else if (c.operator().equals("C")) { x=c.coordinates().get(4); y=c.coordinates().get(5); }
            }
        }
        return segments;
    }

    /** Busca líneas horizontales y verticales en una página rasterizada y convierte sus píxeles a puntos. */
    static List<Segment> rasterLines(BufferedImage image, double width, double height) {
        List<Segment> out=new ArrayList<>();
        double sx=width/image.getWidth(),sy=height/image.getHeight();
        scan(image,true,sx,sy,out); scan(image,false,sx,sy,out);
        return out;
    }
    /** Recorre filas o columnas buscando secuencias oscuras largas, tolera huecos pequeños y agrupa líneas gruesas. */
    private static void scan(BufferedImage image, boolean horizontal, double sx,double sy,List<Segment> out) {
        int outer=horizontal?image.getHeight():image.getWidth();
        int inner=horizontal?image.getWidth():image.getHeight();
        // La longitud mínima se expresa en puntos y se convierte a píxeles para que no dependa de la resolución del render.
        int minimum=(int)(40/(horizontal?sx:sy));
        for (int a=0;a<outer;a++) {
            int start=-1,gap=0;
            for (int b=0;b<=inner;b++) {
                // El umbral oscuro y la tolerancia de huecos son reglas de visión simples; no reconocen estilos originales del escaneo.
                int rgb=b<inner?image.getRGB(horizontal?b:a,horizontal?a:b):0xffffff;
                boolean dark=b<inner && ((rgb>>16)&255)<110 && ((rgb>>8)&255)<110 && (rgb&255)<110;
                if (dark) { if (start<0) start=b; gap=0; }
                else if (start>=0 && (++gap>2 || b==inner)) {
                    int end=b-gap;
                    if (end-start>=minimum) {
                        var s=horizontal?new Segment(start*sx,a*sy,end*sx,a*sy,"raster"):
                                new Segment(a*sx,start*sy,a*sx,end*sy,"raster");
                        // Agrupa píxeles vecinos de un mismo borde grueso para no contar varias líneas.
                        if (out.stream().noneMatch(t->Math.abs(t.x1-s.x1)<2 && Math.abs(t.y1-s.y1)<2
                                && Math.abs(t.x2-s.x2)<2 && Math.abs(t.y2-s.y2)<2)) out.add(s);
                        if (out.size()>2000) return;
                    }
                    start=-1;gap=0;
                }
            }
        }
    }

    /** Reconstruye cuadrículas por componentes conectados y bordes comprobables; deduce spans sin inventar celdas en zonas abiertas. */
    static Tables tables(int page, List<Segment> segments, List<TextRun> text, List<Element> objects) {
        List<Segment> remaining=new ArrayList<>(segments.stream().filter(s->s.horizontal()||s.vertical()).toList());
        List<Element> tables=new ArrayList<>(); Set<TextRun> used=new HashSet<>(); Set<String> borders=new HashSet<>();
        // Se agrupan segmentos conectados antes de buscar filas y columnas para no mezclar tablas alejadas.
        while (!remaining.isEmpty()) {
            List<Segment> component=new ArrayList<>();component.add(remaining.remove(0));
            for (int i=0;i<component.size();i++) {
                var current=component.get(i);
                for (var iterator=remaining.iterator();iterator.hasNext();) {
                    var next=iterator.next();
                    if (intersects(current,next)) { component.add(next);iterator.remove(); }
                }
            }
            var horizontal=component.stream().filter(Segment::horizontal).toList();
            var vertical=component.stream().filter(Segment::vertical).toList();
            // Se agrupan bordes casi coincidentes y se limita la topología para acotar memoria y coste de clasificación.
            var xs=unique(vertical.stream().map(s->s.x1).toList());
            var ys=unique(horizontal.stream().map(s->s.y1).toList());
            if (xs.size()<3 || ys.size()<3 || xs.size()>50 || ys.size()>200) continue;
            double left=xs.get(0),right=xs.get(xs.size()-1),top=ys.get(0),bottom=ys.get(ys.size()-1);
            int rowCount=ys.size()-1,columnCount=xs.size()-1;
            // Union-find representa posiciones elementales: si falta un borde interno, ambas posiciones podrían ser una celda combinada.
            int[] parents=new int[rowCount*columnCount];for(int i=0;i<parents.length;i++) parents[i]=i;
            for(int row=0;row<rowCount;row++) for(int col=0;col<columnCount;col++) {
                int id=row*columnCount+col;
                if(col+1<columnCount && !covers(vertical,false,xs.get(col+1),ys.get(row),ys.get(row+1))) union(parents,id,id+1);
                if(row+1<rowCount && !covers(horizontal,true,ys.get(row+1),xs.get(col),xs.get(col+1))) union(parents,id,id+columnCount);
            }
            // Cada componente de posiciones se evalúa por separado; una fila parcial no obliga a inventar celdas abiertas.
            java.util.Map<Integer,List<Integer>> groups=new java.util.LinkedHashMap<>();
            for(int i=0;i<parents.length;i++) groups.computeIfAbsent(root(parents,i),key->new ArrayList<>()).add(i);
            List<Cell> cells=new ArrayList<>();int populated=0;
            boolean irregular=false;
            Set<String> backgrounds=new HashSet<>();
            Set<TextRun> cellText=new HashSet<>();
            for(var group:groups.values()) {
                int row=group.stream().mapToInt(i->i/columnCount).min().orElseThrow(),col=group.stream().mapToInt(i->i%columnCount).min().orElseThrow();
                int endRow=group.stream().mapToInt(i->i/columnCount).max().orElseThrow()+1,endCol=group.stream().mapToInt(i->i%columnCount).max().orElseThrow()+1;
                int rowSpan=endRow-row,columnSpan=endCol-col;
                Bounds box=new Bounds(xs.get(col),ys.get(row),xs.get(endCol)-xs.get(col),ys.get(endRow)-ys.get(row));
                // Solo se aceptan grupos rectangulares cuyo perímetro esté realmente cubierto. Los huecos de subtotal se descartan.
                if(group.size()!=rowSpan*columnSpan || !covers(horizontal,true,box.y(),box.x(),box.right())
                        || !covers(horizontal,true,box.bottom(),box.x(),box.right()) || !covers(vertical,false,box.x(),box.y(),box.bottom())
                        || !covers(vertical,false,box.right(),box.y(),box.bottom())) { irregular=true;continue; }
                if(rowSpan>1 || columnSpan>1) irregular=true;
                // El centro del run determina su celda; los textos asignados se excluyen posteriormente de los párrafos del cuerpo.
                var runs=text.stream().filter(t->box.contains(t.bounds().x()+t.bounds().width()/2,
                        t.bounds().y()+t.bounds().height()/2)).toList();
                if (!runs.isEmpty()) populated++;
                // Un fondo debe cubrir buena parte de la celda; una letra rellena no debe clasificarse como color de fondo.
                var fills=objects.stream().filter(e->e.shape()!=null && e.shape().paint().fill()!=null
                        && e.bounds().x()>=left-2 && e.bounds().right()<=right+2
                        && e.bounds().y()>=top-2 && e.bounds().bottom()<=bottom+2
                        && e.bounds().width()>=box.width()*.8 && e.bounds().height()>=box.height()*.8
                        && e.bounds().contains(box.x()+box.width()/2,box.y()+box.height()/2))
                        .map(Element::id).toList();
                cells.add(new Cell(row,col,box,join(runs),runs,fills,rowSpan,columnSpan));cellText.addAll(runs);backgrounds.addAll(fills);
            }
            // Se exige texto en al menos dos celdas para reducir falsos positivos de dibujos y cuadrículas decorativas.
            if (populated<2) continue;
            var ids=component.stream().map(Segment::objectId).filter(id->!id.equals("raster")).distinct().toList();
            List<Double> widths=new ArrayList<>();for (int i=0;i<xs.size()-1;i++) widths.add(xs.get(i+1)-xs.get(i));
            List<Double> heights=new ArrayList<>();for (int i=0;i<ys.size()-1;i++) heights.add(ys.get(i+1)-ys.get(i));
            var sources=new ArrayList<>(ids);sources.addAll(backgrounds);
            tables.add(new Element("p"+page+"-table-"+tables.size(),"TABLE",page,new Bounds(left,top,right-left,bottom-top),
                    irregular?.8:.85,true,null,null,null,null,new Table(ys.size()-1,xs.size()-1,widths,heights,cells,ids,irregular?"GRID_IRREGULAR":"GRID"),sources));
            used.addAll(cellText);borders.addAll(sources);
        }
        return new Tables(tables,used,borders);
    }

    /** Comprueba que la unión de segmentos colineales cubre un borde completo, admitiendo pequeños huecos de extracción. */
    private static boolean covers(List<Segment> lines,boolean horizontal,double position,double start,double end) {
        double cursor=start;
        // Los intervalos se recorren ordenados; un hueco mayor que la tolerancia impide dar el borde por completo.
        var candidates=lines.stream().filter(s->Math.abs((horizontal?s.y1():s.x1())-position)<2)
                .sorted(Comparator.comparingDouble(s->horizontal?s.left():s.top())).toList();
        for(var line:candidates) {
            double from=horizontal?line.left():line.top(),to=horizontal?line.right():line.bottom();
            if(to<cursor-2) continue;
            if(from>cursor+2) return false;
            cursor=Math.max(cursor,to);if(cursor>=end-2) return true;
        }
        return cursor>=end-2;
    }
    /** Obtiene el representante de un conjunto de posiciones de cuadrícula y comprime el camino para acelerar consultas posteriores. */
    private static int root(int[] parents,int id) { while(parents[id]!=id) { parents[id]=parents[parents[id]];id=parents[id]; }return id; }
    /** Fusiona los representantes de dos posiciones de la cuadrícula. */
    private static void union(int[] parents,int a,int b) { parents[root(parents,a)]=root(parents,b); }

    /** Busca tablas no resueltas por la cuadrícula mediante contorno cerrado, separadores reales y columnas de texto alineadas. */
    static Tables partialTables(int page,List<Segment> segments,List<TextRun> text,List<Element> objects,Tables grid) {
        List<Element> tables=new ArrayList<>(grid.elements());Set<TextRun> used=new HashSet<>(grid.text());Set<String> borders=new HashSet<>(grid.borders());
        // La pasada parcial solo examina regiones cerradas que no pertenecen ya a otra tabla.
        for (var outline:objects) {
            Bounds box=outline.bounds();
            if (outline.shape()==null || outline.shape().paint().stroke()==null
                    || outline.shape().path().stream().noneMatch(c->c.operator().equals("Z"))
                    || box.width()<100 || box.height()<25 || borders.contains(outline.id())
                    || tables.stream().anyMatch(t->t.bounds().overlap(box)>.5)) continue;
            var inside=text.stream().filter(t->!used.contains(t) && box.contains(t.bounds().x()+t.bounds().width()/2,t.bounds().y()+t.bounds().height()/2)).toList();
            var rows=lines(inside);
            if(rows.size()<2) continue;
            var header=fragments(rows.get(0));
            if(header.size()<2 || header.size()>10) continue;
            // Un hueco amplio puede ser prosa a dos columnas: se exige además un separador real.
            List<Double> separators=new ArrayList<>();Set<String> ids=new HashSet<>();ids.add(outline.id());
            double headerBottom=union(rows.get(0)).bottom();
            for(var segment:segments) if(segment.horizontal() && segment.right()-segment.left()>box.width()*.55
                    && segment.left()>=box.x()-3 && segment.right()<=box.right()+3
                    && segment.y1()>headerBottom+1 && segment.y1()<box.bottom()-5) {
                separators.add(segment.y1());if(!segment.objectId().equals("raster")) ids.add(segment.objectId());
            }
            for(var object:objects) if(object.shape()!=null && object.bounds().height()<=2 && object.bounds().width()>box.width()*.55
                    && object.bounds().x()>=box.x()-3 && object.bounds().right()<=box.right()+3
                    && object.bounds().y()>headerBottom+1 && object.bounds().bottom()<box.bottom()-5) {
                separators.add(object.bounds().y()+object.bounds().height()/2);ids.add(object.id());
            }
            separators=unique(separators);
            if(separators.isEmpty()) continue;
            // El inicio de la siguiente columna y el margen del encabezado aproximan la separación; no crean un borde observado.
            double padding=Math.max(4,Math.min(16,union(header.get(0)).x()-box.x()));
            List<Double> xs=new ArrayList<>();xs.add(box.x());
            for(int i=1;i<header.size();i++) xs.add(union(header.get(i)).x()-padding);
            xs.add(box.right());
            // Si una palabra atraviesa el límite inferido, la hipótesis de columnas se rechaza por ambigua.
            boolean crossing=inside.stream().anyMatch(t->xs.subList(1,xs.size()-1).stream()
                    .anyMatch(x->t.bounds().x()<x-1 && t.bounds().right()>x+1));
            if(crossing) continue;
            List<Double> ys=new ArrayList<>();ys.add(box.y());ys.addAll(separators);ys.add(box.bottom());
            List<Cell> cells=new ArrayList<>();Set<String> backgrounds=new HashSet<>();boolean aligned=true;
            for(int row=0;row<ys.size()-1;row++) {
                int populated=0;
                for(int col=0;col<xs.size()-1;col++) {
                    Bounds cell=new Bounds(xs.get(col),ys.get(row),xs.get(col+1)-xs.get(col),ys.get(row+1)-ys.get(row));
                    var runs=inside.stream().filter(t->cell.contains(t.bounds().x()+t.bounds().width()/2,t.bounds().y()+t.bounds().height()/2)).toList();
                    if(!runs.isEmpty()) populated++;
                    var fills=objects.stream().filter(e->e.shape()!=null && e.shape().paint().fill()!=null
                            && e.bounds().width()>=cell.width()*.8 && e.bounds().height()>=cell.height()*.8
                            && e.bounds().x()>=box.x()-2 && e.bounds().right()<=box.right()+2
                            && e.bounds().y()>=box.y()-2 && e.bounds().bottom()<=box.bottom()+2
                            && e.bounds().contains(cell.x()+cell.width()/2,cell.y()+cell.height()/2)).map(Element::id).toList();
                    cells.add(new Cell(row,col,cell,join(runs),runs,fills));backgrounds.addAll(fills);
                }
                if(populated<2) aligned=false;
            }
            // Cada fila propuesta debe aportar datos en al menos dos columnas antes de convertirse en tabla.
            if(!aligned) continue;
            var borderIds=List.copyOf(ids);ids.addAll(backgrounds);
            tables.add(new Element("p"+page+"-table-"+tables.size(),"TABLE",page,box,.7,true,null,null,null,null,
                    new Table(ys.size()-1,xs.size()-1,differences(xs),differences(ys),cells,borderIds,"PARTIAL_BORDERS"),List.copyOf(ids)));
            used.addAll(inside);borders.addAll(ids);
        }
        return new Tables(tables,used,borders);
    }

    /** Convierte posiciones ordenadas de bordes en anchos de columnas o alturas de filas. */
    private static List<Double> differences(List<Double> values) {
        List<Double> result=new ArrayList<>();for(int i=0;i<values.size()-1;i++) result.add(values.get(i+1)-values.get(i));return result;
    }

    /** Separa una línea visual por huecos amplios para no mezclar columnas de texto independientes. */
    private static List<List<TextRun>> fragments(List<TextRun> line) {
        List<List<TextRun>> fragments=new ArrayList<>();List<TextRun> current=new ArrayList<>();
        for(var run:line) {
            if(!current.isEmpty() && run.bounds().x()-current.get(current.size()-1).bounds().right()>Math.max(24,run.fontSize()*3)) {
                fragments.add(current);current=new ArrayList<>();
            }
            current.add(run);
        }
        if(!current.isEmpty()) fragments.add(current);return fragments;
    }
    /** Comprueba proximidad de segmentos para construir componentes conectados de posibles tablas. */
    private static boolean intersects(Segment a, Segment b) {
        return a.left()<=b.right()+3 && a.right()+3>=b.left() && a.top()<=b.bottom()+3 && a.bottom()+3>=b.top();
    }
    /** Ordena posiciones y agrupa límites prácticamente coincidentes que pueden proceder del grosor de un mismo borde. */
    private static List<Double> unique(List<Double> input) {
        List<Double> out=new ArrayList<>();
        for (double d:input.stream().sorted().toList()) if (out.isEmpty() || d-out.get(out.size()-1)>3) out.add(d);
        return out;
    }
    /** Agrupa runs por proximidad vertical de sus bases y los ordena de izquierda a derecha dentro de cada línea. */
    static List<List<TextRun>> lines(List<TextRun> runs) {
        List<List<TextRun>> lines=new ArrayList<>();
        // Los runs se ordenan visualmente y se comparan sus bases con una tolerancia proporcional a la altura de letra.
        for (var run:runs.stream().sorted(Comparator.comparingDouble((TextRun r)->r.bounds().y()).thenComparingDouble(r->r.bounds().x())).toList()) {
            List<TextRun> match=null;
            for (int i=lines.size()-1;i>=0;i--) {
                var first=lines.get(i).get(0);
                if (run.bounds().y()-first.bounds().y()>Math.max(5,first.bounds().height())) break;
                if (Math.abs(run.bounds().bottom()-first.bounds().bottom())<Math.max(3,first.bounds().height()*.45)) { match=lines.get(i); break; }
            }
            if (match==null) { match=new ArrayList<>();lines.add(match); }
            match.add(run);
        }
        for (var line:lines) line.sort(Comparator.comparingDouble(r->r.bounds().x()));
        return lines;
    }
    /** Reconstruye texto de líneas y espacios a partir de runs nativos o palabras OCR sin fijar una maquetación de salida. */
    static String join(List<TextRun> runs) {
        StringBuilder text=new StringBuilder();
        for (var line:lines(runs)) {
            if (!text.isEmpty()) text.append('\n');
            TextRun previous=null;
            for (var r:line) {
                // OCR ya aporta palabras completas; en texto nativo los espacios se deducen de la distancia entre fragmentos.
                if (previous!=null && (r.origin().equals("OCR") || r.bounds().x()-previous.bounds().right()>Math.max(1,r.fontSize()*.15))) text.append(' ');
                text.append(r.text());previous=r;
            }
        }
        return text.toString();
    }
    /** Calcula la caja envolvente de todos los runs de un bloque. */
    static Bounds union(List<TextRun> runs) {
        double x=runs.stream().mapToDouble(r->r.bounds().x()).min().orElse(0),y=runs.stream().mapToDouble(r->r.bounds().y()).min().orElse(0);
        return new Bounds(x,y,runs.stream().mapToDouble(r->r.bounds().right()).max().orElse(x)-x,
                runs.stream().mapToDouble(r->r.bounds().bottom()).max().orElse(y)-y);
    }
    /** Agrupa líneas compatibles en párrafos refluibles; conserva runs y confianza para permitir corregir las hipótesis. */
    static List<Element> paragraphs(int page,List<TextRun> runs) {
        List<List<TextRun>> groups=new ArrayList<>();
        // La agrupación del párrafo comienza por líneas visuales y separa huecos amplios entre columnas.
        for (var line:lines(runs)) {
            // Los huecos amplios separan bloques para no unir columnas independientes.
            List<List<TextRun>> fragments=new ArrayList<>();List<TextRun> fragment=new ArrayList<>();
            for (var run:line) {
                if (!fragment.isEmpty() && run.bounds().x()-fragment.get(fragment.size()-1).bounds().right()>Math.max(24,run.fontSize()*3)) {
                    fragments.add(fragment);fragment=new ArrayList<>();
                }
                fragment.add(run);
            }
            fragments.add(fragment);
            for (var part:fragments) {
                Bounds box=union(part);List<TextRun> target=null;
                for (int i=groups.size()-1;i>=0;i--) {
                    var group=groups.get(i);var previous=union(group);
                    if (box.y()-previous.bottom()>30) break;
                    double font=group.get(0).fontSize();
                    // Se exige proximidad vertical, alineación y tamaño parecido para no fusionar títulos o columnas con el cuerpo.
                    if (box.y()-previous.bottom()>=0 && box.y()-previous.bottom()<Math.max(5,font*.9)
                            && Math.abs(box.x()-previous.x())<Math.max(8,font)
                            && Math.abs(part.get(0).fontSize()-font)<font*.25) { target=group;break; }
                }
                if (target==null) { target=new ArrayList<>();groups.add(target); }
                target.addAll(part);
            }
        }
        List<Element> result=new ArrayList<>();
        for (var group:groups) {
            boolean ocr=group.stream().anyMatch(r->r.origin().equals("OCR"));
            result.add(new Element("p"+page+"-paragraph-"+result.size(),"PARAGRAPH",page,union(group),
                    ocr?group.stream().mapToDouble(TextRun::confidence).average().orElse(.5):.9,true,
                    join(group).replace('\n',' '),List.copyOf(group),null,null,null,List.of()));
        }
        return result;
    }
}
