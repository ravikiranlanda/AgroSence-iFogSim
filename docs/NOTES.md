# Design / build notes

- `lib/ifogsim-core.jar` was built by cloning `https://github.com/Cloudslab/iFogSim`
  and compiling its `org/fog/**` and `org/cloudbus/cloudsim/**` source with plain
  `javac` (no Maven/Ant), against the third-party jars that already ship in that
  repo's own `jars/` folder (also vendored here in `lib/`). The GUI packages
  (`org/fog/gui/**`), the GeoIP2-dependent geolocation classes, and the
  `EX`/`web` CloudSim extensions were excluded from the compiled jar — none of
  them are needed for a programmatic (non-GUI) fog simulation, and GeoIP2's API
  in this repo snapshot doesn't match the vendored `geoip2` jar version.

- `org.fog.placement.ModulePlacementEdgewards` (the "edge-ward" placement
  policy used by the `FOG_FREE_TIER` deployment mode) identifies the apex of
  the topology by the **literal device name `"cloud"`** — see
  `getLeafToRootPaths()` in that class. `AgroSenceMain.CLOUD_DEVICE_NAME` must
  stay `"cloud"` for that reason.

- Application-DAG design rule learned the hard way: every *destination* tuple
  type in `addTupleMapping(...)` must have exactly **one** producing (source
  tuple type, module) pair. `ModulePlacementEdgewards`'s rate-propagation step
  (`placeModulesInPath`) iteratively recomputes each `AppEdge`'s tuple rate
  from its producers; if two different inputs both map to the same output
  tuple type, the computed rate for that edge can flip-flop between the two
  values forever (across `HashMap` resizes it never settles on a stable
  iteration order), and `mapModules()` spins forever before the simulation
  even starts. This is why AgroSence uses three separate upward tuple types
  (`SOIL_DATA`, `CLIMATE_DATA`, `RAIN_DATA`) instead of one merged
  "sensor snapshot" tuple.

- Periodic `AppEdge`s (the `CLIMATE_DIGEST` / `REGIONAL_CLIMATE_SUMMARY`
  edges between `crop_advisory_engine` and `cloud_analytics`) are
  timer-driven and get a fixed rate from their periodicity. They must **not**
  also appear as the destination of an `addTupleMapping(...)` call, or the
  same rate-propagation step above starts fighting itself over that edge's
  rate.

- `org.fog.placement.Controller` prints its own final KPI report and calls
  `System.exit(0)` directly inside its handling of `FogEvents.STOP_SIMULATION`
  (itself triggered from inside `CloudSim.startSimulation()`), so application
  code written after `CloudSim.startSimulation()`/`stopSimulation()` in
  `main()` does not actually execute in practice. See the comment above that
  code in `AgroSenceMain.java` and the "why" note in `README.md` §4.
