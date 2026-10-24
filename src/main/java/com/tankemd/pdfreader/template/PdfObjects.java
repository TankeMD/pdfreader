package com.tankemd.pdfreader.template;

import static com.tankemd.pdfreader.template.Template.*;

import java.awt.geom.Point2D;
import java.awt.geom.Path2D;
import java.awt.geom.Area;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/** Extrae geometría visible y texto nativo conservando su evidencia separada del flujo semántico. */
final class PdfObjects extends PDFGraphicsStreamEngine {
    final List<Element> elements = new ArrayList<>();
    final List<Asset> assets = new ArrayList<>();
    private final int pageNumber;
    private final List<Command> path = new ArrayList<>();
    private final PDPage page;
    private Point2D current = new Point2D.Float();
    private Point2D start = new Point2D.Float();
    private final Path2D.Float userPath = new Path2D.Float();
    private int pendingClip = -1;
    private boolean rectangle;
    private int sequence;
    boolean clipping;

    /** Inicializa el motor gráfico para una página y el prefijo de sus identificadores de origen. */
    PdfObjects(PDPage page, int pageNumber) { super(page); this.page = page; this.pageNumber = pageNumber; }

    /** Convierte el color PDF a RGB hexadecimal; esta representación no conserva el espacio de color original. */
    static String color(PDColor color) throws IOException {
        return String.format("#%06X", color.toRGB() & 0xffffff);
    }

    /** Normaliza coordenadas PDF al CropBox visible, con origen superior izquierdo y rotación de página aplicada. */
    Point2D display(double x, double y) {
        // Se descuenta el desplazamiento del CropBox antes de aplicar el giro y cambiar al origen superior izquierdo.
        var crop = page.getCropBox();
        x -= crop.getLowerLeftX(); y -= crop.getLowerLeftY();
        return switch (Math.floorMod(page.getRotation(), 360)) {
            case 90 -> new Point2D.Double(y, x);
            case 180 -> new Point2D.Double(crop.getWidth() - x, y);
            case 270 -> new Point2D.Double(crop.getHeight() - y, crop.getWidth() - x);
            default -> new Point2D.Double(x, crop.getHeight() - y);
        };
    }

    /** Conserva un comando vectorial con sus coordenadas normalizadas para inspección y reconstrucción posterior. */
    private void command(String op, double... coordinates) {
        List<Double> points = new ArrayList<>();
        for (int i = 0; i < coordinates.length; i += 2) {
            var p = display(coordinates[i], coordinates[i + 1]);
            points.add(p.getX()); points.add(p.getY());
        }
        path.add(new Command(op, points));
    }
    /** Traduce el operador de rectángulo a un contorno cerrado, conservando la clasificación cuando inicia el trazado. */
    @Override public void appendRectangle(Point2D a, Point2D b, Point2D c, Point2D d) {
        rectangle = path.isEmpty();
        moveTo((float)a.getX(), (float)a.getY()); lineTo((float)b.getX(), (float)b.getY());
        lineTo((float)c.getX(), (float)c.getY()); lineTo((float)d.getX(), (float)d.getY()); closePath();
    }
    /** Inicia un subtrazado y registra su punto inicial tanto para los recortes como para el modelo exportado. */
    /** Añade un segmento recto y actualiza el punto actual en las dos representaciones del trazado. */
    /** Añade una curva Bézier cúbica con dos controles y un destino, sin sustituirla por una forma aproximada. */
    @Override public void moveTo(float x, float y) { current = new Point2D.Float(x, y); start=current; userPath.moveTo(x,y); command("M", x, y); }
    @Override public void lineTo(float x, float y) { current = new Point2D.Float(x, y); userPath.lineTo(x,y); command("L", x, y); }
    @Override public void curveTo(float a, float b, float c, float d, float e, float f) {
        current = new Point2D.Float(e, f); userPath.curveTo(a,b,c,d,e,f); command("C", a,b,c,d,e,f);
    }
    /** Devuelve a PDFBox el punto actual del subtrazado en coordenadas de usuario PDF. */
    /** Cierra el subtrazado y devuelve el punto actual a su inicio para interpretar correctamente los operadores siguientes. */
    /** Aplica el recorte pendiente al estado gráfico antes de vaciar el trazado; su restauración queda a cargo del estado PDF. */
    @Override public Point2D getCurrentPoint() { return current; }
    @Override public void closePath() { userPath.closePath(); current=start; path.add(new Command("Z", List.of())); }
    @Override public void endPath() {
        // El recorte se aplica al terminar el trazado; guardarlo en PDGraphicsState permite respetar operadores de guardar/restaurar estado.
        if (pendingClip>=0) {
            userPath.setWindingRule(pendingClip);
            getGraphicsState().intersectClippingPath(new Area(userPath));
            pendingClip=-1;
        }
        path.clear(); userPath.reset(); rectangle = false;
    }
    /** Difiere la aplicación del recorte hasta el final del trazado, conforme al orden de los operadores PDF. */
    /** Señala un sombreado para revisión; este extractor no lo reconstruye como un objeto semántico independiente. */
    /** Exporta el trazado actual como contorno sin relleno. */
    /** Exporta el relleno del trazado respetando la regla de recorrido indicada por el PDF. */
    /** Exporta el contorno y el relleno en una misma forma para conservar su relación. */
    @Override public void clip(int windingRule) { clipping = true; pendingClip=windingRule; }
    @Override public void shadingFill(COSName name) { clipping = true; }
    @Override public void strokePath() throws IOException { paint(true, false, 0); }
    @Override public void fillPath(int rule) throws IOException { paint(false, true, rule); }
    @Override public void fillAndStrokePath(int rule) throws IOException { paint(true, true, rule); }

    /** Descarta trazados totalmente recortados y conserva geometría, colores y propiedades de pintado de los restantes. */
    private void paint(boolean stroke, boolean fill, int rule) throws IOException {
        if (path.isEmpty()) return;
        userPath.setWindingRule(rule);
        // La visibilidad se calcula con el contorno pintado, incluido su grosor, antes de intersectarlo con el recorte vigente.
        var visible=new Area();
        if (fill) visible.add(new Area(userPath));
        if (stroke) {
            float scale=getGraphicsState().getCurrentTransformationMatrix().getScalingFactorX();
            visible.add(new Area(new java.awt.BasicStroke(Math.max(.1f,getGraphicsState().getLineWidth()*scale)).createStrokedShape(userPath)));
        }
        visible.intersect(getGraphicsState().getCurrentClippingPath());
        if (visible.isEmpty()) { endPath();return; }
        // Las cajas de curvas se obtienen de extremos y controles: son envolventes conservadoras, no límites exactos de Bézier.
        List<Double> xs = new ArrayList<>(), ys = new ArrayList<>();
        for (var c : path) for (int i = 0; i < c.coordinates().size(); i += 2) {
            xs.add(c.coordinates().get(i)); ys.add(c.coordinates().get(i+1));
        }
        if (!xs.isEmpty()) {
            double x = xs.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            double y = ys.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            var box = new Bounds(x,y, xs.stream().mapToDouble(Double::doubleValue).max().orElse(x)-x,
                    ys.stream().mapToDouble(Double::doubleValue).max().orElse(y)-y);
            var state = getGraphicsState();
            List<Double> dash = new ArrayList<>();
            for (float v : state.getLineDashPattern().getDashArray()) dash.add((double)v);
            // Se conserva el estado de trazo/relleno. Los estilos bajo transformaciones complejas pueden requerir revisión adicional.
            var style = new Paint(stroke ? color(state.getStrokingColor()) : null,
                    /** Convierte el color PDF a RGB hexadecimal; esta representación no conserva el espacio de color original. */
                    fill ? color(state.getNonStrokingColor()) : null, state.getLineWidth(), dash,
                    state.getLineCap(), state.getLineJoin(), state.getAlphaConstant(),
                    state.getNonStrokeAlphaConstant(), rule == java.awt.geom.Path2D.WIND_EVEN_ODD ? "EVEN_ODD" : "NON_ZERO");
            String kind = rectangle ? "RECTANGLE" : path.size() == 2 ? "LINE" : ellipse(box) ? "ELLIPSE" : "PATH";
            elements.add(new Element(id(), "SHAPE", pageNumber, box, 1, false, null, null, null,
                    new Shape(kind,List.copyOf(path),style),null,List.of()));
        }
        endPath();
    }
    /** Genera un identificador único dentro de la página para enlazar los objetos con bloques semánticos. */
    private String id() { return "p" + pageNumber + "-object-" + (++sequence); }

    /** Reconoce aproximadamente una elipse formada por cuatro curvas alineadas con los ejes; conserva siempre el trazado exacto. */
    private boolean ellipse(Bounds box) {
        if (box.width()<1 || box.height()<1 || path.stream().filter(c->c.operator().equals("C")).count()!=4
                || path.stream().anyMatch(c->c.operator().equals("L"))) return false;
        // Reconoce cuatro cuadrantes cúbicos alineados con los ejes sin perder el trazado exacto.
        double rx=box.width()/2, ry=box.height()/2, cx=box.x()+rx,cy=box.y()+ry;
        double tolerance=Math.max(.1,Math.min(rx,ry)*.01);
        // Se comprueban los extremos de los cuatro cuadrantes; la clasificación ELLIPSE sigue siendo aproximada.
        Set<Integer> quadrants=new java.util.HashSet<>();
        for (var c:path) if (c.operator().equals("C")) {
            double x=c.coordinates().get(4), y=c.coordinates().get(5);
            if (Math.abs(x-cx)<tolerance && Math.abs(y-(cy-ry))<tolerance) quadrants.add(0);
            else if (Math.abs(x-(cx+rx))<tolerance && Math.abs(y-cy)<tolerance) quadrants.add(1);
            else if (Math.abs(x-cx)<tolerance && Math.abs(y-(cy+ry))<tolerance) quadrants.add(2);
            else if (Math.abs(x-(cx-rx))<tolerance && Math.abs(y-cy)<tolerance) quadrants.add(3);
        }
        return quadrants.size()==4;
    }

    /** Comprueba visibilidad y tamaño de la imagen, extrae sus píxeles a PNG y registra su caja transformada. */
    @Override public void drawImage(PDImage image) throws IOException {
        var matrix = getGraphicsState().getCurrentTransformationMatrix();
        // La matriz transforma el cuadrado unidad de la imagen; primero se comprueba que exista área visible tras el recorte.
        var imagePath=new Path2D.Float();
        for (int i=0;i<4;i++) {
            int[][] points={{0,0},{1,0},{1,1},{0,1}};
            var p=matrix.transformPoint(points[i][0],points[i][1]);
            if(i==0) imagePath.moveTo(p.x,p.y);else imagePath.lineTo(p.x,p.y);
        }
        /** Cierra el subtrazado y devuelve el punto actual a su inicio para interpretar correctamente los operadores siguientes. */
        imagePath.closePath();var visible=new Area(imagePath);visible.intersect(getGraphicsState().getCurrentClippingPath());
        if(visible.isEmpty()) return;
        List<Point2D> corners = new ArrayList<>();
        for (int[] c : new int[][]{{0,0},{0,1},{1,0},{1,1}}) {
            var p = matrix.transformPoint(c[0],c[1]); corners.add(display(p.x,p.y));
        }
        double x = corners.stream().mapToDouble(Point2D::getX).min().orElse(0);
        double y = corners.stream().mapToDouble(Point2D::getY).min().orElse(0);
        var bounds = new Bounds(x,y,corners.stream().mapToDouble(Point2D::getX).max().orElse(x)-x,
                corners.stream().mapToDouble(Point2D::getY).max().orElse(y)-y);
        // El límite se evalúa antes de decodificar los píxeles para evitar imágenes desproporcionadas.
        if ((long)image.getWidth()*image.getHeight() > 40_000_000L) throw new PdfProblem("IMAGE_TOO_LARGE", "Una imagen supera los 40 megapíxeles.");
        var bitmap = image.getImage();
        // La extracción PNG conserva el recurso; las regiones compuestas pueden añadir después otro recurso con su apariencia en página.
        var out = new ByteArrayOutputStream(); ImageIO.write(bitmap,"png",out);
        String id = id();
        assets.add(new Asset(id,"image/png",Base64.getEncoder().encodeToString(out.toByteArray()),bitmap.getWidth(),bitmap.getHeight()));
        elements.add(new Element(id,"IMAGE",pageNumber,bounds,1,false,null,null,id,null,null,List.of()));
    }

    /** Extractor de caracteres nativos de PDFBox, con su estilo y posición visual. */
    static final class Text extends PDFTextStripper {
        final List<TextRun> runs = new ArrayList<>();
        /** Activa el ordenamiento posicional del extractor de texto nativo de PDFBox. */
        Text(PdfObjects geometry) throws IOException { setSortByPosition(true); }
        /** Registra cada fragmento nativo con fuente, tamaño, color y caja visual; los espacios se reconstruyen por geometría. */
            /** Registra cada fragmento nativo con fuente, tamaño, color y caja visual; los espacios se reconstruyen por geometría. */
        @Override protected void processTextPosition(TextPosition text) {
            super.processTextPosition(text);
            if (text.getUnicode() == null || text.getUnicode().isBlank()) return;
            // Las coordenadas de TextPosition ya siguen la rotación visual de la página.
            runs.add(new TextRun(text.getUnicode(),new Bounds(text.getX(),text.getY()-text.getHeight(),
                    Math.max(0,text.getWidth()),Math.max(1,text.getHeight())),
                    text.getFont().getName(),text.getFontSizeInPt(),safeColor(),"NATIVE",1));
        }
        /** Recupera el color de texto o devuelve null si no puede interpretarse, sin inventar un valor. */
        private String safeColor() {
            try { return color(getGraphicsState().getNonStrokingColor()); }
            catch (IOException | RuntimeException e) { return null; }
        }
    }
}
