package com.tankemd.pdfreader;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoint mínimo de disponibilidad HTTP del backend Java. */
@RestController
public class HealthController {
    /** Comprueba que el backend HTTP responde; no acredita la disponibilidad de los modelos Docling. */
    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
