# Performance and acceptance

Profile the actual target chain and version before choosing an optimization.
Record CPU/render work, actual frame intervals, tick cost, allocations, texture
residency and mesh/cache behavior as applicable. PNG archive size does not measure
decoded texture memory; a CPU microbenchmark does not establish client FPS.

## World-scale optimizations

- Separate a visible object's owner from cells that only carry collision or
  selection fragments. Avoid unnecessary renderer dispatch for collision cells.
- Cache immutable geometry, authored transforms and draw preparation. Bound caches
  and release frame-owned references; invalidate on state, chunk, level and reload
  changes. An evicted weak dependency needs an atomic fallback before submission.
- Cull using the current camera and bounds covering the full visual object.
  Test exact distance boundaries and objects crossing section/chunk boundaries.
- Group compatible material/geometry work and instance repeated objects. Preserve
  blend, cutout, fog, overlay and emissive semantics and restore rendering state.
- Test dynamic doors and growing plants on the optimized path or a declared
  reference path. Keep a compatible backend for unsupported GPU features.
- Optimize optional-mod interaction only after inspecting the installed version.
  Preserve ordinary entities and world maps; measure that the adapter is exercised.

## Matched experiment

Use the same initial world, source/resources, pack/configs, cameras, resolution,
JVM limits and FPS policy. Confirm all expected objects are loaded before timing.
Keep every qualification frame. Run diagnostic profiling separately if attaching
it perturbs compilation or GC. Record host competition and collection activity.
Repeat and counterbalance runs when estimating stability or per-stage effects.
Different historical runs are not a controlled optimization ablation.

Report work-time statistics separately from observed FPS under a cap. State
warm/cold cache and compilation conditions. Compare geometry/texture content,
loaded identities and visual controls so missing objects cannot create a speedup.

## Evidence layers

1. Input/adapter/source-oracle identity and coverage.
2. Pure schema, transform, inventory and deterministic-package checks.
3. Native server interactions and cleanup/persistence.
4. Actual packaged-client rendering, network state and movement.
5. Matched performance and complete post-exit source/resource/config audits.

Bind accepted results to final code and resource hashes. Preserve the first
causal failure and distinguish partial stages from complete acceptance. Source
fidelity, visual quality, target functionality and performance each need their
own observations. Retain enough compact evidence before retiring temporary game
copies under the user's storage/recovery policy.
