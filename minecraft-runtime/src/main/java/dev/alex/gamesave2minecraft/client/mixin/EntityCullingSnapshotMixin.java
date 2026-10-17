package dev.alex.gamesave2minecraft.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.alex.gamesave2minecraft.client.compat.CullingSnapshots;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Optional, cooperative adapter for the installed EntityCulling 1.10 snapshot path. */
@Pseudo
@Mixin(targets="dev.tr7zw.entityculling.EntityCullingModBase",remap=false)
public abstract class EntityCullingSnapshotMixin {
    @WrapOperation(method="clientTick",at=@At(value="INVOKE",target="Ljava/util/Map;putAll(Ljava/util/Map;)V"),require=0)
    private void gamesave2mc$visibleSnapshot(Map<Object,Object> destination,Map<Object,Object> source,Operation<Void> original){
        original.call(destination,CullingSnapshots.renderable(source));
    }
}
