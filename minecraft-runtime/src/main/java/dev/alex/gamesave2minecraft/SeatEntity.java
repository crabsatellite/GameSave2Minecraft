package dev.alex.gamesave2minecraft;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public final class SeatEntity extends Entity {
    public BlockPos owner = BlockPos.ZERO;
    public UUID pieceId;
    public double exitY;

    public SeatEntity(EntityType<? extends SeatEntity> type, Level level) {
        super(type, level);
        setNoGravity(true);
        setInvisible(true);
    }

    public static void discard(Level level, BlockPos owner, UUID pieceId) {
        if (level.isClientSide || pieceId == null) {
            return;
        }
        for (var seat : level.getEntitiesOfClass(
            SeatEntity.class,
            new AABB(owner).inflate(32.0D),
            seat -> owner.equals(seat.owner) && pieceId.equals(seat.pieceId)
        )) {
            seat.ejectPassengers();
            seat.discard();
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {}

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        owner = BlockPos.of(tag.getLong("Owner"));
        pieceId = tag.hasUUID("Piece") ? tag.getUUID("Piece") : null;
        exitY = tag.getDouble("ExitY");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putLong("Owner", owner.asLong());
        if (pieceId != null) {
            tag.putUUID("Piece", pieceId);
        }
        tag.putDouble("ExitY", exitY);
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && tickCount > 5) {
            if (getPassengers().isEmpty()
                || !(level().getBlockEntity(owner) instanceof CellEntity cell)
                || cell.root(pieceId) == null) {
                ejectPassengers();
                discard();
            }
        }
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (!level().isClientSide && getPassengers().isEmpty()) {
            discard();
        }
    }

    @Override
    protected void positionRider(Entity passenger, MoveFunction move) {
        move.accept(passenger, getX(), getY(), getZ());
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        double[][] offsets = {
            {0.0D, -0.75D},
            {0.75D, 0.0D},
            {0.0D, 0.75D},
            {-0.75D, 0.0D},
            {0.75D, 0.75D},
            {-0.75D, -0.75D},
            {1.2D, 0.0D},
            {0.0D, 1.2D}
        };
        for (double[] offset : offsets) {
            var position = new Vec3(getX() + offset[0], exitY, getZ() + offset[1]);
            var movedBounds = passenger.getBoundingBox()
                .move(position.subtract(passenger.position()));
            BlockPos floor = BlockPos.containing(position.add(0.0D, -0.1D, 0.0D));
            if (level().noCollision(passenger, movedBounds)
                && !level().getBlockState(floor)
                    .getCollisionShape(level(), floor)
                    .isEmpty()) {
                return position;
            }
        }
        return super.getDismountLocationForPassenger(passenger);
    }
}
