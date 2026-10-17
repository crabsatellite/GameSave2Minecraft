package dev.alex.gamesave2minecraft;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class CellBlock extends BaseEntityBlock {
    public static final MapCodec<CellBlock> CODEC = simpleCodec(CellBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty LIT = BlockStateProperties.LIT;
    public static final BooleanProperty GROWING = BooleanProperty.create("growing");

    public CellBlock(Properties properties) {
        super(properties);
        registerDefaultState(
            stateDefinition.any()
                .setValue(FACING, Direction.SOUTH)
                .setValue(LIT, false)
                .setValue(GROWING,false)
        );
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LIT,GROWING);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CellEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    protected VoxelShape getShape(
        BlockState state,
        BlockGetter level,
        BlockPos pos,
        CollisionContext context
    ) {
        return level.getBlockEntity(pos) instanceof CellEntity cell
            ? cell.selectionShape()
            : Shapes.empty();
    }

    @Override
    protected VoxelShape getCollisionShape(
        BlockState state,
        BlockGetter level,
        BlockPos pos,
        CollisionContext context
    ) {
        return level.getBlockEntity(pos) instanceof CellEntity cell
            ? cell.shape()
            : Shapes.empty();
    }

    @Override
    protected InteractionResult useWithoutItem(
        BlockState state,
        Level level,
        BlockPos pos,
        Player player,
        BlockHitResult hit
    ) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof CellEntity cell) {
            var piece = cell.selected(hit.getLocation());
            if (piece != null) {
                return Household.use(level, player, piece);
            }
        }
        return InteractionResult.PASS;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
        Level level,
        BlockState state,
        BlockEntityType<T> type
    ) {
        return level.isClientSide || !state.getValue(GROWING)
            ? null
            : createTickerHelper(type, GameSaveMod.CELL_ENTITY.get(), CellEntity::serverTick);
    }

    @Override
    protected void onRemove(
        BlockState state,
        Level level,
        BlockPos pos,
        BlockState newState,
        boolean movedByPiston
    ) {
        if (!state.is(newState.getBlock())
            && level.getBlockEntity(pos) instanceof CellEntity cell) {
            if (!level.isClientSide && !Placement.isCoordinatedRemoval()) {
                Placement.removeExternally(level, pos, cell);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public boolean isBed(
        BlockState state,
        BlockGetter level,
        BlockPos pos,
        LivingEntity sleeper
    ) {
        return level.getBlockEntity(pos) instanceof CellEntity cell && cell.bed() != null;
    }

    @Override
    public Direction getBedDirection(BlockState state, LevelReader level, BlockPos pos) {
        return state.getValue(FACING);
    }

    @Override
    public void setBedOccupied(
        BlockState state,
        Level level,
        BlockPos pos,
        LivingEntity sleeper,
        boolean occupied
    ) {}

    @Override
    public ItemStack getCloneItemStack(
        BlockState state,
        HitResult hit,
        LevelReader level,
        BlockPos pos,
        Player player
    ) {
        if (hit instanceof BlockHitResult blockHit
            && level.getBlockEntity(pos) instanceof CellEntity cell) {
            var piece = cell.selected(blockHit.getLocation());
            if (piece != null && level instanceof Level actualLevel) {
                return FurnitureItem.carry(piece, actualLevel);
            }
        }
        return ItemStack.EMPTY;
    }
}
