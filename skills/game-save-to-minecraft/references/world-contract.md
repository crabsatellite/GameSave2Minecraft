# World contract

The normalized scene schema is `gamesave2minecraft.scene/1`. The adapter exports
metres, right-handed coordinates and Y up. An asset contains an ID, behavior,
visual bounds, collision boxes, mesh reference and capability parameters.
Instances contain a stable normalized ID, asset ID, position, quaternion, scale,
optional parent and a separate state record. Keep the original source identity
and the private mapping when its representation needs normalization.

Use object definitions to share geometry; instances retain independent state.
Parent links can occur before or after their children. Normalize quaternions,
reject non-finite numbers and detect cycles. Retain a full world affine matrix
while flattening a hierarchy; a target cannot discard shear without a declared
bake or approximation step.

The coverage record contains `expected_instances` and explicit excluded source
identities with reasons. Structural coverage checks count and resolve exported
records. Source-oracle checks establish that those records correspond to the
source game. Both are needed for a source-fidelity claim.

`evidence.asset_inventory` reconciles every extracted visual carrier with an
`expected` count and unique `records`. Each record has a `source_id` and either
`status: mapped` with target resource paths, or `status: excluded` with a reason.
Unresolved outcomes fail acceptance. Verify this ledger against the source
extractor's inventory; a self-consistent subset does not establish completeness.

Inventory state includes capacity, original slot, source item identity, amount
and descriptive fields. A source lot may exceed a native stack limit. Select a
target representation that retains quantity and slot identity, and label its
gameplay behavior. Preserved source resources and fully simulated source recipes
are separate claims.

The portable compiler partitions instance plans and asset metadata and emits
acceptance requirements and resource hashes. Full world matrices use row-major
order with column vectors. A Minecraft target emitter consumes those matrices
and capability records; compiler output alone is not a running Minecraft world.

The target emission contract must state supported transform/material forms and
require a bake or explicit unresolved outcome for the others. Only completed
outcomes enter the accepted source coverage denominator.
