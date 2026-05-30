package cn.nuaa.jensonxu.fairy.integration.service.tools.service.impl;

import cn.nuaa.jensonxu.fairy.integration.agent.harness.risk.ToolRisk;
import cn.nuaa.jensonxu.fairy.integration.agent.harness.risk.ToolRiskLevel;
import cn.nuaa.jensonxu.fairy.integration.service.tools.service.McpToolService;

import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@ToolRisk(ToolRiskLevel.READ_ONLY)
public class UnitConverterService implements McpToolService {

    private static final Map<String, Double> LENGTH = new HashMap<>();
    private static final Map<String, Double> WEIGHT = new HashMap<>();
    private static final Map<String, Double> AREA   = new HashMap<>();
    private static final Map<String, Double> VOLUME = new HashMap<>();
    private static final Map<String, Double> SPEED  = new HashMap<>();
    private static final List<Map<String, Double>> ALL_CATEGORIES;
    private static final Set<String> TEMP_UNITS = Set.of("c", "f", "k");

    static {
        // 长度，基准：m
        LENGTH.put("mm", 0.001);        LENGTH.put("cm", 0.01);
        LENGTH.put("m",  1.0);          LENGTH.put("km", 1000.0);
        LENGTH.put("in", 0.0254);       LENGTH.put("ft", 0.3048);
        LENGTH.put("yd", 0.9144);       LENGTH.put("mi", 1609.344);

        // 重量，基准：g
        WEIGHT.put("mg",  0.001);       WEIGHT.put("g",  1.0);
        WEIGHT.put("kg",  1000.0);      WEIGHT.put("t",  1_000_000.0);
        WEIGHT.put("lb",  453.592);     WEIGHT.put("oz", 28.3495);

        // 面积，基准：m²
        AREA.put("mm2",  0.000001);     AREA.put("cm2", 0.0001);
        AREA.put("m2",   1.0);          AREA.put("km2", 1_000_000.0);
        AREA.put("ha",   10_000.0);     AREA.put("acre", 4046.856);

        // 体积，基准：L
        VOLUME.put("ml",    0.001);     VOLUME.put("l",   1.0);
        VOLUME.put("m3",    1000.0);    VOLUME.put("gal", 3.78541);
        VOLUME.put("fl_oz", 0.0295735); VOLUME.put("cup", 0.236588);

        // 速度，基准：m/s
        SPEED.put("m/s",  1.0);         SPEED.put("km/h", 1.0 / 3.6);
        SPEED.put("mph",  0.44704);     SPEED.put("knot", 0.514444);

        ALL_CATEGORIES = List.of(LENGTH, WEIGHT, AREA, VOLUME, SPEED);
    }

    @Tool(description = """
            Convert a value from one unit to another.
            Supported units:
            - Length:      mm, cm, m, km, in, ft, yd, mi
            - Weight:      mg, g, kg, t, lb, oz
            - Area:        mm2, cm2, m2, km2, ha, acre
            - Volume:      ml, l, m3, gal, fl_oz, cup
            - Speed:       m/s, km/h, mph, knot
            - Temperature: c (Celsius), f (Fahrenheit), k (Kelvin)
            Common full names are also accepted (e.g. 'kilogram', 'celsius', 'miles').
            The from and to units must belong to the same category.
            """)
    public String convert(
            @ToolParam(description = "Numeric value to convert") double value,
            @ToolParam(description = "Source unit, e.g. 'kg', 'c', 'm', 'km/h'") String from,
            @ToolParam(description = "Target unit, e.g. 'lb', 'f', 'mi', 'mph'") String to) {

        log.info("[unit-converter] 请求换算: {} {} -> {}", value, from, to);
        String fromUnit = normalize(from);
        String toUnit = normalize(to);

        if (fromUnit.equals(toUnit)) {
            return formatResult(value, from, value, to);
        }

        if (TEMP_UNITS.contains(fromUnit) && TEMP_UNITS.contains(toUnit)) {
            return formatResult(value, from, convertTemperature(value, fromUnit, toUnit), to);
        }

        for (Map<String, Double> category : ALL_CATEGORIES) {
            if (category.containsKey(fromUnit) && category.containsKey(toUnit)) {
                double base = value * category.get(fromUnit);
                double result = base  / category.get(toUnit);
                return formatResult(value, from, result, to);
            }
        }

        return String.format(
                "Cannot convert '%s' to '%s'. Units may belong to different categories or are not supported. " +
                        "Supported: Length(mm/cm/m/km/in/ft/yd/mi) | Weight(mg/g/kg/t/lb/oz) | " +
                        "Area(mm2/cm2/m2/km2/ha/acre) | Volume(ml/l/m3/gal/fl_oz/cup) | " +
                        "Speed(m/s/km/h/mph/knot) | Temperature(c/f/k)", from, to);
    }

    private String normalize(String unit) {
        return switch (unit.toLowerCase().trim()) {
            case "celsius",     "°c"                          -> "c";
            case "fahrenheit",  "°f"                          -> "f";
            case "kelvin"                                      -> "k";
            case "meter",       "meters",    "metre"          -> "m";
            case "kilometer",   "kilometers","kilometre"      -> "km";
            case "centimeter",  "centimeters","centimetre"    -> "cm";
            case "millimeter",  "millimeters","millimetre"    -> "mm";
            case "inch",        "inches"                      -> "in";
            case "foot",        "feet"                        -> "ft";
            case "yard",        "yards"                       -> "yd";
            case "mile",        "miles"                       -> "mi";
            case "kilogram",    "kilograms"                   -> "kg";
            case "gram",        "grams"                       -> "g";
            case "milligram",   "milligrams"                  -> "mg";
            case "ton",         "tonne",     "tons"           -> "t";
            case "pound",       "pounds"                      -> "lb";
            case "ounce",       "ounces"                      -> "oz";
            case "liter",       "litre",     "liters"         -> "l";
            case "milliliter",  "millilitre"                  -> "ml";
            case "gallon",      "gallons"                     -> "gal";
            case "hectare",     "hectares"                    -> "ha";
            case "kph",         "kmh"                         -> "km/h";
            default -> unit.toLowerCase().trim();
        };
    }

    private double convertTemperature(double value, String from, String to) {
        double celsius = switch (from) {
            case "f" -> (value - 32) * 5.0 / 9.0;
            case "k" -> value - 273.15;
            default  -> value;
        };
        return switch (to) {
            case "f" -> celsius * 9.0 / 5.0 + 32;
            case "k" -> celsius + 273.15;
            default  -> celsius;
        };
    }

    private String formatResult(double input, String from, double result, String to) {
        String inputStr = (input == Math.floor(input) && !Double.isInfinite(input)) ? String.valueOf((long) input) : String.valueOf(input);
        double abs = Math.abs(result);
        String resultStr;
        if (abs == 0) {
            resultStr = "0";
        } else if (abs >= 0.0001 && abs < 10_000_000) {
            resultStr = String.format("%.6f", result)
                    .replaceAll("0+$", "").replaceAll("\\.$", "");
        } else {
            resultStr = String.format("%.4e", result);
        }
        log.info("[unit-converter] 换算结果: {} {}", result, to);
        return String.format("%s %s = %s %s", inputStr, from, resultStr, to);
    }
}