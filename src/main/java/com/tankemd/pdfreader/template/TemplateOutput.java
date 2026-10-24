package com.tankemd.pdfreader.template;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.DecimalNode;
import tools.jackson.databind.node.ObjectNode;

/** Frontera de exportación: las heurísticas internas siguen operando en puntos y con precisión completa. */
final class TemplateOutput {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Set<String> LENGTHS=Set.of("x","y","width","height","top","right","bottom","left","fontSize","lineWidth");
    private static final Set<String> LENGTH_ARRAYS=Set.of("coordinates","dash","columnWidths","rowHeights");
    /** La conversión es independiente del proceso de extracción y no necesita instancias. */
    private TemplateOutput() {}

    /** Exporta toda medida en pulgadas y todos los decimales con como máximo cuatro cifras fraccionarias. */
    static JsonNode inches(Template template) {
        ObjectNode result=JSON.valueToTree(template);
        result.put("schemaVersion","2.0");result.put("units","inches");
        return convert(result,"");
    }

    /** Recorre también objetos originales, celdas, curvas e imágenes anotadas; los píxeles e índices no se escalan. */
    private static JsonNode convert(JsonNode node,String field) {
        if(node.isObject()) {
            ObjectNode out=JSON.createObjectNode();
            for(var entry:node.properties()) out.set(entry.getKey(),convert(entry.getValue(),entry.getKey()));
            return out;
        }
        if(node.isArray()) {
            ArrayNode out=JSON.createArrayNode();
            for(var value:node) out.add(convert(value,field));
            return out;
        }
        if(node.isNumber()) {
            BigDecimal value=node.decimalValue();
            if(LENGTHS.contains(field) || LENGTH_ARRAYS.contains(field)) value=value.divide(BigDecimal.valueOf(72),12,RoundingMode.HALF_UP);
            else if(node.isIntegralNumber()) return node;
            value=value.setScale(4,RoundingMode.HALF_UP).stripTrailingZeros();
            // Una escala negativa puede producir exponentes; escala cero mantiene la salida decimal habitual.
            return DecimalNode.valueOf(value.scale()<0?value.setScale(0):value);
        }
        return node;
    }
}
