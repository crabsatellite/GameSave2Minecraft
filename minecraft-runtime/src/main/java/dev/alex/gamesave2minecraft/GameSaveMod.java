package dev.alex.gamesave2minecraft;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Mod(GameSaveMod.ID)
public final class GameSaveMod {
    public static final String ID = "gamesave2mc";
    private static final int INITIAL_FURNITURE_LIMIT = 12;
    private static final Set<String> RESERVED_ITEM_IDS = Set.of(
        "generic_model", "plant_fiber", "cotton_fiber", "sunflower_seed"
    );
    private static final Set<String> PLACEABLE_BEHAVIORS = Set.of(
        "static", "door", "storage", "light", "plant", "seat", "bed", "workstation"
    );

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
    private static final DeferredRegister.Items ITEM_REGISTRY = DeferredRegister.createItems(ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ID);
    private static final DeferredRegister<EntityType<?>> ENTITIES =
        DeferredRegister.create(Registries.ENTITY_TYPE, ID);
    private static final DeferredRegister<CreativeModeTab> TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ID);

    public static final DeferredBlock<CellBlock> CELL = BLOCKS.register(
        "component_cell",
        () -> new CellBlock(
            BlockBehaviour.Properties.of()
                .strength(1.5F)
                .noOcclusion()
                .lightLevel(state -> state.getValue(CellBlock.LIT) ? 14 : 0)
        )
    );
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CellEntity>> CELL_ENTITY =
        BLOCK_ENTITIES.register(
            "component_cell",
            () -> BlockEntityType.Builder.of(CellEntity::new, CELL.get()).build(null)
        );
    public static final DeferredHolder<EntityType<?>, EntityType<SeatEntity>> SEAT =
        ENTITIES.register(
            "seat",
            () -> EntityType.Builder.<SeatEntity>of(SeatEntity::new, MobCategory.MISC)
                .sized(0.01F, 0.01F)
                .clientTrackingRange(8)
                .updateInterval(1)
                .build(ID + ":seat")
        );

    public static final Map<String, DeferredItem<FurnitureItem>> ITEMS = new LinkedHashMap<>();

    static {
        ModelCatalog.ASSETS.values().stream()
            .filter(asset -> !asset.id().startsWith("raw_"))
            .filter(asset -> !RESERVED_ITEM_IDS.contains(asset.id()))
            .filter(asset -> PLACEABLE_BEHAVIORS.contains(asset.behavior()))
            .limit(INITIAL_FURNITURE_LIMIT)
            .forEach(asset -> ITEMS.put(
                asset.id(),
                ITEM_REGISTRY.register(
                    asset.id(),
                    () -> new FurnitureItem(asset.id(), new net.minecraft.world.item.Item.Properties())
                )
            ));
    }

    public static final DeferredItem<FurnitureItem> GENERIC_MODEL = ITEM_REGISTRY.register(
        "generic_model",
        () -> new FurnitureItem(null, new net.minecraft.world.item.Item.Properties().stacksTo(1))
    );
    public static final DeferredItem<Item> PLANT_FIBER = ITEM_REGISTRY.register(
        "plant_fiber",
        () -> new Item(new Item.Properties())
    );
    public static final DeferredItem<Item> COTTON_FIBER = ITEM_REGISTRY.register(
        "cotton_fiber",
        () -> new Item(new Item.Properties())
    );
    public static final DeferredItem<Item> SUNFLOWER_SEED = ITEM_REGISTRY.register(
        "sunflower_seed",
        () -> new Item(new Item.Properties())
    );
    public static final DeferredItem<Item> SOURCE_RESOURCE = ITEM_REGISTRY.register(
        "source_resource", () -> new Item(new Item.Properties().stacksTo(1))
    );

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register(
        "components",
        () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup." + ID))
            .icon(() -> ITEMS.values().stream()
                .findFirst()
                .map(item -> new ItemStack(item.get()))
                .orElseGet(() -> new ItemStack(Items.CHEST)))
            .displayItems((parameters, output) ->
            {
                ITEMS.values().forEach(item -> output.accept(item.get()));
                output.accept(PLANT_FIBER.get());
                output.accept(COTTON_FIBER.get());
                output.accept(SUNFLOWER_SEED.get());
            })
            .build()
    );

    public GameSaveMod(IEventBus bus) {
        BLOCKS.register(bus);
        ITEM_REGISTRY.register(bus);
        BLOCK_ENTITIES.register(bus);
        ENTITIES.register(bus);
        TABS.register(bus);
        NeoForge.EVENT_BUS.addListener(GameSaveMod::commands);
        NeoForge.EVENT_BUS.addListener(GameSaveMod::breakPiece);
        NeoForge.EVENT_BUS.addListener(SceneImport::tick);
        NeoForge.EVENT_BUS.addListener(SceneImport::stopping);
    }

    private static void commands(RegisterCommandsEvent event) {
        var demo = Commands.literal("demo")
            .executes(context -> buildDemo(
                context.getSource(),
                BlockPos.containing(context.getSource().getPosition()).offset(2, 0, 2)
            ))
            .then(Commands.argument("origin", BlockPosArgument.blockPos())
                .executes(context -> buildDemo(
                    context.getSource(),
                    BlockPosArgument.getLoadedBlockPos(context, "origin")
                )));

        var give = Commands.literal("give")
            .then(Commands.argument("asset", StringArgumentType.word())
                .suggests((context, builder) -> {
                    String prefix = builder.getRemainingLowerCase();
                    ModelCatalog.ASSETS.keySet().stream()
                        .filter(id -> id.toLowerCase(java.util.Locale.ROOT).startsWith(prefix))
                        .limit(100)
                        .forEach(builder::suggest);
                    return builder.buildFuture();
                })
                .executes(context -> give(
                    context.getSource(),
                    StringArgumentType.getString(context, "asset")
                )));

        event.getDispatcher().register(
            Commands.literal(ID)
                .requires(source -> source.hasPermission(2))
                .then(demo)
                .then(give)
                .then(SceneImport.commands())
        );
    }

    private static int buildDemo(
        net.minecraft.commands.CommandSourceStack source,
        BlockPos origin
    ) {
        var pieces = Placement.demoCourtyard(origin);
        if (pieces.isEmpty()) {
            source.sendFailure(Component.literal("没有可注册的交互组件，无法生成演示庭院"));
            return 0;
        }
        String denied = Placement.denial(source.getLevel(), pieces, true);
        if (denied != null) {
            source.sendFailure(Component.literal(denied));
            return 0;
        }
        pieces.forEach(piece -> Placement.add(source.getLevel(), piece));
        source.sendSuccess(
            () -> Component.literal(
                "Generated original GameSave2Minecraft demonstration: " + pieces.size()
                    + " components."
            ),
            false
        );
        return pieces.size();
    }

    private static int give(
        net.minecraft.commands.CommandSourceStack source,
        String asset
    ) throws CommandSyntaxException {
        var registered = ITEMS.get(asset);
        if (registered == null && !ModelCatalog.ASSETS.containsKey(asset)) {
            source.sendFailure(Component.literal("模型目录中不存在组件：" + asset));
            return 0;
        }
        var player = source.getPlayerOrException();
        var stack = registered == null
            ? FurnitureItem.stackFor(asset)
            : new ItemStack(registered.get());
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        source.sendSuccess(
            () -> Component.literal("已给予组件：").append(ModelCatalog.displayName(asset)),
            false
        );
        return 1;
    }

    private static void breakPiece(BlockEvent.BreakEvent event) {
        if (!event.getState().is(CELL.get())
            || !(event.getLevel() instanceof Level level)
            || level.isClientSide) {
            return;
        }
        event.setCanceled(true);
        var player = event.getPlayer();
        var hit = player.pick(6.0D, 0.0F, false);
        if (!(level.getBlockEntity(event.getPos()) instanceof CellEntity cell)) {
            return;
        }
        Piece piece = hit instanceof BlockHitResult blockHit
            ? cell.selected(blockHit.getLocation())
            : null;
        if (piece == null && cell.roots.size() == 1) {
            piece = cell.roots.getFirst();
        }
        if (piece == null) {
            player.displayClientMessage(Component.literal("没有选中可独立拆除的组件"), true);
            return;
        }
        var stack = FurnitureItem.carry(piece, level);
        if (stack.isEmpty()) {
            player.displayClientMessage(Component.literal("该组件没有注册搬运物品"), true);
            return;
        }
        Placement.remove(level, piece);
        if (player.isCreative()) {
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
        } else {
            Block.popResource(level, event.getPos(), stack);
        }
        level.sendBlockUpdated(
            event.getPos(),
            level.getBlockState(event.getPos()),
            level.getBlockState(event.getPos()),
            3
        );
    }
}
