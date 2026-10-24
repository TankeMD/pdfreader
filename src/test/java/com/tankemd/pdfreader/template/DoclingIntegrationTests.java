package com.tankemd.pdfreader.template;

import static org.assertj.core.api.Assertions.*;
import static com.tankemd.pdfreader.template.Template.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pruebas de contrato y combinación con respuestas semánticas simuladas; no prueban inferencia neuronal. */
class DoclingIntegrationTests {
    private final List<SourcePage> pages=List.of(new SourcePage(1,600,800,"NATIVE"));

    /** Combina una respuesta semántica sintética y comprueba spans, estilos nativos y eliminación de párrafos consumidos. */
    @Test void preservesMergedCellsSourceStylesAndRemovesDuplicateParagraphs() {
        var runs=List.of(run("Header",60,60),run("Item",60,100),run("10",260,100),run("Total",260,140));
        var text=new Element("text","TEXT",1,new Bounds(50,50,300,120),1,false,"",runs,null,null,null,List.of());
        var paragraph=new Element("paragraph","PARAGRAPH",1,text.bounds(),1,true,"Header Item 10 Total",runs,null,null,null,List.of());
        // La cabecera combina dos columnas y el subtotal no ocupa la primera: ambas situaciones deben conservar su topología.
        var cells=List.of(new DoclingClient.CellResult(0,0,1,2,"Header",new Bounds(50,50,300,40)),
                new DoclingClient.CellResult(1,0,1,1,"Item",new Bounds(50,90,200,40)),
                new DoclingClient.CellResult(1,1,1,1,"10",new Bounds(250,90,100,40)),
                new DoclingClient.CellResult(2,1,1,1,"Total",new Bounds(250,130,100,40)));
        var table=new DoclingClient.TableResult("table",1,0,new Bounds(50,50,300,120),3,2,cells);
        var flow=new ArrayList<>(List.of(paragraph));var notices=new ArrayList<Notice>();
        DoclingMerger.merge(new DoclingClient.Result("docling","test",List.of(table),List.of()),pages,flow,List.of(text),notices);
        assertThat(flow).hasSize(1);assertThat(flow.get(0).table().cells()).hasSize(4);
        assertThat(flow.get(0).table().cells().get(0).columnSpan()).isEqualTo(2);
        assertThat(flow.get(0).table().cells().get(1).runs().get(0).font()).isEqualTo("SourceFont");
        assertThat(flow.get(0).table().columnWidths()).containsExactly(200d,100d);
        assertThat(notices).extracting(Notice::code).contains("DOCLING_APPLIED");
    }

    /** Comprueba que spans solapados o cajas fuera del papel no llegan a la plantilla. */
    @Test void rejectsOverlappingMergedCellsAndCoordinatesOutsidePage() {
        var bad=new DoclingClient.TableResult("bad",1,0,new Bounds(50,50,300,120),1,2,List.of(
                new DoclingClient.CellResult(0,0,1,2,"merged",null),new DoclingClient.CellResult(0,1,1,1,"overlap",null)));
        assertThatThrownBy(()->DoclingMerger.merge(new DoclingClient.Result("docling","test",List.of(bad),List.of()),pages,
                new ArrayList<>(),List.of(),new ArrayList<>())).isInstanceOf(PdfProblem.class);
        var outside=new DoclingClient.BlockResult("outside",1,0,new Bounds(1000,0,100,10),"text","outside");
        assertThatThrownBy(()->DoclingMerger.merge(new DoclingClient.Result("docling","test",List.of(),List.of(outside)),pages,
                new ArrayList<>(),List.of(),new ArrayList<>())).isInstanceOf(PdfProblem.class);
    }

    /** Asegura que una tabla propuesta dentro de la etiqueta no duplica la imagen conservada. */
    @Test void semanticTableInsideLabelDoesNotDuplicatePreservedImage() {
        var image=new Element("image","IMAGE",1,new Bounds(50,50,300,120),1,true,null,null,"asset",null,null,List.of());
        var table=new DoclingClient.TableResult("table",1,0,image.bounds(),1,1,List.of());
        var flow=new ArrayList<>(List.of(image));var notices=new ArrayList<Notice>();
        DoclingMerger.merge(new DoclingClient.Result("docling","test",List.of(table),List.of()),pages,flow,List.of(image),notices);
        assertThat(flow).containsExactly(image);assertThat(notices).extracting(Notice::code).contains("DOCLING_IMAGE_OVERLAP");
    }

    /** Prueba el transporte HTTP con un servidor sintético y exige error ante 503; no ejecuta modelos neuronales. */
    @Test void clientUsesRealHttpAndPropagatesModelUnavailabilityWithoutFallback() throws Exception {
        // Este servidor prueba el transporte y el contrato; sus respuestas son sintéticas y no acreditan inferencia Docling.
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/analyze",exchange->{
            byte[] body="{\"engine\":\"docling\",\"version\":\"test\",\"tables\":[],\"blocks\":[]}".getBytes(StandardCharsets.UTF_8);
            boolean valid="application/pdf".equals(exchange.getRequestHeaders().getFirst("Content-Type")) && exchange.getRequestURI().getQuery().equals("ocr=OFF");
            exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(valid?200:400,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var client=new DoclingClient("http://127.0.0.1:"+server.getAddress().getPort());
            assertThat(client.analyze(new byte[]{1},TemplateService.OcrMode.OFF).version()).isEqualTo("test");
        } finally { server.stop(0); }
        var unavailable=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        unavailable.createContext("/analyze",exchange->{exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(503,-1);exchange.close();});unavailable.start();
        try {
            var client=new DoclingClient("http://127.0.0.1:"+unavailable.getAddress().getPort());
            assertThatThrownBy(()->client.analyze(new byte[]{1},TemplateService.OcrMode.AUTO)).isInstanceOf(PdfProblem.class).hasMessageContaining("503");
        } finally { unavailable.stop(0); }
    }

    /** Verifica que el orden semántico prevalece sobre el orden por altura al recorrer columnas. */
    @Test void doclingReadingOrderOverridesGeometricRowOrder() {
        var a=run("First column",50,200);var b=run("Second column",350,100);
        var source=List.of(new Element("a","TEXT",1,a.bounds(),1,false,a.text(),List.of(a),null,null,null,List.of()),
                new Element("b","TEXT",1,b.bounds(),1,false,b.text(),List.of(b),null,null,null,List.of()));
        var blocks=List.of(new DoclingClient.BlockResult("a",1,0,a.bounds(),"text",a.text()),new DoclingClient.BlockResult("b",1,1,b.bounds(),"text",b.text()));
        var flow=new ArrayList<Element>();DoclingMerger.merge(new DoclingClient.Result("docling","test",List.of(),blocks),pages,flow,source,new ArrayList<>());
        assertThat(flow).extracting(Element::text).containsExactly("First column","Second column");
        assertThat(flow).allMatch(e->e.sourceObjectIds().stream().allMatch(id->id.equals("a")||id.equals("b")));
    }

    /** Construye un run nativo con estilo conocido para comprobar su conservación durante la combinación. */
    private static TextRun run(String text,double x,double y) { return new TextRun(text,new Bounds(x,y,80,10),"SourceFont",12,"#000000","NATIVE",1); }
}
