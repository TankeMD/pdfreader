package com.tankemd.pdfreader.template;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Identifica medidas estándar; nunca asigna el formato más cercano si excede la tolerancia. */
final class PaperFormats {
    private record Format(String name,double shortSide,double longSide) {}
    private static final List<Format> FORMATS=new ArrayList<>();
    // Medio punto admite redondeos habituales del PDF (p. ej., 595 x 842 para A4).
    private static final double TOLERANCE_PT=.5;
    static {
        series("A",new int[][]{{841,1189},{594,841},{420,594},{297,420},{210,297},{148,210},{105,148},{74,105},{52,74},{37,52},{26,37}});
        series("B",new int[][]{{1000,1414},{707,1000},{500,707},{353,500},{250,353},{176,250},{125,176},{88,125},{62,88},{44,62},{31,44}});
        series("C",new int[][]{{917,1297},{648,917},{458,648},{324,458},{229,324},{162,229},{114,162},{81,114},{57,81},{40,57},{28,40}});
        series("JIS B",new int[][]{{1030,1456},{728,1030},{515,728},{364,515},{257,364},{182,257},{128,182},{91,128},{64,91},{45,64},{32,45}});
        series("RA",new int[][]{{860,1220},{610,860},{430,610},{305,430},{215,305}});
        series("SRA",new int[][]{{900,1280},{640,900},{450,640},{320,450},{225,320}});
        inches("Letter",8.5,11);inches("Legal",8.5,14);inches("Tabloid",11,17);
        inches("Executive",7.25,10.5);inches("Statement",5.5,8.5);inches("Folio",8.5,13);
    }
    /** Utilidad de catálogo: no mantiene estado por documento. */
    private PaperFormats() {}

    /** Devuelve un nombre solo cuando ambos lados coinciden, independientemente del giro. */
    static String identify(double width,double height) {
        double shortSide=Math.min(width,height),longSide=Math.max(width,height);
        return FORMATS.stream().filter(f->Math.abs(f.shortSide-shortSide)<=TOLERANCE_PT && Math.abs(f.longSide-longSide)<=TOLERANCE_PT)
                .min(Comparator.comparingDouble(f->Math.abs(f.shortSide-shortSide)+Math.abs(f.longSide-longSide)))
                .map(Format::name).orElse("custom");
    }
    /** La orientación se refiere al papel visible después de la rotación del PDF; un cuadrado usa vertical. */
    static String orientation(double width,double height) { return width>height?"horizontal":"vertical"; }
    /** Registra series métricas oficiales en puntos, sin redondear las medidas de comparación. */
    private static void series(String prefix,int[][] sizes) {
        for(int i=0;i<sizes.length;i++) FORMATS.add(new Format(prefix+i,sizes[i][0]*72/25.4,sizes[i][1]*72/25.4));
    }
    /** Registra tamaños norteamericanos; Tabloid/Ledger comparten tamaño y se usa Tabloid como nombre canónico. */
    private static void inches(String name,double width,double height) { FORMATS.add(new Format(name,width*72,height*72)); }
}
