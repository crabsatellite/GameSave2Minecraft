package dev.alex.gamesave2minecraft.client;

import dev.alex.gamesave2minecraft.CellEntity;
import dev.alex.gamesave2minecraft.GameSaveMod;
import dev.alex.gamesave2minecraft.ModelCatalog;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Only loaded visual owners enter this index; collision cells retain their normal world data. */
@EventBusSubscriber(modid=GameSaveMod.ID,value=Dist.CLIENT)
public final class WorldRoots {
    private static final class Entry {
        final CellEntity cell;
        GpuMeshes.PreparedPlan plan;
        boolean prepared;
        Entry(CellEntity cell){this.cell=cell;}
    }
    private static final Long2ObjectOpenHashMap<Entry> CELLS=new Long2ObjectOpenHashMap<>();
    private static final ClientModels.CellRenderer RENDERER=new ClientModels.CellRenderer();
    private static ClientLevel indexedLevel;

    public static void install(){CellEntity.setClientVisualListener(WorldRoots::changed);}

    private static void changed(CellEntity cell,boolean present){
        var minecraft=Minecraft.getInstance();
        if(!minecraft.isSameThread()){minecraft.execute(()->changed(cell,present));return;}
        if(cell.getLevel()!=minecraft.level||minecraft.level==null)return;
        if(indexedLevel!=minecraft.level){CELLS.clear();indexedLevel=minecraft.level;}
        long key=cell.getBlockPos().asLong();
        var previous=CELLS.get(key);
        if(present&&!cell.isRemoved()&&!cell.roots.isEmpty())CELLS.put(key,new Entry(cell));
        else if(previous!=null&&previous.cell==cell)CELLS.remove(key);
    }

    @SubscribeEvent public static void unload(LevelEvent.Unload event){
        if(event.getLevel()==indexedLevel){CELLS.clear();indexedLevel=null;}
    }

    /** Read-only acceptance observation, outside the frame measurement interval. */
    public static long trackedRootCount(){
        return indexedLevel==Minecraft.getInstance().level?CELLS.values().stream().mapToLong(entry->entry.cell.roots.size()).sum():0;
    }

    public static boolean isTracked(BlockPos position){
        return indexedLevel==Minecraft.getInstance().level&&CELLS.containsKey(position.asLong());
    }

    public static boolean isTracked(CellEntity cell){
        var entry=CELLS.get(cell.getBlockPos().asLong());
        return indexedLevel==cell.getLevel()&&entry!=null&&entry.cell==cell;
    }

    static void invalidatePlans(){for(var entry:CELLS.values()){entry.plan=null;entry.prepared=false;}}

    static void render(RenderLevelStageEvent event){
        var minecraft=Minecraft.getInstance();
        if(indexedLevel!=minecraft.level||indexedLevel==null)return;
        var camera=event.getCamera().getPosition();var poses=event.getPoseStack();
        var buffers=minecraft.renderBuffers().bufferSource();
        float partialTick=event.getPartialTick().getGameTimeDeltaPartialTick(false);
        for(var entry:CELLS.values()){
            var cell=entry.cell;
            if(cell.isRemoved()||!RENDERER.shouldRender(cell,camera))continue;
            var pos=cell.getBlockPos();poses.pushPose();
            try{
                poses.translate(pos.getX()-camera.x,pos.getY()-camera.y,pos.getZ()-camera.z);
                int light=LevelRenderer.getLightColor(indexedLevel,pos);
                float time=indexedLevel.getGameTime()+partialTick;
                if(ClientModels.gpuEnabled&&GpuMeshes.shader!=null){
                    if(!entry.prepared){
                        boolean growing=cell.roots.stream().anyMatch(piece->!piece.active&&ModelCatalog.get(piece.asset).behavior().equals("plant"));
                        if(!growing)entry.plan=GpuMeshes.prepare(()->RENDERER.render(cell,0,new PoseStack(),null,0,OverlayTexture.NO_OVERLAY));
                        entry.prepared=true;
                    }
                    if(entry.plan!=null){
                        if(entry.plan.submit(poses.last(),light,OverlayTexture.NO_OVERLAY,time))continue;
                        // A weak mesh was evicted from the bounded CPU cache. Rebuild next frame.
                        entry.plan=null;entry.prepared=false;
                    }
                }
                RENDERER.render(cell,partialTick,poses,buffers,light,OverlayTexture.NO_OVERLAY);
            }finally{poses.popPose();}
        }
    }

    private WorldRoots(){}
}
