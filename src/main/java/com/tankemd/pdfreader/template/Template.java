package com.tankemd.pdfreader.template;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Modelo interno en puntos desde el CropBox visible. TemplateOutput lo exporta a pulgadas con precisión acotada. */
public record Template(String schemaVersion, String units, List<SourcePage> sourcePages,
        List<PageMaster> masters, List<Element> flow, List<Element> objects,
        List<Asset> assets, List<Notice> notices, Analysis analysis, List<FontInfo> fonts) {
    /** Recursos tipográficos reales del PDF; no incluye fuentes instaladas usadas únicamente como candidatos visuales. */
    public record FontInfo(String originalName,String family,String weight,String style,boolean embedded,List<Integer> sourcePages) {}
    /** Evidencia de comparación visual; la similitud no es una probabilidad de identificación. */
    public record FontMatch(String family,String weight,String style,String source,double similarity) {}
    /** Identifica el motor que produjo la plantilla y la versión Docling solo cuando realmente se utilizó. */
    public record Analysis(String engine, String doclingVersion) {}
    /** Caja geométrica de origen en puntos; no impone posición fija al contenido de salida. */
    public record Bounds(double x, double y, double width, double height) {
        /** Calcula la coordenada derecha de la caja en el sistema de origen superior izquierdo. */
        public double right() { return x + width; }
        /** Calcula la coordenada inferior de la caja en puntos. */
        public double bottom() { return y + height; }
        /** Comprueba si un punto pertenece a la caja, incluidos sus bordes. */
        public boolean contains(double px, double py) {
            return px >= x && px <= right() && py >= y && py <= bottom();
        }
        /** Mide la intersección respecto al área de la caja menor; no es una medida IoU y protege el denominador de áreas nulas. */
        public double overlap(Bounds other) {
            double area = Math.max(0, Math.min(right(), other.right()) - Math.max(x, other.x))
                    * Math.max(0, Math.min(bottom(), other.bottom()) - Math.max(y, other.y));
            return area / Math.max(1, Math.min(width * height, other.width * other.height));
        }
    }
    /** Tamaño y método de extracción de una página de entrada; no representa una página final maquetada. */
    public record SourcePage(int number, double width, double height, String method,String paper,String orientation) {
        /** Clasifica dimensiones originales antes del redondeo y la conversión a pulgadas. */
        public SourcePage(int number,double width,double height,String method) {
            this(number,width,height,method,PaperFormats.identify(width,height),PaperFormats.orientation(width,height));
        }
    }
    /** Márgenes editables de la maestra, expresados en puntos y pendientes de revisión. */
    public record Margins(double top, double right, double bottom, double left) {}
    /** Papel y contenido estático para una paridad; los márgenes y agrupaciones son inferidos. */
    public record PageMaster(String id, String parity, double width, double height, Margins margins,
            boolean reviewRequired, List<Element> header, List<Element> footer, List<Element> background) {}
    /** Fragmento con evidencia tipográfica; el estado nativo se distingue siempre de una hipótesis visual OCR. */
    public record TextRun(String text, Bounds bounds, String font, double fontSize, String color,
            String origin, double confidence,String fontOriginal,String fontWeight,String fontStyle,
            boolean fontEstimated,String fontEstimationMethod,Double fontConfidence,List<FontMatch> fontCandidates) {
        /** Mantiene la evidencia nativa; un OCR sin comparación se marca desconocido, nunca como Arial por defecto. */
        public TextRun(String text,Bounds bounds,String font,double fontSize,String color,String origin,double confidence) {
            this(text,bounds,font==null?"unknown":FontCatalog.family(font),fontSize,color,origin,confidence,font,
                    font==null?"unknown":FontCatalog.weight(font),font==null?"unknown":FontCatalog.style(font),font==null,
                    font==null?"UNRESOLVED":"PDF_TEXT_STATE",font==null?null:1d,List.of());
        }
        /** Cambia solo la caja al deshacer un giro, sin perder la procedencia o confianza tipográfica. */
        public TextRun withBounds(Bounds box) {
            return new TextRun(text,box,font,fontSize,color,origin,confidence,fontOriginal,fontWeight,fontStyle,fontEstimated,fontEstimationMethod,fontConfidence,fontCandidates);
        }
    }
    /** Propiedades gráficas extraídas del estado PDF para trazo y relleno. */
    public record Paint(String stroke, String fill, double lineWidth, List<Double> dash,
            int lineCap, int lineJoin, double strokeOpacity, double fillOpacity, String fillRule) {}
    /** Operador vectorial y coordenadas normalizadas: M, L, C o Z. */
    public record Command(String operator, List<Double> coordinates) {}
    /** Clasificación de una forma junto con el trazado original y sus propiedades de pintado. */
    public record Shape(String kind, List<Command> path, Paint paint) {}
    /** Celda identificada por fila y columna, con spans positivos, texto y referencias a sus fondos. */
    public record Cell(int row, int column, Bounds sourceBounds, String text, List<TextRun> runs,
            List<String> backgroundObjectIds, int rowSpan, int columnSpan) {
        /** Crea una celda sin combinación cuando no se especifican spans; ambos toman el valor 1. */
        public Cell(int row,int column,Bounds sourceBounds,String text,List<TextRun> runs,List<String> backgroundObjectIds) {
            this(row,column,sourceBounds,text,runs,backgroundObjectIds,1,1);
        }
    }
    /** Topología de celdas y medidas de referencia. Una posición ausente no implica una celda vacía con bordes. */
    public record Table(int rows, int columns, List<Double> columnWidths, List<Double> rowHeights, List<Cell> cells,
            List<String> borderObjectIds, String detection) {}
    /** OCR de consulta de una imagen; el giro describe la lectura, no una orden de rotación al renderizar. */
    public record ImageAnalysis(int rotationClockwise, String text, List<TextRun> runs) {}
    /** Bloque del modelo con evidencia de origen; los campos opcionales dependen de su tipo. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Element(String id, String type, int sourcePage, Bounds bounds, double confidence,
            boolean reviewRequired, String text, List<TextRun> runs, String assetId,
            Shape shape, Table table, List<String> sourceObjectIds, ImageAnalysis imageAnalysis) {
        /** Construye un elemento sin anotación OCR de imagen, manteniendo la misma representación de los demás tipos. */
        public Element(String id, String type, int sourcePage, Bounds bounds, double confidence,
                boolean reviewRequired, String text, List<TextRun> runs, String assetId,
                Shape shape, Table table, List<String> sourceObjectIds) {
            this(id,type,sourcePage,bounds,confidence,reviewRequired,text,runs,assetId,shape,table,sourceObjectIds,null);
        }
    }
    /** Imagen serializada en Base64 con tipo MIME y dimensiones en píxeles. */
    public record Asset(String id, String mediaType, String base64, int pixelWidth, int pixelHeight) {}
    /** Aviso de revisión o limitación; una página null indica que afecta al documento completo. */
    public record Notice(String code, Integer sourcePage, String message) {}
}
