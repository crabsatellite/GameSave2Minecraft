---
name: game-save-to-minecraft
description: Migrate a game's saved world and locally available client assets into a playable Minecraft world, using reverse engineering and agent-authored source adapters, reusable interaction rules, performance tuning, and source-bound acceptance. Use for game-save migration across engines; ordinary Minecraft version conversion, scan reconstruction, and display-only model import have different workflows.
---

# Game save to Minecraft

Deliver a Minecraft world whose objects retain their source identity and spatial
relationships and receive explicit target interactions and persistent state.
Minecraftization includes placement, selection, collision, interaction, editing,
and save/load behavior. Artistic stylization is selected with the user and recorded
separately. A new source game is an adapter-authoring task within this workflow;
the source game does not need a pre-existing converter.

## Establish the migration contract

Resolve the supplied save, matching client/mod versions, local asset carriers,
target Minecraft/loader and requested region/entities. Preserve the original
inputs. Record what must survive: layout, identity, appearances, inventory lots,
ownership and interaction states. Resolve missing information from supplied
artifacts before asking the user to reconstruct it from memory.

Read [source-adapters](references/source-adapters.md) for source investigation and
adapter implementation. Read [world-contract](references/world-contract.md) when
normalizing a recovered scene. Source asset availability and successful decoding
are observations to establish, not assumptions.

For a shipped binary or opaque package, use REA if available. Select its tool by
artifact type; managed, IL2CPP, native and archive targets need different routes.
Use authoritative source repositories directly when they establish the behavior.
REA supplies inspection evidence; select engine-specific asset decoders and a
source runtime oracle as needed. Keep the selected version, hashes, source
locations and unresolved findings attached to the adapter's observations.

## Recover a world, then translate its objects

1. Recover saved instances and state before selecting display meshes. Resolve
   object definitions, renderer/material references, parent transforms, selected
   variants, visibility conditions and growth/animation states. Enumerating loose
   meshes alone does not recover an assembled saved world.
2. Normalize units, axes, handedness, pivots and transform hierarchy explicitly.
   Preserve full affine transforms. Bake a transform when a target representation
   cannot express it, and check normals, winding and interaction anchors afterwards.
3. Reconcile the source inventory: every in-scope source instance and extracted
   asset receives an identity and an outcome. Keep exclusions, approximations and
   unresolved dependencies explicit. A silently missing object is a failed migration.
4. Map capabilities using [interaction-rules](references/interaction-rules.md).
   Introduce a new rule when existing ones cannot express the source mechanic.
   Describe the target policy for timing, recipes, yields and source quantities.
5. Keep object definitions reusable and instances separately editable. Bind visual,
   selection and collision data to one stable owner. Preserve contents when an
   object is moved or broken. Source identities must survive retries and reloads.

Agent-authored adapters implement discovery, scene export and source verification.
The common scene/interaction contracts and Minecraft backend remain the reusable
parts. Keep a private source-to-target mapping for identifying user data and game
assets; the public framework uses original fixtures and code.

## Build and verify the target

Use the project's actual Minecraft build/runtime rules. Prefer an owned dimension
or explicitly selected placement region. Generation must check existing blocks,
remain incremental, release owned chunk tickets and handle interruption without
duplicating objects or resetting an already imported inventory.

For each capability test native behavior and synchronized results, including
failure boundaries, object removal and save/load. Check actual traversal and
rendered growth/appearance states. The server logic, actual packaged client,
source-game comparison and visual acceptance have separate evidence scopes.
Keep automated games hidden, unfocused, muted and input/clipboard isolated under
the available test adapter. A requested interactive play session is a separate
human use of the production game.

Read [performance-and-acceptance](references/performance-and-acceptance.md) when
profiling, comparing candidates or deciding delivery. Bind receipts to the final
program, source, resources, fixture, dependencies and scenario selection after the
process exits. Report failed and unexecuted scenarios accurately.

## Deliver reusable results

Keep program code and model payloads separately manageable under the user's
existing distribution workflow. Use bounded ordinary files and validate path,
hash, dependency and content coverage. Preserve the user's established recovery
policy; large duplicate game directories are temporary test resources and should
be retired when their retained evidence is sufficient.

For a public package, distribute author-owned framework code, this Skill, schemas
and original reproducible fixtures. Review dependencies and attribution. Do not
include original game files, generated game interop assemblies, extracted art,
personal saves, credentials, raw dialogue or private repository history. Check
text, binary payloads, archives, history and media with an explicit export roster.

The handoff states what was migrated, the target interaction policy, complete
coverage counts, retained exceptions, measured performance conditions and the
exact accepted artifact. General applicability is a method claim; source-game
and runtime experiments establish their own tested cases.
