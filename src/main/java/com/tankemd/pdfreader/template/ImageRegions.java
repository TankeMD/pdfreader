package com.tankemd.pdfreader.template;

import static com.tankemd.pdfreader.template.Template.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Conserva regiones de imagen compuestas y añade OCR consultable sin duplicar su texto en el flujo. */
final class ImageRegions {
    /** Página enmascarada, bloques de imagen sustitutos, recursos compuestos y avisos de las regiones analizadas. */
    record Result(BufferedImage masked, Map<String,Element> replacements, List<Asset> assets, List<Notice> notices) {}

    /** Recorta regiones significativas, anota su OCR orientado y las enmascara para que no se conviertan además en párrafos. */
    static Result analyze(BufferedImage page,double width,double height,List<Element> objects,OcrReader ocr,long deadline,FontCatalog fonts) throws IOException {
        // Se trabaja sobre una copia; la imagen de página original sirve para obtener recortes con la composición visible.
        var masked=new BufferedImage(page.getWidth(),page.getHeight(),BufferedImage.TYPE_INT_RGB);
        var painter=masked.createGraphics();painter.drawImage(page,0,0,null);painter.setColor(Color.WHITE);
        Map<String,Element> replacements=new LinkedHashMap<>();List<Asset> assets=new ArrayList<>();List<Notice> notices=new ArrayList<>();
        try {
            // Solo las imágenes significativas y menores que un escaneo de página completa se analizan como regiones independientes.
            var candidates=objects.stream().filter(e->e.type().equals("IMAGE") && e.bounds().width()>=100 && e.bounds().height()>=40
                    && e.bounds().width()*e.bounds().height()>width*height*.02 && e.bounds().width()*e.bounds().height()<width*height*.65).toList();
            // El límite de seis regiones acota el coste de cuatro orientaciones OCR por cada una.
            for(var element:candidates.stream().limit(6).toList()) {
                var b=element.bounds();
                // Los límites en puntos se redondean y recortan al lienzo para extraer una región válida en píxeles.
                int x=Math.max(0,(int)Math.floor(b.x()*page.getWidth()/width));
                int y=Math.max(0,(int)Math.floor(b.y()*page.getHeight()/height));
                int right=Math.min(page.getWidth(),(int)Math.ceil(b.right()*page.getWidth()/width));
                int bottom=Math.min(page.getHeight(),(int)Math.ceil(b.bottom()*page.getHeight()/height));
                if(right<=x || bottom<=y) continue;
                var crop=page.getSubimage(x,y,right-x,bottom-y);
                // La caja se recalcula tras redondear píxeles para que el OCR se pueda transformar de vuelta sin perder la referencia.
                var visible=new Bounds(x*width/page.getWidth(),y*height/page.getHeight(),crop.getWidth()*width/page.getWidth(),crop.getHeight()*height/page.getHeight());
                var result=ocr.region(crop,visible,deadline,fonts);
                var bytes=new ByteArrayOutputStream();ImageIO.write(crop,"png",bytes);
                // El recurso compuesto conserva lo que se ve en la página; el recurso original sigue disponible para inspección.
                String asset=element.id()+"-composed";
                assets.add(new Asset(asset,"image/png",Base64.getEncoder().encodeToString(bytes.toByteArray()),crop.getWidth(),crop.getHeight()));
                replacements.put(element.id(),new Element(element.id(),"IMAGE",element.sourcePage(),visible,.85,true,null,null,asset,null,null,
                        List.of(element.id()),new ImageAnalysis(result.rotation(),result.text(),result.runs())));
                // El enmascaramiento evita que el OCR posterior de página vuelva a incorporar la etiqueta como párrafos.
                painter.fillRect(x,y,right-x,bottom-y);
                if(!result.runs().isEmpty()) notices.add(new Notice("IMAGE_OCR_ANNOTATION",element.sourcePage(),
                        "Región de imagen conservada como bloque; OCR asociado tras rotación de "+result.rotation()+" grados. No se duplica su texto en párrafos ni se decodifican códigos de barras."));
            }
            if(candidates.size()>6) notices.add(new Notice("IMAGE_REGION_LIMIT",null,"Solo se analizan por separado las primeras seis regiones de imagen por página."));
            // Los logos pequeños siguen siendo imágenes: su OCR no debe crear párrafos adicionales.
            for(var element:objects) if(element.type().equals("IMAGE") && element.bounds().width()*element.bounds().height()<width*height*.65) {
                var b=element.bounds();
                painter.fillRect((int)Math.floor(b.x()*page.getWidth()/width),(int)Math.floor(b.y()*page.getHeight()/height),
                        (int)Math.ceil(b.width()*page.getWidth()/width)+1,(int)Math.ceil(b.height()*page.getHeight()/height)+1);
            }
        } finally { painter.dispose(); }
        return new Result(masked,replacements,assets,notices);
    }
}
