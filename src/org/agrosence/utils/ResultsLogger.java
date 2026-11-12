package org.agrosence.utils;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

/**
 * AgroSence :: ResultsLogger
 * -----------------------------------------------------------------------
 * Tiny, dependency-free helper that writes the end-of-simulation KPIs
 * (collected from org.fog.utils.TimeKeeper / energy & network maps) to a
 * CSV file under results/, so the run can be compared across module
 * placement strategies (Cloud-only vs. Edge-ward / Free-Tier) without
 * needing any external library (no Maven/Gradle reporting plugin).
 */
public final class ResultsLogger {

    private ResultsLogger() { }

    public static void writeSummary(String filePath,
                                     String scenarioName,
                                     double executionTimeMs,
                                     Map<String, Double> loopLatencies,
                                     Map<String, Double> deviceEnergy,
                                     double totalNetworkUsage,
                                     double totalCost) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(filePath, true))) {
            boolean writeHeader = new java.io.File(filePath).length() == 0;
            if (writeHeader) {
                pw.println("scenario,metric,key,value");
            }
            pw.printf("%s,ExecutionTime_ms,-,%.4f%n", scenarioName, executionTimeMs);
            if (loopLatencies != null) {
                for (Map.Entry<String, Double> e : loopLatencies.entrySet()) {
                    pw.printf("%s,LoopLatency_ms,%s,%.4f%n", scenarioName, e.getKey(), e.getValue());
                }
            }
            if (deviceEnergy != null) {
                for (Map.Entry<String, Double> e : deviceEnergy.entrySet()) {
                    pw.printf("%s,EnergyConsumption_J,%s,%.4f%n", scenarioName, e.getKey(), e.getValue());
                }
            }
            pw.printf("%s,NetworkUsage_total,-,%.4f%n", scenarioName, totalNetworkUsage);
            pw.printf("%s,ExecutionCost_total,-,%.4f%n", scenarioName, totalCost);
        } catch (IOException ex) {
            System.err.println("[ResultsLogger] Could not write results file: " + ex.getMessage());
        }
    }
}
