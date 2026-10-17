package dev.alex.gamesave2minecraft.testing;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.alex.gamesave2minecraft.CellEntity;
import dev.alex.gamesave2minecraft.FurnitureItem;
import dev.alex.gamesave2minecraft.Household;
import dev.alex.gamesave2minecraft.ModelCatalog;
import dev.alex.gamesave2minecraft.Piece;
import dev.alex.gamesave2minecraft.Placement;
import dev.alex.gamesave2minecraft.SeatEntity;
import dev.alex.gamesave2minecraft.GameSaveMod;
import dev.alex.gamesave2minecraft.ScenePlans;
import dev.alex.gamesave2minecraft.SceneImport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

@GameTestHolder(GameSaveMod.ID)
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = GameSaveMod.ID)
public final class GameSave2MinecraftGameTests {
    private static final Set<String> CURATED = Set.of("door","bed","chair","crop","floor","lamp","chest","bench");

    private record Case(String id, Runnable action) {
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void componentContractBatch(GameTestHelper helper) {
        var level = helper.getLevel();
        Vec3 origin = Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(16, 3, 16)));
        var owned = new ArrayList<Piece>();
        var players = new ArrayList<ServerPlayer>();
        var seats = new ArrayList<SeatEntity>();
        var droppedItems = new ArrayList<ItemEntity>();

        Function<String, Piece> add = asset -> {
            Piece piece = new Piece(asset, origin, 0, new Vec3(1, 1, 1));
            String denial = Placement.denial(level, List.of(piece), true);
            helper.assertTrue(denial == null, "Placement was denied for " + asset + ": " + denial);
            Placement.add(level, piece);
            owned.add(piece);
            return piece;
        };
        Supplier<ServerPlayer> player = () -> {
            ServerPlayer result = helper.makeMockServerPlayerInLevel();
            result.setPos(origin.x + .5, origin.y, origin.z - 1);
            players.add(result);
            return result;
        };

        List<Case> cases = List.of(
                new Case("normalized_scene_and_source_lot_are_stable",()->{
                    helper.assertTrue(ScenePlans.IDS.equals(List.of("original_demo")),"Scene registry is not data-driven");
                    var plan=ScenePlans.read("original_demo");helper.assertTrue(plan.placements().size()==15,"Original fixture lost instances");
                    var row=plan.placements().stream().filter(r->r.get("asset").getAsString().equals("chest")).findFirst().orElseThrow();
                    var first=ScenePlans.piece(plan,row);var second=ScenePlans.piece(plan,row);
                    helper.assertTrue(first.id.equals(second.id),"Import retry changes identity");
                    var restored=Piece.load(first.save(level.registryAccess()),level.registryAccess());
                    helper.assertTrue(ItemStack.matches(first.inventory.get(37),restored.inventory.get(37)),"Source lot components changed during native NBT round trip");
                    var data=restored.inventory.get(37).get(DataComponents.CUSTOM_DATA).copyTag();
                    helper.assertTrue(restored.inventory.size()==54&&data.getLong("SourceAmount")==100000&&data.getString("SourceId").equals("demo:ore"),"Source slot/amount/identity changed through native NBT");
                }),
                new Case("catalog_uses_external_triangle_meshes_and_legal_collision", () -> {
                    helper.assertTrue(ModelCatalog.ASSETS.keySet().containsAll(CURATED),
                            "Curated catalog is incomplete: " + difference(CURATED, ModelCatalog.ASSETS.keySet()));
                    for (String id : CURATED) {
                        ModelCatalog.Asset asset = ModelCatalog.get(id);
                        helper.assertTrue(asset.cubes().isEmpty(), id + " fell back to authored cube geometry");
                        helper.assertTrue(asset.mesh() != null && !asset.mesh().isBlank(),
                                id + " has no imported triangle mesh");
                        helper.assertTrue(asset.behavior().equals("plant") || !asset.collisions().isEmpty(), id + " has no collision contract");
                        for (AABB box : asset.collisions()) {
                            helper.assertTrue(finite(box) && box.getXsize() > 0 && box.getYsize() > 0
                                            && box.getZsize() > 0,
                                    id + " has an invalid collision box: " + box);
                        }
                    }
                }),
                new Case("external_fragment_replacement_cleans_piece_and_drops_one_carry", () -> {
                    Piece pillar = add.apply("floor");
                    pillar.inventory.set(11, new ItemStack(Items.DIAMOND, 7));
                    var cells = new ArrayList<>(pillar.fragments().keySet());
                    helper.assertTrue(cells.size() > 1, "Component collapsed to one block cell");
                    var nonOwners = cells.stream().filter(pos -> !pos.equals(pillar.owner())).toList();
                    helper.assertTrue(!nonOwners.isEmpty(), "Component has no non-owner fragment");
                    BlockPos replaced = nonOwners.get(level.random.nextInt(nonOwners.size()));
                    level.setBlock(replaced, Blocks.STONE.defaultBlockState(), 3);
                    helper.assertTrue(level.getBlockState(replaced).is(Blocks.STONE),
                            "External replacement was overwritten during coordinated cleanup");
                    helper.assertTrue(cells.stream()
                                    .filter(pos -> !pos.equals(replaced))
                                    .noneMatch(pos -> level.getBlockState(pos).is(GameSaveMod.CELL.get())),
                            "External replacement left another fragment cell behind");
                    AABB search = new AABB(Vec3.atCenterOf(replaced), Vec3.atCenterOf(replaced)).inflate(3);
                    var matchingDrops = level.getEntitiesOfClass(ItemEntity.class, search).stream()
                            .filter(item -> pillar.asset.equals(assetId(item.getItem())))
                            .toList();
                    droppedItems.addAll(matchingDrops);
                    helper.assertTrue(matchingDrops.size() == 1,
                            "External fragment replacement produced " + matchingDrops.size()
                                    + " carry drops instead of one");
                    Piece carried = carriedPiece(matchingDrops.getFirst().getItem(), level.registryAccess(),
                            level.getGameTime());
                    helper.assertTrue(carried.inventory.get(11).is(Items.DIAMOND)
                                    && carried.inventory.get(11).getCount() == 7,
                            "External replacement lost the original piece inventory");
                    owned.remove(pillar);
                    matchingDrops.forEach(Entity::discard);
                    droppedItems.removeAll(matchingDrops);
                    level.removeBlock(replaced, false);
                }),
                new Case("placement_denial_and_creative_break_preserve_world_and_carry", () -> {
                    Piece piece = new Piece("floor", origin, 0, new Vec3(1, 1, 1));
                    BlockPos blocked = piece.fragments().keySet().iterator().next();
                    var previous = level.getBlockState(blocked);
                    try {
                        level.setBlock(blocked, Blocks.STONE.defaultBlockState(), 3);
                        helper.assertTrue(Placement.denial(level, List.of(piece), false) != null,
                                "Placement accepted an occupied stone block");
                        helper.assertTrue(level.getBlockState(blocked).is(Blocks.STONE),
                                "Denied placement modified the existing block");
                    } finally {
                        level.setBlock(blocked, previous, 3);
                    }
                    Piece chest = add.apply("chest");
                    chest.inventory.set(6, new ItemStack(Items.EMERALD, 9));
                    var occupied = new ArrayList<>(chest.fragments().keySet());
                    ServerPlayer actor = player.get();
                    actor.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
                    actor.setPos(chest.position.x, chest.position.y, chest.position.z - 1);
                    actor.gameMode.destroyBlock(chest.owner());
                    helper.assertTrue(occupied.stream()
                                    .noneMatch(pos -> level.getBlockState(pos).is(GameSaveMod.CELL.get())),
                            "Creative destroy left storage fragments behind");
                    var carries = actor.getInventory().items.stream()
                            .filter(stack -> chest.asset.equals(assetId(stack)))
                            .toList();
                    helper.assertTrue(carries.size() == 1,
                            "Creative destroy recovered " + carries.size() + " carries instead of one");
                    Piece carried = carriedPiece(carries.getFirst(), level.registryAccess(),
                            level.getGameTime());
                    helper.assertTrue(carried.inventory.get(6).is(Items.EMERALD)
                                    && carried.inventory.get(6).getCount() == 9,
                            "Creative destroy did not preserve the storage inventory");
                    owned.remove(chest);
                }),
                new Case("door_toggle_changes_collision_and_preserves_identity", () -> {
                    Piece door = add.apply("door");
                    UUID id = door.id;
                    List<AABB> closed = List.copyOf(door.boxes());
                    helper.assertTrue(Placement.toggleDoor(level, door), "Component door could not open");
                    helper.assertTrue(door.active && door.id.equals(id), "Door identity changed while opening");
                    helper.assertTrue(!sameBoxes(closed, door.boxes()), "Door collision did not change when opened");
                    helper.assertTrue(Placement.toggleDoor(level, door), "Component door could not close");
                    helper.assertTrue(!door.active && door.id.equals(id), "Door did not return to its closed identity");
                    helper.assertTrue(level.getBlockEntity(door.owner()) instanceof CellEntity cell
                                    && cell.root(id) != null,
                            "Door root disappeared after open/close");
                }),
                new Case("bed_uses_native_sleep_and_respawn", () -> {
                    Piece bed = add.apply("bed");
                    ServerPlayer actor = player.get();
                    Vec3 target = bed.transform(ModelCatalog.get(bed.asset).seat());
                    BlockPos bedPos = BlockPos.containing(target);
                    actor.setPos(target.x, target.y, target.z - .8);
                    long previousTime = level.getDayTime();
                    try {
                        level.setDayTime(13000);
                        level.updateSkyBrightness();
                        helper.assertTrue(level.getBlockState(bedPos).isBed(level, bedPos, actor),
                                "Bed is not exposed through native bed hooks");
                        Household.use(level, actor, bed);
                        if (!actor.isSleeping()) {
                            helper.fail("Native sleep rejected the bed: " + actor.startSleepInBed(bedPos).left());
                        }
                        helper.assertTrue(bedPos.equals(actor.getRespawnPosition()),
                                "Bed did not set the native respawn position");
                        actor.stopSleepInBed(true, true);
                    } finally {
                        if (actor.isSleeping()) {
                            actor.stopSleepInBed(true, true);
                        }
                        level.setDayTime(previousTime);
                        level.updateSkyBrightness();
                    }
                }),
                new Case("chair_mounts_native_owned_seat", () -> {
                    Piece chair = add.apply("chair");
                    ServerPlayer actor = player.get();
                    Household.use(level, actor, chair);
                    helper.assertTrue(actor.getVehicle() instanceof SeatEntity,
                            "Chair interaction did not mount the native seat entity");
                    SeatEntity seat = (SeatEntity) actor.getVehicle();
                    seats.add(seat);
                    helper.assertTrue(chair.id.equals(seat.pieceId), "Seat ownership does not match the chair");
                    actor.stopRiding();
                    helper.assertTrue(seat.isRemoved(), "Unused chair seat leaked after dismount");
                }),
                new Case("storage_inventory_has_27_slots_and_survives_nbt", () -> {
                    Piece chest = add.apply("chest");
                    helper.assertTrue(chest.inventory.size() == 27, "Storage does not expose 27 slots");
                    chest.inventory.set(0, new ItemStack(Items.DIAMOND, 5));
                    chest.inventory.set(26, new ItemStack(Items.BOOK, 3));
                    ServerPlayer first = player.get();
                    ServerPlayer second = player.get();
                    InteractionResult firstResult = Household.use(level, first, chest);
                    InteractionResult secondResult = Household.use(level, second, chest);
                    helper.assertTrue(firstResult.consumesAction() && secondResult.consumesAction()
                                    && first.containerMenu instanceof ChestMenu
                                    && second.containerMenu instanceof ChestMenu,
                            "Storage did not open native menus for both players");
                    var firstMenu = (ChestMenu) first.containerMenu;
                    var secondMenu = (ChestMenu) second.containerMenu;
                    helper.assertTrue(firstMenu.stillValid(first) && secondMenu.stillValid(second),
                            "Live storage menus were invalid before the piece was removed");
                    ItemStack taken = firstMenu.getSlot(0).remove(5);
                    firstMenu.getSlot(0).setChanged();
                    helper.assertTrue(taken.is(Items.DIAMOND) && taken.getCount() == 5,
                            "First player did not take the stored diamonds");
                    helper.assertTrue(secondMenu.getSlot(0).getItem().isEmpty()
                                    && chest.inventory.get(0).isEmpty(),
                            "Second storage menu retained a stale duplicate stack");
                    CompoundTag saved = chest.save(level.registryAccess());
                    Piece restored = Piece.load(saved, level.registryAccess());
                    helper.assertTrue(restored.inventory.size() == 27
                                    && restored.inventory.get(0).isEmpty()
                                    && restored.inventory.get(26).is(Items.BOOK)
                                    && restored.inventory.get(26).getCount() == 3,
                            "Storage contents did not survive piece NBT");
                    Placement.remove(level, chest);
                    owned.remove(chest);
                    helper.assertTrue(!firstMenu.stillValid(first) && !secondMenu.stillValid(second),
                            "Storage menus stayed valid after their piece was removed");
                    first.closeContainer();
                    second.closeContainer();
                }),
                new Case("lamp_uses_native_emission", () -> {
                    Piece light = add.apply("lamp");
                    ServerPlayer actor = player.get();
                    helper.assertTrue(light.active
                                    && level.getBlockState(light.owner()).getLightEmission(level, light.owner()) == 14,
                            "Ceiling light did not begin with native block light");
                    Household.use(level, actor, light);
                    helper.assertTrue(!light.active
                                    && level.getBlockState(light.owner()).getLightEmission(level, light.owner()) == 0,
                            "Ceiling light did not switch off through the interaction");
                    Household.use(level, actor, light);
                    helper.assertTrue(light.active
                                    && level.getBlockState(light.owner()).getLightEmission(level, light.owner()) == 14,
                            "Ceiling light did not switch back on");
                }),
                new Case("plant_harvest_is_single_and_regrows_from_production_tick", () -> {
                    Piece crop = add.apply("crop");
                    ServerPlayer actor = player.get();
                    Item harvest = BuiltInRegistries.ITEM.get(
                            ResourceLocation.parse(ModelCatalog.get(crop.asset).harvestItem())
                    );
                    helper.assertTrue(harvest != Items.AIR,
                            "Cotton harvest item is not registered");
                    crop.active = true;
                    int before = count(actor, harvest);
                    helper.assertTrue(Household.use(level, actor, crop).consumesAction(),
                            "Mature crop was not harvested");
                    int afterFirst = count(actor, harvest);
                    helper.assertTrue(!crop.active && afterFirst > before,
                            "Harvest did not make the plant dormant and grant its item");
                    Household.use(level, actor, crop);
                    helper.assertTrue(!crop.active && count(actor, harvest) == afterFirst,
                            "Dormant crop could be harvested twice");
                    crop.growAt = level.getGameTime();
                    CellEntity.serverTick(level, crop.owner(), level.getBlockState(crop.owner()),
                            (CellEntity) level.getBlockEntity(crop.owner()));
                    helper.assertTrue(crop.active, "Production growth tick did not reactivate mature crop");
                }),
                new Case("plant_growth_state_survives_nbt", () -> {
                    Piece crop = add.apply("crop");
                    crop.active = false;
                    crop.growAt = level.getGameTime() + 37;
                    Piece restored = Piece.load(crop.save(level.registryAccess()), level.registryAccess());
                    helper.assertTrue(restored.id.equals(crop.id)
                                    && !restored.active
                                    && restored.growAt == crop.growAt,
                            "Dormant plant growth deadline did not survive NBT");
                }),
                new Case("cell_state_restores_roots_fragments_and_inventory", () -> {
                    Piece workbench = add.apply("bench");
                    workbench.inventory.set(8, new ItemStack(Items.REDSTONE, 11));
                    ServerPlayer actor = player.get();
                    helper.assertTrue(Household.use(level, actor, workbench).consumesAction()
                                    && actor.containerMenu instanceof net.minecraft.world.inventory.CraftingMenu
                                    && actor.containerMenu.stillValid(actor),
                            "Alchemy workbench did not open a live native crafting menu");
                    CellEntity cell = (CellEntity) level.getBlockEntity(workbench.owner());
                    CompoundTag saved = cell.saveWithoutMetadata(level.registryAccess());
                    CellEntity restored = new CellEntity(cell.getBlockPos(), cell.getBlockState());
                    restored.loadWithComponents(saved, level.registryAccess());
                    Piece restoredRoot = restored.root(workbench.id);
                    helper.assertTrue(restoredRoot != null
                                    && restoredRoot.inventory.get(8).is(Items.REDSTONE)
                                    && restoredRoot.inventory.get(8).getCount() == 11
                                    && !restored.fragments.isEmpty(),
                            "Cell root, fragments, or inventory were lost on save/load");
                    var menu = actor.containerMenu;
                    Placement.remove(level, workbench);
                    owned.remove(workbench);
                    helper.assertTrue(!menu.stillValid(actor),
                            "Alchemy workbench menu stayed valid after the piece was removed");
                    actor.closeContainer();
                }),
                new Case("demo_blueprint_generates_and_cleans_without_source_save_claim", () -> {
                    List<Piece> demo = Placement.demoCourtyard(BlockPos.containing(origin));
                    helper.assertTrue(!demo.isEmpty(), "Demo blueprint is empty");
                    helper.assertTrue(demo.stream().map(piece -> piece.asset).collect(
                                    java.util.stream.Collectors.toCollection(LinkedHashSet::new)).size() >= 6,
                            "Demo blueprint does not exercise a representative component set");
                    helper.assertTrue(Placement.denial(level, demo, true) == null,
                            "Demo blueprint was denied at a clean origin");
                    demo.forEach(piece -> {
                        Placement.add(level, piece);
                        owned.add(piece);
                    });
                    helper.assertTrue(Placement.denial(level,
                                    Placement.demoCourtyard(BlockPos.containing(origin)), true) != null,
                            "A duplicate demo could overwrite the existing preview");
                    var occupied = demo.stream().flatMap(piece -> piece.fragments().keySet().stream()).distinct().toList();
                    demo.forEach(piece -> {
                        Placement.remove(level, piece);
                        owned.remove(piece);
                    });
                    helper.assertTrue(occupied.stream().noneMatch(
                                    pos -> level.getBlockState(pos).is(GameSaveMod.CELL.get())),
                            "Demo cleanup left component cells");
                })
        );

        JsonArray observations = new JsonArray();
        String failure = null;
        int cleanupFailures = 0;
        for (Case test : cases) {
            boolean passed = false;
            boolean clean = false;
            String error = "";
            long startedAtTick = level.getGameTime();
            try {
                test.action.run();
                passed = true;
            } catch (Throwable throwable) {
                error = throwable.toString();
                failure = test.id + ": " + error;
            } finally {
                try {
                    for (ServerPlayer actor : players) {
                        actor.closeContainer();
                        if (actor.isSleeping()) {
                            actor.stopSleepInBed(true, true);
                        }
                        actor.stopRiding();
                        level.getServer().getPlayerList().remove(actor);
                        level.removePlayerImmediately(actor, Entity.RemovalReason.DISCARDED);
                    }
                    seats.forEach(Entity::discard);
                    droppedItems.forEach(Entity::discard);
                    for (Piece piece : List.copyOf(owned)) {
                        Placement.remove(level, piece);
                    }
                    clean = owned.stream().flatMap(piece -> piece.fragments().keySet().stream())
                            .noneMatch(pos -> level.getBlockState(pos).is(GameSaveMod.CELL.get()));
                } catch (Throwable cleanupError) {
                    error += " cleanup: " + cleanupError;
                    clean = false;
                }
                players.clear();
                seats.clear();
                droppedItems.clear();
                owned.clear();
                if (!clean) {
                    cleanupFailures++;
                }
            }

            JsonObject result = new JsonObject();
            result.addProperty("id", test.id);
            result.addProperty("passed", passed);
            result.addProperty("cleanupPassed", clean);
            result.addProperty("startedAtTick", startedAtTick);
            result.addProperty("finishedAtTick", level.getGameTime());
            result.addProperty("error", error);
            observations.add(result);
            if (!passed || !clean) {
                break;
            }
        }

        try {
            JsonObject receipt = new JsonObject();
            receipt.addProperty("schemaVersion", 1);
            receipt.addProperty("sessionId", System.getenv("MINECRAFT_MOD_TEST_SESSION_ID"));
            receipt.addProperty("processStartCount", 1);
            receipt.addProperty("worldLoadCount", 1);
            receipt.addProperty("scenarioCount", cases.size());
            receipt.addProperty("failedCount", failure == null ? 0 : 1);
            receipt.addProperty("cleanupFailureCount", cleanupFailures);
            receipt.addProperty("allScenariosPassed",
                    failure == null && cleanupFailures == 0 && observations.size() == cases.size());
            receipt.addProperty("sourceScope",
                    "Original procedural fixture, generic source lots and native Minecraft capability interactions");
            JsonArray requested = new JsonArray();
            cases.forEach(test -> requested.add(test.id));
            JsonArray observed = new JsonArray();
            observations.forEach(row -> observed.add(row.getAsJsonObject().get("id")));
            receipt.add("requestedScenarios", requested);
            receipt.add("observedScenarios", observed);
            receipt.add("scenarios", observations);
            Path path = Path.of(System.getProperty("gamesave2mc.receipt"));
            Files.createDirectories(path.getParent());
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(receipt),
                    StandardOpenOption.CREATE_NEW);
        } catch (Exception error) {
            helper.fail("Receipt failure: " + error);
            return;
        }

        if (failure != null || cleanupFailures > 0) {
            helper.fail(failure == null ? "Cleanup failed" : failure);
        } else {
            helper.succeed();
        }
    }

    private static int count(ServerPlayer player, Item item) {
        int result = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item)) {
                result += stack.getCount();
            }
        }
        return result;
    }

    private static String assetId(ItemStack stack) {
        return stack.getItem() instanceof FurnitureItem item ? item.assetId(stack) : null;
    }

    private static Piece carriedPiece(
            ItemStack stack,
            net.minecraft.core.HolderLookup.Provider registries,
            long gameTime
    ) {
        var customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) {
            throw new IllegalStateException("Carry stack has no custom piece data");
        }
        CompoundTag outer = customData.copyTag();
        if (!outer.contains("GameSave2MinecraftPiece", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            throw new IllegalStateException("Carry stack has no serialized piece");
        }
        return Piece.loadCarried(outer.getCompound("GameSave2MinecraftPiece"), registries, gameTime);
    }

    @SubscribeEvent
    public static void heartbeat(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        if (tick > 1 && tick % 20 != 0) {
            return;
        }
        String configured = System.getProperty("gamesave2mc.heartbeat");
        if (configured == null || configured.isBlank()) {
            return;
        }
        Path path = Path.of(configured);
        JsonObject value = new JsonObject();
        value.addProperty("schemaVersion", 1);
        value.addProperty("lane", "gametest");
        value.addProperty("sessionId", System.getenv("MINECRAFT_MOD_TEST_SESSION_ID"));
        value.addProperty("pid", ProcessHandle.current().pid());
        value.addProperty("tick", tick);
        value.addProperty("timestamp", Instant.now().toString());
        writeAtomic(path, new GsonBuilder().create().toJson(value));
    }

    private static void writeAtomic(Path path, String value) {
        try {
            dev.alex.gamesave2minecraft.testing.io.HeartbeatFile.write(path,value);
        } catch (Exception error) {
            throw new IllegalStateException("Could not write GameTest heartbeat", error);
        }
    }

    private static boolean finite(AABB box) {
        return Double.isFinite(box.minX) && Double.isFinite(box.minY) && Double.isFinite(box.minZ)
                && Double.isFinite(box.maxX) && Double.isFinite(box.maxY) && Double.isFinite(box.maxZ);
    }

    private static boolean sameBoxes(List<AABB> first, List<AABB> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int index = 0; index < first.size(); index++) {
            if (!first.get(index).equals(second.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> difference(Set<String> expected, Set<String> actual) {
        var result = new LinkedHashSet<>(expected);
        result.removeAll(actual);
        return result;
    }

    private GameSave2MinecraftGameTests() {
    }
}
