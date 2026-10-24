package com.tankemd.pdfreader.template;

import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

/** API multipart para solicitar extracción local o híbrida de una plantilla CCM. */
@RestController
@RequestMapping("/api/templates")
public final class TemplateController {
    private final TemplateService service;
    /** Inyecta el servicio que realiza el análisis y la generación del modelo intermedio. */
    TemplateController(TemplateService service) { this.service=service; }

    /** Recibe el PDF multipart y las opciones de OCR/motor; aplica el límite de tamaño antes de delegar la extracción. */
    @PostMapping(value="/analyze",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public tools.jackson.databind.JsonNode analyze(@RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue="AUTO") TemplateService.OcrMode ocr,
            @RequestParam(defaultValue="${pdfreader.analysis.default:LOCAL}") TemplateService.Engine engine) throws IOException {
        // Además del límite multipart del servidor, el dominio comprueba el tamaño recibido antes de analizarlo.
        byte[] bytes=file.getBytes();
        if (bytes.length>20*1024*1024) throw new PdfProblem("FILE_TOO_LARGE","El tamaño máximo es 20 MiB.");
        return TemplateOutput.inches(service.analyze(bytes,ocr,engine));
    }
}

/** Traduce fallos de infraestructura, formato y dominio sin exponer detalles del documento. */
@RestControllerAdvice(assignableTypes=TemplateController.class)
class TemplateErrors {
    /** Traduce los errores de análisis a estados HTTP diferenciados, incluidos indisponibilidad y timeout de Docling. */
    @ExceptionHandler(PdfProblem.class)
    ResponseEntity<Map<String,String>> problem(PdfProblem e) {
        // Los errores operativos se distinguen de restricciones del PDF para que el cliente pueda decidir si reintentar.
        HttpStatus status=switch(e.code) {
            case "BUSY" -> HttpStatus.TOO_MANY_REQUESTS;
            case "OCR_UNAVAILABLE", "OCR_FAILED", "DOCLING_UNAVAILABLE", "DOCLING_INVALID_RESPONSE" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "OCR_TIMEOUT", "DOCLING_TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;
            case "EMPTY_FILE" -> HttpStatus.BAD_REQUEST;
            case "FILE_TOO_LARGE" -> HttpStatus.PAYLOAD_TOO_LARGE;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(Map.of("code",e.code,"message",e.getMessage()));
    }
    /** Informa de un PDF ilegible sin devolver detalles internos del parser ni contenido del documento. */
    @ExceptionHandler(IOException.class)
    ResponseEntity<Map<String,String>> invalid(IOException e) {
        return ResponseEntity.badRequest().body(Map.of("code","INVALID_PDF","message","No se pudo leer el PDF o sus recursos."));
    }
    /** Devuelve un error estable cuando el contenedor rechaza una carga multipart demasiado grande. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String,String>> tooLarge() {
        return ResponseEntity.status(413).body(Map.of("code","FILE_TOO_LARGE","message","El tamaño máximo es 20 MiB."));
    }
}
