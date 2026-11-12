package org.agrosence.advisory;

/**
 * AgroSence :: AdvisoryLogic
 * -----------------------------------------------------------------------
 * Pure, dependency-free decision logic that models the "brain" of the
 * crop_advisory_engine fog module used in the iFogSim topology
 * (see org.agrosence.AgroSenceMain). It is kept independent of CloudSim /
 * iFogSim classes on purpose so that:
 *
 *   1. It can be unit-tested / demoed with `java` directly (see main()).
 *   2. It documents, in plain Java, the rules that the simulated
 *      "crop_advisory_engine" module is conceptually executing every time
 *      a SENSOR_DATA tuple is processed inside the simulation.
 *
 * The thresholds below are simplified, generic agronomic rules of thumb
 * (not crop-specific agronomy advice) meant purely to demonstrate a
 * realistic edge-analytics workload for the simulation.
 */
public class AdvisoryLogic {

    /** Soil moisture is expressed as volumetric water content in %. */
    public static final double SOIL_MOISTURE_DRY_THRESHOLD = 28.0;
    public static final double SOIL_MOISTURE_SATURATED_THRESHOLD = 68.0;

    /** Ambient temperature in Celsius. */
    public static final double FROST_RISK_TEMP = 4.0;
    public static final double HEAT_STRESS_TEMP = 38.0;

    /** Relative humidity in %. */
    public static final double FUNGAL_RISK_HUMIDITY = 85.0;

    /** Rainfall forecast / measured in mm over the last window. */
    public static final double HEAVY_RAIN_MM = 20.0;

    public enum IrrigationAction { HOLD, LIGHT_IRRIGATION, FULL_IRRIGATION, SKIP_RAIN_EXPECTED }

    public static class SensorReading {
        public final double soilMoisturePct;
        public final double temperatureC;
        public final double humidityPct;
        public final double rainfallMm;

        public SensorReading(double soilMoisturePct, double temperatureC, double humidityPct, double rainfallMm) {
            this.soilMoisturePct = soilMoisturePct;
            this.temperatureC = temperatureC;
            this.humidityPct = humidityPct;
            this.rainfallMm = rainfallMm;
        }

        @Override
        public String toString() {
            return String.format("soil=%.1f%% temp=%.1fC rh=%.1f%% rain=%.1fmm",
                    soilMoisturePct, temperatureC, humidityPct, rainfallMm);
        }
    }

    public static class Advisory {
        public final IrrigationAction irrigationAction;
        public final boolean frostAlert;
        public final boolean heatStressAlert;
        public final boolean fungalRiskAlert;
        public final String summary;

        public Advisory(IrrigationAction irrigationAction, boolean frostAlert,
                         boolean heatStressAlert, boolean fungalRiskAlert, String summary) {
            this.irrigationAction = irrigationAction;
            this.frostAlert = frostAlert;
            this.heatStressAlert = heatStressAlert;
            this.fungalRiskAlert = fungalRiskAlert;
            this.summary = summary;
        }

        @Override
        public String toString() {
            return summary;
        }
    }

    /**
     * Core rule-based advisory generator. This is intentionally simple
     * (threshold + rule based) so it runs in O(1) and is cheap enough to be
     * modeled as a low-MIPS fog-level operator in the simulation, which is
     * the whole point of doing this at the edge instead of the cloud.
     */
    public static Advisory evaluate(SensorReading r) {
        boolean frost = r.temperatureC <= FROST_RISK_TEMP;
        boolean heat = r.temperatureC >= HEAT_STRESS_TEMP;
        boolean fungal = r.humidityPct >= FUNGAL_RISK_HUMIDITY && r.temperatureC >= 18 && r.temperatureC <= 30;

        IrrigationAction action;
        if (r.rainfallMm >= HEAVY_RAIN_MM || r.soilMoisturePct >= SOIL_MOISTURE_SATURATED_THRESHOLD) {
            action = IrrigationAction.SKIP_RAIN_EXPECTED;
        } else if (r.soilMoisturePct < SOIL_MOISTURE_DRY_THRESHOLD && heat) {
            action = IrrigationAction.FULL_IRRIGATION;
        } else if (r.soilMoisturePct < SOIL_MOISTURE_DRY_THRESHOLD) {
            action = IrrigationAction.LIGHT_IRRIGATION;
        } else {
            action = IrrigationAction.HOLD;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Irrigation=").append(action);
        if (frost) sb.append(" | FROST ALERT");
        if (heat) sb.append(" | HEAT-STRESS ALERT");
        if (fungal) sb.append(" | FUNGAL-DISEASE RISK");

        return new Advisory(action, frost, heat, fungal, sb.toString());
    }

    /** Small, dependency-free demo so the logic can be sanity-checked in isolation. */
    public static void main(String[] args) {
        SensorReading[] samples = new SensorReading[] {
            new SensorReading(18.5, 39.0, 40.0, 0.0),   // dry + hot  -> full irrigation + heat alert
            new SensorReading(45.0, 25.0, 90.0, 0.0),   // humid + mild temp -> fungal risk
            new SensorReading(30.0, 22.0, 55.0, 25.0),  // heavy rain -> skip irrigation
            new SensorReading(60.0, 2.0, 50.0, 0.0),    // frost risk
            new SensorReading(50.0, 24.0, 50.0, 0.0)    // all nominal -> hold
        };
        System.out.println("AgroSence AdvisoryLogic standalone demo");
        System.out.println("----------------------------------------");
        for (SensorReading r : samples) {
            Advisory a = evaluate(r);
            System.out.println(r + "  =>  " + a);
        }
    }
}
