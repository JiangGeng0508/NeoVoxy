# SeedGen client verification

Run from the repository root with Java 21 and a working OpenGL driver:

```powershell
.\gradlew.bat --offline -I scripts/seedgen-smoke.gradle writeSeedGenSmokeLaunch
python scripts/seedgen-smoke/run.py --seedgen-jar 'path/to/Voxy SeedGen 0.3.0 1.21.1 NeoForge.jar'
```

The script creates an isolated client configuration and a disposable flat world under
`build/reports/seedgen-smoke`. It loads the supplied, unmodified SeedGen JAR and Sodium,
starts NeoVoxy's renderer, and exercises SeedGen's actual `LadderWriter` at L1, L4, L5 and
L15. Assertions check solid and air persistence, full-octant coverage, the save backpressure
path, fractional render distances and the generation dimension gate. The client closes the
world normally on success. Logs and `complete.pass` remain in the run directory.

This checks binary linkage and data publication. It does not measure prediction accuracy,
throughput, or the visual correctness of extended-distance rings. Probe classes are only
compiled when the init script is used and are excluded from ordinary builds.

After removing NeoVoxy's native seed preview on 2026-10-29, the external SeedGen 0.3.0
probe passed again at `build/reports/seedgen-smoke/run-20261029-213729` (exit code 0).
The direct section-write API, projection constant and required ABI adapters remain;
seed sampling itself is provided solely by the external mod.
