package com.tankemd.pdfreader.template;

import static com.tankemd.pdfreader.template.Template.*;

import java.awt.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

/** Catálogo real del PDF, separado de candidatos visuales instalados en la máquina. */
final class FontCatalog {
    /** Fuente renderizable y su origen; nunca se presentan candidatos instalados como recursos incrustados. */
    record Candidate(String family,String weight,String style,String source,PDFont pdfFont,Font installed) {}
    private final Map<String,PDFont> resources=new LinkedHashMap<>();
    private final Map<String,Set<Integer>> pages=new LinkedHashMap<>();
    private List<Candidate> candidates;
    /** Se construye mediante from para conservar recursos y procedencia de un mismo documento. */
    private FontCatalog() {}

    /** Recorre también formularios XObject; evita ciclos y registra las páginas de cada recurso. */
    static FontCatalog from(PDDocument document) throws IOException {
        var result=new FontCatalog();
        for(int i=0;i<document.getNumberOfPages();i++) result.visit(document.getPage(i).getResources(),i+1,new HashSet<>());
        return result;
    }

    /** Conserva nombres originales y presencia de incrustación; el catálogo no identifica por sí solo una palabra OCR. */
    List<FontInfo> infos() {
        return resources.entrySet().stream().map(e->new FontInfo(e.getKey(),family(e.getKey()),weight(e.getKey()),style(e.getKey()),
                e.getValue().isEmbedded(),pages.get(e.getKey()).stream().sorted().toList())).toList();
    }

    /** Da prioridad exclusiva a fuentes incrustadas. Solo sin ellas se compara con ficheros TTF locales. */
    List<Candidate> candidates() {
        if(candidates!=null) return candidates;
        List<Candidate> out=new ArrayList<>();
        resources.forEach((name,font)->{ if(font.isEmbedded()) out.add(new Candidate(family(name),weight(name),style(name),"embedded",font,null)); });
        if(out.isEmpty()) {
            Path directory=Path.of("/usr/share/fonts/truetype");
            if(Files.isDirectory(directory)) try(var files=Files.walk(directory)) {
                for(var path:files.filter(p->p.toString().endsWith(".ttf") && !p.getFileName().toString().contains("Math")).sorted().limit(40).toList()) {
                    try {
                        Font font=Font.createFont(Font.TRUETYPE_FONT,path.toFile());String name=font.getFontName(Locale.ROOT);
                        out.add(new Candidate(font.getFamily(Locale.ROOT),weight(name),style(name),"installed",null,font));
                    } catch(java.awt.FontFormatException | IOException ignored) {
                        // Un candidato no legible se omite; no se sustituye por una fuente de sistema elegida implícitamente.
                    }
                }
            } catch(IOException ignored) { /* Un sistema sin fuentes conserva unknown y no inventa una familia. */ }
        }
        candidates=List.copyOf(out);return candidates;
    }

    /** Normaliza prefijos de subconjunto y variantes PostScript sin descartar el nombre original del recurso. */
    static String family(String original) {
        if(original==null) return "unknown";
        String value=original.replaceFirst("^[A-Z]{6}\\+","");
        value=value.replaceFirst("(?i)(?:[- ,]*(?:BoldItalic|BoldOblique|DemiBold|SemiBold|Bold|Italic|Oblique|Regular|Roman|Medium|Book))+(?:MT)?$","");
        value=value.replaceFirst("(?:PS)?MT$","");
        return switch(value) { case "TimesNewRomanPS","TimesNewRoman" -> "Times New Roman";case "CourierNewPS","CourierNew" -> "Courier New";default -> value; };
    }
    /** Extrae peso nominal; la coincidencia visual se ocupa por separado de los runs OCR. */
    static String weight(String name) { return name!=null && name.toLowerCase(Locale.ROOT).matches(".*(bold|black|heavy).*" )?"bold":"normal"; }
    /** Las variantes Oblique e Italic se representan con el mismo estilo de salida. */
    static String style(String name) { return name!=null && name.toLowerCase(Locale.ROOT).matches(".*(italic|oblique).*" )?"italic":"normal"; }

    /** Inspecciona recursos compartidos una vez por página para mantener procedencia sin recursión infinita. */
    private void visit(PDResources resource,int page,Set<COSDictionary> visited) throws IOException {
        if(resource==null || !visited.add(resource.getCOSObject())) return;
        for(var key:resource.getFontNames()) {
            PDFont font=resource.getFont(key);if(font==null) continue;
            String name=font.getName();resources.putIfAbsent(name,font);pages.computeIfAbsent(name,k->new HashSet<>()).add(page);
        }
        for(var key:resource.getXObjectNames()) if(resource.getXObject(key) instanceof PDFormXObject form) visit(form.getResources(),page,visited);
    }
}
