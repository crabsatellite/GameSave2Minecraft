package dev.alex.gamesave2minecraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One independently removable and movable authored Minecraft component. */
public final class Piece {
    private static final double EPSILON = 1.0E-7D;

    public UUID id;
    public String asset;
    public Vec3 position;
    public Vec3 scale;
    public double rotation;
    /** Source scan orientation, followed by the player's extra world-Y turn. */
    public Quaternionf orientation = new Quaternionf();
    public boolean active;
    /** Absolute game time while placed; portable items store only the remaining ticks. */
    public long growAt;
    public String sourceData = "";
    public NonNullList<ItemStack> inventory =
        NonNullList.withSize(27, ItemStack.EMPTY);

    public void inventorySize(int size) {
        if(size < 0 || size > 4096)throw new IllegalArgumentException("Invalid inventory capacity");
        int target=Math.max(27,size);
        var next=NonNullList.withSize(target,ItemStack.EMPTY);
        for(int i=0;i<Math.min(target,inventory.size());i++)next.set(i,inventory.get(i));
        inventory=next;
    }

    public Piece(String asset, Vec3 position, double rotation, Vec3 scale) {
        this.id = UUID.randomUUID();
        this.asset = asset;
        this.position = position;
        this.rotation = rotation;
        this.scale = scale;
        String behavior = ModelCatalog.get(asset).behavior();
        this.active = behavior.equals("light") || behavior.equals("plant");
    }

    public BlockPos owner() {
        return BlockPos.containing(position);
    }

    public double angle() {
        return rotation + Math.toDegrees(Math.atan2(
            2.0D * (orientation.w * orientation.y + orientation.x * orientation.z),
            1.0D - 2.0D * (orientation.y * orientation.y + orientation.z * orientation.z)
        ))
            + (ModelCatalog.get(asset).behavior().equals("door") && ModelCatalog.get(asset).door()==null && active ? 90.0D : 0.0D);
    }

    public Vec3 transform(Vec3 point) {
        var door=ModelCatalog.get(asset).door();
        return transform(point,door!=null&&DoorMotion.right(door,point.x));
    }
    private Vec3 transform(Vec3 point,boolean right) {
        double radians = Math.toRadians(rotation);
        var door=ModelCatalog.get(asset).door();
        if(door!=null&&active)point=DoorMotion.point(door,point,right);
        var local = new Vector3f((float)(point.x * scale.x), (float)(point.y * scale.y), (float)(point.z * scale.z));
        if (ModelCatalog.get(asset).behavior().equals("door") && door==null && active) {
            new Quaternionf().rotationY((float)Math.PI / 2).transform(local);
        }
        orientation.transform(local);
        double x = local.x;
        double z = local.z;
        return position.add(
            Math.cos(radians) * x + Math.sin(radians) * z,
            local.y,
            -Math.sin(radians) * x + Math.cos(radians) * z
        );
    }

    public List<AABB> boxes() {
        var result = new ArrayList<AABB>();
        boolean diagonal = Math.abs(Math.sin(Math.toRadians(angle() * 2.0D))) > 1.0E-5D;
        var definition=ModelCatalog.get(asset);var localBoxes=new ArrayList<AABB>();
        for(var box:definition.collisions()){
            var door=definition.door();double middle=door==null?0:(door.left()+door.right())*.5;
            if(door!=null&&door.mode().equals("double")&&box.minX<middle&&box.maxX>middle){
                localBoxes.add(new AABB(box.minX,box.minY,box.minZ,middle,box.maxY,box.maxZ));
                localBoxes.add(new AABB(middle,box.minY,box.minZ,box.maxX,box.maxY,box.maxZ));
            }else localBoxes.add(box);
        }
        for (var box : localBoxes) {
            int first = diagonal ? (int) Math.floor(box.minX * scale.x * 16.0D) : 0;
            int last = diagonal ? (int) Math.ceil(box.maxX * scale.x * 16.0D) : 1;
            for (int index = first; index < last; index++) {
                double x1 = diagonal
                    ? Math.max(box.minX, index / (scale.x * 16.0D))
                    : box.minX;
                double x2 = diagonal
                    ? Math.min(box.maxX, (index + 1) / (scale.x * 16.0D))
                    : box.maxX;
                Vec3[] corners = new Vec3[8];
                int next = 0;
                for (double x : new double[]{x1, x2})
                    for (double y : new double[]{box.minY, box.maxY})
                        for (double z : new double[]{box.minZ, box.maxZ})
                            corners[next++] = transform(new Vec3(x, y, z),definition.door()!=null&&DoorMotion.right(definition.door(),box.getCenter().x));
                double lowX = Double.POSITIVE_INFINITY;
                double lowY = Double.POSITIVE_INFINITY;
                double lowZ = Double.POSITIVE_INFINITY;
                double highX = Double.NEGATIVE_INFINITY;
                double highY = Double.NEGATIVE_INFINITY;
                double highZ = Double.NEGATIVE_INFINITY;
                for (var corner : corners) {
                    lowX = Math.min(lowX, corner.x);
                    lowY = Math.min(lowY, corner.y);
                    lowZ = Math.min(lowZ, corner.z);
                    highX = Math.max(highX, corner.x);
                    highY = Math.max(highY, corner.y);
                    highZ = Math.max(highZ, corner.z);
                }
                result.add(new AABB(lowX, lowY, lowZ, highX, highY, highZ));
            }
        }
        return result;
    }

    public AABB visualBounds() {
        var local=ModelCatalog.bounds(ModelCatalog.get(asset));
        double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX;
        double maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        for(double x:new double[]{local.minX,(local.minX+local.maxX)*.5,local.maxX})
            for(double y:new double[]{local.minY,local.maxY})
                for(double z:new double[]{local.minZ,local.maxZ}){
                    Vec3 point=transform(new Vec3(x,y,z));
                    minX=Math.min(minX,point.x);minY=Math.min(minY,point.y);minZ=Math.min(minZ,point.z);
                    maxX=Math.max(maxX,point.x);maxY=Math.max(maxY,point.y);maxZ=Math.max(maxZ,point.z);
                }
        return new AABB(minX,minY,minZ,maxX,maxY,maxZ);
    }

    public Map<BlockPos, List<AABB>> fragments() {
        var cells = new LinkedHashMap<BlockPos, List<AABB>>();
        cells.put(owner(), new ArrayList<>());
        for (var box : boxes()) {
            for (int x = (int) Math.floor(box.minX); x < Math.ceil(box.maxX - EPSILON); x++) {
                for (int y = (int) Math.floor(box.minY); y < Math.ceil(box.maxY - EPSILON); y++) {
                    for (int z = (int) Math.floor(box.minZ); z < Math.ceil(box.maxZ - EPSILON); z++) {
                        var cell = new BlockPos(x, y, z);
                        var clipped = new AABB(
                            Math.max(box.minX, x) - x,
                            Math.max(box.minY, y) - y,
                            Math.max(box.minZ, z) - z,
                            Math.min(box.maxX, x + 1.0D) - x,
                            Math.min(box.maxY, y + 1.0D) - y,
                            Math.min(box.maxZ, z + 1.0D) - z
                        );
                        if (clipped.getXsize() > EPSILON
                            && clipped.getYsize() > EPSILON
                            && clipped.getZsize() > EPSILON) {
                            cells.computeIfAbsent(cell, ignored -> new ArrayList<>()).add(clipped);
                        }
                    }
                }
            }
        }
        if(ModelCatalog.get(asset).collisions().isEmpty()){
            var bounds=visualBounds();
            for(int x=(int)Math.floor(bounds.minX+EPSILON);x<Math.ceil(bounds.maxX-EPSILON);x++)
                for(int y=(int)Math.floor(bounds.minY+EPSILON);y<Math.ceil(bounds.maxY-EPSILON);y++)
                    for(int z=(int)Math.floor(bounds.minZ+EPSILON);z<Math.ceil(bounds.maxZ-EPSILON);z++)
                        cells.computeIfAbsent(new BlockPos(x,y,z),ignored->new ArrayList<>());
        }
        return cells;
    }

    public List<AABB> picks(BlockPos cell,List<AABB> collisions){
        if(!ModelCatalog.get(asset).collisions().isEmpty())return collisions;
        var b=visualBounds();int x=cell.getX(),y=cell.getY(),z=cell.getZ();
        double minX=Math.max(b.minX,x)-x,minY=Math.max(b.minY,y)-y,minZ=Math.max(b.minZ,z)-z;
        double maxX=Math.min(b.maxX,x+1)-x,maxY=Math.min(b.maxY,y+1)-y,maxZ=Math.min(b.maxZ,z+1)-z;
        if(maxX-minX<=EPSILON||maxY-minY<=EPSILON||maxZ-minZ<=EPSILON)return List.of();
        var clipped=new AABB(minX,minY,minZ,maxX,maxY,maxZ);
        return clipped.getXsize()>EPSILON&&clipped.getYsize()>EPSILON&&clipped.getZsize()>EPSILON?List.of(clipped):List.of();
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        var tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Asset", asset);
        tag.putDouble("X", position.x);
        tag.putDouble("Y", position.y);
        tag.putDouble("Z", position.z);
        tag.putDouble("SX", scale.x);
        tag.putDouble("SY", scale.y);
        tag.putDouble("SZ", scale.z);
        tag.putDouble("Rotation", rotation);
        tag.putFloat("QX", orientation.x); tag.putFloat("QY", orientation.y);
        tag.putFloat("QZ", orientation.z); tag.putFloat("QW", orientation.w);
        tag.putBoolean("Active", active);
        tag.putLong("GrowAt", growAt);
        tag.putString("SourceData",sourceData);
        tag.putInt("InventorySize",inventory.size());
        var pages=new ListTag();
        for(int start=0;start<inventory.size();start+=27){
            var page=new CompoundTag();var items=NonNullList.withSize(27,ItemStack.EMPTY);
            for(int slot=0;slot<27&&start+slot<inventory.size();slot++)items.set(slot,inventory.get(start+slot));
            ContainerHelper.saveAllItems(page,items,registries);pages.add(page);
        }
        tag.put("InventoryPages",pages);
        return tag;
    }

    public CompoundTag saveForCarry(HolderLookup.Provider registries, long gameTime) {
        var tag = save(registries);
        tag.remove("GrowAt");
        long remaining = !active && ModelCatalog.get(asset).behavior().equals("plant")
            ? Math.max(0L, growAt - gameTime)
            : 0L;
        tag.putLong("GrowthRemaining", remaining);
        return tag;
    }

    public static Piece load(CompoundTag tag, HolderLookup.Provider registries) {
        var piece = new Piece(
            tag.getString("Asset"),
            new Vec3(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z")),
            tag.getDouble("Rotation"),
            new Vec3(tag.getDouble("SX"), tag.getDouble("SY"), tag.getDouble("SZ"))
        );
        piece.id = tag.hasUUID("Id") ? tag.getUUID("Id") : UUID.randomUUID();
        if (tag.contains("QW")) {
            piece.orientation.set(tag.getFloat("QX"), tag.getFloat("QY"), tag.getFloat("QZ"), tag.getFloat("QW"));
            if (!Float.isFinite(piece.orientation.lengthSquared()) || piece.orientation.lengthSquared() < 0.5F) {
                throw new IllegalArgumentException("Invalid component orientation");
            }
            piece.orientation.normalize();
        }
        piece.active = tag.getBoolean("Active");
        piece.growAt = tag.getLong("GrowAt");
        piece.sourceData=tag.getString("SourceData");
        piece.inventorySize(tag.contains("InventorySize")?tag.getInt("InventorySize"):27);
        if(tag.contains("InventoryPages",Tag.TAG_LIST)){
            int start=0;
            for(var raw:tag.getList("InventoryPages",Tag.TAG_COMPOUND)){
                var page=NonNullList.withSize(27,ItemStack.EMPTY);
                ContainerHelper.loadAllItems((CompoundTag)raw,page,registries);
                for(int slot=0;slot<27&&start+slot<piece.inventory.size();slot++)piece.inventory.set(start+slot,page.get(slot));
                start+=27;
            }
        }else ContainerHelper.loadAllItems(tag, piece.inventory, registries);
        return piece;
    }

    public static Piece loadCarried(
        CompoundTag tag,
        HolderLookup.Provider registries,
        long targetGameTime
    ) {
        var piece = load(tag, registries);
        if (ModelCatalog.get(piece.asset).behavior().equals("plant") && !piece.active) {
            piece.growAt = targetGameTime + Math.max(0L, tag.getLong("GrowthRemaining"));
        } else {
            piece.growAt = 0L;
        }
        return piece;
    }

    public static boolean validScale(Vec3 value) {
        return Double.isFinite(value.x)
            && Double.isFinite(value.y)
            && Double.isFinite(value.z)
            && value.x > 0.0D
            && value.y > 0.0D
            && value.z > 0.0D
            && Math.max(value.x, Math.max(value.y, value.z)) <= 4.0D;
    }
}
