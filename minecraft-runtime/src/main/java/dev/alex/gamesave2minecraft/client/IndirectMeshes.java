package dev.alex.gamesave2minecraft.client;

import com.mojang.blaze3d.vertex.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** Bounded GL 4.3 arena; one indirect submission per material, with streamed instance data. */
final class IndirectMeshes {
    static ShaderInstance shader;
    private static final long VERTEX_LIMIT=192L*1024*1024,INDEX_LIMIT=64L*1024*1024;
    private static final int INSTANCE_BYTES=160,MAX_INSTANCES=524288;
    private record Geometry(int count,int firstIndex,int baseVertex){}
    private record Material(ResourceLocation texture,List<ResourceLocation> trim,boolean emissive){}
    private record Draw(Geometry geometry,GpuMeshes.Batch batch){}
    private record Range(Material material,int first,int count){}
    private static final Map<String,Geometry> GEOMETRY=new HashMap<>();
    private static int vao,vertices,indices,instanceIds,storage,commands,instanceCapacity,commandCapacity;
    private static long vertexBytes,indexBytes;
    private static ByteBuffer instanceData,commandData;
    private static final Set<Integer> FILTERED_TEXTURES=new HashSet<>();

    static boolean supported(){return GL.getCapabilities().OpenGL43;}
    static boolean available(){return GpuMeshes.indirectEnabled&&shader!=null&&supported();}
    static long cachedBytes(){return vertexBytes+indexBytes;}

    static void clear(){
        if(vao!=0)GL30.glDeleteVertexArrays(vao);
        for(int handle:new int[]{vertices,indices,instanceIds,storage,commands})if(handle!=0)GL15.glDeleteBuffers(handle);
        if(instanceData!=null)MemoryUtil.memFree(instanceData);
        if(commandData!=null)MemoryUtil.memFree(commandData);
        vao=vertices=indices=instanceIds=storage=commands=instanceCapacity=commandCapacity=0;
        vertexBytes=indexBytes=0;instanceData=commandData=null;GEOMETRY.clear();FILTERED_TEXTURES.clear();
    }

    private record SavedState(int vao,int array,int indirect,int storage,int indexedStorage,int activeTexture) implements AutoCloseable {
        static SavedState capture(){return new SavedState(GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING),GL11.glGetInteger(GL40.GL_DRAW_INDIRECT_BUFFER_BINDING),GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING),GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,0),GlStateManager._getActiveTexture());}
        @Override public void close(){
            GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,array);
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,indirect);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,indexedStorage);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,storage);
            RenderSystem.activeTexture(activeTexture);
        }
    }

    private static void initialize(){
        if(vao!=0){GL30.glBindVertexArray(vao);return;}
        vao=GL30.glGenVertexArrays();vertices=GL15.glGenBuffers();indices=GL15.glGenBuffers();
        instanceIds=GL15.glGenBuffers();storage=GL15.glGenBuffers();commands=GL15.glGenBuffers();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vertices);GL15.glBufferData(GL15.GL_ARRAY_BUFFER,VERTEX_LIMIT,GL15.GL_STATIC_DRAW);
        DefaultVertexFormat.NEW_ENTITY.setupBufferState();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,indices);GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER,INDEX_LIMIT,GL15.GL_STATIC_DRAW);
        // NEW_ENTITY's UV2 attribute carries a per-instance index rather than per-vertex light.
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,instanceIds);GL30.glVertexAttribIPointer(4,1,GL11.GL_UNSIGNED_INT,4,0L);GL33.glVertexAttribDivisor(4,1);
    }

    private static Geometry geometry(GpuMeshes.Batch batch){
        var previous=GEOMETRY.get(batch.geometryKey());if(previous!=null)return previous;
        var mesh=batch.mesh();var group=batch.group();int count=group.positions().length/3;
        long vertexSize=(long)count*DefaultVertexFormat.NEW_ENTITY.getVertexSize(),indexSize=group.indices().length*4L;
        if(vertexBytes+vertexSize>VERTEX_LIMIT||indexBytes+indexSize>INDEX_LIMIT)return null;
        try(var arena=new ByteBufferBuilder(Math.max(4096,Math.toIntExact(vertexSize)))){
            var builder=new BufferBuilder(arena,VertexFormat.Mode.TRIANGLES,DefaultVertexFormat.NEW_ENTITY);
            for(int i=0;i<count;i++){
                int p=i*3,t=i*2,trim=group.trimSelectors()==null?0:Math.round(group.trimSelectors()[i]*256);
                builder.addVertex((group.positions()[p]-mesh.originX())*mesh.scale(),(group.positions()[p+1]-mesh.originY())*mesh.scale(),(group.positions()[p+2]-mesh.originZ())*mesh.scale())
                        .setColor(group.color()).setUv(group.uvs()[t],group.uvs()[t+1]).setUv1(trim,0).setUv2(0,0)
                        .setNormal(group.normals()[p],group.normals()[p+1],group.normals()[p+2]);
            }
            try(var built=builder.buildOrThrow()){
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vertices);GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER,vertexBytes,built.vertexBuffer());
            }
        }
        IntBuffer source=MemoryUtil.memAllocInt(group.indices().length);
        try{source.put(group.indices()).flip();GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,indices);GL15.glBufferSubData(GL15.GL_ELEMENT_ARRAY_BUFFER,indexBytes,source);}
        finally{MemoryUtil.memFree(source);}
        var result=new Geometry(group.indices().length,Math.toIntExact(indexBytes/4),Math.toIntExact(vertexBytes/DefaultVertexFormat.NEW_ENTITY.getVertexSize()));
        vertexBytes+=vertexSize;indexBytes+=indexSize;GEOMETRY.put(batch.geometryKey(),result);GpuMeshes.recordUpload();return result;
    }

    private static int capacity(int count){int capacity=256;while(capacity<count)capacity*=2;return capacity;}
    private static void prepareStreams(int instanceCount,int drawCount){
        if(instanceCount>instanceCapacity){
            instanceCapacity=capacity(instanceCount);if(instanceData!=null)MemoryUtil.memFree(instanceData);
            instanceData=MemoryUtil.memAlloc(instanceCapacity*INSTANCE_BYTES).order(ByteOrder.nativeOrder());
            IntBuffer ids=MemoryUtil.memAllocInt(instanceCapacity);
            try{for(int i=0;i<instanceCapacity;i++)ids.put(i);ids.flip();GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,instanceIds);GL15.glBufferData(GL15.GL_ARRAY_BUFFER,ids,GL15.GL_STATIC_DRAW);}
            finally{MemoryUtil.memFree(ids);}
        }
        if(drawCount>commandCapacity){commandCapacity=capacity(drawCount);if(commandData!=null)MemoryUtil.memFree(commandData);commandData=MemoryUtil.memAlloc(commandCapacity*20).order(ByteOrder.nativeOrder());}
        instanceData.clear();commandData.clear();
    }

    private static void instance(GpuMeshes.Instance instance,ImportedMeshes.Group group,float time){
        int start=instanceData.position();instance.pose().get(start,instanceData);instanceData.position(start+64);
        var n=instance.normal();
        instanceData.putFloat(n.m00()).putFloat(n.m01()).putFloat(n.m02()).putFloat(0);
        instanceData.putFloat(n.m10()).putFloat(n.m11()).putFloat(n.m12()).putFloat(0);
        instanceData.putFloat(n.m20()).putFloat(n.m21()).putFloat(n.m22()).putFloat(0);
        instanceData.putFloat(0).putFloat(0).putFloat(0).putFloat(1);
        instanceData.putFloat(instance.light()&65535).putFloat(instance.light()>>>16).putFloat(instance.overlay()&65535).putFloat(instance.overlay()>>>16);
        instanceData.putFloat(group.wind()?1:0).putFloat(group.minY()).putFloat(Math.max(group.maxY()-group.minY(),1e-4f)).putFloat(time);
    }

    /** Returns only overflow batches, rendered by the existing bounded compatibility path. */
    static List<GpuMeshes.Batch> flush(Collection<GpuMeshes.Batch> batches){
        int total=batches.stream().mapToInt(b->b.instances().size()).sum();
        if(total==0)return List.of();
        if(total>MAX_INSTANCES)return List.copyOf(batches);
        try(var state=SavedState.capture()){
            initialize();
            Map<Material,List<Draw>> materials=new LinkedHashMap<>();var overflow=new ArrayList<GpuMeshes.Batch>();int drawCount=0;
            for(var batch:batches){
                Geometry geometry=geometry(batch);if(geometry==null){overflow.add(batch);continue;}
                var group=batch.group();var material=new Material(group.texture(),group.trimTextures(),batch.emissive());
                materials.computeIfAbsent(material,k->new ArrayList<>()).add(new Draw(geometry,batch));drawCount++;
            }
            if(drawCount==0)return overflow;
            prepareStreams(total,drawCount);var ranges=new ArrayList<Range>();int instanceIndex=0,commandIndex=0;
            for(var entry:materials.entrySet()){
                int first=commandIndex;
                for(var draw:entry.getValue()){
                    var batch=draw.batch();var geometry=draw.geometry();
                    commandData.putInt(geometry.count()).putInt(batch.instances().size()).putInt(geometry.firstIndex()).putInt(geometry.baseVertex()).putInt(instanceIndex);
                    for(var instance:batch.instances())instance(instance,batch.group(),batch.time());
                    instanceIndex+=batch.instances().size();commandIndex++;
                }
                ranges.add(new Range(entry.getKey(),first,commandIndex-first));
            }
            instanceData.flip();commandData.flip();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,storage);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,instanceData,GL15.GL_STREAM_DRAW);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,storage);
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,commands);GL15.glBufferData(GL40.GL_DRAW_INDIRECT_BUFFER,commandData,GL15.GL_STREAM_DRAW);
            GpuMeshes.recordInstances(instanceIndex);
            drawPass(ranges,false);drawPass(ranges,true);
            return overflow;
        }
    }

    private static void bindTexture(int unit,ResourceLocation location){
        RenderSystem.activeTexture(GL13.GL_TEXTURE0+unit);
        var texture=Minecraft.getInstance().getTextureManager().getTexture(location);int id=texture.getId();
        RenderSystem.setShaderTexture(unit,id);RenderSystem.bindTexture(id);
        if(FILTERED_TEXTURES.add(id))texture.setFilter(false,false);
    }

    private static void drawPass(List<Range> ranges,boolean emissive){
        Material first=null;for(var range:ranges)if(range.material().emissive()==emissive){first=range.material();break;}
        if(first==null)return;
        var type=emissive?RenderType.entityTranslucentEmissive(first.texture()):RenderType.entityCutoutNoCull(first.texture());
        int[] previous=new int[7];for(int i=0;i<7;i++)previous[i]=RenderSystem.getShaderTexture(i+3);
        type.setupRenderState();
        try{
            shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES,RenderSystem.getModelViewMatrix(),RenderSystem.getProjectionMatrix(),Minecraft.getInstance().getWindow());
            shader.getUniform("Emissive").set(emissive?1:0);
            shader.apply();
            GpuMeshes.recordPass();
            try{
                int previousTrim=-1;var trimUniform=shader.getUniform("TrimCount");
                for(var range:ranges){
                    var material=range.material();if(material.emissive()!=emissive)continue;
                    bindTexture(0,material.texture());
                    for(int i=1;i<material.trim().size();i++)bindTexture(i+2,material.trim().get(i));
                    if(previousTrim!=material.trim().size()){previousTrim=material.trim().size();trimUniform.set(previousTrim);trimUniform.upload();}
                    GL43.glMultiDrawElementsIndirect(GL11.GL_TRIANGLES,GL11.GL_UNSIGNED_INT,range.first()*20L,range.count(),20);GpuMeshes.recordDraw();
                }
            }
            finally{shader.clear();}
        }finally{
            for(int i=0;i<7;i++)RenderSystem.setShaderTexture(i+3,previous[i]);
            type.clearRenderState();
        }
    }
}
