# GameSave2Minecraft

**Agent-guided migration of game saves into playable Minecraft worlds.**

[![Watch the GameSave2Minecraft demo on YouTube](https://img.youtube.com/vi/tzVSkMqvyoQ/maxresdefault.jpg)](https://www.youtube.com/watch?v=tzVSkMqvyoQ)

[Watch the 80-second demo](https://www.youtube.com/watch?v=tzVSkMqvyoQ): the V Rising
saved-castle case study, captured in Minecraft with mapped object interactions.

| Build and adapt | Read the research |
| --- | --- |
| [Start with the Skill](#start-with-the-skill), [run the example](#run-the-original-example), [build the Minecraft runtime](#minecraft-runtime) | [Read the paper](paper/GameSave2Minecraft.pdf), [paper archive and version history](https://doi.org/10.5281/zenodo.23437177), [case data](docs/case-summary.json) |

Supply a save and its matching local game installation. A coding agent uses REA
and suitable engine tools to recover the saved world, writes a source adapter,
and connects it to shared scene contracts, interaction rules and a Minecraft
runtime. New source games enter through this adapter-development workflow.

Minecraftization gives objects target-world placement, selection, collision,
interaction, editing and persistence. Doors open, seats can be occupied, beds
use sleep rules, containers retain contents, and plants can be harvested and
regrow. Visual stylization is a configurable part of the migration.

## Start with the Skill

The portable [game-save-to-minecraft Skill](https://github.com/CrabMods/game-save-to-minecraft)
is maintained by CrabMods and contains the source-investigation and migration
workflow. Install it independently in your agent's supported skill directory,
or ask the agent to read its `SKILL.md` directly. This personal repository
contains the research paper, reference implementation and original example.
REA is an external inspection tool; source-specific asset decoders are selected
as needed. Game files and generated interop assemblies are supplied locally.

Example request:

> Use $game-save-to-minecraft to migrate the world in my save. Investigate my local
> client and assets, generate the source adapter, map interactions to Minecraft,
> and verify source coverage, persistence, rendering and runtime cost.

## Run the original example

Python 3.10+ is sufficient for the data pipeline and tests; it uses the standard
library. The example generator authors every supplied model and texture.

```powershell
python tools/generate_demo.py --check
python -m unittest discover -s tests -v
python tools/run_demo.py --output build/demo
```

The example reads an original compressed save and a separate client catalog,
recovers 15 instances from 8 definitions, verifies them against the saved data,
then emits a hash-bound portable plan and Minecraft metadata/resource pack.
Its asset ledger accounts for all 17 original model and texture carriers.
Its container retains 100,000 source units in slot 37 of a 54-slot inventory.
Choose a fresh output directory on each run.

For a new normalized source scene, install the package and use the CLI:

```powershell
python -m pip install -e .
gamesave2minecraft validate scene.json --resources local-inputs/resources
gamesave2minecraft compile scene.json --resources local-inputs/resources --output build/portable
gamesave2minecraft emit-minecraft scene.json --resources local-inputs/resources --output build/minecraft
gamesave2minecraft verify-minecraft build/minecraft
```

The [adapter protocol](src/gamesave2minecraft/adapters.py),
[original adapter](examples/demo_source.py) and
[world contract](https://github.com/CrabMods/game-save-to-minecraft/blob/main/references/world-contract.md)
show the boundary. The initial target emitter makes transform-baking and
unsupported-interaction requirements explicit so an agent can resolve them.

## Minecraft runtime

The included backend targets **Minecraft Java 1.21.1, NeoForge and Java 21**.
Select Java 21 for `JAVA_HOME`, then build with upstream Gradle tooling:

```powershell
cd minecraft-runtime
./gradlew.bat build
```

Install the resulting `build/libs/gamesave2minecraft-0.1.0.jar` on the server and
clients. Copy `minecraft-runtime/resource-pack` **contents** into the client's
game-directory folder `gamesave2mc-models`. The mod enables it automatically.

The original demo can be generated in its own dimension with:

```text
/gamesave2mc scene import original_demo
/gamesave2mc scene enter original_demo
/gamesave2mc scene return
```

Use `/gamesave2mc give <asset-id>` to obtain an independently placeable component.
To compile metadata for another scene, point Gradle at the emitter's `generated`
directory using `-PsceneGenerated=...`; install that emission's resource pack.
Keep user-generated source assets in the user's local/private distribution.

The original fixture passed 13 native GameTest scenarios and 22 actual
packaged-client scenarios, including complete scene and inventory readback.
The tested program is byte-identical to the independent Gradle build. See the
[runtime summary](docs/runtime-summary.json) for artifact identity and measured
rendering comparisons. These checks use the original example; the larger
V Rising case has its own artifact and measurements below.

## Method and case study

The method is developed with a completed **V Rising saved-castle migration**:
four castles, 12,057 visible source objects plus 182 measured terrain patches,
and 1,044 retained inventory lots. The generic source and original fixture are
separate artifacts from the evaluated case mod.

The [methodology paper](paper/GameSave2Minecraft.pdf) describes the adapter,
world contract, interaction rules, runtime optimizations and measured case.
[Case data](docs/case-summary.json) records the conditions and aggregate results.
The matched case measured interior median render work of 162.64 to 13.81 ms and
observed 4.61 to 29.19 FPS under a 30 FPS cap. Those are observations of the
evaluated case configuration.

Useful source locations:

- `src/gamesave2minecraft/`: adapter interfaces, validation, transforms and compilers.
- `minecraft-runtime/`: reusable interactions, persistence, generation and rendering.
- [CrabMods/game-save-to-minecraft](https://github.com/CrabMods/game-save-to-minecraft): separately maintained agent workflow and supporting contracts.
- `examples/demo/`: original source save, catalog, models and textures.
- `tools/generate_demo.py`: reproducible visual/data authoring.

See [provenance](docs/PROVENANCE.md) and [third-party notices](THIRD_PARTY_NOTICES.md).
The distribution contains framework code, an original example and the research
paper. Commercial game assets, personal saves and source-game binaries remain
outside it.

## Citation and rights

> Li, Alex Chengyu. 2026. *GameSave2Minecraft: Agent-Guided Migration of Game
> Saves into Playable Minecraft Worlds*. Working paper.
> https://doi.org/10.5281/zenodo.23437177

First publicly available on 17 October 2026 through Zenodo. The
[first paper edition and accompanying source archives](https://zenodo.org/records/23437178)
retain the published snapshot; this repository contains the reusable implementation
and research. The independently maintained Skill is now hosted by
[CrabMods](https://github.com/CrabMods/game-save-to-minecraft).
[CITATION.cff](CITATION.cff) provides machine-readable citation metadata.

Author-owned code and the Skill use [MIT](LICENSE). The paper and documentation
use [CC BY 4.0](LICENSE-PAPER.md); original demo visuals use
[CC BY 4.0](LICENSE-ASSETS.md). See [license scope](LICENSE-SCOPE.md) for the
division and [third-party notices](THIRD_PARTY_NOTICES.md) for upstream terms.
