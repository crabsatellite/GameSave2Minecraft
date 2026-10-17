package dev.alex.gamesave2minecraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class Household {
    public static InteractionResult use(Level level, Player player, Piece piece) {
        var asset = ModelCatalog.get(piece.asset);
        if (player.isShiftKeyDown()) {
            if (!Placement.rotate(level, piece)) {
                player.displayClientMessage(Component.literal("旋转位置被其他组件或方块占用"), true);
                return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
        }

        switch (asset.behavior()) {
            case "bed" -> useBed(level, player, piece, asset);
            case "seat" -> {
                Vec3 target = interactionPoint(piece, asset);
                if (!level.getEntitiesOfClass(
                    SeatEntity.class,
                    new AABB(target, target).inflate(0.15D)
                ).isEmpty()) {
                    player.displayClientMessage(Component.literal("座位已有人使用"), true);
                    return InteractionResult.FAIL;
                }
                var seat = new SeatEntity(GameSaveMod.SEAT.get(), level);
                seat.owner = piece.owner();
                seat.pieceId = piece.id;
                seat.exitY = piece.position.y;
                seat.setPos(target.x, target.y, target.z);
                level.addFreshEntity(seat);
                if (!player.startRiding(seat, true)) {
                    seat.discard();
                }
            }
            case "storage" -> openStorage(level, player, piece, asset);
            case "door" -> {
                if (!Placement.toggleDoor(level, piece)) {
                    player.displayClientMessage(Component.literal("门的转动区域被挡住"), true);
                    return InteractionResult.FAIL;
                }
                level.playSound(
                    null,
                    piece.owner(),
                    piece.active ? SoundEvents.WOODEN_DOOR_OPEN : SoundEvents.WOODEN_DOOR_CLOSE,
                    SoundSource.BLOCKS,
                    0.55F,
                    1.0F
                );
            }
            case "light" -> toggle(level, piece);
            case "plant" -> harvest(level, player, piece, asset);
            case "workstation" -> {
                if(!piece.sourceData.isEmpty())openStorage(level,player,piece,asset);
                else openCrafting(level,player,piece,asset);
            }
            default -> {
                return InteractionResult.PASS;
            }
        }
        return InteractionResult.SUCCESS;
    }

    private static void openCrafting(Level level,Player player,Piece piece,ModelCatalog.Asset asset){
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, user) -> new CraftingMenu(
                    containerId,
                    inventory,
                    ContainerLevelAccess.create(level, piece.owner())
                ) {
                    @Override
                    public boolean stillValid(Player menuUser) {
                        return pieceIsAvailable(level, piece, menuUser);
                    }
                },
                ModelCatalog.displayName(asset.id())
            ));
    }

    private static void useBed(
        Level level,
        Player player,
        Piece piece,
        ModelCatalog.Asset asset
    ) {
        if (!level.dimensionType().bedWorks()) {
            player.displayClientMessage(Component.literal("这个维度不能睡觉"), true);
            return;
        }
        Vec3 target = interactionPoint(piece, asset);
        BlockPos sleepingPos = BlockPos.containing(target);
        if (level.players().stream().anyMatch(other ->
            other != player
                && other.isSleeping()
                && other.getSleepingPos().filter(sleepingPos::equals).isPresent())) {
            player.displayClientMessage(Component.literal("床已有人使用"), true);
            return;
        }
        player.startSleepInBed(sleepingPos).ifLeft(problem -> {
            if (problem.getMessage() != null) {
                player.displayClientMessage(problem.getMessage(), true);
            }
        });
        if (player.isSleeping()) {
            player.setPos(target.x, target.y + 0.10D, target.z);
        }
    }

    private static void openStorage(
        Level level,
        Player player,
        Piece piece,
        ModelCatalog.Asset asset
    ) {
        var inventory = new LivePieceContainer(level, piece);
        player.openMenu(new SimpleMenuProvider(
            (containerId, playerInventory, user) -> {
                if(!inventory.paged)return ChestMenu.threeRows(containerId, playerInventory, inventory);
                return new ChestMenu(MenuType.GENERIC_9x6,containerId,playerInventory,inventory,6){
                    {
                        for(int index=0;index<54;index++){
                            final int mapped=index;var old=getSlot(index);
                            var guarded=new Slot(inventory,index,old.x,old.y){
                                @Override public boolean mayPlace(ItemStack stack){return inventory.validSlot(mapped);}
                                @Override public boolean mayPickup(Player actor){return inventory.validSlot(mapped);}
                            };
                            guarded.index=index;slots.set(index,guarded);
                        }
                    }
                    @Override public void clicked(int slot,int button,ClickType click,Player actor){
                        if(slot>=45&&slot<54){
                            if(click!=ClickType.PICKUP)return;
                            if(slot==45)inventory.page=Math.max(0,inventory.page-1);
                            if(slot==53)inventory.page=Math.min(inventory.pages()-1,inventory.page+1);
                            if(slot==47&&asset.behavior().equals("workstation")){openCrafting(level,actor,piece,asset);return;}
                            broadcastChanges();return;
                        }
                        super.clicked(slot,button,click,actor);
                    }
                    @Override public ItemStack quickMoveStack(Player actor,int slot){return slot>=45&&slot<54?ItemStack.EMPTY:super.quickMoveStack(actor,slot);}
                    @Override public boolean canDragTo(Slot slot){return slot.index>=54||inventory.validSlot(slot.index);}
                };
            },
            ModelCatalog.displayName(asset.id())
        ));
    }

    private static Piece resolveLivePiece(Level level, Piece original) {
        if (level.getBlockEntity(original.owner()) instanceof CellEntity cell) {
            Piece livePiece = cell.root(original.id);
            if (livePiece != null && livePiece.asset.equals(original.asset)) {
                return livePiece;
            }
        }
        return null;
    }

    private static boolean pieceIsAvailable(Level level, Piece piece, Player player) {
        return resolveLivePiece(level, piece) != null
            && player.distanceToSqr(piece.position) <= 64.0D;
    }

    /**
     * A menu-facing view of the current root Piece. It never owns an inventory
     * snapshot, so concurrent viewers operate on one server-side list.
     */
    private static final class LivePieceContainer implements Container {
        private final boolean paged;
        private int page;
        private final Level level;
        private final Piece identity;

        private LivePieceContainer(Level level, Piece identity) {
            this.level = level;
            this.identity = identity;
            this.paged=!identity.sourceData.isEmpty()||identity.inventory.size()>27;
        }

        private Piece live() {
            return resolveLivePiece(level, identity);
        }

        private boolean validSlot(int slot) {
            return slot >= 0 && slot < (paged?45:27) && index(slot)<identity.inventory.size();
        }
        private int index(int slot){return (paged?page*45:0)+slot;}
        private int pages(){return Math.max(1,(identity.inventory.size()+44)/45);}
        private ItemStack control(Item item,String label){var stack=new ItemStack(item);stack.set(DataComponents.CUSTOM_NAME,Component.literal(label));return stack;}

        @Override
        public int getContainerSize() {
            return paged?54:27;
        }

        @Override
        public boolean isEmpty() {
            Piece piece = live();
            if (piece == null) {
                return true;
            }
            return piece.inventory.stream().allMatch(ItemStack::isEmpty);
        }

        @Override
        public ItemStack getItem(int slot) {
            Piece piece = live();
            if(paged&&slot>=45&&slot<54){
                if(slot==45)return control(Items.ARROW,"上一页");
                if(slot==53)return control(Items.ARROW,"下一页");
                if(slot==49)return control(Items.PAPER,"第 "+(page+1)+" / "+pages()+" 页");
                if(slot==47&&ModelCatalog.get(identity.asset).behavior().equals("workstation"))return control(Items.CRAFTING_TABLE,"打开 Minecraft 合成界面");
                return ItemStack.EMPTY;
            }
            return piece != null && validSlot(slot)
                ? piece.inventory.get(index(slot))
                : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            Piece piece = live();
            if (piece == null || !validSlot(slot)) {
                return ItemStack.EMPTY;
            }
            ItemStack removed = ContainerHelper.removeItem(piece.inventory, index(slot), amount);
            if (!removed.isEmpty()) {
                setChanged();
            }
            return removed;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            Piece piece = live();
            if (piece == null || !validSlot(slot)) {
                return ItemStack.EMPTY;
            }
            ItemStack removed = ContainerHelper.takeItem(piece.inventory, index(slot));
            if (!removed.isEmpty()) {
                setChanged();
            }
            return removed;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            Piece piece = live();
            if (piece == null || !validSlot(slot)) {
                return;
            }
            piece.inventory.set(index(slot), stack);
            stack.limitSize(getMaxStackSize(stack));
            setChanged();
        }

        @Override
        public void setChanged() {
            Piece piece = live();
            if (piece != null
                && level.getBlockEntity(piece.owner()) instanceof CellEntity cell) {
                cell.changed();
            }
        }

        @Override
        public boolean stillValid(Player player) {
            return pieceIsAvailable(level, identity, player);
        }

        @Override
        public void clearContent() {
            Piece piece = live();
            if (piece == null) {
                return;
            }
            piece.inventory.clear();
            setChanged();
        }
    }

    private static void harvest(
        Level level,
        Player player,
        Piece piece,
        ModelCatalog.Asset asset
    ) {
        if (!piece.active) {
            long remaining = Math.max(0L, piece.growAt - level.getGameTime());
            player.displayClientMessage(
                Component.literal("植物仍在生长，剩余约 " + ((remaining + 19L) / 20L) + " 秒"),
                true
            );
            return;
        }

        String harvestItem = asset.harvestItem();
        if (harvestItem != null && !harvestItem.isBlank()) {
            var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(harvestItem));
            if (item != Items.AIR) {
                var stack = new ItemStack(item);
                if (!player.getInventory().add(stack)) {
                    player.drop(stack, false);
                }
            }
        }

        piece.active = false;
        piece.growAt = level.getGameTime() + Math.max(0, asset.growTicks());
        if (level.getBlockEntity(piece.owner()) instanceof CellEntity cell) {
            cell.changed();
        }
        level.playSound(
            null,
            piece.owner(),
            SoundEvents.CROP_BREAK,
            SoundSource.BLOCKS,
            0.5F,
            1.0F
        );
    }

    private static Vec3 interactionPoint(Piece piece, ModelCatalog.Asset asset) {
        return piece.transform(asset.seat() == null ? Vec3.ZERO : asset.seat());
    }

    private static void toggle(Level level, Piece piece) {
        piece.active = !piece.active;
        if (level.getBlockEntity(piece.owner()) instanceof CellEntity cell) {
            cell.changed();
        }
        level.playSound(
            null,
            piece.owner(),
            SoundEvents.LEVER_CLICK,
            SoundSource.BLOCKS,
            0.25F,
            piece.active ? 0.6F : 0.5F
        );
    }

    private Household() {}
}
