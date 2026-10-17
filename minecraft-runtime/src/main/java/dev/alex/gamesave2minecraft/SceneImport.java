package dev.alex.gamesave2minecraft;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;

/** Incremental generation in an owned void dimension. Never imports over another world. */
public final class SceneImport {
    public static final ResourceKey<Level> DIMENSION=ResourceKey.create(Registries.DIMENSION,ResourceLocation.fromNamespaceAndPath(GameSaveMod.ID,"scenes"));
    private static final Map<net.minecraft.server.MinecraftServer,Job> JOBS=new WeakHashMap<>();
    public static final class Progress extends SavedData {
        public final Set<String> complete=new LinkedHashSet<>();
        static Progress load(CompoundTag tag,HolderLookup.Provider registries){var p=new Progress();for(String id:ScenePlans.IDS)if(tag.getBoolean(id))p.complete.add(id);return p;}
        @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries){for(String id:complete)tag.putBoolean(id,true);return tag;}
    }
    public static Progress progress(ServerLevel level){return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(Progress::new,Progress::load),"gamesave2mc_scenes");}
    private static final class Job {
        final ServerLevel level;final Queue<String> remaining;final CommandSourceStack source;
        ScenePlans.Plan plan;Iterator<com.google.gson.JsonObject> rows;Iterator<Map.Entry<BlockPos,List<AABB>>> cells;
        Piece piece;int placed;long started=System.nanoTime();
        final Set<Long> forced=new LinkedHashSet<>();
        Job(ServerLevel l,Collection<String> ids,CommandSourceStack s){level=l;remaining=new ArrayDeque<>(ids);source=s;}
    }
    public static LiteralArgumentBuilder<CommandSourceStack> commands(){
        return Commands.literal("scene")
            .then(Commands.literal("list").executes(c->{var l=c.getSource().getServer().getLevel(DIMENSION);c.getSource().sendSuccess(()->Component.literal("Scenes: "+ScenePlans.IDS+"; imported: "+(l==null?"维度未加载":progress(l).complete)),false);return 1;}))
            .then(Commands.literal("import").executes(c->start(c.getSource(),"all"))
                .then(Commands.argument("id",StringArgumentType.word()).suggests((c,b)->{b.suggest("all");ScenePlans.IDS.forEach(b::suggest);return b.buildFuture();}).executes(c->start(c.getSource(),StringArgumentType.getString(c,"id")))))
            .then(Commands.literal("enter").then(Commands.argument("id",StringArgumentType.word()).suggests((c,b)->{ScenePlans.IDS.forEach(b::suggest);return b.buildFuture();}).executes(c->enter(c.getSource(),StringArgumentType.getString(c,"id")))))
            .then(Commands.literal("return").executes(c->{var p=c.getSource().getPlayerOrException();var level=p.server.overworld();BlockPos pos=level.getSharedSpawnPos();p.teleportTo(level,pos.getX()+.5,pos.getY()+1,pos.getZ()+.5,Set.of(),p.getYRot(),p.getXRot());return 1;}));
    }
    public static int start(CommandSourceStack source,String id){
        var server=source.getServer();var level=server.getLevel(DIMENSION);
        if(level==null){source.sendFailure(Component.literal("场景维度未加载"));return 0;}
        if(JOBS.containsKey(server)){source.sendFailure(Component.literal("场景导入仍在进行"));return 0;}
        if(!id.equals("all")&&!ScenePlans.IDS.contains(id)){source.sendFailure(Component.literal("未知场景编号"));return 0;}
        var ids=new ArrayList<>(id.equals("all")?ScenePlans.IDS:List.of(id));ids.removeAll(progress(level).complete);
        if(ids.isEmpty()){source.sendSuccess(()->Component.literal("这些场景已经导入，可用 scene enter 进入"),false);return 1;}
        JOBS.put(server,new Job(level,ids,source));source.sendSuccess(()->Component.literal("开始按原存档生成场景 "+ids+"，完成后会显示结果。"),false);return 1;
    }
    public static boolean running(net.minecraft.server.MinecraftServer server){return JOBS.containsKey(server);}
    public static void stopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event){var job=JOBS.remove(event.getServer());if(job!=null)release(job);}
    public static void tick(ServerTickEvent.Post event){
        var server=event.getServer();var job=JOBS.get(server);if(job==null)return;
        long deadline=System.nanoTime()+6_000_000L;int budget=96;
        try {
            while(budget-->0&&System.nanoTime()<deadline){
                if(job.cells!=null&&job.cells.hasNext()){
                    var cell=job.cells.next();apply(job,cell);continue;
                }
                if(job.rows!=null&&job.rows.hasNext()){
                    job.piece=ScenePlans.piece(job.plan,job.rows.next());
                    if(ModelCatalog.get(job.piece.asset).behavior().equals("plant")&&!job.piece.active)job.piece.growAt=job.level.getGameTime()+job.piece.growAt;
                    job.cells=job.piece.fragments().entrySet().iterator();job.placed++;continue;
                }
                if(job.plan!=null){progress(job.level).complete.add(job.plan.id());progress(job.level).setDirty();String doneId=job.plan.id();int count=job.placed;job.source.sendSuccess(()->Component.literal("场景 "+doneId+" 已生成，共 "+count+" 个物件。"),false);release(job);job.plan=null;}
                if(job.remaining.isEmpty()){JOBS.remove(server);job.source.sendSuccess(()->Component.literal("全部选定场景已导入完成。"),false);break;}
                job.plan=ScenePlans.read(job.remaining.remove());job.rows=job.plan.placements().iterator();job.placed=0;
            }
        }catch(Exception error){release(job);JOBS.remove(server);job.source.sendFailure(Component.literal("导入暂停，保留已完成物件，可重新执行继续："+error));}
    }
    private static void release(Job job){for(long key:job.forced)job.level.setChunkForced(ChunkPos.getX(key),ChunkPos.getZ(key),false);job.forced.clear();}
    private static void apply(Job job,Map.Entry<BlockPos,List<AABB>> entry){
        BlockPos pos=entry.getKey();var level=job.level;
        if(!level.isInWorldBounds(pos))throw new IllegalStateException("Source object outside dimension height "+pos);
        long key=ChunkPos.asLong(pos.getX()>>4,pos.getZ()>>4);
        if(!level.getForcedChunks().contains(key)){level.setChunkForced(pos.getX()>>4,pos.getZ()>>4,true);job.forced.add(key);}
        var state=level.getBlockState(pos);
        if(!state.isAir()&&!state.is(GameSaveMod.CELL.get()))throw new IllegalStateException("Refusing to overwrite existing block at "+pos);
        if(!state.is(GameSaveMod.CELL.get()))level.setBlock(pos,GameSaveMod.CELL.get().defaultBlockState(),2);
        if(!(level.getBlockEntity(pos)instanceof CellEntity cell))throw new IllegalStateException("Missing scene component cell");
        Piece piece=job.piece;cell.fragments.removeIf(f->f.id().equals(piece.id));cell.fragments.add(new CellEntity.Fragment(piece.id,piece.owner(),List.copyOf(entry.getValue()),piece.picks(pos,entry.getValue())));
        if(pos.equals(piece.owner())){cell.roots.removeIf(p->p.id.equals(piece.id));cell.roots.add(piece);}
        if(ModelCatalog.get(piece.asset).behavior().equals("bed"))level.setBlock(pos,level.getBlockState(pos).setValue(CellBlock.FACING,Direction.fromYRot((float)-piece.angle())),2);
        cell.changed();
    }
    public static int enter(CommandSourceStack source,String id)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        var level=source.getServer().getLevel(DIMENSION);
        if(level==null||!progress(level).complete.contains(id)){source.sendFailure(Component.literal("先执行 /gamesave2mc scene import "+id));return 0;}
        var plan=ScenePlans.read(id);Vec3 spawn=findSafeSpawn(level,plan);
        if(spawn==null){source.sendFailure(Component.literal("未找到已验证的安全落脚点，保留当前位置"));return 0;}
        var p=source.getPlayerOrException();p.teleportTo(level,spawn.x,spawn.y,spawn.z,Set.of(),0,0);return 1;
    }
    public static Vec3 findSafeSpawn(ServerLevel level,ScenePlans.Plan plan){
        Vec3 heart=ScenePlans.heart(plan);
        var candidates=new ArrayList<Vec3>();
        for(var raw:plan.metadata().getAsJsonArray("floorCandidates"))candidates.add(ModelCatalog.vector(raw.getAsJsonArray()).add(ScenePlans.ORIGIN));
        candidates.sort(Comparator.comparingDouble(p->p.distanceToSqr(heart)));
        // Find measured supporting collision and an actual two-block player clearance.
        for(var p:candidates){
            // Entry is requested from another dimension; collision queries do
            // not load distant saved chunks. Load just this tile's footprint.
            for(int x=((int)Math.floor(p.x-2))>>4;x<=((int)Math.floor(p.x+2))>>4;x++)
                for(int z=((int)Math.floor(p.z-2))>>4;z<=((int)Math.floor(p.z+2))>>4;z++)level.getChunk(x,z);
            for(double[] offset:new double[][]{{0,0},{.75,0},{-.75,0},{0,.75},{0,-.75},{1.5,1.5},{-1.5,1.5},{1.5,-1.5},{-1.5,-1.5}})
                for(double y=p.y+1;y>=p.y-.25;y-=.0625){
                    double x=p.x+offset[0],z=p.z+offset[1];
                    var box=new AABB(x-.3,y,z-.3,x+.3,y+1.8,z+.3);
                    if(level.noCollision(box)&&!level.noCollision(box.move(0,-.08,0)))return new Vec3(x,y,z);
                }
        }
        return null;
    }
    private SceneImport(){}
}
