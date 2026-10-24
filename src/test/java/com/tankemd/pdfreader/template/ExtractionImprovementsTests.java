package com.tankemd.pdfreader.template;

import static org.assertj.core.api.Assertions.*;
import static com.tankemd.pdfreader.template.Template.*;

import java.awt.Color;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Regresiones de OCR, rotaciones, recortes y bordes parciales con casos sintéticos reproducibles. */
@SpringBootTest
class ExtractionImprovementsTests {
    @Autowired TemplateService service;
    @Autowired OcrReader ocr;

    /** Verifica OCR automático sobre letras convertidas en curvas y un pie aislado, sin imágenes incrustadas. */
    @Test void autoOcrReadsOutlinedTextAndSparseFooterWithoutAnyImage() throws Exception {
        try(var doc=new PDDocument()) {
            var first=new PDPage(PDRectangle.LETTER);doc.addPage(first);
            try(var out=new PDPageContentStream(doc,first)) { outline(out,54,700,"TEXTO CONVERTIDO EN TRAZADOS",18); }
            var second=new PDPage(PDRectangle.LETTER);doc.addPage(second);
            try(var out=new PDPageContentStream(doc,second)) { outline(out,530,18,"2/2",12); }
            var result=service.analyze(save(doc),TemplateService.OcrMode.AUTO);
            assertThat(result.sourcePages()).allMatch(p->p.method().equals("OCR"));
            assertThat(result.assets()).isEmpty();
            assertThat(result.flow()).anyMatch(e->e.sourcePage()==1 && e.text()!=null && e.text().contains("TRAZADOS"));
            assertThat(result.flow()).anyMatch(e->e.sourcePage()==2 && e.text()!=null && e.text().contains("2/2"));
            assertThat(result.flow().stream().filter(e->e.type().equals("SHAPE")).count()).isLessThan(result.objects().stream().filter(e->e.type().equals("SHAPE")).count());
            assertThat(result.flow()).filteredOn(e->e.type().equals("PARAGRAPH")).anyMatch(e->!e.sourceObjectIds().isEmpty());
        }
    }

    /** Comprueba detección por bordes parciales y conservación de descripciones multilínea dentro de su celda. */
    @Test void detectsPartialBorderTableAndRetainsWrappedDescriptionInOneCell() throws Exception {
        try(var doc=new PDDocument()) {
            var page=new PDPage(PDRectangle.LETTER);doc.addPage(page);
            try(var out=new PDPageContentStream(doc,page)) {
                out.addRect(54,500,480,120);out.stroke();
                out.setNonStrokingColor(Color.LIGHT_GRAY);out.addRect(54,584,480,1);out.fill();out.setNonStrokingColor(Color.BLACK);
                TemplateServiceTests.text(out,64,600,"Descripcion articulo");TemplateServiceTests.text(out,464,600,"Cantidad");
                TemplateServiceTests.text(out,64,565,"Producto con descripcion");TemplateServiceTests.text(out,464,565,"1");
                TemplateServiceTests.text(out,64,550,"que continua en otra linea");
                out.addRect(54,300,480,80);out.stroke();
                TemplateServiceTests.text(out,64,355,"Un recuadro sin columnas");TemplateServiceTests.text(out,64,335,"sigue siendo un recuadro");
            }
            var result=service.analyze(save(doc),TemplateService.OcrMode.OFF);
            var tables=result.flow().stream().filter(e->e.type().equals("TABLE")).toList();assertThat(tables).hasSize(1);
            var table=tables.get(0).table();assertThat(table.detection()).isEqualTo("PARTIAL_BORDERS");
            assertThat(table.rows()).isEqualTo(2);assertThat(table.columns()).isEqualTo(2);
            assertThat(table.cells().get(2).text()).contains("Producto con descripcion","que continua en otra linea");
            assertThat(table.cells().get(3).text()).isEqualTo("1");
            assertThat(result.flow()).anyMatch(e->e.shape()!=null && e.bounds().y()>400);
        }
    }

    /** Comprueba que un recorte descarta formas invisibles y que restaurar el estado gráfico recupera la visibilidad. */
    @Test void honorsClipAndRestoresGraphicsStateInsteadOfExportingInvisibleShapes() throws Exception {
        try(var doc=new PDDocument()) {
            var page=new PDPage(PDRectangle.LETTER);doc.addPage(page);
            try(var out=new PDPageContentStream(doc,page)) {
                out.saveGraphicsState();out.addRect(0,0,10,10);out.clip();
                out.addRect(100,100,50,50);out.fill();out.restoreGraphicsState();
                out.addRect(200,200,50,50);out.fill();
            }
            var result=service.analyze(save(doc),TemplateService.OcrMode.OFF);
            assertThat(result.objects()).hasSize(1);assertThat(result.objects().get(0).bounds().x()).isEqualTo(200);
        }
    }

    /** Verifica lectura orientada de una etiqueta sin duplicar sus palabras como párrafos del cuerpo. */
    @Test void rotatedImageIsOneFlowBlockWithUprightOcrAnnotation() throws Exception {
        var upright=new BufferedImage(640,220,BufferedImage.TYPE_INT_RGB);var g=upright.createGraphics();
        g.setColor(Color.WHITE);g.fillRect(0,0,640,220);g.setColor(Color.BLACK);g.setFont(new Font("SansSerif",Font.BOLD,30));
        g.drawString("ETIQUETA DE ENVIO",25,70);g.drawString("PRODUCTO PARA DEVOLUCION",25,130);g.dispose();
        // Se gira la imagen de prueba, no la página PDF, para verificar OCR de una región orientada.
        var sideways=new BufferedImage(220,640,BufferedImage.TYPE_INT_RGB);g=sideways.createGraphics();
        g.translate(220,0);g.rotate(Math.PI/2);g.drawImage(upright,0,0,null);g.dispose();
        try(var doc=new PDDocument()) {
            var page=new PDPage(PDRectangle.LETTER);doc.addPage(page);
            try(var out=new PDPageContentStream(doc,page)) {
                out.drawImage(LosslessFactory.createFromImage(doc,sideways),54,200,110,320);
                TemplateServiceTests.text(out,54,700,"Parrafo fuera de la etiqueta");
            }
            var result=service.analyze(save(doc),TemplateService.OcrMode.AUTO);
            var images=result.flow().stream().filter(e->e.type().equals("IMAGE")).toList();assertThat(images).hasSize(1);
            var analysis=images.get(0).imageAnalysis();assertThat(analysis).isNotNull();
            assertThat(analysis.rotationClockwise()).isEqualTo(270);
            assertThat(analysis.text()).contains("ETIQUETA DE ENVIO","PRODUCTO PARA DEVOLUCION");
            assertThat(result.flow()).noneMatch(e->e.type().equals("PARAGRAPH") && e.text().contains("ETIQUETA"));
            assertThat(analysis.runs()).allSatisfy(r->{
                assertThat(r.bounds().x()).isBetween(53d,165d);assertThat(r.bounds().y()).isBetween(271d,593d);
            });
        }
    }

    /** Contrasta las transformaciones inversas de cajas para los giros de 90, 180 y 270 grados. */
    @Test void orientationMappingKeepsAllFourCornersInSourceCoordinates() {
        var source=new Bounds(10,20,100,200);
        assertThat(OcrReader.restore(new Bounds(5,7,20,30),source,90)).isEqualTo(new Bounds(17,195,30,20));
        assertThat(OcrReader.restore(new Bounds(5,7,20,30),source,180)).isEqualTo(new Bounds(85,183,20,30));
        assertThat(OcrReader.restore(new Bounds(5,7,20,30),source,270)).isEqualTo(new Bounds(73,25,30,20));
    }

    /** Comprueba que un plazo agotado se comunica como error, sin devolver un resultado OCR parcial. */
    @Test void expiredPageDeadlineNeverReturnsPartialSuccess() throws Exception {
        var image=new BufferedImage(20,20,BufferedImage.TYPE_INT_RGB);
        assertThatThrownBy(()->ocr.read(image,20,20,System.nanoTime()-1)).isInstanceOf(PdfProblem.class).hasMessageContaining("45 segundos");
    }

    /** Convierte glifos de una fuente de prueba a trazados PDF para reproducir texto sin objetos textuales nativos. */
    private static void outline(PDPageContentStream out,float x,float y,String value,int size) throws IOException {
        // Los glifos se exportan uno a uno como geometría; el PDF resultante no contiene operadores de texto.
        var vector=new Font("SansSerif",Font.PLAIN,size).createGlyphVector(new FontRenderContext(new AffineTransform(),true,true),value);
        for(int glyph=0;glyph<vector.getNumGlyphs();glyph++) {
            var iterator=vector.getGlyphOutline(glyph).getPathIterator(null);double[] p=new double[6];double px=0,py=0,sx=0,sy=0;
            while(!iterator.isDone()) {
                switch(iterator.currentSegment(p)) {
                    case PathIterator.SEG_MOVETO -> { out.moveTo(x+(float)p[0],y-(float)p[1]);px=p[0];py=p[1];sx=px;sy=py; }
                    case PathIterator.SEG_LINETO -> { out.lineTo(x+(float)p[0],y-(float)p[1]);px=p[0];py=p[1]; }
                    // El glifo usa curvas cuadráticas; se convierten a controles cúbicos equivalentes porque PDF utiliza Bézier cúbicas.
                    case PathIterator.SEG_QUADTO -> {
                        out.curveTo(x+(float)(px+2*(p[0]-px)/3),y-(float)(py+2*(p[1]-py)/3),
                                x+(float)(p[2]+2*(p[0]-p[2])/3),y-(float)(p[3]+2*(p[1]-p[3])/3),x+(float)p[2],y-(float)p[3]);px=p[2];py=p[3];
                    }
                    case PathIterator.SEG_CUBICTO -> { out.curveTo(x+(float)p[0],y-(float)p[1],x+(float)p[2],y-(float)p[3],x+(float)p[4],y-(float)p[5]);px=p[4];py=p[5]; }
                    case PathIterator.SEG_CLOSE -> { out.closePath();px=sx;py=sy; }
                    default -> throw new IllegalStateException();
                }
                iterator.next();
            }
            out.fill();
        }
    }
    /** Serializa el PDF sintético a bytes para analizarlo en memoria. */
    private static byte[] save(PDDocument doc) throws IOException { var out=new ByteArrayOutputStream();doc.save(out);return out.toByteArray(); }
}
