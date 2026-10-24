package com.tankemd.pdfreader.template;

import static com.tankemd.pdfreader.template.Template.*;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Adaptador local de Tesseract con lectura orientada, plazo por página y limpieza de recursos. */
@Component
final class OcrReader {
    private final String executable;
    private final String languages;
    private final String tessdata;

    /** Recibe el ejecutable, los idiomas y la ubicación de modelos de Tesseract desde la configuración. */
    OcrReader(@Value("${pdfreader.ocr.command:tesseract}") String executable,
            @Value("${pdfreader.ocr.languages:spa+eng}") String languages,
            @Value("${pdfreader.ocr.tessdata:}") String tessdata) {
        this.executable=executable; this.languages=languages; this.tessdata=tessdata;
    }

    /** Lee texto con segmentación automática y utiliza texto disperso si no encuentra palabras; respeta el presupuesto temporal de la página. */
    List<TextRun> read(BufferedImage image, double pageWidth, double pageHeight) throws IOException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
        return read(image,pageWidth,pageHeight,deadline);
    }

    /** Lee texto con segmentación automática y utiliza texto disperso si no encuentra palabras; respeta el presupuesto temporal de la página. */
    List<TextRun> read(BufferedImage image, double pageWidth, double pageHeight, long deadline) throws IOException {
        // PSM 3 intenta segmentación automática; PSM 11 rescata texto disperso como un número de página aislado.
        var words=recognize(image,pageWidth,pageHeight,3,deadline);
        return words.isEmpty()?recognize(image,pageWidth,pageHeight,11,deadline):words;
    }

    /** Lectura de la orientación elegida y runs transformados de vuelta a la página original. */
    record Oriented(int rotation, String text, List<TextRun> runs) {}

    /** Prueba cuatro orientaciones de una región y conserva la mejor lectura como anotación, transformando sus cajas al origen. */
    Oriented region(BufferedImage image, Bounds box, long deadline,FontCatalog fonts) throws IOException {
        List<TextRun> best=List.of();int angle=0;double score=-1;
        // Las cuatro lecturas compiten dentro del plazo común; probar un giro no modifica la apariencia del recurso del flow.
        for (int rotation:new int[]{0,90,180,270}) {
            var rotated=rotate(image,rotation);
            double w=rotation%180==0?box.width():box.height(),h=rotation%180==0?box.height():box.width();
            var runs=recognize(rotated,w,h,11,deadline);
            // La puntuación favorece letras reconocidas con confianza y proporción horizontal plausible; no es una probabilidad calibrada.
            double candidate=runs.stream().mapToDouble(r->{
                long letters=r.text().codePoints().filter(Character::isLetter).count();
                double aspect=Math.min(1,r.bounds().width()/Math.max(1,r.bounds().height()*r.text().length()*.3));
                return letters*r.confidence()*aspect;
            }).sum();
            if (candidate>score) { score=candidate;best=runs;angle=rotation; }
        }
        // La comparación se realiza en la orientación legible, antes de devolver las cajas al sistema de página.
        best=FontEstimator.estimate(best,rotate(image,angle),angle%180==0?box.width():box.height(),angle%180==0?box.height():box.width(),fonts);
        String text=Structure.join(best);
        final int rotation=angle;
        // El texto se une en la orientación legible, pero sus cajas vuelven al sistema original para enlazarlas a la página.
        var mapped=best.stream().map(r->r.withBounds(restore(r.bounds(),box,rotation))).toList();
        return new Oriented(angle,text,mapped);
    }

    /** Deshace el giro de lectura y traslada la caja de una palabra a las coordenadas originales de la página. */
    static Bounds restore(Bounds b, Bounds source, int rotation) {
        return switch(rotation) {
            case 90 -> new Bounds(source.x()+b.y(),source.y()+source.height()-b.right(),b.height(),b.width());
            case 180 -> new Bounds(source.x()+source.width()-b.right(),source.y()+source.height()-b.bottom(),b.width(),b.height());
            case 270 -> new Bounds(source.x()+source.width()-b.bottom(),source.y()+b.x(),b.height(),b.width());
            default -> new Bounds(source.x()+b.x(),source.y()+b.y(),b.width(),b.height());
        };
    }

    /** Genera una imagen girada en múltiplos de 90 grados y adapta sus dimensiones sin modificar la imagen de entrada. */
    private static BufferedImage rotate(BufferedImage source,int angle) {
        if (angle==0) return source;
        int w=source.getWidth(),h=source.getHeight();
        // Los giros de 90/270 intercambian dimensiones; las traslaciones mantienen todos los píxeles dentro del lienzo.
        var target=new BufferedImage(angle%180==0?w:h,angle%180==0?h:w,BufferedImage.TYPE_INT_RGB);
        var g=target.createGraphics();
        switch(angle) {
            case 90 -> { g.translate(h,0);g.rotate(Math.PI/2); }
            case 180 -> { g.translate(w,h);g.rotate(Math.PI); }
            case 270 -> { g.translate(0,w);g.rotate(-Math.PI/2); }
            default -> throw new IllegalArgumentException("Unsupported rotation");
        }
        g.drawImage(source,0,0,null);g.dispose();return target;
    }

    /** Ejecuta una pasada local de Tesseract con salida TSV, plazo compartido y limpieza de temporales incluso ante fallos. */
    private List<TextRun> recognize(BufferedImage image, double pageWidth, double pageHeight,int segmentation,long deadline) throws IOException {
        // Cada pasada usa su propio directorio, evitando colisiones entre regiones y restos de ejecuciones anteriores.
        Path dir = Files.createTempDirectory("pdfreader-ocr-");
        Process process = null;
        try {
            Path input=dir.resolve("page.png"), base=dir.resolve("result"), log=dir.resolve("stderr.log");
            ImageIO.write(image,"png",input.toFile());
            // Los argumentos se pasan directamente al proceso, sin shell; TSV se activa explícitamente para no depender de un archivo de configuración.
            var command = new ArrayList<>(List.of(executable,input.toString(),base.toString(),"-l",languages,"--psm",Integer.toString(segmentation)));
            if (!tessdata.isBlank()) command.addAll(List.of("--tessdata-dir",tessdata));
            command.addAll(List.of("-c","tessedit_create_tsv=1"));
            try { process = new ProcessBuilder(command).redirectError(log.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start(); }
            catch (IOException e) { throw new PdfProblem("OCR_UNAVAILABLE","No se puede ejecutar Tesseract. Configura pdfreader.ocr.command."); }
            // Se calcula el tiempo restante con reloj monotónico y se termina el proceso si ya se agotó el plazo de página.
            long remaining=deadline-System.nanoTime();
            if (remaining<=0 || !process.waitFor(Math.max(0,remaining),TimeUnit.NANOSECONDS)) {
                process.destroyForcibly(); process.waitFor();
                throw new PdfProblem("OCR_TIMEOUT","El OCR ha superado el límite de 45 segundos por página.");
            }
            if (process.exitValue()!=0) throw new PdfProblem("OCR_FAILED","Tesseract no pudo analizar la página. Comprueba los idiomas y pdfreader.ocr.tessdata.");
            if (!Files.isRegularFile(dir.resolve("result.tsv"))) throw new PdfProblem("OCR_FAILED","Tesseract no generó el resultado TSV esperado.");
            return parse(Files.readAllLines(dir.resolve("result.tsv")),pageWidth/image.getWidth(),pageHeight/image.getHeight());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new PdfProblem("OCR_INTERRUPTED","OCR interrumpido.");
        } finally {
            // El finally detiene procesos pendientes y borra temporales incluso tras errores o interrupciones.
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try { process.waitFor(5,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            try (var paths=Files.walk(dir)) {
                for (Path p: paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }

    /** Convierte filas de palabras TSV en runs en puntos. Descarta filas no textuales y resultados de confianza muy baja. */
    static List<TextRun> parse(List<String> lines, double sx, double sy) {
        List<TextRun> words=new ArrayList<>();
        for (String line:lines) {
            // Solo las filas de nivel 5 contienen palabras; se conserva la última columna completa aunque el texto contenga separadores.
            String[] p=line.split("\t",12);
            if (p.length<12 || !p[0].equals("5") || p[11].isBlank()) continue;
            double confidence=Double.parseDouble(p[10])/100;
            if (confidence<0.25) continue;
            words.add(new TextRun(p[11],new Bounds(Double.parseDouble(p[6])*sx,Double.parseDouble(p[7])*sy,
                    Double.parseDouble(p[8])*sx,Double.parseDouble(p[9])*sy),null,
                    Double.parseDouble(p[9])*sy,null,"OCR",confidence));
        }
        return words;
    }
}
