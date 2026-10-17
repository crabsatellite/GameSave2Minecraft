# GameSave2Minecraft

**Agent-guided migration of game saves into playable Minecraft worlds.**

Supply a save and its matching local game installation. A coding agent uses REA
and suitable engine tools to recover the saved world, writes a source adapter,
and connects it to shared scene contracts, interaction rules and a Minecraft
runtime. New source games enter through this adapter-development workflow.

Minecraftization gives objects target-world placement, selection, collision,
interaction, editing and persistence. Doors open, seats can be occupied, beds
use sleep rules, containers retain contents, and plants can be harvested and
regrow. Visual stylization is a configurable part of the migration.

## Start with the Skill

The portable [game-save-to-minecraft Skill](skills/game-save-to-minecraft/SKILL.md)
contains the source-investigation and migration workflow. Install that folder in
your agent's supported skill directory, or ask the agent to read it directly.
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
[world contract](skills/game-save-to-minecraft/references/world-contract.md)
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
- `skills/game-save-to-minecraft/`: agent workflow and supporting contracts.
- `examples/demo/`: original source save, catalog, models and textures.
- `tools/generate_demo.py`: reproducible visual/data authoring.

See [provenance](docs/PROVENANCE.md) and [third-party notices](THIRD_PARTY_NOTICES.md).
The distribution contains framework code, an original example and the research
paper. Commercial game assets, personal saves and source-game binaries remain
outside it.
