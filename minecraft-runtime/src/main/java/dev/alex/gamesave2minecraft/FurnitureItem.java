package dev.alex.gamesave2minecraft;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public final class FurnitureItem extends Item {
    private static final String PIECE_TAG = "GameSave2MinecraftPiece";
    private static final String ASSET_TAG = "GameSave2MinecraftAsset";
    private final String fixedAsset;

    public FurnitureItem(String asset, Properties properties) {
        super(properties);
        this.fixedAsset = asset;
    }

    public String assetId() {
        return fixedAsset;
    }
    @Override public Component getName(ItemStack stack){String id=assetId(stack);return id==null?super.getName(stack):ModelCatalog.displayName(id);}

    public String assetId(ItemStack stack) {
        if (fixedAsset != null) {
            return fixedAsset;
        }
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) {
            return null;
        }
        String candidate = customData.copyTag().getString(ASSET_TAG);
        return ModelCatalog.ASSETS.containsKey(candidate) ? candidate : null;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        var level = context.getLevel();
        var player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        String asset = assetId(context.getItemInHand());
        if (asset == null) {
            player.displayClientMessage(
                Component.literal("通用模型物品缺少有效的目录组件 ID"),
                true
            );
            return InteractionResult.FAIL;
        }
        Vec3 hit = context.getClickLocation()
            .add(Vec3.atLowerCornerOf(context.getClickedFace().getNormal()).scale(0.005D));
        Vec3 position = new Vec3(
            Math.round(hit.x * 64.0D) / 64.0D,
            Math.round(hit.y * 64.0D) / 64.0D,
            Math.round(hit.z * 64.0D) / 64.0D
        );
        var piece = new Piece(
            asset,
            position,
            -player.getDirection().toYRot(),
            new Vec3(1.0D, 1.0D, 1.0D)
        );

        CustomData customData = context.getItemInHand().get(DataComponents.CUSTOM_DATA);
        if (customData != null) {
            var outer = customData.copyTag();
            if (outer.contains(PIECE_TAG, Tag.TAG_COMPOUND)) {
                try {
                    var saved = Piece.loadCarried(
                        outer.getCompound(PIECE_TAG),
                        level.registryAccess(),
                        level.getGameTime()
                    );
                    if (!saved.asset.equals(asset) || !Piece.validScale(saved.scale)) {
                        player.displayClientMessage(
                            Component.literal("搬运数据与物品组件不匹配，已拒绝放置"),
                            true
                        );
                        return InteractionResult.FAIL;
                    }
                    piece.scale = saved.scale;
                    piece.active = saved.active;
                    piece.growAt = saved.growAt;
                    piece.sourceData=saved.sourceData;
                    piece.orientation.set(saved.orientation);
                    piece.inventorySize(saved.inventory.size());
                    for (int slot = 0; slot < piece.inventory.size(); slot++) {
                        piece.inventory.set(slot, saved.inventory.get(slot).copy());
                    }
                } catch (RuntimeException error) {
                    player.displayClientMessage(
                        Component.literal("搬运数据无效，已拒绝放置"),
                        true
                    );
                    return InteractionResult.FAIL;
                }
            }
        }

        String denied = Placement.denial(level, List.of(piece), true);
        if (denied != null) {
            player.displayClientMessage(Component.literal(denied), true);
            return InteractionResult.FAIL;
        }
        Placement.add(level, piece);
        if (!player.isCreative()) {
            context.getItemInHand().shrink(1);
        }
        return InteractionResult.CONSUME;
    }

    public static ItemStack carry(Piece piece, Level level) {
        return carry(piece, level.registryAccess(), level.getGameTime());
    }

    public static ItemStack carry(
        Piece piece,
        HolderLookup.Provider registries,
        long gameTime
    ) {
        var registered = GameSaveMod.ITEMS.get(piece.asset);
        if (!ModelCatalog.ASSETS.containsKey(piece.asset) || !Piece.validScale(piece.scale)) {
            return ItemStack.EMPTY;
        }
        var stack = registered == null
            ? new ItemStack(GameSaveMod.GENERIC_MODEL.get())
            : new ItemStack(registered.get());
        var outer = new net.minecraft.nbt.CompoundTag();
        outer.putString(ASSET_TAG, piece.asset);
        outer.put(PIECE_TAG, piece.saveForCarry(registries, gameTime));
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(outer));
        return stack;
    }

    public static ItemStack stackFor(String asset) {
        if (!ModelCatalog.ASSETS.containsKey(asset)) {
            return ItemStack.EMPTY;
        }
        var registered = GameSaveMod.ITEMS.get(asset);
        if (registered != null) {
            return new ItemStack(registered.get());
        }
        var stack = new ItemStack(GameSaveMod.GENERIC_MODEL.get());
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putString(ASSET_TAG, asset);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }
}
