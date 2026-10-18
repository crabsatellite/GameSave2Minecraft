# Source and asset provenance

The generic Minecraft component and rendering code is extracted and adapted from
the author's own migration implementation. Source-specific identifiers and
saved layouts are replaced by data-driven scene metadata and an original demo.
The paper's V Rising observations belong to the separately evaluated case mod;
the generic runtime and its own checks have separate artifact identities.

`tools/generate_demo.py` authors the example's meshes, texture pixels, catalog and
compressed save using Python's standard library. It consumes no photograph,
downloaded texture, game model or external animation. The original adapter reads
that save and catalog; target emission generates the demo's resource pack from
those original inputs. Generation checks establish this production path and
byte identity, with no universal originality claim about common geometric forms.

The runtime references Minecraft texture locations for ordinary item carriers
and block particles. Those assets are resolved by Minecraft; their images and
the Minecraft installation are not copied into this repository.

The case summary exposes aggregate object counts and timing observations.
It contains no saved placement coordinates, source-resource carriers, player
names, platform IDs or personal save files. The paper's diagrams are authored
vector graphics. The public example is independently generated and does not
reconstruct the case owner's exact castle layout.

Framework code and generators use the MIT license. The Agent Skill, also MIT,
is now maintained separately at [CrabMods/game-save-to-minecraft](https://github.com/CrabMods/game-save-to-minecraft);
earlier published snapshots retain the original Skill files. Original
fixture assets, documentation and the paper use CC BY 4.0. Dependency rights and
trademarks remain with their owners. The accompanying license scope identifies
the grants; future source adapters and appearance packs need their own provenance.
