# AgroSence: Smart Agri-Climate Monitoring and Advisory System
### An iFogSim Fog-Computing Simulation Project

AgroSence models a precision-agriculture IoT deployment on a 3-tier fog
topology (Cloud → Regional Fog Gateway → Field Edge Node → Sensors/Actuators)
using **iFogSim** (built on CloudSim) to simulate and compare how a
smallholder-farm advisory system behaves depending on *where* its decision
logic runs.

---

## 1. Project compliance notes (per assignment requirements)

| Requirement | How this project satisfies it |
|---|---|
| **iFogSim is mandatory** | The simulation (`org.agrosence.AgroSenceMain`) is built entirely on the real `Cloudslab/iFogSim` source (fetched from GitHub), using its `FogDevice`, `Sensor`, `Actuator`, `Application`, `AppEdge`, `AppModule`, `Controller`, `ModulePlacementEdgewards`/`ModulePlacementMapping` classes. |
| **No Maven, no Ant** | The project is built and run with **plain `javac`/`java`** commands only (see `run/build.sh`, `run/run.sh`). There is no `pom.xml`, no `build.xml`, no Gradle file anywhere in this project. |
| **Free-Tier Deployment** | The default deployment strategy (`FOG_FREE_TIER`) pushes the latency-critical advisory logic down onto the **already-owned, free** field/gateway hardware via iFogSim's Edge-ward placement policy, instead of paying for cloud compute/storage for every irrigation decision. Only a small, infrequent analytics digest is ever sent to the cloud tier. A `CLOUD_CENTRIC` mode is included purely as a **comparison baseline** to quantify the savings (see §5 results below) — it is not how the system is meant to be deployed. "Free-tier" also describes *how this project is built*: every dependency is a vendored, open-source `.jar` (no paid service, no license, no package-manager/network access required to build or run it). |

---

## 2. What the simulated system represents

```
                         ┌───────────────────────────┐
                         │   Cloud (agri datacenter)  │   level 0
                         │   "cloud_analytics" module │
                         └─────────────┬───────────────┘
                     (WAN/4G backhaul, 80ms latency)
             ┌───────────────┬─────────┴─────────┬───────────────┐
      ┌──────┴──────┐ ┌──────┴──────┐     ┌──────┴──────┐
      │ Zone Gateway│ │ Zone Gateway│ ... │ Zone Gateway│         level 1
      │ (village /  │ │             │     │             │
      │ cooperative)│ │             │     │             │
      └──────┬──────┘ └─────────────┘     └─────────────┘
       (LoRa/WiFi, 6ms latency)
   ┌─────────┼─────────┬─────────┐
┌──┴──┐   ┌──┴──┐    ┌──┴──┐  ┌──┴──┐
│Field│   │Field│    │Field│  │Field│                               level 2
│Edge │   │Edge │    │Edge │  │Edge │   each Field Edge Node runs:
│Node │   │Node │    │Node │  │Node │     - field_gateway_client (ingest)
└──┬──┘   └─────┘    └─────┘  └─────┘     - crop_advisory_engine (free-tier mode)
   │
   ├── SoilMoistureSensor  ──▶ SOIL_MOISTURE
   ├── ClimateSensor       ──▶ CLIMATE_READING (temperature + humidity)
   ├── RainGaugeSensor     ──▶ RAINFALL_READING
   ├── IrrigationValve     ◀── VALVE_COMMAND        (actuator)
   └── FarmerAlertDisplay  ◀── ALERT_DISPLAY        (actuator)
```

Default sizing: **3 zones × 4 fields = 12 Field Edge Nodes**, each with
3 sensors + 2 actuators (configurable via `NUM_ZONES` / `NUM_FIELDS_PER_ZONE`
constants at the top of `AgroSenceMain.java`).

### Application modules (the "software" on top of the topology)
- **`field_gateway_client`** — runs on every Field Edge Node. Ingests raw
  sensor tuples and forwards per-channel readings (`SOIL_DATA`,
  `CLIMATE_DATA`, `RAIN_DATA`) upward; receives decisions/alerts and drives
  the two actuators.
- **`crop_advisory_engine`** — the decision-making module. Placed at the
  *field/zone* level in `FOG_FREE_TIER` mode (edge-ward placement), or
  forced onto the cloud in `CLOUD_CENTRIC` mode for comparison. Computes
  irrigation actions and farmer alerts. The actual rule-based logic it
  conceptually executes is implemented, standalone and unit-testable, in
  `org.agrosence.advisory.AdvisoryLogic` (see §4).
- **`cloud_analytics`** — always runs in the cloud. Receives a periodic,
  low-frequency climate digest (every 2s of sim time) for long-term
  trend storage/reporting, and periodically pushes back a regional
  climate/seasonal summary (every 5s of sim time).

---

## 3. Project layout

```
AgroSence-iFogSim/
├── README.md                     <- you are here
├── lib/                          <- vendored jars (iFogSim/CloudSim core + deps) -- no internet needed
│   ├── ifogsim-core.jar          <- compiled from Cloudslab/iFogSim source (org.fog.* + org.cloudbus.cloudsim.*)
│   ├── commons-math3-3.5.jar
│   ├── guava-18.0.jar
│   ├── json-simple-1.1.1.jar
│   ├── commons-io-2.17.0.jar
│   ├── commons-lang3-3.17.0.jar
│   ├── commons-net-3.11.1.jar
│   ├── opencsv-5.9.jar
│   └── uncommons-maths-1.2.3.jar
├── src/org/agrosence/
│   ├── AgroSenceMain.java        <- fog topology + application DAG + simulation entry point
│   ├── advisory/AdvisoryLogic.java   <- standalone rule-based advisory engine (+ demo main())
│   └── utils/ResultsLogger.java      <- CSV KPI writer (extension point, see note in AgroSenceMain)
├── run/
│   ├── build.sh                  <- plain javac build (no Maven/Ant)
│   ├── run.sh                    <- run the simulation (fog | cloud | advisory)
│   └── parse_results.sh          <- summarizes saved run logs into results/summary.csv
├── results/                       <- run logs + summary.csv land here (empty in the distributed zip)
├── docs/
│   └── NOTES.md                   <- design notes / troubleshooting log
└── bin/                            <- created by build.sh (compiled .class files)
```

---

## 4. Building and running

**Requirements:** a JDK (8+ ; tested on JDK 21), bash. Nothing else —
no Maven, no Ant, no internet access needed at build or run time (everything
required is already inside `lib/`).

```bash
cd AgroSence-iFogSim
chmod +x run/*.sh        # first time only

./run/build.sh           # compiles src/ -> bin/ with plain javac

./run/run.sh             # runs the FREE-TIER (fog-only / edge-ward) deployment
./run/run.sh cloud       # runs the CLOUD-CENTRIC deployment, for comparison
./run/run.sh advisory    # runs the standalone AdvisoryLogic rule-engine demo (no simulation)

./run/parse_results.sh   # turns the saved logs in results/ into results/summary.csv
```

Each `run.sh` invocation also saves the full console report to
`results/agrosence_<mode>_<timestamp>.log`; `parse_results.sh` is additive,
so you can run `fog` and `cloud` a few times and compare all of them at once
in `results/summary.csv`.

> **Why there's no code "after" `CloudSim.startSimulation()` that visibly
> runs:** stock iFogSim's `org.fog.placement.Controller` prints its own
> final KPI report (execution time, per-loop latency, per-device energy,
> execution cost, network usage) and calls `System.exit(0)` itself when it
> receives the `STOP_SIMULATION` event — this happens *inside*
> `CloudSim.startSimulation()`. `AgroSenceMain` documents this in place and
> keeps a `printAndSaveResults()`/`ResultsLogger` extension point for anyone
> who swaps in a custom, non-exiting Controller later.

## 5. Sample results (Free-Tier vs. Cloud-Centric)

A representative run with the default topology (3 zones × 4 fields):

| Metric | `FOG_FREE_TIER` (default) | `CLOUD_CENTRIC` (baseline) |
|---|---:|---:|
| Execution cost | **≈ 94,335** | ≈ 833,652 |
| Total network usage | **≈ 34,428** | ≈ 339,134 |

Numbers come straight from iFogSim's own cost/energy/network models
(`FogDevice.getTotalCost()`, `NetworkUsageMonitor`). The ~9× lower cost and
~10× lower backhaul network usage for `FOG_FREE_TIER` is the quantitative
case for why this system is designed to run its advisory logic at the edge
rather than the cloud — exactly the "free-tier" deployment goal.

## 6. Extending the project

- Change `NUM_ZONES` / `NUM_FIELDS_PER_ZONE` in `AgroSenceMain.java` to
  scale the topology up or down.
- Tune `AdvisoryLogic`'s thresholds (soil moisture, frost/heat temps,
  fungal-risk humidity, heavy-rain mm) to match a specific crop/region.
- Feed `AdvisoryLogic.evaluate(...)` with real sensor-log CSVs (e.g. from an
  open agro-meteorological dataset) for an offline accuracy study, separate
  from the fog-latency/cost simulation.
