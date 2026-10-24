package com.tankemd.pdfreader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Punto de entrada de la aplicación Spring Boot para convertir PDF en modelos CCM. */
@SpringBootApplication
public class PdfreaderApplication {
    /** Arranca Spring Boot y registra los controladores y servicios del proyecto. */
    public static void main(String[] args) {
        SpringApplication.run(PdfreaderApplication.class, args);
    }
}
