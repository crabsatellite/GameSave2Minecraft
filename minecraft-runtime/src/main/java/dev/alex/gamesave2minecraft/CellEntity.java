package dev.alex.gamesave2minecraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public final class CellEntity extends BlockEntity {
    public record Fragment(UUID id, BlockPos owner, List<AABB> boxes,List<AABB> picks) {
        public Fragment(UUID id,BlockPos owner,List<AABB> boxes){this(id,owner,boxes,boxes);}
    }

    public final List<Piece> roots = new ArrayList<>();
    public final List<Fragment> fragments = new ArrayList<>();
    private VoxelShape cached;
    private VoxelShape cachedSelection;
    private AABB cachedVisualBounds;
    private static java.util.function.BiConsumer<CellEntity,Boolean> clientVisualListener=(cell,present)->{};

    /** Client installs a render index; the dedicated server never loads client classes. */
    public static void setClientVisualListener(java.util.function.BiConsumer<CellEntity,Boolean> listener) {
        clientVisualListener=java.util.Objects.requireNonNull(listener);
    }

    private void updateClientVisual(boolean present) {
        if(level!=null&&level.isClientSide)clientVisualListener.accept(this,present);
    }

    @Override public void onLoad() {
        super.onLoad();
        if(!roots.isEmpty())updateClientVisual(true);
    }

    @Override public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if(!roots.isEmpty())updateClientVisual(false);
    }

    @Override public void setRemoved() {
        super.setRemoved();
        if(!roots.isEmpty())updateClientVisual(false);
    }

    @Override public void clearRemoved() {
        super.clearRemoved();
        if(!roots.isEmpty())updateClientVisual(true);
    }

    public CellEntity(BlockPos pos, BlockState state) {
        super(GameSaveMod.CELL_ENTITY.get(), pos, state);
    }

    public static void serverTick(
        Level level,
        BlockPos pos,
        BlockState state,
        CellEntity cell
    ) {
        long now = level.getGameTime();
        boolean matured = false;
        for (var piece : cell.roots) {
            var asset = ModelCatalog.get(piece.asset);
            if (asset.behavior().equals("plant")
                && !piece.active
                && piece.growAt <= now) {
                piece.active = true;
                piece.growAt = 0L;
                matured = true;
            }
        }
        if (matured) {
            cell.changed();
        }
    }

    public VoxelShape shape() {
        if (cached == null) {
            cached = Shapes.empty();
            for (var fragment : fragments) {
                for (var box : fragment.boxes()) {
                    cached = Shapes.or(cached, Shapes.create(box));
                }
            }
        }
        return cached;
    }

    public VoxelShape selectionShape() {
        if(cachedSelection!=null)return cachedSelection;
        var selection=Shapes.empty();
        for(var fragment:fragments)for(var box:fragment.picks())selection=Shapes.or(selection,Shapes.create(box));
        return cachedSelection=selection;
    }

    public void changed() {
        cached = null;
        cachedSelection=null;
        cachedVisualBounds=null;
        updateClientVisual(true);
        setChanged();
        if (level == null) {
            return;
        }
        boolean light = roots.stream().anyMatch(piece ->
            piece.active && ModelCatalog.get(piece.asset).behavior().equals("light"));
        boolean growing=roots.stream().anyMatch(p->!p.active&&ModelCatalog.get(p.asset).behavior().equals("plant"));
        BlockState state = getBlockState();
        if (state.getValue(CellBlock.LIT) != light || state.getValue(CellBlock.GROWING)!=growing) {
            level.setBlock(worldPosition, state.setValue(CellBlock.LIT, light).setValue(CellBlock.GROWING,growing), 3);
        }
        level.sendBlockUpdated(
            worldPosition,
            getBlockState(),
            getBlockState(),
            3
        );
    }

    public Piece root(UUID id) {
        if (id == null) {
            return null;
        }
        return roots.stream()
            .filter(piece -> piece.id.equals(id))
            .findFirst()
            .orElse(null);
    }

    public Piece resolve(Fragment fragment) {
        if (level != null
            && level.getBlockEntity(fragment.owner()) instanceof CellEntity owner) {
            return owner.root(fragment.id());
        }
        return null;
    }

    public Piece selected(Vec3 worldHit) {
        Vec3 localHit = worldHit.subtract(Vec3.atLowerCornerOf(worldPosition));
        Fragment best = null;
        double volume = Double.POSITIVE_INFINITY;
        for (var fragment : fragments) {
            for (var box : fragment.picks()) {
                if (box.inflate(0.015D).contains(localHit)) {
                    double nextVolume = box.getXsize() * box.getYsize() * box.getZsize();
                    if (nextVolume < volume) {
                        best = fragment;
                        volume = nextVolume;
                    }
                }
            }
        }
        if (best != null) {
            return resolve(best);
        }
        if (roots.size() == 1) {
            return roots.getFirst();
        }
        if (fragments.size() == 1) {
            return resolve(fragments.getFirst());
        }
        return null;
    }

    public Piece bed() {
        for (var fragment : fragments) {
            var piece = resolve(fragment);
            if (piece != null && ModelCatalog.get(piece.asset).behavior().equals("bed")) {
                return piece;
            }
        }
        return null;
    }

    public void discardSeats() {
        discardSeats(roots.stream().map(piece -> piece.id).toList());
    }

    public void discardSeats(Collection<UUID> ids) {
        if (!(level instanceof ServerLevel)) {
            return;
        }
        for (UUID id : ids) {
            SeatEntity.discard(level, worldPosition, id);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        var rootsTag = new ListTag();
        for (var piece : roots) {
            rootsTag.add(piece.save(registries));
        }
        tag.put("Roots", rootsTag);

        var fragmentsTag = new ListTag();
        for (var fragment : fragments) {
            var fragmentTag = new CompoundTag();
            fragmentTag.putUUID("Id", fragment.id());
            fragmentTag.putLong("Owner", fragment.owner().asLong());
            // Exact IEEE-754 doubles, without twelve boxed maps per solid box.
            fragmentTag.putLongArray("Bounds",pack(fragment.boxes()));
            if(!fragment.picks().equals(fragment.boxes()))fragmentTag.putLongArray("PickBounds",pack(fragment.picks()));
            fragmentsTag.add(fragmentTag);
        }
        tag.put("Fragments", fragmentsTag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        boolean hadVisualRoots=!roots.isEmpty();
        cachedVisualBounds=null;
        roots.clear();
        fragments.clear();
        for (var raw : tag.getList("Roots", Tag.TAG_COMPOUND)) {
            roots.add(Piece.load((CompoundTag) raw, registries));
        }
        for (var raw : tag.getList("Fragments", Tag.TAG_COMPOUND)) {
            var fragmentTag = (CompoundTag) raw;
            List<AABB> boxes = fragmentTag.contains("Bounds",Tag.TAG_LONG_ARRAY)?unpack(fragmentTag.getLongArray("Bounds")):new ArrayList<>();
            if(!fragmentTag.contains("Bounds"))for (var boxRaw : fragmentTag.getList("Boxes", Tag.TAG_COMPOUND)) {
                var boxTag = (CompoundTag) boxRaw;
                boxes.add(new AABB(
                    boxTag.getDouble("LX"),
                    boxTag.getDouble("LY"),
                    boxTag.getDouble("LZ"),
                    boxTag.getDouble("HX"),
                    boxTag.getDouble("HY"),
                    boxTag.getDouble("HZ")
                ));
            }
            List<AABB> picks=fragmentTag.contains("PickBounds",Tag.TAG_LONG_ARRAY)?unpack(fragmentTag.getLongArray("PickBounds")):new ArrayList<>();
            if(fragmentTag.contains("PickBounds")){}
            else if(fragmentTag.contains("Picks"))for(var rawPick:fragmentTag.getList("Picks",Tag.TAG_COMPOUND)){
                var b=(CompoundTag)rawPick;picks.add(new AABB(b.getDouble("LX"),b.getDouble("LY"),b.getDouble("LZ"),b.getDouble("HX"),b.getDouble("HY"),b.getDouble("HZ")));
            }else picks=boxes;
            fragments.add(new Fragment(
                fragmentTag.getUUID("Id"),
                BlockPos.of(fragmentTag.getLong("Owner")),
                boxes,picks
            ));
        }
        cached = null;
        cachedSelection=null;
        if(hadVisualRoots||!roots.isEmpty())updateClientVisual(true);
    }

    private static long[] pack(List<AABB> boxes){
        long[] values=new long[boxes.size()*6];int i=0;
        for(var b:boxes)for(double value:new double[]{b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ})values[i++]=Double.doubleToRawLongBits(value);
        return values;
    }
    private static List<AABB> unpack(long[] values){
        if(values.length%6!=0)throw new IllegalArgumentException("Incomplete component bounds");
        var result=new ArrayList<AABB>(values.length/6);
        for(int i=0;i<values.length;i+=6){
            double[] b=new double[6];for(int k=0;k<6;k++){b[k]=Double.longBitsToDouble(values[i+k]);if(!Double.isFinite(b[k]))throw new IllegalArgumentException("Non-finite component bound");}
            result.add(new AABB(b[0],b[1],b[2],b[3],b[4],b[5]));
        }
        return result;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    public AABB renderBounds() {
        AABB result = new AABB(worldPosition);
        for (var piece : roots) {
            var boxes = piece.boxes();
            for (var box : boxes) {
                result = result.minmax(box);
            }
            var assetBounds = ModelCatalog.bounds(ModelCatalog.get(piece.asset));
            if (assetBounds != null) {
                for (double x : new double[]{assetBounds.minX, assetBounds.maxX}) {
                    for (double y : new double[]{assetBounds.minY, assetBounds.maxY}) {
                        for (double z : new double[]{assetBounds.minZ, assetBounds.maxZ}) {
                            Vec3 point = piece.transform(new Vec3(x, y, z));
                            result = result.minmax(new AABB(point, point));
                        }
                    }
                }
            }
        }
        return result.inflate(0.2D);
    }

    /** Bounds of the visible roots, independent of the collision-only fragments. */
    public AABB visualRenderBounds() {
        if(cachedVisualBounds==null){
            AABB bounds=new AABB(worldPosition);
            for(var piece:roots)bounds=bounds.minmax(piece.visualBounds());
            cachedVisualBounds=bounds.inflate(.25);
        }
        return cachedVisualBounds;
    }
}
