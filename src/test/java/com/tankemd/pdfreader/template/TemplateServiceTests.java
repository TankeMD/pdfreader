package com.tankemd.pdfreader.template;

import static org.assertj.core.api.Assertions.*;
import static com.tankemd.pdfreader.template.Template.*;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Pruebas funcionales con documentos sintéticos y OCR real; no requieren datos de usuarios. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class TemplateServiceTests {
    @Autowired TemplateService service;
    @LocalServerPort int port;

    /** Verifica que una cuadrícula produce celdas con texto/fondo y un rectángulo independiente sigue siendo una forma. */
    @Test void separatesGridFromRectangleAndAssignsTextAndFillToCells() throws Exception {
        var result=service.analyze(digital(),TemplateService.OcrMode.OFF);
        var tables=result.flow().stream().filter(e->e.type().equals("TABLE")).toList();
        assertThat(tables).hasSize(1);
        var table=tables.get(0).table();
        assertThat(table.rows()).isEqualTo(2);assertThat(table.columns()).isEqualTo(2);
        assertThat(table.cells()).extracting(Cell::text).containsExactly("Nombre","Precio","Libro","10");
        assertThat(table.cells().get(0).backgroundObjectIds()).isNotEmpty();
        assertThat(result.flow()).anyMatch(e->e.shape()!=null && e.shape().kind().equals("RECTANGLE"));
        assertThat(result.flow()).filteredOn(e->e.type().equals("PARAGRAPH")).extracting(Element::text)
                .contains("Primera linea del parrafo segunda linea del parrafo").doesNotContain("Libro","Nombre");
        assertThat(result.flow()).noneMatch(e->tables.get(0).sourceObjectIds().contains(e.id()));
        assertThat(result.objects()).anyMatch(e->e.shape()!=null && "#FFFF00".equals(e.shape().paint().fill()));
    }

    /** Comprueba maestras pares/impares, campos de paginación y conservación de texto estático y del cuerpo. */
    @Test void createsParityMastersAndDynamicPageNumbersWithoutRemovingBody() throws Exception {
        byte[] bytes;
        try (var doc=new PDDocument()) {
            // Cuatro páginas aportan dos muestras por paridad; se añaden datos variables y un año fijo para evitar falsos campos dinámicos.
            for (int i=1;i<=4;i++) {
                var page=new PDPage(PDRectangle.LETTER);doc.addPage(page);
                try (var out=new PDPageContentStream(doc,page)) {
                    text(out,54,750,i%2==0?"CABECERA PAR":"CABECERA IMPAR");
                    text(out,54,600,"Contenido pagina "+i);text(out,280,30,"Pagina "+i);
                    // El pie numérico comprueba campos dinámicos; el año fijo debe seguir siendo literal.
                    text(out,540,30,Integer.toString(i));
                    text(out,54,30,"2026");
                }
            }
            bytes=save(doc);
        }
        var result=service.analyze(bytes,TemplateService.OcrMode.OFF);
        assertThat(result.masters()).hasSize(2);
        assertThat(result.masters()).allSatisfy(master->{
            assertThat(master.header()).hasSize(1);
            assertThat(master.footer()).anyMatch(e->e.type().equals("PAGE_NUMBER") && e.text().equals("${pageNumber}"));
            assertThat(master.footer()).anyMatch(e->e.type().equals("PARAGRAPH") && e.text().equals("2026"));
        });
        assertThat(result.flow()).noneMatch(e->e.text()!=null && e.text().startsWith("CABECERA"));
        assertThat(result.flow()).filteredOn(e->e.text()!=null && e.text().startsWith("Contenido pagina")).hasSize(4);
    }

    /** Verifica OCR y detección de tabla en un escaneo sin duplicar la imagen de página en el flow. */
    @Test void recognizesScannedTextAndGridWithoutAddingPageImageToFlow() throws Exception {
        var result=service.analyze(scan(false),TemplateService.OcrMode.AUTO);
        assertThat(result.sourcePages().get(0).method()).isEqualTo("OCR");
        assertThat(result.objects()).anyMatch(e->e.type().equals("IMAGE"));
        assertThat(result.assets()).isNotEmpty();
        assertThat(result.flow()).noneMatch(e->e.type().equals("IMAGE"));
        assertThat(allText(result)).containsIgnoringCase("DOCUMENTO ESCANEADO");
        assertThat(result.flow()).anyMatch(e->e.type().equals("TABLE") && e.table().rows()==2 && e.table().columns()==2);
        assertThat(result.notices()).extracting(Notice::code).contains("OCR_ESTIMATED","SCAN_BACKGROUND_REMOVED_FROM_FLOW");
    }

    /** Comprueba la coexistencia de texto nativo y OCR evitando una segunda copia de la cabecera nativa. */
    @Test void combinesNativeAndScannedRegionsWithoutDuplicatingNativeText() throws Exception {
        var result=service.analyze(scan(true),TemplateService.OcrMode.AUTO);
        assertThat(result.sourcePages().get(0).method()).isEqualTo("MIXED");
        assertThat(allText(result)).contains("TEXTO NATIVO").containsIgnoringCase("DOCUMENTO ESCANEADO");
        long nativeRuns=result.flow().stream().filter(e->e.runs()!=null).flatMap(e->e.runs().stream())
                .filter(r->r.origin().equals("OCR") && r.bounds().y()<60).count();
        assertThat(nativeRuns).isZero();
    }

    /** Asegura que desactivar OCR conserva el escaneo y avisa de que su texto no se ha recuperado. */
    @Test void ocrOffPreservesScanAsImageAndReportsMissingText() throws Exception {
        var result=service.analyze(scan(false),TemplateService.OcrMode.OFF);
        assertThat(result.flow()).anyMatch(e->e.type().equals("IMAGE"));
        assertThat(result.notices()).extracting(Notice::code).contains("NO_TEXT","OCR_NOT_RUN");
    }

    /** Comprueba que un ejecutable OCR ausente provoca error y no una conversión aparentemente correcta. */
    @Test void unsupportedOcrCommandIsAnExplicitFailure() throws Exception {
        var broken=new TemplateService(new OcrReader("/nonexistent/tesseract","eng",""));
        assertThatThrownBy(()->broken.analyze(scan(false),TemplateService.OcrMode.AUTO))
                .isInstanceOf(PdfProblem.class).hasMessageContaining("Tesseract");
    }

    /** Verifica que contraseñas y límites de páginas bloquean el análisis antes de producir una plantilla. */
    @Test void rejectsPasswordProtectedAndTooManyPages() throws Exception {
        try (var doc=new PDDocument()) {
            doc.addPage(new PDPage());doc.protect(new StandardProtectionPolicy("owner","secret",new AccessPermission()));
            assertThatThrownBy(()->service.analyze(save(doc),TemplateService.OcrMode.OFF)).isInstanceOf(PdfProblem.class).hasMessageContaining("contraseña");
        }
        try (var doc=new PDDocument()) {
            for (int i=0;i<21;i++) doc.addPage(new PDPage());
            assertThatThrownBy(()->service.analyze(save(doc),TemplateService.OcrMode.OFF)).isInstanceOf(PdfProblem.class).hasMessageContaining("20 páginas");
        }
    }

    /** Comprueba normalización de coordenadas al combinar CropBox desplazado y rotación de página. */
    @Test void croppedAndRotatedPageGeometryStaysInsideDisplayedPage() throws Exception {
        try (var doc=new PDDocument()) {
            var page=new PDPage(new PDRectangle(800,800));page.setCropBox(new PDRectangle(100,100,400,600));page.setRotation(90);doc.addPage(page);
            try (var out=new PDPageContentStream(doc,page)) { text(out,150,650,"ROTATED");out.addRect(150,200,80,40);out.stroke(); }
            var result=service.analyze(save(doc),TemplateService.OcrMode.OFF);
            assertThat(result.sourcePages().get(0).width()).isEqualTo(600);
            assertThat(result.sourcePages().get(0).height()).isEqualTo(400);
            assertThat(result.objects()).allSatisfy(e->{
                assertThat(e.bounds().x()).isBetween(0d,600d);assertThat(e.bounds().y()).isBetween(0d,400d);
            });
        }
    }

    /** Prueba la API real por HTTP, genera muestras reproducibles y comprueba el rechazo de un PDF inválido. */
    @Test void httpEndpointReturnsTemplateJsonAndRejectsMalformedPdf() throws Exception {
        var response=upload(digital());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"schemaVersion\":\"2.0\"","\"units\":\"inches\"","\"flow\"","\"masters\"","\"TABLE\"","\"engine\":\"LOCAL\"");
        // Las muestras se generan en target, nunca en fuentes, y sirven para comprobar manualmente la API con los mismos casos.
        var samples=java.nio.file.Path.of("target","samples");java.nio.file.Files.createDirectories(samples);
        java.nio.file.Files.write(samples.resolve("digital.pdf"),digital());
        java.nio.file.Files.write(samples.resolve("scanned.pdf"),scan(false));
        java.nio.file.Files.writeString(samples.resolve("digital-template.json"),response.body());
        var invalid=upload("not a pdf".getBytes(StandardCharsets.UTF_8));
        assertThat(invalid.statusCode()).isEqualTo(400);assertThat(invalid.body()).contains("INVALID_PDF");
    }

    /** Construye una carga multipart de prueba y consulta el servidor Spring arrancado en un puerto aleatorio. */
    private HttpResponse<String> upload(byte[] pdf) throws Exception {
        // Se construye multipart real con sus delimitadores; un test de servicio directo no probaría el contrato HTTP.
        var body=new ByteArrayOutputStream();
        body.write("--test-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample.pdf\"\r\nContent-Type: application/pdf\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        body.write(pdf);body.write("\r\n--test-boundary--\r\n".getBytes(StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/templates/analyze?ocr=OFF"))
                .header("Content-Type","multipart/form-data; boundary=test-boundary").POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
    }
    /** Genera un PDF sintético con párrafo, tabla coloreada y rectángulo aislado, sin depender de documentos del usuario. */
    static byte[] digital() throws IOException {
        try (var doc=new PDDocument()) {
            var page=new PDPage(PDRectangle.LETTER);doc.addPage(page);
            try (var out=new PDPageContentStream(doc,page)) {
                text(out,54,700,"Primera linea del parrafo");text(out,54,685,"segunda linea del parrafo");
                // El relleno prueba asociación de estilos a celdas; el rectángulo final prueba que no se clasifique cualquier contorno como tabla.
                out.setNonStrokingColor(Color.YELLOW);out.addRect(54,500,150,40);out.fill();
                out.setNonStrokingColor(Color.BLACK);
                for (int x:new int[]{54,204,354}) { out.moveTo(x,460);out.lineTo(x,540); }
                for (int y:new int[]{460,500,540}) { out.moveTo(54,y);out.lineTo(354,y); }out.stroke();
                text(out,64,515,"Nombre");text(out,214,515,"Precio");text(out,64,475,"Libro");text(out,214,475,"10");
                out.addRect(430,450,100,80);out.stroke();
            }
            return save(doc);
        }
    }
    /** Crea un escaneo sintético con cuadrícula y, opcionalmente, una cabecera nativa para probar documentos mixtos. */
    static byte[] scan(boolean mixed) throws IOException {
        // Se dibujan píxeles y luego se incrustan como imagen: el texto no es seleccionable y obliga a ejecutar OCR real.
        var image=new BufferedImage(1224,1584,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        g.setColor(Color.WHITE);g.fillRect(0,0,image.getWidth(),image.getHeight());g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif",Font.PLAIN,32));g.drawString("DOCUMENTO ESCANEADO",108,210);
        g.drawString("Este texto necesita reconocimiento optico.",108,270);
        g.setStroke(new java.awt.BasicStroke(3));
        for (int x:new int[]{108,408,708}) g.drawLine(x,500,x,700);
        for (int y:new int[]{500,600,700}) g.drawLine(108,y,708,y);
        g.drawString("Nombre",128,560);g.drawString("Precio",428,560);
        g.drawString("Libro",128,660);g.drawString("100",428,660);g.dispose();
        try (var doc=new PDDocument()) {
            var page=new PDPage(PDRectangle.LETTER);doc.addPage(page);
            try (var out=new PDPageContentStream(doc,page)) {
                out.drawImage(LosslessFactory.createFromImage(doc,image),0,0,612,792);
                if (mixed) text(out,54,750,"TEXTO NATIVO");
            }
            return save(doc);
        }
    }
    /** Escribe texto nativo Helvetica en una posición PDF para construir las muestras de prueba. */
    static void text(PDPageContentStream out,float x,float y,String text) throws IOException {
        out.beginText();out.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);out.newLineAtOffset(x,y);out.showText(text);out.endText();
    }
    /** Serializa un documento de prueba a memoria; no deja muestras del usuario dentro del código fuente. */
    private static byte[] save(PDDocument doc) throws IOException { var out=new ByteArrayOutputStream();doc.save(out);return out.toByteArray(); }
    /** Reúne el texto de párrafos y celdas para comprobar contenido semántico sin contar imágenes como texto. */
    private static String allText(Template t) {
        return t.flow().stream().map(e->e.text()!=null?e.text():e.table()!=null?e.table().cells().stream().map(Cell::text).reduce("",(a,b)->a+" "+b):"")
                .reduce("",(a,b)->a+" "+b);
    }
}
