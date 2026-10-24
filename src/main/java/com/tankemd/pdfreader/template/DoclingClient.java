package com.tankemd.pdfreader.template;

import static com.tankemd.pdfreader.template.Template.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Cliente del servicio Python local. Distingue indisponibilidad, timeout y errores de contrato. */
@Component
final class DoclingClient {
    /** Celda semántica recibida por HTTP con posición lógica, spans y caja opcional. */
    record CellResult(int row,int column,int rowSpan,int columnSpan,String text,Bounds bounds) {}
    /** Región de tabla propuesta por Docling antes de validarla y asociarla a objetos originales. */
    record TableResult(String id,int page,int order,Bounds bounds,int rows,int columns,List<CellResult> cells) {}
    /** Bloque de texto semántico con orden de lectura, etiqueta y región de origen. */
    record BlockResult(String id,int page,int order,Bounds bounds,String label,String text) {}
    /** Respuesta normalizada del servicio con versión, tablas y bloques en orden semántico. */
    record Result(String engine,String version,List<TableResult> tables,List<BlockResult> blocks) {}
    private final URI endpoint;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final JsonMapper json=JsonMapper.builder().build();

    /** Configura la dirección del servicio semántico; el PDF se envía como bytes, no como una URL de descarga. */
    DoclingClient(@Value("${pdfreader.docling.url:http://127.0.0.1:8090}") String url) {
        endpoint=URI.create(url+"/analyze");
    }

    /** Consulta Docling con límites de conexión, duración y tamaño; propaga los fallos sin recurrir silenciosamente al motor local. */
    Result analyze(byte[] bytes,TemplateService.OcrMode mode) {
        // La llamada acota su duración y envía el PDF binario al servicio configurado, sin publicar el documento.
        var request=HttpRequest.newBuilder(URI.create(endpoint+"?ocr="+mode)).timeout(Duration.ofSeconds(240))
                .header("Content-Type","application/pdf").POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        try {
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var body=response.body()) {
                // Cualquier respuesta no satisfactoria es un fallo explícito del modo híbrido; no se devuelve una plantilla local disfrazada.
                if(response.statusCode()!=200) throw new PdfProblem("DOCLING_UNAVAILABLE",
                        "Docling devolvió HTTP "+response.statusCode()+". Comprueba /health, sus modelos y la configuración de red.");
                // Se lee un byte extra para detectar respuestas demasiado grandes antes de deserializar el contrato.
                byte[] payload=body.readNBytes(20*1024*1024+1);
                if(payload.length>20*1024*1024) throw new PdfProblem("DOCLING_INVALID_RESPONSE","La respuesta de Docling supera 20 MiB.");
                Result result=json.readValue(payload,Result.class);
                if(!"docling".equals(result.engine()) || result.tables()==null || result.blocks()==null)
                    throw new PdfProblem("DOCLING_INVALID_RESPONSE","Respuesta incompleta del analizador semántico.");
                return result;
            }
        } catch(java.net.http.HttpTimeoutException e) {
            throw new PdfProblem("DOCLING_TIMEOUT","Docling ha superado el límite de 240 segundos.");
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();throw new PdfProblem("DOCLING_UNAVAILABLE","Análisis Docling interrumpido.");
        } catch(IOException | tools.jackson.core.JacksonException e) {
            throw new PdfProblem("DOCLING_UNAVAILABLE","No se pudo consultar el servicio local Docling o interpretar su respuesta.");
        }
    }
}
