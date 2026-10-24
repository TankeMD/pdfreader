package com.tankemd.pdfreader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/** Prueba de disponibilidad del backend sobre un servidor HTTP real. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PdfreaderApplicationTests {
    @LocalServerPort
    private int port;

    /** Comprueba por HTTP el estado y el contenido del endpoint de salud del backend. */
    @Test
    void healthEndpointReturnsUp() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/health")).GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
    }
}
