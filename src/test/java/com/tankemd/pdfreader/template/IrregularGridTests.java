package com.tankemd.pdfreader.template;

import static org.assertj.core.api.Assertions.*;
import static com.tankemd.pdfreader.template.Template.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Pruebas de la topología de tablas con celdas combinadas y subtotales incompletos. */
class IrregularGridTests {
    /** Verifica una cabecera combinada y un subtotal parcial sin crear una celda en la zona abierta de la fila. */
    @Test void recoversMergedHeaderAndSubtotalWithoutInventingAbsentCell() {
        // Se omite un separador de cabecera y parte del perímetro inferior para reproducir un subtotal parcial.
        var segments=List.of(line(50,50,350,50),line(50,80,350,80),line(50,110,350,110),line(150,140,350,140),
                line(50,50,50,110),line(150,80,150,140),line(250,50,250,140),line(350,50,350,140));
        var text=List.of(text("Merged",60,60),text("Amount",260,60),text("A",60,90),text("B",160,90),text("10",260,90),
                text("Total",160,120),text("10",260,120));
        var result=Structure.tables(1,segments,text,List.of());
        assertThat(result.elements()).hasSize(1);var table=result.elements().get(0).table();
        assertThat(table.rows()).isEqualTo(3);assertThat(table.columns()).isEqualTo(3);
        assertThat(table.detection()).isEqualTo("GRID_IRREGULAR");assertThat(table.cells()).hasSize(7);
        assertThat(table.cells()).anyMatch(c->c.row()==0 && c.column()==0 && c.columnSpan()==2);
        assertThat(table.cells()).noneMatch(c->c.row()==2 && c.column()==0);
        assertThat(table.cells()).anyMatch(c->c.row()==2 && c.column()==1 && c.text().equals("Total"));
        assertThat(result.text()).containsExactlyInAnyOrderElementsOf(text);
    }
    /** Construye un borde sintético para probar la topología de cuadrícula sin renderizar un PDF. */
    private static Structure.Segment line(double x1,double y1,double x2,double y2) { return new Structure.Segment(x1,y1,x2,y2,"border"); }
    /** Construye texto nativo sintético centrado dentro de las celdas esperadas. */
    private static TextRun text(String text,double x,double y) { return new TextRun(text,new Bounds(x,y,50,10),"Font",12,"#000000","NATIVE",1); }
}
