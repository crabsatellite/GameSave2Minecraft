# Reproducing the public reference example

This guide concerns the independently authored fixture distributed with the
research paper. The commercial-game case has separate source inputs, artifacts
and measurements; its game assets and personal save are not included here.

## Python fixture and contracts

Use Python 3.10 or later from the repository root. The pipeline and tests use
the standard library; editable package installation uses the declared build
tooling in `pyproject.toml`.

```powershell
python tools/generate_demo.py --check
python -m unittest discover -s tests -v
python tools/run_demo.py --output build/demo
```

Choose a fresh output directory. The original adapter reads a compressed source
save and a separate client catalog, recovers 15 instances from eight definitions
and emits a portable plan and Minecraft metadata/resource pack. Its ledger
accounts for 17 original visual carriers. The storage fixture has capacity 54
and retains 100,000 source units in original slot 37.

For another scene that already satisfies the normalized contract:

```powershell
python -m pip install -e .
gamesave2minecraft validate scene.json --resources local-inputs/resources
gamesave2minecraft compile scene.json --resources local-inputs/resources --output build/portable
gamesave2minecraft emit-minecraft scene.json --resources local-inputs/resources --output build/minecraft
gamesave2minecraft verify-minecraft build/minecraft
```

The [adapter interface](../src/gamesave2minecraft/adapters.py),
[original adapter](../examples/demo_source.py) and
[scene schema](../schemas/scene.schema.json) define the reference path.
Emission checks do not establish that a scene has run successfully in Minecraft.

## Reference Minecraft runtime

The backend targets **Minecraft Java 1.21.1, NeoForge and Java 21**. Follow the
checkout's actual project build/runtime instructions. In an ordinary public
checkout, select Java 21 through `JAVA_HOME` and build with the included wrapper:

```powershell
cd minecraft-runtime
./gradlew.bat build
```

Install the resulting `build/libs/gamesave2minecraft-0.1.0.jar` on the selected
test server and clients. Copy `minecraft-runtime/resource-pack` **contents** into
the client's game-directory folder `gamesave2mc-models`. The mod enables that
local pack automatically. Use a test instance with a fresh target world.

```text
/gamesave2mc scene import original_demo
/gamesave2mc scene enter original_demo
/gamesave2mc scene return
```

Use `/gamesave2mc give <asset-id>` for an independently placeable component.
For another normalized scene, point the runtime build at its emitted `generated`
directory with `-PsceneGenerated=...` and install the matching emitted resources.

Automated runtime checks must follow the project's isolation and test workflow.
Keep server GameTest, packaged-client behavior, visual comparison and performance
results separate. A build or a generic fixture pass is not acceptance of another
source game, an altered artifact or an unexecuted target environment.

## Recorded evaluation and source boundaries

[`runtime-summary.json`](runtime-summary.json) identifies the accepted original
fixture program and checks. [`case-summary.json`](case-summary.json) identifies
the independently evaluated V Rising case and its measured conditions.
Use the exact artifact/version when interpreting either record; the reports
are historical results, not live test status for a working tree.

The public source save, meshes and textures are procedurally authored by the
fixture generator. Commercial artwork, original game installations, generated
interop assemblies, private saves and internal test infrastructure are excluded.
The [provenance record](PROVENANCE.md) and
[license scope](../LICENSE-SCOPE.md) explain the distributed materials.

## Separate agent workflow

The portable [game-save-to-minecraft Skill](https://github.com/CrabMods/game-save-to-minecraft)
is maintained separately by CrabMods. Use that repository for Skill installation
and agent instructions; this guide documents reproduction of the research
reference implementation.
