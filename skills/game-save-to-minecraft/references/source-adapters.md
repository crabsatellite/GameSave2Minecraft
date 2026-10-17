# Source investigation and adapter authoring

Use the source client and save as a joint input. A save often contains instance
IDs and state while the client provides object definitions, art and behavior.
Pin both identities, including enabled content mods. Keep the original inputs
read-only and follow the existing recovery policy for any isolated runtime copy.

## Select evidence by engine

| Observation | Useful route | What it establishes |
| --- | --- | --- |
| Managed assemblies | REA managed inspection or an available decompiler | Serialization/component code and API types |
| IL2CPP game | Matching native binary and metadata, focused interop generation | Candidate field layouts and native bindings |
| Unreal game | Save/container analysis, matching package metadata and asset tooling | Saved properties and asset references |
| Open source/mod API | Repository and documented APIs | Direct source contracts; a decompiler may be unnecessary |
| Proprietary native engine | REA native inspection and focused data/runtime probes | Evidence for the specific artifact and observed behavior |

A client's layout is not automatically its dedicated server's layout. Marketing
version equality does not establish binary identity. A successful plugin compile
establishes API compatibility with its references; actual load/export still needs
runtime evidence. Preserve failed reads in the export and fail its acceptance.

## Minimal adapter interface

`SourceInputs` names a save, optional install and version. A `SourceAdapter`
implements `probe`, `export_scene` and `verify_source` (see the package's
`adapters.py`). The original `examples/demo_source.py` shows a complete small
adapter. For a new game, the agent writes an implementation after recovering its
format; it does not substitute the demo decoder for an unknown source.

Probe should report tool/format/version identities. Export resolves definitions,
instances, parent links, transforms, variant/material selections, inventories and
interaction states into the shared contract. Source verification compares those
results with independent saved data, authoritative APIs or a runtime readback.

Use the source engine to load its own serialization when direct binary decoding
is fragile. Wait for actual scene streaming and complete object enumeration.
Prefer a one-shot exporter with a bounded lifecycle. Progress heartbeats and a
plugin binary are insufficient evidence of a completed export.

## Binding pitfalls

- A composite furnishing may use several meshes, parent matrices and materials.
  The largest mesh is not necessarily the whole object.
- Source material alpha, texture cutout alpha and shader blend mode are distinct.
  Check representative foliage, opaque surfaces and transparent/emissive parts.
- Source collider, visual geometry and Minecraft traversal requirements may differ.
  Preserve interaction clearance and avoid making cloth/foliage solid by accident.
- Keep dormant and mature plant definitions, door pivots, selected dyes and flags.
- Record source inventory quantities independently of Minecraft stack counts.
- Keep unresolved component semantics visible for follow-up. A general adapter
  can extend the rule vocabulary when a new mechanic requires it.
