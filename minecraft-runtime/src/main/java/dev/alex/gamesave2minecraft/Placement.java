package dev.alex.gamesave2minecraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Placement {
    private record Proposed(UUID id, AABB box) {}
    private static final ThreadLocal<Integer> COORDINATED_REMOVAL_DEPTH =
        ThreadLocal.withInitial(() -> 0);

    public static String denial(
        Level level,
        Collection<Piece> pieces,
        boolean checkFurnitureOverlap
    ) {
        return denial(level, pieces, checkFurnitureOverlap, false);
    }

    private static String denial(Level level, Collection<Piece> pieces,
                                 boolean checkFurnitureOverlap, boolean savedDoorAssembly) {
        Map<UUID, Boolean> originalPoses = new LinkedHashMap<>();
        Map<BlockPos, List<Proposed>> proposedByCell = new LinkedHashMap<>();
        for (var piece : pieces) {
            for (var entry : piece.fragments().entrySet()) {
                var pos = entry.getKey();
                if (!level.isInWorldBounds(pos) || !level.hasChunkAt(pos)) {
                    return "目标位置超出已加载区域";
                }
                var state = level.getBlockState(pos);
                if (!state.canBeReplaced() && !state.is(GameSaveMod.CELL.get())) {
                    return "目标区域已有方块，未覆盖现有建筑："
                        + pos.toShortString()
                        + " "
                        + BuiltInRegistries.BLOCK.getKey(state.getBlock());
                }
                if (checkFurnitureOverlap
                    && level.getBlockEntity(pos) instanceof CellEntity cell) {
                    for (var oldFragment : cell.fragments) {
                        if (oldFragment.id().equals(piece.id)) {
                            continue;
                        }
                        // Source door leaves deliberately embed in their frame/columns.
                        // Only unchanged saved neighbours belong to that assembly;
                        // subsequently moved or newly placed furniture still blocks it.
                        if (savedDoorAssembly && originalPoses.computeIfAbsent(
                            oldFragment.id(), ignored -> originalSourcePose(cell.resolve(oldFragment)))) {
                            continue;
                        }
                        for (var oldBox : oldFragment.boxes()) {
                            for (var nextBox : entry.getValue()) {
                                if (oldBox.deflate(1.0E-5D)
                                    .intersects(nextBox.deflate(1.0E-5D))) {
                                    return "组件与现有物件重叠";
                                }
                            }
                        }
                    }
                }
                if (checkFurnitureOverlap) {
                    var planned = proposedByCell.computeIfAbsent(pos, ignored -> new ArrayList<>());
                    for (var nextBox : entry.getValue()) {
                        for (var existing : planned) {
                            if (!existing.id().equals(piece.id)
                                && existing.box().deflate(1.0E-5D)
                                    .intersects(nextBox.deflate(1.0E-5D))) {
                                return "演示组件之间发生重叠";
                            }
                        }
                        planned.add(new Proposed(piece.id, nextBox));
                    }
                }
            }
        }
        return null;
    }

    public static void add(Level level, Piece piece) {
        var definition = ModelCatalog.get(piece.asset);
        if (definition.behavior().equals("plant") && !piece.active && piece.growAt <= 0L) {
            piece.growAt = level.getGameTime() + Math.max(0, definition.growTicks());
        }
        for (var entry : piece.fragments().entrySet()) {
            var pos = entry.getKey();
            if (!level.getBlockState(pos).is(GameSaveMod.CELL.get())) {
                level.setBlock(pos, GameSaveMod.CELL.get().defaultBlockState(), 3);
            }
            if (!(level.getBlockEntity(pos) instanceof CellEntity cell)) {
                throw new IllegalStateException("Missing component cell after placement at " + pos);
            }
            cell.fragments.removeIf(fragment -> fragment.id().equals(piece.id));
            cell.fragments.add(new CellEntity.Fragment(
                piece.id,
                piece.owner(),
                List.copyOf(entry.getValue()),piece.picks(pos,entry.getValue())
            ));
            if (pos.equals(piece.owner())) {
                cell.roots.removeIf(root -> root.id.equals(piece.id));
                cell.roots.add(piece);
            }
            if (definition.behavior().equals("bed")) {
                level.setBlock(
                    pos,
                    level.getBlockState(pos).setValue(
                        CellBlock.FACING,
                        Direction.fromYRot((float) -piece.angle())
                    ),
                    3
                );
            }
            cell.changed();
        }
    }

    public static void remove(Level level, Piece piece) {
        coordinated(() -> removeInternal(level, piece, null));
    }

    static boolean isCoordinatedRemoval() {
        return COORDINATED_REMOVAL_DEPTH.get() > 0;
    }

    static void removeExternally(Level level, BlockPos triggerPos, CellEntity triggerCell) {
        if (level.isClientSide || isCoordinatedRemoval()) {
            return;
        }
        Map<UUID, Piece> affected = new LinkedHashMap<>();
        for (var piece : triggerCell.roots) {
            affected.put(piece.id, piece);
        }
        for (var fragment : triggerCell.fragments) {
            Piece piece = triggerCell.resolve(fragment);
            if (piece != null) {
                affected.put(piece.id, piece);
            }
        }
        if (affected.isEmpty()) {
            return;
        }

        var drops = new ArrayList<ItemStack>();
        for (var piece : affected.values()) {
            ItemStack stack = FurnitureItem.carry(piece, level);
            if (!stack.isEmpty()) {
                drops.add(stack);
            }
        }
        coordinated(() -> {
            for (var piece : affected.values()) {
                removeInternal(level, piece, triggerPos);
            }
        });
        for (var stack : drops) {
            Block.popResource(level, triggerPos, stack);
        }
    }

    private static void removeInternal(Level level, Piece piece, BlockPos triggerPos) {
        SeatEntity.discard(level, piece.owner(), piece.id);
        var occupiedCells = List.copyOf(piece.fragments().keySet());
        for (var pos : occupiedCells) {
            if (!(level.getBlockEntity(pos) instanceof CellEntity cell)) {
                continue;
            }
            cell.fragments.removeIf(fragment -> fragment.id().equals(piece.id));
            cell.roots.removeIf(root -> root.id.equals(piece.id));
            if (pos.equals(triggerPos)) {
                // The caller is replacing this cell. Never remove or rewrite
                // the new state from inside the old block's onRemove callback.
                continue;
            }
            if (cell.fragments.isEmpty() && cell.roots.isEmpty()) {
                level.removeBlock(pos, false);
            } else {
                cell.changed();
            }
        }
    }

    private static void coordinated(Runnable action) {
        int previous = COORDINATED_REMOVAL_DEPTH.get();
        COORDINATED_REMOVAL_DEPTH.set(previous + 1);
        try {
            action.run();
        } finally {
            if (previous == 0) {
                COORDINATED_REMOVAL_DEPTH.remove();
            } else {
                COORDINATED_REMOVAL_DEPTH.set(previous);
            }
        }
    }

    public static boolean rotate(Level level, Piece piece) {
        double previous = piece.rotation;
        piece.rotation = previous + 90.0D;
        String denied = denial(level, List.of(piece), true);
        piece.rotation = previous;
        if (denied != null) {
            return false;
        }
        remove(level, piece);
        piece.rotation = previous + 90.0D;
        add(level, piece);
        return true;
    }

    public static boolean toggleDoor(Level level, Piece piece) {
        if (doorDenial(level, piece) != null) return false;
        boolean previous = piece.active;
        remove(level, piece);
        piece.active = !previous;
        add(level, piece);
        return true;
    }

    public static String doorDenial(Level level, Piece piece) {
        boolean previous = piece.active;
        try {
            piece.active = !previous;
            boolean savedAssembly = ModelCatalog.get(piece.asset).behavior().equals("door")
                && originalSourcePose(piece);
            return denial(level, List.of(piece), true, savedAssembly);
        } finally { piece.active = previous; }
    }

    private static boolean originalSourcePose(Piece piece) {
        if (piece == null || piece.sourceData.isBlank()
            || Math.abs(Math.IEEEremainder(piece.rotation, 360.0D)) > 1.0E-5D) return false;
        try {
            var row = com.google.gson.JsonParser.parseString(piece.sourceData).getAsJsonObject();
            if (!row.get("asset").getAsString().equals(piece.asset)) return false;
            if (ModelCatalog.vector(row.getAsJsonArray("position")).add(ScenePlans.ORIGIN)
                .distanceToSqr(piece.position) > 1.0E-8D) return false;
            if (ModelCatalog.vector(row.getAsJsonArray("scale")).distanceToSqr(piece.scale) > 1.0E-8D) return false;
            var q = row.getAsJsonArray("quaternion");
            var saved = new org.joml.Quaternionf(q.get(0).getAsFloat(), q.get(1).getAsFloat(),
                q.get(2).getAsFloat(), q.get(3).getAsFloat()).normalize();
            return Math.abs(saved.dot(piece.orientation)) > .99999F;
        } catch (RuntimeException malformedSource) {
            return false;
        }
    }

    /**
     * Authored Minecraft interaction showcase. This is deliberately not a
     * recovered source-game save layout.
     */
    public static List<Piece> demoCourtyard(BlockPos origin) {
        var pieces = new ArrayList<Piece>();
        double cursorX = 0.0D;
        double cursorZ = 0.0D;
        double rowDepth = 0.0D;
        int column = 0;
        for (String id : GameSaveMod.ITEMS.keySet()) {
            var asset = ModelCatalog.get(id);
            var bounds = safeBounds(asset);
            double width = Math.max(1.0D, bounds.getXsize());
            double depth = Math.max(1.0D, bounds.getZsize());
            if (column == 4) {
                cursorX = 0.0D;
                cursorZ += rowDepth + 2.0D;
                rowDepth = 0.0D;
                column = 0;
            }
            Vec3 position = Vec3.atLowerCornerOf(origin).add(
                cursorX - bounds.minX,
                -bounds.minY,
                cursorZ - bounds.minZ
            );
            pieces.add(new Piece(id, position, 0.0D, new Vec3(1.0D, 1.0D, 1.0D)));
            cursorX += width + 2.0D;
            rowDepth = Math.max(rowDepth, depth);
            column++;
        }
        return pieces;
    }

    private static AABB safeBounds(ModelCatalog.Asset asset) {
        AABB bounds = ModelCatalog.bounds(asset);
        if (bounds != null) {
            return bounds;
        }
        for (var collision : asset.collisions()) {
            bounds = bounds == null ? collision : bounds.minmax(collision);
        }
        return bounds == null ? new AABB(0, 0, 0, 1, 1, 1) : bounds;
    }

    private Placement() {}
}
