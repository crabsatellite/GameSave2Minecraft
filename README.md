# GameSave2Minecraft

**GameSave2Minecraft: Agent-Guided Migration of Game Saves into Playable Minecraft Worlds**

Alex Chengyu Li · Working paper, 2026

[Paper PDF](paper/GameSave2Minecraft.pdf) · [Archived paper and artifacts](https://zenodo.org/records/23437178) · [Version history](https://doi.org/10.5281/zenodo.23437177) · [Reproduction guide](docs/REPRODUCING.md)

This is the research repository for the paper, reference implementation,
original reproducible fixture and reported evaluation. The independently
maintained agent Skill is available from
[CrabMods/game-save-to-minecraft](https://github.com/CrabMods/game-save-to-minecraft).

## Research overview

The work studies how a coding agent can recover a saved game world and translate
it into an editable, persistent Minecraft representation. A source adapter
combines saved instances with the matching local client's object definitions
and assets. A normalized scene contract separates reusable definitions,
instance transforms, source state and evidence. Target interaction rules and
a Minecraft runtime supply placement, selection, collision, interaction and
save/load behavior.

The research addresses three connected problems:

- Recovering a saved assembly and its state, including composite objects,
  transforms, variants and source inventories.
- Mapping recovered objects to target capabilities such as doors, seats, beds,
  storage, lights and growing plants, with explicit semantic choices.
- Evaluating source coverage, native interaction, actual client behavior,
  rendering and runtime cost as distinct evidence layers.

## Artifacts

| Location | Research role |
| --- | --- |
| [`paper/`](paper/) | English manuscript PDF, TeX sources and source archive |
| [`src/gamesave2minecraft/`](src/gamesave2minecraft/) and [`schemas/`](schemas/) | Reference adapter, scene, transform and compilation contracts |
| [`minecraft-runtime/`](minecraft-runtime/) | Reference Minecraft Java 1.21.1 / NeoForge backend |
| [`examples/`](examples/), [`tools/`](tools/) and [`tests/`](tests/) | Original source fixture, generators and executable checks |
| [`docs/runtime-summary.json`](docs/runtime-summary.json) | Artifact-bound results for the original reproducible fixture |
| [`docs/case-summary.json`](docs/case-summary.json) | Aggregate conditions, observations, scenario inventory and limits of the V Rising case |

## Evaluation scope

The empirical case is one **V Rising** save containing four castles. The
reported selected scope includes 12,057 visible source objects, 182 measured
terrain patches and 1,044 retained inventory lots. Logical/invisible entities
and source characters have explicit exclusions in the case summary.

The case records 19 native GameTest cases, 38 packaged-client cases and four
real OpenGL 3.2 cases. The independently generated public fixture has separate
results: 13 native GameTest scenarios and 22 packaged-client scenarios, with
15 saved instances from eight definitions. These are archived, artifact-bound
results; they do not establish acceptance of arbitrary later code or adapters.

In the reported matched interior scene, median render work changed from
162.64 ms to 13.81 ms. Observed FPS changed from 4.61 to 29.19 under a 30 FPS cap.
The experiment used an RTX 3090 and one paired session with other host work
continuing. It does not establish uncapped 60 FPS, hardware generalization or
the separate contribution of each optimization. Full conditions are retained
in the [case data](docs/case-summary.json).

A [case demonstration video](https://www.youtube.com/watch?v=tzVSkMqvyoQ) shows
the reconstructed V Rising environment and mapped interactions. The video is
an illustration of that case; reproducible checks and their artifact identities
are recorded separately.

## Reproduction and limitations

See [REPRODUCING.md](docs/REPRODUCING.md) for the public original fixture,
Python checks and reference Minecraft runtime. Reconstructing another source
game requires an investigated source adapter and matching local inputs.

Commercial game artwork, source-game binaries, personal saves and the case's
saved placement coordinates are not distributed. The public fixture is
independently authored and does not reconstruct the private castle layout.
Consequently, the public fixture is reproducible from this repository, while
the commercial-game case cannot be independently rerun from these files alone.
Converted V Rising assets are not publicly released.

## Citation

> Li, Alex Chengyu. 2026. *GameSave2Minecraft: Agent-Guided Migration of Game
> Saves into Playable Minecraft Worlds*. Working paper.
> https://doi.org/10.5281/zenodo.23437178

The [first archived paper and accompanying artifacts](https://zenodo.org/records/23437178)
were made publicly available on 17 October 2026. This is a working-paper record;
no journal or proceedings publication is claimed.
[CITATION.cff](CITATION.cff) identifies the reference software and its preferred
paper citation. Historical publication snapshots remain unchanged.

## Licenses and provenance

The reference implementation uses [MIT](LICENSE). The paper, documentation and
case-summary data use [CC BY 4.0](LICENSE-PAPER.md); original fixture visuals use
[CC BY 4.0](LICENSE-ASSETS.md). See [license scope](LICENSE-SCOPE.md),
[provenance](docs/PROVENANCE.md) and [third-party notices](THIRD_PARTY_NOTICES.md).

The separate [CrabMods Skill](https://github.com/CrabMods/game-save-to-minecraft)
contains agent workflow instructions and its own installation guide. It uses
this repository as a research/reference implementation, rather than being
packaged inside the current research tree.
