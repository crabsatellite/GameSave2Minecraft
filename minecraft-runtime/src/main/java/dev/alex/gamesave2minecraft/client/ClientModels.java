package dev.alex.gamesave2minecraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.alex.gamesave2minecraft.CellEntity;
import dev.alex.gamesave2minecraft.FurnitureItem;
import dev.alex.gamesave2minecraft.ModelCatalog;
import dev.alex.gamesave2minecraft.SeatEntity;
import dev.alex.gamesave2minecraft.GameSaveMod;
import dev.alex.gamesave2minecraft.DoorMotion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.minecraft.client.renderer.ShaderInstance;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

import java.util.stream.Stream;
import org.joml.Matrix4f;

@EventBusSubscriber(modid = GameSaveMod.ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModels {
    /** Diagnostic switch used to compare the cached path with the reference renderer. */
    public static boolean gpuEnabled=true;
    private static long sortedEmissiveDraws;
    public static long sortedEmissiveDraws(){return sortedEmissiveDraws;}
    private static final java.util.Map<ModelCatalog.RenderPart,Matrix4f> PART_TRANSFORMS=new java.util.IdentityHashMap<>();
    @SubscribeEvent
    public static void setup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event){
        event.enqueueWork(WorldRoots::install);
    }
    @SubscribeEvent
    public static void registerShader(RegisterShadersEvent event)throws java.io.IOException{
        event.registerShader(new ShaderInstance(event.getResourceProvider(),ResourceLocation.fromNamespaceAndPath(GameSaveMod.ID,"scene"),DefaultVertexFormat.NEW_ENTITY),shader->GpuMeshes.shader=shader);
        if(IndirectMeshes.supported())event.registerShader(new ShaderInstance(event.getResourceProvider(),ResourceLocation.fromNamespaceAndPath(GameSaveMod.ID,"scene_indirect"),DefaultVertexFormat.NEW_ENTITY),shader->IndirectMeshes.shader=shader);
    }
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(GameSaveMod.SEAT.get(), SeatRenderer::new);
    }

    @SubscribeEvent
    public static void registerReloadListener(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(
                (net.minecraft.server.packs.resources.ResourceManagerReloadListener) resources -> {ImportedMeshes.clear();GpuMeshes.clear();PART_TRANSFORMS.clear();}
        );
    }

    @SubscribeEvent
    public static void registerItemRenderer(RegisterClientExtensionsEvent event) {
        var extension = new IClientItemExtensions() {
            private BlockEntityWithoutLevelRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) {
                    var minecraft = Minecraft.getInstance();
                    renderer = new BlockEntityWithoutLevelRenderer(
                            minecraft.getBlockEntityRenderDispatcher(),
                            minecraft.getEntityModels()
                    ) {
                        @Override
                        public void renderByItem(
                                ItemStack stack,
                                ItemDisplayContext context,
                                PoseStack poses,
                                MultiBufferSource buffers,
                                int light,
                                int overlay
                        ) {
                            if (!(stack.getItem() instanceof FurnitureItem item)) {
                                return;
                            }
                            String assetId = item.assetId(stack);
                            if (assetId == null || !ModelCatalog.ASSETS.containsKey(assetId)) {
                                poses.pushPose();
                                poses.translate(.5, .25, .5);
                                drawPlaceholder(
                                        new AABB(-.25, 0, -.25, .25, .5, .25),
                                        poses.last(),
                                        buffers
                                );
                                poses.popPose();
                                return;
                            }
                            ModelCatalog.Asset asset = ModelCatalog.get(assetId);
                            AABB bounds = ModelCatalog.bounds(asset);
                            double size = Math.max(bounds.getXsize(), Math.max(bounds.getYsize(), bounds.getZsize()));
                            float scale = (float) (.82 / Math.max(size, 1.0e-4));
                            poses.pushPose();
                            poses.translate(.5, .5, .5);
                            poses.scale(scale, scale, scale);
                            Vec3 center = bounds.getCenter();
                            poses.translate(-center.x, -center.y, -center.z);
                            renderAsset(asset, poses, buffers, light, overlay, true, animationTime(0));
                            poses.popPose();
                        }
                    };
                }
                return renderer;
            }
        };
        event.registerItem(
                extension,
                Stream.concat(
                        GameSaveMod.ITEMS.values().stream().map(holder -> holder.get()),
                        Stream.of(GameSaveMod.GENERIC_MODEL.get())
                ).distinct().toArray(Item[]::new)
        );
    }

    private static final class SeatRenderer extends EntityRenderer<SeatEntity> {
        private SeatRenderer(EntityRendererProvider.Context context) {
            super(context);
        }

        @Override
        public ResourceLocation getTextureLocation(SeatEntity entity) {
            return TextureAtlas.LOCATION_BLOCKS;
        }

        @Override
        public void render(
                SeatEntity entity,
                float yaw,
                float partialTick,
                PoseStack poses,
                MultiBufferSource buffers,
                int light
        ) {
        }
    }

    public static final class CellRenderer implements BlockEntityRenderer<CellEntity> {
        public CellRenderer() {
        }

        @Override
        public boolean shouldRenderOffScreen(CellEntity entity) {
            // Roots can cross section boundaries; collision-only cells cannot.
            return !entity.roots.isEmpty();
        }

        @Override
        public boolean shouldRender(CellEntity entity, Vec3 camera) {
            double distance=getViewDistance();
            return !entity.roots.isEmpty()
                    && entity.getBlockPos().distToCenterSqr(camera)<distance*distance
                    && GpuMeshes.visible(entity.visualRenderBounds());
        }

        @Override
        public int getViewDistance() {
            return 256;
        }

        @Override
        public AABB getRenderBoundingBox(CellEntity cell) {
            return cell.visualRenderBounds();
        }

        @Override
        public void render(
                CellEntity cell,
                float partialTick,
                PoseStack poses,
                MultiBufferSource buffers,
                int light,
                int overlay
        ) {
            float time = cell.getLevel() == null ? animationTime(partialTick)
                    : cell.getLevel().getGameTime() + partialTick;
            Vec3 cellOrigin = Vec3.atLowerCornerOf(cell.getBlockPos());
            for (var piece : cell.roots) {
                poses.pushPose();
                Vec3 delta = piece.position.subtract(cellOrigin);
                poses.translate(delta.x, delta.y, delta.z);
                poses.mulPose(Axis.YP.rotationDegrees((float) piece.rotation));
                poses.mulPose(piece.orientation);
                if (ModelCatalog.get(piece.asset).behavior().equals("door") && ModelCatalog.get(piece.asset).door()==null && piece.active) {
                    poses.mulPose(Axis.YP.rotationDegrees(90));
                }
                poses.scale((float) piece.scale.x, (float) piece.scale.y, (float) piece.scale.z);
                var definition=ModelCatalog.get(piece.asset);
                if(definition.behavior().equals("plant")&&!piece.active&&!definition.dormantRenderParts().isEmpty()){
                    float progress=Math.clamp(1f-(piece.growAt-time)/Math.max(1f,definition.growTicks()),0f,1f);
                    float growth=.2f+.8f*progress;poses.scale(growth,growth,growth);
                }
                renderAssetInternal(
                        ModelCatalog.get(piece.asset),
                        poses,
                        buffers,
                        light,
                        overlay,
                        piece.active,
                        time,true
                );
                poses.popPose();
            }
        }
    }

    public static void renderAsset(
            ModelCatalog.Asset asset,
            PoseStack poses,
            MultiBufferSource buffers,
            int light,
            int overlay,
            boolean active,
            float animationTime
    ) {
        renderAssetInternal(asset,poses,buffers,light,overlay,active,animationTime,false);
    }
    private static void renderAssetInternal(ModelCatalog.Asset asset,PoseStack poses,MultiBufferSource buffers,int light,int overlay,boolean active,float animationTime,boolean gpu){
        String meshId = asset.behavior().equals("plant") && !active ? asset.dormantMesh() : asset.mesh();
        if(!meshId.isEmpty())renderMesh(asset,meshId,poses,buffers,light,overlay,active,animationTime,gpu);
        var parts=asset.behavior().equals("plant")&&!active&&!asset.dormantRenderParts().isEmpty()?asset.dormantRenderParts():asset.renderParts();
        for(var part:parts){
            poses.pushPose();
            poses.mulPose(PART_TRANSFORMS.computeIfAbsent(part,p->{float[] values=new float[16];for(int i=0;i<16;i++)values[i]=p.matrix().get(i);return new Matrix4f().set(values);}));
            renderMesh(asset,part.mesh(),poses,buffers,light,overlay,active,animationTime,gpu);
            poses.popPose();
        }
    }

    private static void renderMesh(ModelCatalog.Asset asset,String meshId,PoseStack poses,MultiBufferSource buffers,int light,int overlay,boolean active,float animationTime,boolean gpu){
        ImportedMeshes.Mesh mesh = ImportedMeshes.get(meshId);
        if (mesh.placeholder()) {
            if(GpuMeshes.capturing()){GpuMeshes.rejectCapture();return;}
            drawPlaceholder(ModelCatalog.bounds(asset), poses.last(), buffers);
            return;
        }
        PoseStack.Pose pose = poses.last();
        boolean useGpu=gpu&&gpuEnabled&&GpuMeshes.shader!=null&&!(asset.door()!=null&&active);
        GpuMeshes.Instance instance=useGpu?GpuMeshes.instance(pose,light,overlay):null;
        int groupIndex=-1;
        for (ImportedMeshes.Group group : mesh.groups()) {
            groupIndex++;
            if(group.shadowOnly()||group.overlayOnly())continue;
            if(group.conditions()!=null){
                boolean enabled=false;
                for(int[] condition:group.conditions())if((asset.sourceFlags()&condition[0])==condition[0]&&(asset.sourceFlags()&condition[1])==0){enabled=true;break;}
                if(!enabled)continue;
            }
            boolean fullyLit = group.emissive() && (!asset.behavior().equals("light") || active);
            // Native translucent emissive quads require camera-dependent surface
            // ordering. Keep them in Minecraft's sorted buffer rather than the
            // static opaque index order used by the instanced geometry path.
            if(useGpu&&!fullyLit){
                GpuMeshes.draw(mesh.geometryKey()+":"+groupIndex,mesh,groupIndex,instance,animationTime,fullyLit);continue;
            }
            if(GpuMeshes.capturing()){GpuMeshes.rejectCapture();continue;}
            if(useGpu&&fullyLit)sortedEmissiveDraws++;
            RenderType type = fullyLit
                    ? RenderType.entityTranslucentEmissive(group.texture())
                    : RenderType.entityCutoutNoCull(group.texture());
            VertexConsumer output = buffers.getBuffer(type);
            int packedLight = fullyLit ? LightTexture.FULL_BRIGHT : light;
            for (int face = 0; face < group.indices().length; face += 3) {
                for (int corner = 0; corner < 4; corner++) {
                    int vertex = group.indices()[face + Math.min(corner, 2)];
                    int positionOffset = vertex * 3;
                    int textureOffset = vertex * 2;
                    float x = (group.positions()[positionOffset] - mesh.originX()) * mesh.scale();
                    float y = (group.positions()[positionOffset + 1] - mesh.originY()) * mesh.scale();
                    float z = (group.positions()[positionOffset + 2] - mesh.originZ()) * mesh.scale();
                    float nx=group.normals()[positionOffset],ny=group.normals()[positionOffset+1],nz=group.normals()[positionOffset+2];
                    if(asset.door()!=null&&active){
                        boolean right=DoorMotion.right(asset.door(),x);
                        Vec3 moved=DoorMotion.point(asset.door(),new Vec3(x,y,z),right);
                        x=(float)moved.x;y=(float)moved.y;z=(float)moved.z;
                        float oldX=nx;nx=right?-nz:nz;nz=right?oldX:-oldX;
                    }
                    if (group.wind()) {
                        float range = Math.max(group.maxY() - group.minY(), 1.0e-4f);
                        float weight = Math.clamp((y - group.minY()) / range, 0, 1);
                        float phase = animationTime * .09f + x * 1.7f + z * 1.3f;
                        x += (float) Math.sin(phase) * .018f * weight;
                        z += (float) Math.cos(phase * .83f) * .009f * weight;
                    }
                    output.addVertex(pose.pose(), x, y, z)
                            .setColor(group.color())
                            .setUv(group.uvs()[textureOffset], group.uvs()[textureOffset + 1])
                            .setOverlay(overlay)
                            .setLight(packedLight)
                            .setNormal(
                                    pose,
                                    nx,ny,nz
                            );
                }
            }
        }
    }

    private static void drawPlaceholder(
            AABB sourceBounds,
            PoseStack.Pose pose,
            MultiBufferSource buffers
    ) {
        AABB bounds = sourceBounds.getSize() < 1.0e-7
                ? new AABB(-.25, 0, -.25, .25, .5, .25)
                : sourceBounds;
        float x = (float) bounds.minX;
        float y = (float) bounds.minY;
        float z = (float) bounds.minZ;
        float maxX = (float) bounds.maxX;
        float maxY = (float) bounds.maxY;
        float maxZ = (float) bounds.maxZ;
        VertexConsumer output = buffers.getBuffer(RenderType.debugQuads());
        placeholderFace(pose, output, new float[][]{{x, y, z}, {x, maxY, z}, {maxX, maxY, z}, {maxX, y, z}});
        placeholderFace(pose, output, new float[][]{{maxX, y, maxZ}, {maxX, maxY, maxZ}, {x, maxY, maxZ}, {x, y, maxZ}});
        placeholderFace(pose, output, new float[][]{{x, y, maxZ}, {x, maxY, maxZ}, {x, maxY, z}, {x, y, z}});
        placeholderFace(pose, output, new float[][]{{maxX, y, z}, {maxX, maxY, z}, {maxX, maxY, maxZ}, {maxX, y, maxZ}});
        placeholderFace(pose, output, new float[][]{{x, maxY, z}, {x, maxY, maxZ}, {maxX, maxY, maxZ}, {maxX, maxY, z}});
        placeholderFace(pose, output, new float[][]{{x, y, maxZ}, {x, y, z}, {maxX, y, z}, {maxX, y, maxZ}});
    }

    private static void placeholderFace(PoseStack.Pose pose, VertexConsumer output, float[][] corners) {
        for (float[] corner : corners) {
            output.addVertex(pose.pose(), corner[0], corner[1], corner[2]).setColor(255, 32, 224, 180);
        }
    }

    private static float animationTime(float partialTick) {
        var level = Minecraft.getInstance().level;
        return (level == null ? 0 : level.getGameTime()) + partialTick;
    }

    private ClientModels() {
    }
}
