package com.tankemd.pdfreader.template;

import static com.tankemd.pdfreader.template.Template.*;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;

/** Comparación visual local y acotada; no confunde una fuente similar con una identificación incrustada. */
final class FontEstimator {
    /** Puntuación media de las muestras válidas para una variante candidata. */
    private record Score(FontCatalog.Candidate candidate,double similarity) {}
    /** Silueta normalizada y proporción del recorte original antes de normalizarlo. */
    private record Mask(boolean[] pixels,double aspect) {}
    /** Comparador sin estado global: el catálogo conserva sus candidatos durante un solo documento. */
    private FontEstimator() {}

    /** Compara muestras de cada altura OCR y propaga la estimación al grupo, con evidencia y candidatos. */
    static List<TextRun> estimate(List<TextRun> words,BufferedImage page,double pageWidth,double pageHeight,FontCatalog catalog) {
        if(words.isEmpty()) return words;
        Map<Integer,List<TextRun>> groups=new LinkedHashMap<>();
        for(var run:words) if(run.fontEstimated()) groups.computeIfAbsent(bucket(run),k->new ArrayList<>()).add(run);
        Map<Integer,List<FontMatch>> matches=new HashMap<>();
        // Se acota el coste a ocho tamaños y dos palabras representativas por tamaño. No se atribuye certeza individual a cada run.
        for(var group:groups.entrySet().stream().sorted(Comparator.<Map.Entry<Integer,List<TextRun>>>comparingInt(e->e.getValue().size()).reversed()).limit(8).toList()) {
            var samples=group.getValue().stream().filter(r->r.confidence()>=.8 && r.text().matches("[\\p{L}]{4,24}"))
                    .sorted(Comparator.comparingInt((TextRun r)->r.text().length()).reversed()).limit(2).toList();
            List<Score> scores=new ArrayList<>();
            for(var candidate:catalog.candidates()) {
                double score=0;int compared=0;
                for(var sample:samples) {
                    Mask target=crop(page,sample.bounds(),pageWidth,pageHeight);
                    try {
                        Mask reference=mask(render(candidate,sample.text()));
                        if(target!=null && reference!=null) { score+=similarity(target,reference);compared++; }
                    } catch(IOException | IllegalArgumentException ignored) {
                        // Un subconjunto incrustado puede no contener las letras OCR. No se inventan glifos ni se usa otra familia silenciosamente.
                    }
                }
                if(compared==samples.size() && compared>0) scores.add(new Score(candidate,score/compared));
            }
            // Variantes duplicadas de un recurso no deben aparentar candidatos independientes.
            Map<String,Score> unique=new LinkedHashMap<>();
            for(var score:scores.stream().sorted(Comparator.comparingDouble(Score::similarity).reversed()).toList()) {
                var c=score.candidate();unique.putIfAbsent(c.family()+"|"+c.weight()+"|"+c.style(),score);
            }
            matches.put(group.getKey(),unique.values().stream().limit(3).map(s->new FontMatch(s.candidate().family(),s.candidate().weight(),s.candidate().style(),s.candidate().source(),s.similarity())).toList());
        }
        return words.stream().map(run->{
            if(!run.fontEstimated()) return run;
            List<FontMatch> ranked=matches.getOrDefault(bucket(run),List.of());
            FontMatch best=ranked.isEmpty()?null:ranked.get(0);
            boolean resolved=best!=null && best.similarity()>=.75 && (ranked.size()==1 || best.similarity()-ranked.get(1).similarity()>=.008);
            return new TextRun(run.text(),run.bounds(),resolved?best.family():"unknown",run.fontSize(),run.color(),run.origin(),run.confidence(),null,
                    resolved?best.weight():"unknown",resolved?best.style():"unknown",true,
                    best==null?"UNRESOLVED":(best.source().equals("embedded")?"EMBEDDED_FONT_VISUAL_MATCH":"INSTALLED_FONT_VISUAL_MATCH"),best==null?null:best.similarity(),ranked);
        }).toList();
    }

    /** Agrupa alturas similares en puntos. La confianza pertenece a la comparación representativa, no a cada letra. */
    private static int bucket(TextRun run) { return (int)Math.round(run.bounds().height()/2); }

    /** Renderiza con el candidato real, sin sustitución tipográfica implícita cuando falten caracteres. */
    private static BufferedImage render(FontCatalog.Candidate candidate,String text) throws IOException {
        if(candidate.pdfFont()!=null) {
            candidate.pdfFont().encode(text);
            try(var document=new PDDocument()) {
                var page=new PDPage(new PDRectangle(1000,100));document.addPage(page);
                try(var out=new PDPageContentStream(document,page)) {
                    out.beginText();out.setFont(candidate.pdfFont(),36);out.newLineAtOffset(10,30);out.showText(text);out.endText();
                }
                return new PDFRenderer(document).renderImageWithDPI(0,72);
            }
        }
        var font=candidate.installed().deriveFont(48f);
        if(font.canDisplayUpTo(text)!=-1) throw new IllegalArgumentException("Missing glyph");
        var bounds=font.createGlyphVector(new FontRenderContext(new AffineTransform(),true,true),text).getVisualBounds();
        var image=new BufferedImage(Math.max(1,(int)Math.ceil(bounds.getWidth())+12),Math.max(1,(int)Math.ceil(bounds.getHeight())+12),BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();
        try {
            g.setColor(Color.WHITE);g.fillRect(0,0,image.getWidth(),image.getHeight());g.setColor(Color.BLACK);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setFont(font);g.drawString(text,6-(float)bounds.getX(),6-(float)bounds.getY());
        } finally { g.dispose(); }
        return image;
    }

    /** Usa únicamente la región de palabra identificada por OCR; no interpreta logos o códigos como muestras de fuente. */
    private static Mask crop(BufferedImage page,Bounds box,double width,double height) {
        int x=Math.max(0,(int)Math.floor(box.x()*page.getWidth()/width)),y=Math.max(0,(int)Math.floor(box.y()*page.getHeight()/height));
        int right=Math.min(page.getWidth(),(int)Math.ceil(box.right()*page.getWidth()/width)),bottom=Math.min(page.getHeight(),(int)Math.ceil(box.bottom()*page.getHeight()/height));
        return right>x && bottom>y?mask(page.getSubimage(x,y,right-x,bottom-y)):null;
    }

    /** Elimina blanco exterior y normaliza la silueta, guardando la proporción para penalizar fuentes de anchura distinta. */
    private static Mask mask(BufferedImage image) {
        int left=image.getWidth(),top=image.getHeight(),right=-1,bottom=-1;
        for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) if(dark(image.getRGB(x,y))) {
            left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);
        }
        if(right<left || bottom<top) return null;
        boolean[] pixels=new boolean[128*32];
        for(int y=0;y<32;y++) for(int x=0;x<128;x++) pixels[y*128+x]=dark(image.getRGB(left+x*(right-left+1)/128,top+y*(bottom-top+1)/32));
        return new Mask(pixels,(right-left+1d)/(bottom-top+1d));
    }
    /** Umbral de tinta común para la muestra PDF y los candidatos renderizados. */
    private static boolean dark(int rgb) { return (((rgb>>16)&255)+((rgb>>8)&255)+(rgb&255))<480; }

    /** Coincidencia simétrica tolerante a un píxel, combinada con la proporción original. No es una probabilidad calibrada. */
    private static double similarity(Mask a,Mask b) {
        double matched=0,total=0;
        for(int i=0;i<a.pixels.length;i++) {
            if(a.pixels[i]) { total++;if(touches(b.pixels,i)) matched++; }
            if(b.pixels[i]) { total++;if(touches(a.pixels,i)) matched++; }
        }
        return .8*matched/Math.max(1,total)+.2*Math.exp(-Math.abs(Math.log(a.aspect/b.aspect)));
    }
    /** Tolera antialiasing y ligeras diferencias de resolución sin comparar posiciones de página. */
    private static boolean touches(boolean[] pixels,int at) {
        int x=at%128,y=at/128;
        for(int dy=-1;dy<=1;dy++) for(int dx=-1;dx<=1;dx++) {
            int xx=x+dx,yy=y+dy;if(xx>=0 && xx<128 && yy>=0 && yy<32 && pixels[yy*128+xx]) return true;
        }
        return false;
    }
}
