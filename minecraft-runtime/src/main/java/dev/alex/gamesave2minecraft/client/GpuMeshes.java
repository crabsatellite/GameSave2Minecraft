package dev.alex.gamesave2minecraft.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryUtil;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import dev.alex.gamesave2minecraft.GameSaveMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import java.util.*;

/** Indexed static geometry stays on the GPU; pose, light and wind are uniforms. */
@EventBusSubscriber(modid=GameSaveMod.ID,value=Dist.CLIENT)
public final class GpuMeshes {
    public static ShaderInstance shader;
    /** Diagnostic switch to exercise the supported GL 3.2 compatibility path. */
    public static boolean indirectEnabled=true;
    private static final long LIMIT=256L*1024*1024;
    private record Entry(IndexedBuffer buffer,long bytes){}
    private static final Map<String,Entry> CACHE=new LinkedHashMap<>(256,.75f,true);
    private static long bytes,drawCalls,uploads,instances,indirectDraws,indirectPasses,preparedDraws,preparedBuilds;
    static final class Instance {
        private final Matrix4f pose;
        private final Matrix3f normal;
        private int light,overlay;
        Instance(Matrix4f pose,Matrix3f normal,int light,int overlay){this.pose=pose;this.normal=normal;this.light=light;this.overlay=overlay;}
        Matrix4f pose(){return pose;} Matrix3f normal(){return normal;}
        int light(){return light;} int overlay(){return overlay;}
        void update(PoseStack.Pose parent,Instance local,int light,int overlay){
            pose.set(parent.pose()).mul(local.pose());normal.set(parent.normal()).mul(local.normal());this.light=light;this.overlay=overlay;
        }
    }
    static final class Batch {
        private final String geometryKey;
        private final boolean emissive;
        private final List<Instance> instances=new ArrayList<>();
        private ImportedMeshes.Mesh mesh;
        private ImportedMeshes.Group group;
        private float time;
        private Batch(String geometryKey,boolean emissive){this.geometryKey=geometryKey;this.emissive=emissive;}
        String geometryKey(){return geometryKey;} boolean emissive(){return emissive;}
        List<Instance> instances(){return instances;} ImportedMeshes.Mesh mesh(){return mesh;}
        ImportedMeshes.Group group(){return group;} float time(){return time;}
        void releaseFrame(){instances.clear();mesh=null;group=null;}
    }
    private record BatchKey(String geometryKey,ResourceLocation texture,List<ResourceLocation> trim,boolean emissive){}
    private static final Map<BatchKey,Batch> BATCHES=new LinkedHashMap<>(256,.75f,true);
    private static final List<Batch> QUEUED=new ArrayList<>();
    private record Template(Batch batch,java.lang.ref.WeakReference<ImportedMeshes.Mesh> mesh,int groupIndex,Instance pose){}
    private static List<Template> capturing;
    private static boolean captureSupported;

    /** Geometry remains owned by the bounded CPU cache, never pinned by these plans. */
    static final class PreparedPlan {
        private final List<Template> templates;
        private final ImportedMeshes.Mesh[] resolved;
        private final Instance[] framePoses;
        private PreparedPlan(List<Template> templates){
            this.templates=templates;resolved=new ImportedMeshes.Mesh[templates.size()];framePoses=new Instance[templates.size()];
            var poses=new IdentityHashMap<Instance,Instance>();
            for(int i=0;i<templates.size();i++)framePoses[i]=poses.computeIfAbsent(templates.get(i).pose(),p->new Instance(new Matrix4f(),new Matrix3f(),0,0));
        }
        boolean submit(PoseStack.Pose cellPose,int light,int overlay,float time){
            try{
                for(int i=0;i<templates.size();i++){
                    resolved[i]=templates.get(i).mesh().get();
                    if(resolved[i]==null)return false;
                }
                Instance previous=null,transformed=null;
                for(int i=0;i<templates.size();i++){
                    var template=templates.get(i);var local=template.pose();
                    if(previous!=local){
                        transformed=framePoses[i];transformed.update(cellPose,local,light,overlay);
                        previous=local;
                    }
                    queue(template.batch(),resolved[i],resolved[i].groups().get(template.groupIndex()),transformed,time);
                }
                preparedDraws++;
                return true;
            }finally{Arrays.fill(resolved,null);}
        }
    }

    static PreparedPlan prepare(Runnable render){
        if(capturing!=null)throw new IllegalStateException("Nested render-plan capture");
        capturing=new ArrayList<>();captureSupported=true;
        try{
            render.run();
            if(!captureSupported)return null;
            preparedBuilds++;return new PreparedPlan(List.copyOf(capturing));
        }finally{capturing=null;}
    }
    static boolean capturing(){return capturing!=null;}
    static void rejectCapture(){captureSupported=false;}
    private static Frustum currentFrustum;
    private static final float[] MATRICES=new float[16*16],NORMALS=new float[9*16],LIGHT_DATA=new float[4*16];
    private static final class IndexedBuffer extends VertexBuffer {
        final int count;
        IndexedBuffer(int count){super(Usage.STATIC);this.count=count;}
        @Override public void draw(){RenderSystem.drawElements(GL11.GL_TRIANGLES,count,GL11.GL_UNSIGNED_INT);}
    }
    public static void clear(){
        if(!RenderSystem.isOnRenderThread()){RenderSystem.recordRenderCall(GpuMeshes::clear);return;}
        CACHE.values().forEach(e->e.buffer().close());CACHE.clear();QUEUED.forEach(Batch::releaseFrame);QUEUED.clear();BATCHES.clear();bytes=0;currentFrustum=null;IndirectMeshes.clear();WorldRoots.invalidatePlans();
    }
    public static long cachedBytes(){return bytes+IndirectMeshes.cachedBytes();}
    static void recordUpload(){uploads++;}
    static void recordDraw(){drawCalls++;indirectDraws++;}
    public static long indirectDrawCalls(){return indirectDraws;}
    static void recordPass(){indirectPasses++;}
    public static long indirectShaderPasses(){return indirectPasses;}
    public static long preparedPlanSubmissions(){return preparedDraws;}
    public static long preparedPlanBuilds(){return preparedBuilds;}
    public static long excludedCullingCells(){return dev.alex.gamesave2minecraft.client.compat.CullingSnapshots.excludedCells();}
    static void recordInstances(int count){instances+=count;}
    public static long drawCalls(){return drawCalls;}
    public static long uploads(){return uploads;}
    public static long instances(){return instances;}
    public static boolean visible(AABB bounds){return currentFrustum==null||currentFrustum.isVisible(bounds);}
    @SubscribeEvent
    public static void frame(RenderFrameEvent.Pre event){currentFrustum=null;}
    @SubscribeEvent
    public static void stage(RenderLevelStageEvent event){
        if(event.getStage()==RenderLevelStageEvent.Stage.AFTER_SKY||event.getStage()==RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS)currentFrustum=event.getFrustum();
        if(event.getStage()==RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES){WorldRoots.render(event);flush();}
    }
    private static Entry upload(ImportedMeshes.Mesh mesh,ImportedMeshes.Group group){
        int vertices=group.positions().length/3;
        var result=new IndexedBuffer(group.indices().length);
        try(var arena=new ByteBufferBuilder(Math.max(4096,vertices*DefaultVertexFormat.NEW_ENTITY.getVertexSize()));
            var indices=new ByteBufferBuilder(Math.max(4096,group.indices().length*4))){
            var builder=new BufferBuilder(arena,VertexFormat.Mode.TRIANGLES,DefaultVertexFormat.NEW_ENTITY);
            for(int i=0;i<vertices;i++){
                int p=i*3,t=i*2;int trim=group.trimSelectors()==null?0:Math.round(group.trimSelectors()[i]*256);
                builder.addVertex((group.positions()[p]-mesh.originX())*mesh.scale(),(group.positions()[p+1]-mesh.originY())*mesh.scale(),(group.positions()[p+2]-mesh.originZ())*mesh.scale())
                    .setColor(group.color()).setUv(group.uvs()[t],group.uvs()[t+1]).setUv1(trim,0).setUv2(240,240)
                    .setNormal(group.normals()[p],group.normals()[p+1],group.normals()[p+2]);
            }
            result.bind();result.upload(builder.buildOrThrow());
            long pointer=indices.reserve(group.indices().length*4);
            for(int i=0;i<group.indices().length;i++)MemoryUtil.memPutInt(pointer+i*4L,group.indices()[i]);
            result.uploadIndexBuffer(indices.build());VertexBuffer.unbind();
        }catch(Throwable failure){result.close();throw failure;}
        uploads++;return new Entry(result,(long)vertices*DefaultVertexFormat.NEW_ENTITY.getVertexSize()+group.indices().length*4L);
    }
    static Instance instance(PoseStack.Pose pose,int light,int overlay){return new Instance(new Matrix4f(pose.pose()),new Matrix3f(pose.normal()),light,overlay);}
    static void draw(String key,ImportedMeshes.Mesh mesh,int groupIndex,Instance pose,float time,boolean emissive){
        var group=mesh.groups().get(groupIndex);
        var materialKey=new BatchKey(key,group.texture(),group.trimTextures(),emissive);
        var batch=BATCHES.get(materialKey);
        if(batch==null){
            batch=new Batch(key,emissive);BATCHES.put(materialKey,batch);
            if(BATCHES.size()>8192){var iterator=BATCHES.entrySet().iterator();iterator.next();iterator.remove();}
        }
        if(capturing!=null)capturing.add(new Template(batch,new java.lang.ref.WeakReference<>(mesh),groupIndex,pose));
        else queue(batch,mesh,group,pose,time);
    }
    private static void queue(Batch batch,ImportedMeshes.Mesh mesh,ImportedMeshes.Group group,Instance pose,float time){
        if(batch.instances.isEmpty()){batch.mesh=mesh;batch.group=group;batch.time=time;QUEUED.add(batch);}
        batch.instances.add(pose);
    }
    private static void flush(){
        try{
            if(IndirectMeshes.available()){
                for(var batch:IndirectMeshes.flush(QUEUED))flush(batch);
            }else for(var batch:QUEUED)flush(batch);
        }
        finally{QUEUED.forEach(Batch::releaseFrame);QUEUED.clear();}
    }
    private static void flush(Batch batch){
        String key=batch.geometryKey();
        var mesh=batch.mesh();var group=batch.group();
        var entry=CACHE.get(key);
        if(entry==null){
            entry=upload(mesh,group);CACHE.put(key,entry);bytes+=entry.bytes();
            var iterator=CACHE.entrySet().iterator();
            while(bytes>LIMIT&&CACHE.size()>1&&iterator.hasNext()){
                var old=iterator.next();bytes-=old.getValue().bytes();old.getValue().buffer().close();iterator.remove();
            }
        }
        var type=batch.emissive()?RenderType.entityTranslucentEmissive(group.texture()):RenderType.entityCutoutNoCull(group.texture());
        int[] previous=new int[7];
        type.setupRenderState();
        try{
            for(int i=1;i<group.trimTextures().size();i++){
                int unit=i+2;previous[i-1]=RenderSystem.getShaderTexture(unit);RenderSystem.setShaderTexture(unit,group.trimTextures().get(i));
            }
            entry.buffer().bind();
            for(int start=0;start<batch.instances().size();start+=16){
                int count=Math.min(16,batch.instances().size()-start);
                for(int i=0;i<count;i++){
                    var instance=batch.instances().get(start+i);instance.pose().get(MATRICES,i*16);instance.normal().get(NORMALS,i*9);
                    LIGHT_DATA[i*4]=instance.light()&65535;LIGHT_DATA[i*4+1]=instance.light()>>>16;
                    LIGHT_DATA[i*4+2]=instance.overlay()&65535;LIGHT_DATA[i*4+3]=instance.overlay()>>>16;
                }
                shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES,RenderSystem.getModelViewMatrix(),RenderSystem.getProjectionMatrix(),Minecraft.getInstance().getWindow());
                shader.getUniform("PoseMats").set(MATRICES);shader.getUniform("NormalMats").set(NORMALS);shader.getUniform("LightData").set(LIGHT_DATA);
                shader.getUniform("Emissive").set(batch.emissive()?1:0);
                shader.getUniform("Wind").set(group.wind()?1f:0f,group.minY(),Math.max(group.maxY()-group.minY(),1e-4f),batch.time());
                shader.getUniform("TrimCount").set(group.trimTextures().size());
                shader.apply();GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES,entry.buffer().count,GL11.GL_UNSIGNED_INT,0L,count);shader.clear();drawCalls++;instances+=count;
            }
            VertexBuffer.unbind();
        }finally{
            for(int i=1;i<group.trimTextures().size();i++)RenderSystem.setShaderTexture(i+2,previous[i-1]);
            type.clearRenderState();
        }
    }
    private GpuMeshes(){}
}
