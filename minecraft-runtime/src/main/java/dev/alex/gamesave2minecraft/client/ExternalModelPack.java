package dev.alex.gamesave2minecraft.client;

import dev.alex.gamesave2minecraft.GameSaveMod;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddPackFindersEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Keeps extracted model payloads outside the program JAR while retaining normal pack reloads. */
@EventBusSubscriber(modid = GameSaveMod.ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ExternalModelPack {
    private static final String DIRECTORY_PROPERTY = "gamesave2mc.modelpack.directory";

    public static Path directory() {
        String override = System.getProperty(DIRECTORY_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        return Minecraft.getInstance().gameDirectory.toPath().resolve("gamesave2mc-models");
    }

    @SubscribeEvent
    public static void addPack(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }
        event.addRepositorySource(consumer -> {
            Path root = directory();
            if (!Files.isRegularFile(root.resolve("pack.mcmeta"))) {
                return;
            }
            var location = new PackLocationInfo(
                    "gamesave2mc/local_models",
                    Component.translatable("gamesave2mc.modelpack.name"),
                    PackSource.DEFAULT,
                    Optional.empty()
            );
            var pack = Pack.readMetaAndCreate(
                    location,
                    new PathPackResources.PathResourcesSupplier(root),
                    PackType.CLIENT_RESOURCES,
                    new PackSelectionConfig(true, Pack.Position.BOTTOM, false)
            );
            if (pack != null) {
                consumer.accept(pack);
            }
        });
    }

    private ExternalModelPack() {
    }
}
