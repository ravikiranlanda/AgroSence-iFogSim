package org.agrosence;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.sdn.overbooking.BwProvisionerOverbooking;
import org.cloudbus.cloudsim.sdn.overbooking.PeProvisionerOverbooking;

import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.application.Application;
import org.fog.application.selectivity.FractionalSelectivity;
import org.fog.entities.Actuator;
import org.fog.entities.FogBroker;
import org.fog.entities.FogDevice;
import org.fog.entities.FogDeviceCharacteristics;
import org.fog.entities.Sensor;
import org.fog.entities.Tuple;
import org.fog.placement.Controller;
import org.fog.placement.ModuleMapping;
import org.fog.placement.ModulePlacementEdgewards;
import org.fog.placement.ModulePlacementMapping;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;
import org.fog.utils.NetworkUsageMonitor;
import org.fog.utils.TimeKeeper;
import org.fog.utils.distribution.DeterministicDistribution;

import org.agrosence.utils.ResultsLogger;

/**
 * ===========================================================================
 *  AgroSence : Smart Agri-Climate Monitoring and Advisory System
 * ===========================================================================
 *
 * A fog-computing simulation built on iFogSim that models a precision-
 * agriculture deployment:
 *
 *   Cloud (agri-cloud analytics & long-term storage)
 *      |
 *   Regional Fog Gateway   (one per farm "Zone" -- e.g. a village cluster)
 *      |
 *   Field Edge Node        (one per physical "Field" inside a zone)
 *      |-- SoilMoistureSensor
 *      |-- ClimateSensor (temperature + humidity)
 *      |-- RainGaugeSensor
 *      |-- IrrigationValve (actuator)
 *      |-- FarmerAlertDisplay (actuator)
 *
 * DEPLOYMENT MODE ("Free-Tier Deployment")
 * -----------------------------------------------------------------------
 * The whole point of using fog computing for agri-advisory is to avoid
 * recurring cloud compute/storage cost for latency-critical decisions
 * (irrigation, frost/heat alerts) that smallholder/free-tier deployments
 * cannot afford to pay cloud bills for. This project therefore defaults to
 * FOG-ONLY ("free-tier") placement: the latency-sensitive advisory logic
 * (crop_advisory_engine) is pushed down to the free, already-owned Field
 * Edge / Fog Gateway hardware via Edge-ward module placement, and only a
 * cheap, infrequent analytics digest (cloud_analytics) is ever sent
 * up to the cloud tier. Set DEPLOY_MODE = CLOUD below to compare against a
 * (costlier) cloud-centric placement for the report/graphs.
 *
 * Build / run: no Maven, no Ant -- plain javac/java (see run/build.sh).
 */
public class AgroSenceMain {

    /** Deployment strategy toggle used for the comparison study. */
    public enum DeployMode { FOG_FREE_TIER, CLOUD_CENTRIC }
    static DeployMode DEPLOY_MODE = DeployMode.FOG_FREE_TIER;

    static List<FogDevice> fogDevices = new ArrayList<FogDevice>();
    static List<Sensor> sensors = new ArrayList<Sensor>();
    static List<Actuator> actuators = new ArrayList<Actuator>();

    // ---- Topology sizing (kept small so the free-tier/laptop-class run is fast) ----
    static int NUM_ZONES = 3;          // regional fog gateways
    static int NUM_FIELDS_PER_ZONE = 4; // field edge nodes per zone

    static double SENSOR_TRANSMISSION_INTERVAL = 5; // ms (simulation time-scale)

    public static void main(String[] args) {

        Log.printLine("Starting AgroSence (Smart Agri-Climate Monitoring and Advisory System)...");

        if (args.length > 0 && args[0].equalsIgnoreCase("cloud")) {
            DEPLOY_MODE = DeployMode.CLOUD_CENTRIC;
        }
        Log.printLine("Deployment mode: " + DEPLOY_MODE);

        try {
            Log.disable(); // keep console clean; detailed KPIs are printed/logged explicitly below

            int numUser = 1;
            Calendar calendar = Calendar.getInstance();
            boolean traceFlag = false;

            CloudSim.init(numUser, calendar, traceFlag);

            String appId = "agrosence";

            FogBroker broker = new FogBroker("broker");

            Application application = createApplication(appId, broker.getId());
            application.setUserId(broker.getId());

            createFogDevices(broker.getId(), appId);

            ModuleMapping moduleMapping = ModuleMapping.createModuleMapping();

            if (DEPLOY_MODE == DeployMode.CLOUD_CENTRIC) {
                // Cloud-centric: every farm's advisory engine AND analytics run in the cloud.
                moduleMapping.addModuleToDevice("crop_advisory_engine", CLOUD_DEVICE_NAME);
                moduleMapping.addModuleToDevice("cloud_analytics", CLOUD_DEVICE_NAME);
                for (FogDevice device : fogDevices) {
                    if (device.getName().startsWith("field-")) {
                        moduleMapping.addModuleToDevice("field_gateway_client", device.getName());
                    }
                }
            } else {
                // FREE-TIER / fog-only: pin only the cheap, infrequent long-term analytics
                // digest to the cloud; everything latency-critical stays on owned hardware
                // and is resolved by the Edge-ward placement policy below.
                moduleMapping.addModuleToDevice("cloud_analytics", CLOUD_DEVICE_NAME);
            }

            Controller controller = new Controller("master-controller", fogDevices, sensors, actuators);

            controller.submitApplication(
                    application,
                    0,
                    (DEPLOY_MODE == DeployMode.CLOUD_CENTRIC)
                            ? new ModulePlacementMapping(fogDevices, application, moduleMapping)
                            : new ModulePlacementEdgewards(fogDevices, sensors, actuators, application, moduleMapping));

            TimeKeeper.getInstance().setSimulationStartTime(Calendar.getInstance().getTimeInMillis());

            long wallClockStart = System.currentTimeMillis();
            // NOTE: org.fog.placement.Controller handles the FogEvents.STOP_SIMULATION event by
            // itself printing the full KPI report (execution time, loop latencies, per-device
            // energy, cost, network usage) AND calling System.exit(0) -- this is how stock
            // iFogSim's Controller is implemented. That means control never actually returns to
            // this line in practice; CloudSim.startSimulation() runs the whole simulation AND
            // the shutdown report before the process exits. The code below is kept as a
            // defensive/extension point (e.g. if you swap in a custom Controller that does not
            // exit the JVM) and mirrors what Controller already prints, this time also written
            // to results/agrosence_results.csv. For normal runs, use run/run.sh, which tees the
            // Controller's own console report to results/agrosence_<mode>_<timestamp>.log, and
            // run/parse_results.sh to turn those logs into a comparable CSV.
            CloudSim.startSimulation();
            CloudSim.stopSimulation();
            long wallClockEnd = System.currentTimeMillis();

            printAndSaveResults(appId, wallClockEnd - wallClockStart);

            Log.printLine("AgroSence simulation finished!");
        } catch (Exception e) {
            e.printStackTrace();
            Log.printLine("AgroSence simulation encountered an error.");
        }
    }

    // -----------------------------------------------------------------
    // Physical fog topology
    // -----------------------------------------------------------------

    /** NOTE: ModulePlacementEdgewards locates the apex device by the exact name "cloud" -- keep this literal. */
    static final String CLOUD_DEVICE_NAME = "cloud";

    private static void createFogDevices(int userId, String appId) {
        FogDevice cloud = createFogDevice(CLOUD_DEVICE_NAME, 44800, 40000, 100, 10000, 0, 0.01, 16 * 103, 16 * 83.25);
        cloud.setParentId(-1);
        fogDevices.add(cloud);

        for (int z = 0; z < NUM_ZONES; z++) {
            addZoneGateway(z, userId, appId, cloud.getId());
        }
    }

    /** One regional fog gateway per farming zone (village / cooperative cluster). */
    private static FogDevice addZoneGateway(int zoneIdx, int userId, String appId, int cloudId) {
        FogDevice gateway = createFogDevice("zone-gateway-" + zoneIdx, 4000, 4000, 10000, 10000, 1, 0.0, 107.339, 83.4333);
        gateway.setParentId(cloudId);
        gateway.setUplinkLatency(80); // zone-gateway <-> cloud WAN/4G backhaul latency
        fogDevices.add(gateway);

        for (int f = 0; f < NUM_FIELDS_PER_ZONE; f++) {
            FogDevice field = addFieldEdgeNode(zoneIdx, f, userId, appId, gateway.getId());
            fogDevices.add(field);
        }
        return gateway;
    }

    /** One low-power edge node per physical field, with its sensors and actuators attached. */
    private static FogDevice addFieldEdgeNode(int zoneIdx, int fieldIdx, int userId, String appId, int parentId) {
        String fieldName = "field-" + zoneIdx + "-" + fieldIdx;
        FogDevice field = createFogDevice(fieldName, 1200, 1024, 10000, 270, 2, 0.0, 87.53, 82.44);
        field.setParentId(parentId);
        field.setUplinkLatency(6); // field-edge-node <-> zone gateway (LoRa/WiFi) latency

        Sensor soilMoisture = new Sensor("soil-" + fieldName, "SOIL_MOISTURE", userId, appId,
                new DeterministicDistribution(SENSOR_TRANSMISSION_INTERVAL));
        Sensor climate = new Sensor("climate-" + fieldName, "CLIMATE_READING", userId, appId,
                new DeterministicDistribution(SENSOR_TRANSMISSION_INTERVAL));
        Sensor rainGauge = new Sensor("rain-" + fieldName, "RAINFALL_READING", userId, appId,
                new DeterministicDistribution(SENSOR_TRANSMISSION_INTERVAL * 2));

        Actuator irrigationValve = new Actuator("valve-" + fieldName, userId, appId, "IRRIGATION_VALVE");
        Actuator alertDisplay = new Actuator("alert-" + fieldName, userId, appId, "FARMER_ALERT_DISPLAY");

        sensors.add(soilMoisture);
        sensors.add(climate);
        sensors.add(rainGauge);
        actuators.add(irrigationValve);
        actuators.add(alertDisplay);

        soilMoisture.setGatewayDeviceId(field.getId());
        soilMoisture.setLatency(2.0);
        climate.setGatewayDeviceId(field.getId());
        climate.setLatency(2.0);
        rainGauge.setGatewayDeviceId(field.getId());
        rainGauge.setLatency(2.0);

        irrigationValve.setGatewayDeviceId(field.getId());
        irrigationValve.setLatency(1.0);
        alertDisplay.setGatewayDeviceId(field.getId());
        alertDisplay.setLatency(1.0);

        return field;
    }

    /**
     * Creates a vanilla fog device (cloud / zone gateway / field edge node share the
     * same CloudSim PowerHost wiring, only the resource sizing and hierarchy level differ).
     */
    private static FogDevice createFogDevice(String nodeName, long mips, int ram, long upBw, long downBw,
                                              int level, double ratePerMips, double busyPower, double idlePower) {
        List<Pe> peList = new ArrayList<Pe>();
        peList.add(new Pe(0, new PeProvisionerOverbooking(mips)));

        int hostId = FogUtils.generateEntityId();
        long storage = 1000000;
        int bw = 10000;

        PowerHost host = new PowerHost(
                hostId,
                new RamProvisionerSimple(ram),
                new BwProvisionerOverbooking(bw),
                storage,
                peList,
                new StreamOperatorScheduler(peList),
                new FogLinearPowerModel(busyPower, idlePower));

        List<Host> hostList = new ArrayList<Host>();
        hostList.add(host);

        String arch = "x86";
        String os = "Linux";
        String vmm = "Xen";
        double timeZone = 10.0;
        double cost = 3.0;
        double costPerMem = 0.05;
        double costPerStorage = 0.001;
        double costPerBw = 0.0;
        LinkedList<Storage> storageList = new LinkedList<Storage>();

        FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
                arch, os, vmm, host, timeZone, cost, costPerMem, costPerStorage, costPerBw);

        FogDevice fogDevice = null;
        try {
            fogDevice = new FogDevice(nodeName, characteristics,
                    new AppModuleAllocationPolicy(hostList), storageList, 10, upBw, downBw, 0, ratePerMips);
        } catch (Exception e) {
            e.printStackTrace();
        }
        fogDevice.setLevel(level);
        return fogDevice;
    }

    // -----------------------------------------------------------------
    // Application model (the "software" running on the fog topology)
    // -----------------------------------------------------------------

    @SuppressWarnings({"serial"})
    private static Application createApplication(String appId, int userId) {

        Application application = Application.createApplication(appId, userId);

        // ---- Modules (vertices of the application DAG) ----
        application.addAppModule("field_gateway_client", 10);   // runs on the Field Edge Node: ingest + light filtering
        application.addAppModule("crop_advisory_engine", 10);   // runs at Zone Gateway (free-tier) or Cloud: irrigation/alert decisions
        application.addAppModule("cloud_analytics", 10);        // runs in the Cloud: long-term climate trend storage/reporting

        // ---- Edges (data flow of the application DAG) ----

        // Sensors -> field_gateway_client (UP)
        application.addAppEdge("SOIL_MOISTURE", "field_gateway_client", 1000, 500, "SOIL_MOISTURE", Tuple.UP, AppEdge.SENSOR);
        application.addAppEdge("CLIMATE_READING", "field_gateway_client", 1000, 500, "CLIMATE_READING", Tuple.UP, AppEdge.SENSOR);
        application.addAppEdge("RAINFALL_READING", "field_gateway_client", 1000, 500, "RAINFALL_READING", Tuple.UP, AppEdge.SENSOR);

        // field_gateway_client -> crop_advisory_engine : per-sensor data, kept as separate tuple
        // types (rather than merged into one shared tuple) so each downstream edge has exactly
        // one upstream source feeding it -- this keeps the Edge-ward placement algorithm's
        // rate-propagation step convergent instead of oscillating between competing producers.
        application.addAppEdge("field_gateway_client", "crop_advisory_engine", 1000, 500, "SOIL_DATA", Tuple.UP, AppEdge.MODULE);
        application.addAppEdge("field_gateway_client", "crop_advisory_engine", 1000, 500, "CLIMATE_DATA", Tuple.UP, AppEdge.MODULE);
        application.addAppEdge("field_gateway_client", "crop_advisory_engine", 1000, 500, "RAIN_DATA", Tuple.UP, AppEdge.MODULE);

        // crop_advisory_engine -> field_gateway_client : irrigation decision (from soil data) + farmer alert (from climate data) (DOWN)
        application.addAppEdge("crop_advisory_engine", "field_gateway_client", 500, 100, "IRRIGATION_DECISION", Tuple.DOWN, AppEdge.MODULE);
        application.addAppEdge("crop_advisory_engine", "field_gateway_client", 500, 100, "ADVISORY_ALERT", Tuple.DOWN, AppEdge.MODULE);

        // field_gateway_client -> actuators (DOWN)
        application.addAppEdge("field_gateway_client", "IRRIGATION_VALVE", 500, 100, "VALVE_COMMAND", Tuple.DOWN, AppEdge.ACTUATOR);
        application.addAppEdge("field_gateway_client", "FARMER_ALERT_DISPLAY", 500, 100, "ALERT_DISPLAY", Tuple.DOWN, AppEdge.ACTUATOR);

        // crop_advisory_engine -> cloud_analytics : periodic (every 2000ms) climate digest (UP)
        application.addAppEdge("crop_advisory_engine", "cloud_analytics", 100, 1000, 2000, "CLIMATE_DIGEST", Tuple.UP, AppEdge.MODULE);

        // cloud_analytics -> crop_advisory_engine : periodic (every 5000ms) regional climate/seasonal summary (DOWN)
        application.addAppEdge("cloud_analytics", "crop_advisory_engine", 100, 500, 5000, "REGIONAL_CLIMATE_SUMMARY", Tuple.DOWN, AppEdge.MODULE);

        // ---- Tuple mappings (selectivity: how each module turns inputs into outputs) ----
        // Each (destination tuple type) below has exactly ONE producing (source tuple type),
        // which is required for the Edge-ward placement algorithm's rate propagation to converge.
        application.addTupleMapping("field_gateway_client", "SOIL_MOISTURE", "SOIL_DATA", new FractionalSelectivity(1.0));
        application.addTupleMapping("field_gateway_client", "CLIMATE_READING", "CLIMATE_DATA", new FractionalSelectivity(1.0));
        application.addTupleMapping("field_gateway_client", "RAINFALL_READING", "RAIN_DATA", new FractionalSelectivity(1.0));

        application.addTupleMapping("crop_advisory_engine", "SOIL_DATA", "IRRIGATION_DECISION", new FractionalSelectivity(1.0));
        application.addTupleMapping("crop_advisory_engine", "CLIMATE_DATA", "ADVISORY_ALERT", new FractionalSelectivity(0.3));

        application.addTupleMapping("field_gateway_client", "IRRIGATION_DECISION", "VALVE_COMMAND", new FractionalSelectivity(1.0));
        application.addTupleMapping("field_gateway_client", "ADVISORY_ALERT", "ALERT_DISPLAY", new FractionalSelectivity(1.0));

        // NOTE: CLIMATE_DIGEST and REGIONAL_CLIMATE_SUMMARY are periodic edges (fixed-rate,
        // timer-driven -- see addAppEdge calls above) and intentionally have NO tuple mapping:
        // periodic edges are self-triggered and must not also be fed by a selectivity rule,
        // otherwise the placement algorithm's rate solver never reaches a fixed point.

        // ---- Application loops to track end-to-end latency for ----
        final AppLoop irrigationLoop = new AppLoop(new ArrayList<String>() {{
            add("SOIL_MOISTURE"); add("field_gateway_client"); add("crop_advisory_engine");
            add("field_gateway_client"); add("IRRIGATION_VALVE");
        }});
        final AppLoop alertLoop = new AppLoop(new ArrayList<String>() {{
            add("CLIMATE_READING"); add("field_gateway_client"); add("crop_advisory_engine");
            add("field_gateway_client"); add("FARMER_ALERT_DISPLAY");
        }});

        List<AppLoop> loops = new ArrayList<AppLoop>() {{
            add(irrigationLoop);
            add(alertLoop);
        }};
        application.setLoops(loops);

        return application;
    }

    // -----------------------------------------------------------------
    // KPI reporting
    // -----------------------------------------------------------------

    private static void printAndSaveResults(String appId, long wallClockMs) {
        Map<Integer, Double> loopIdToAvg = TimeKeeper.getInstance().getLoopIdToCurrentAverage();
        Map<Integer, List<Integer>> loopIdToTupleIds = TimeKeeper.getInstance().getLoopIdToTupleIds();

        java.util.Map<String, Double> loopLatencies = new java.util.LinkedHashMap<String, Double>();
        System.out.println("=========================================");
        System.out.println(" AgroSence Simulation Results (" + DEPLOY_MODE + ")");
        System.out.println("=========================================");
        System.out.println("Wall-clock execution time   : " + wallClockMs + " ms");

        for (Integer loopId : loopIdToTupleIds.keySet()) {
            Double avg = loopIdToAvg.get(loopId);
            if (avg != null) {
                String key = "loop-" + loopId;
                loopLatencies.put(key, avg);
                System.out.printf("Average end-to-end latency for %s : %.4f ms%n", key, avg);
            }
        }

        double totalEnergy = 0.0;
        java.util.Map<String, Double> deviceEnergy = new java.util.LinkedHashMap<String, Double>();
        double totalCost = 0.0;
        for (FogDevice device : fogDevices) {
            double e = device.getEnergyConsumption();
            deviceEnergy.put(device.getName(), e);
            totalEnergy += e;
            totalCost += device.getTotalCost();
        }
        System.out.printf("Total energy consumption    : %.4f%n", totalEnergy);
        System.out.printf("Total execution cost        : %.4f%n", totalCost);

        double networkUsage = NetworkUsageMonitor.getNetworkUsage();
        System.out.printf("Total network usage         : %.4f%n", networkUsage);
        System.out.println("=========================================");

        ResultsLogger.writeSummary(
                "results/agrosence_results.csv",
                DEPLOY_MODE.toString(),
                wallClockMs,
                loopLatencies,
                deviceEnergy,
                networkUsage,
                totalCost);
    }
}
