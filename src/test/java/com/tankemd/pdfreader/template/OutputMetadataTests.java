package com.tankemd.pdfreader.template;

import static org.assertj.core.api.Assertions.*;
import static com.tankemd.pdfreader.template.Template.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.math.BigDecimal;
import java.util.List;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Verifica clasificación del papel, conversión completa de unidades y procedencia tipográfica. */
class OutputMetadataTests {
    /** Contrasta formatos métricos, tamaños norteamericanos, orientación y rechazo del tamaño personalizado. */
    @Test void identifiesIsoAndUsPaperBeforeRoundingRegardlessOfOrientation() {
        assertThat(new SourcePage(1,595.32,841.92,"OCR").paper()).isEqualTo("A4");
        var landscape=new SourcePage(1,841.92,595.32,"OCR");
        assertThat(landscape.paper()).isEqualTo("A4");assertThat(landscape.orientation()).isEqualTo("horizontal");
        assertThat(PaperFormats.identify(297*72/25.4,420*72/25.4)).isEqualTo("A3");
        assertThat(PaperFormats.identify(612,792)).isEqualTo("Letter");
        assertThat(PaperFormats.identify(612,1008)).isEqualTo("Legal");
        assertThat(PaperFormats.identify(792,1224)).isEqualTo("Tabloid");
        assertThat(PaperFormats.identify(555,802)).isEqualTo("custom");
        assertThat(PaperFormats.identify(590,840)).isEqualTo("custom");
    }

    /** Audita geometría y estilo en todas las colecciones sin modificar el modelo original en puntos. */
    @Test void convertsAllNestedLengthsButNotPixelsCountsOrConfidence() {
        Bounds box=new Bounds(72,-36,144,216);
        var run=new TextRun("Text",box,"ABCDEF+Arial-BoldItalicMT",12,"#000000","NATIVE",.987654321);
        var paint=new Paint("#000000",null,1,List.of(72d,36d),0,0,.12345678,.9,"NON_ZERO");
        var shape=new Shape("PATH",List.of(new Command("M",List.of(72d,-36d))),paint);
        var table=new Table(1,1,List.of(144d),List.of(216d),List.of(new Cell(0,0,box,"Text",List.of(run),List.of())),List.of(),"GRID");
        var element=new Element("e","TABLE",1,box,.987654321,true,null,List.of(run),null,shape,table,List.of(),new ImageAnalysis(90,"Text",List.of(run)));
        var master=new PageMaster("master","ODD",612,792,new Margins(54,54,54,54),true,List.of(element),List.of(),List.of());
        var input=new Template("1.2","pt",List.of(new SourcePage(1,612,792,"NATIVE")),List.of(master),List.of(element),List.of(element),
                List.of(new Asset("a","image/png","",72,144)),List.of(),new Analysis("LOCAL",null),List.of());
        var json=TemplateOutput.inches(input);
        assertThat(json.path("units").asText()).isEqualTo("inches");
        assertThat(json.path("sourcePages").get(0).path("width").doubleValue()).isEqualTo(8.5);
        assertThat(json.path("masters").get(0).path("margins").path("top").doubleValue()).isEqualTo(.75);
        assertThat(json.path("objects").get(0).path("bounds").path("y").doubleValue()).isEqualTo(-.5);
        assertThat(json.path("flow").get(0).path("runs").get(0).path("fontSize").doubleValue()).isEqualTo(.1667);
        assertThat(json.path("flow").get(0).path("table").path("columnWidths").get(0).doubleValue()).isEqualTo(2);
        assertThat(json.path("flow").get(0).path("shape").path("path").get(0).path("coordinates").get(0).doubleValue()).isEqualTo(1);
        assertThat(json.path("flow").get(0).path("shape").path("paint").path("dash").get(1).doubleValue()).isEqualTo(.5);
        assertThat(json.path("flow").get(0).path("shape").path("paint").path("strokeOpacity").doubleValue()).isEqualTo(.1235);
        assertThat(json.path("assets").get(0).path("pixelWidth").intValue()).isEqualTo(72);
        assertThat(input.flow().get(0).bounds().width()).isEqualTo(144);
        checkPrecision(json);
    }

    /** Distingue recursos nativos de hipótesis OCR y verifica que un giro no pierda esa evidencia. */
    @Test void normalizesNativeFontButNeverPretendsOcrIsAnIdentifiedFont() {
        var nativeRun=new TextRun("T",new Bounds(0,0,10,10),"VSZKRH+Arial-BoldItalicMT",12,"#000000","NATIVE",1);
        assertThat(nativeRun.font()).isEqualTo("Arial");assertThat(nativeRun.fontOriginal()).isEqualTo("VSZKRH+Arial-BoldItalicMT");
        assertThat(nativeRun.fontWeight()).isEqualTo("bold");assertThat(nativeRun.fontStyle()).isEqualTo("italic");
        assertThat(nativeRun.fontEstimated()).isFalse();
        var ocr=new TextRun("T",nativeRun.bounds(),null,10,null,"OCR",.9);
        assertThat(ocr.font()).isEqualTo("unknown");assertThat(ocr.fontEstimated()).isTrue();assertThat(ocr.fontOriginal()).isNull();
        assertThat(ocr.withBounds(new Bounds(1,2,3,4)).fontEstimationMethod()).isEqualTo("UNRESOLVED");
    }

    /** Compara contra un subconjunto verdaderamente incrustado en un PDF serializado y vuelto a cargar. */
    @Test void catalogFindsEmbeddedResourceAndVisualEstimationUsesItExclusively() throws Exception {
        try(var document=new PDDocument()) {
            var font=PDType0Font.load(document,new File("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"));
            var page=new PDPage(new PDRectangle(1000,100));document.addPage(page);
            try(var out=new PDPageContentStream(document,page)) {
                out.beginText();out.setFont(font,36);out.newLineAtOffset(10,30);out.showText("Typography");out.endText();
            }
            // El PDF de entrada debe estar serializado: PDFBox materializa el subconjunto incrustado al guardar.
            var bytes=new java.io.ByteArrayOutputStream();document.save(bytes);
            try(var loaded=org.apache.pdfbox.Loader.loadPDF(bytes.toByteArray())) {
                var catalog=FontCatalog.from(loaded);
                assertThat(catalog.infos()).hasSize(1);assertThat(catalog.infos().get(0).embedded()).isTrue();
                assertThat(catalog.candidates()).allMatch(c->c.source().equals("embedded"));
                var image=new PDFRenderer(loaded).renderImageWithDPI(0,72);
                var run=new TextRun("Typography",new Bounds(0,0,1000,100),null,36,null,"OCR",1);
                var estimated=FontEstimator.estimate(List.of(run),image,1000,100,catalog).get(0);
                assertThat(estimated.font()).isNotEqualTo("unknown");assertThat(estimated.fontEstimated()).isTrue();
                assertThat(estimated.fontEstimationMethod()).isEqualTo("EMBEDDED_FONT_VISUAL_MATCH");
                assertThat(estimated.fontConfidence()).isGreaterThan(.95);
            }
        }
    }

    /** Una silueta sin letras no debe convertirse en una identificación aunque existan fuentes instaladas. */
    @Test void aPdfWithoutFontsDoesNotInventEmbeddedResourcesAndBadVisualMatchStaysUnknown() throws Exception {
        try(var document=new PDDocument()) {
            document.addPage(new PDPage());var catalog=FontCatalog.from(document);assertThat(catalog.infos()).isEmpty();
            assertThat(catalog.candidates()).allMatch(c->c.source().equals("installed"));
            var image=new BufferedImage(100,100,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
            g.setColor(Color.WHITE);g.fillRect(0,0,100,100);g.setColor(Color.BLACK);g.fillRect(25,25,50,50);g.dispose();
            var run=new TextRun("Typography",new Bounds(0,0,100,100),null,36,null,"OCR",1);
            var result=FontEstimator.estimate(List.of(run),image,100,100,catalog).get(0);
            assertThat(result.font()).isEqualTo("unknown");assertThat(result.fontEstimated()).isTrue();assertThat(result.fontOriginal()).isNull();
        }
    }

    /** Audita todos los números de la salida, incluidas listas anidadas y puntuaciones no geométricas. */
    private static void checkPrecision(JsonNode node) {
        if(node.isNumber()) assertThat(new BigDecimal(node.asText()).stripTrailingZeros().scale()).isLessThanOrEqualTo(4);
        else if(node.isObject()) node.properties().forEach(e->checkPrecision(e.getValue()));
        else if(node.isArray()) node.forEach(OutputMetadataTests::checkPrecision);
    }
}
