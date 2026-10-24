package com.tankemd.pdfreader.template;

/** Error de dominio con código estable que los controladores traducen a una respuesta HTTP. */
final class PdfProblem extends RuntimeException {
    final String code;
    /** Conserva un código de error estable y un mensaje apto para la respuesta HTTP del dominio. */
    PdfProblem(String code, String message) { super(message); this.code = code; }
}
