# Background-generation shutdown verification

The probe enables production background generation in a disposable copy of a supplied world. It uses the default generation radius, concurrency and MSPT ceiling, a 960×540 window, four-chunk vanilla render distance and 320-block LoD radius. Audio is muted in the fixture. A cold run requests exit as soon as it observes pending generation; a warm run first requires at least 128 chunks handed to ingestion. The observation is made on the server thread. Generation continues until the normal shutdown hook runs, so these are pre-exit observations, not exact counts at the instant of shutdown.

Prepare the opt-in probe and launch metadata with Java 21:

```powershell
.\gradlew.bat writeDistantGenSmokeLaunch --offline --console=plain -I scripts/distant-gen-smoke.gradle
python scripts/distant-gen-smoke/run.py --phase cold --force-unload-retry --exit-mode disconnect
python scripts/distant-gen-smoke/run.py --phase warm
python scripts/distant-gen-smoke/run.py --shaders --phase cold
python scripts/distant-gen-smoke/run.py --shaders --phase warm
```

The default exit path returns to the title screen before closing. Use `--exit-mode direct` to call `Minecraft.stop()` while still in the world, `--world D:/path/to/world` for another source, and `--timeout 420` on slower machines. The default source is the existing local verification world under `.agents/work/verification/server/smoke-world`. The source is never modified. Each invocation creates a new timestamped directory in `build/reports/distant-gen-shutdown`; reports from failed runs remain there. Iris runs require `run/client/shaderpacks/ComplementaryReimagined_r5.8.1.zip` and use the pack defaults.

`--force-unload-retry` separately verifies the starvation condition: a synthetic `ChunkHolder` has a completed save future and one outstanding generation reference. The probe invokes the real `ChunkMap.scheduleUnload` and `processUnloads` methods with an unlimited time supplier. The first drain must yield, keep the pending unload, and preserve the generation reference. After the probe releases its reference, a second drain must complete the unload. The holder has no chunk payload and does not write terrain. Without the fix this stays in the same `CompletableFuture.postComplete` / `ChunkMap.processUnloads` stack observed in the previous game shutdown failures. This controlled injection establishes the failure mechanism; ordinary cold/warm runs exercise actual background generation separately.

A pass requires a successful process exit, the expected Sodium or Iris pipeline, pending generation observed before exit, empty generator ownership at server stopping, completed server/renderer/instance shutdown, and no ERROR log entries. Forced termination and missing completion evidence fail. `result.json` records checks, timings, source-world and compiled-class hashes; `complete.json` contains server-thread observations. On timeout, `timeout-threads.txt` captures the process before termination.

The direct-close Sodium and Iris runs, and an Iris disconnect, completed saving, released all generation tickets and exited with code 0, but logged `ClosedChannelException` while closing the local connection. They remain failed under the strict log check; the probe does not suppress those errors. A test-only outbound handler records the failed packet type, custom payload ID when present, exception and time relative to the close request in `complete.json`. The Iris trace first failed vanilla block/entity updates after the close request; later failures also included a Voxy far-entity payload. These observations are separate from the reproduced unload starvation.

For an independent connection control, prepare the launch metadata above, then run:

```powershell
python scripts/distant-gen-smoke/run.py --without-voxy --shaders --exit-mode direct --timeout 240
python scripts/distant-gen-smoke/run.py --without-voxy --shaders --exit-mode direct --force-local-close-race --timeout 240
```

`--without-voxy` selects a separate `disconnect_control` mod. Its classpath and mod folders exclude the Voxy classes/resources, and it asserts that Voxy is absent from the loaded mod list. It uses a fresh copy of the same world, runs 180 client world ticks, then saves and exits. `--phase` has no effect on this control. Control results have a `-control` directory suffix and record control-class hashes, not Voxy-class hashes.

`--force-local-close-race` is valid only with `--without-voxy`. A bounded event-loop gate queues an ordinary `ClientboundSetTimePacket`, closes the client connection, then releases the server event loop. This schedules an already-queued vanilla packet across peer close without changing `Connection` or its error handling. The gate is released in `finally` and has a ten-second timeout. `forcedLocalCloseRace` and `packetFailures` record the injection and observed failures. ERRORs still fail the strict runner, including in this diagnostic mode; a reproduced race is not a clean-exit acceptance pass. A forced reproduction establishes that the mechanism can occur without Voxy, not its natural incidence or Voxy's effect on timing.

The ordinary Iris control `run-20261107-171913-iris-warm-control` passed without errors. The forced control `run-20261107-172848-iris-warm-control` reproduced `ClosedChannelException` for `ClientboundSetTimePacket` in `LocalChannel.doWrite`, logged a connection ERROR and therefore remains failed. Both saved all dimensions and exited with code 0. Full evidence and limits are recorded in [the shutdown diagnosis](../../docs/distant-generation-shutdown.md).

An unmuted Iris disconnect also completed lifecycle checks but logged an OpenAL `Stop: Invalid name parameter` error. Its failed record is retained; subsequent fixtures mute audio to focus on terrain lifecycle. This does not establish an audio shutdown fix.

Do not build while a probe is running in the same checkout. After probing, run a normal build to remove opt-in classes from the production output:

```powershell
.\gradlew.bat build --offline --console=plain
```
