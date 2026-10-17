package dev.alex.gamesave2minecraft;

import com.google.gson.*;
import net.minecraft.world.phys.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;

/** Small common/server metadata; mesh payloads are client resources. */
public final class ModelCatalog {
    public record Cube(Vec3 min,Vec3 max,String material,String motion,Vec3 rotation,Vec3 origin,String name) {
        public Vec3 point(Vec3 p){return p;}
    }
    public record RenderPart(String mesh, List<Float> matrix) {}
    public record Door(String mode,double left,double right,double z){}
    public record Asset(String id,String label,String behavior,List<Cube> cubes,List<AABB> collisions,Vec3 seat,String mesh,String dormantMesh,int growTicks,String harvestItem,AABB visualBounds,List<RenderPart> renderParts,int sourceFlags,Door door,List<RenderPart> dormantRenderParts) {}
    public static final Map<String,Asset> ASSETS=new LinkedHashMap<>();
    static {
        try {
            loadCatalog("/assets/gamesave2mc/model_catalog.json");
            try(var in=ModelCatalog.class.getResourceAsStream("/assets/gamesave2mc/scene_catalog/index.json")){
                if(in!=null){
                    var index=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();
                    int before=ASSETS.size();
                    for(var file:index.getAsJsonArray("files")){
                        String name=file.getAsString();
                        if(!name.matches("[a-z0-9_]+\\.json"))throw new IOException("Invalid scene metadata part");
                        loadCatalog("/assets/gamesave2mc/scene_catalog/"+name);
                    }
                    if(ASSETS.size()-before!=index.get("assetCount").getAsInt())throw new IOException("Scene catalog coverage mismatch");
                }
            }
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    private static void loadCatalog(String resource)throws IOException{
        try(var in=ModelCatalog.class.getResourceAsStream(resource)){
            if(in==null)throw new IOException("Missing component metadata "+resource);
            for(var element:JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("assets")){
                var a=element.getAsJsonObject();var boxes=new ArrayList<AABB>();
                for(var raw:a.getAsJsonArray("collision")){var b=raw.getAsJsonObject();boxes.add(box(b));}
                String id=a.get("id").getAsString();
                var parts=new ArrayList<RenderPart>();
                if(a.has("parts"))for(var raw:a.getAsJsonArray("parts")){
                    var part=raw.getAsJsonObject();var matrix=new ArrayList<Float>();
                    for(var value:part.getAsJsonArray("matrix")){float f=value.getAsFloat();if(!Float.isFinite(f))throw new IOException("Non-finite model transform");matrix.add(f);}
                    if(matrix.size()!=16)throw new IOException("Incomplete model transform");
                    parts.add(new RenderPart(part.get("mesh").getAsString(),List.copyOf(matrix)));
                }
                Door door=null;
                if(a.has("door")){var d=a.getAsJsonObject("door");door=new Door(d.get("mode").getAsString(),d.get("left").getAsDouble(),d.get("right").getAsDouble(),d.get("z").getAsDouble());}
                var dormant=new ArrayList<RenderPart>();
                if(a.has("dormantParts"))for(var raw:a.getAsJsonArray("dormantParts")){
                    var part=raw.getAsJsonObject();var matrix=new ArrayList<Float>();for(var f:part.getAsJsonArray("matrix"))matrix.add(f.getAsFloat());
                    if(matrix.size()!=16||matrix.stream().anyMatch(f->!Float.isFinite(f)))throw new IOException("Invalid dormant plant transform");
                    dormant.add(new RenderPart(part.get("mesh").getAsString(),List.copyOf(matrix)));
                }
                if(ASSETS.put(id,new Asset(id,a.get("label").getAsString(),a.get("behavior").getAsString(),List.of(),List.copyOf(boxes),a.has("seat")&&!a.get("seat").isJsonNull()?vector(a.getAsJsonArray("seat")):null,a.get("mesh").getAsString(),a.has("dormantMesh")?a.get("dormantMesh").getAsString():a.get("mesh").getAsString(),a.has("growTicks")?a.get("growTicks").getAsInt():1200,a.has("harvestItem")?a.get("harvestItem").getAsString():"minecraft:wheat",a.has("bounds")?box(a.getAsJsonObject("bounds")):null,List.copyOf(parts),a.has("sourceFlags")?a.get("sourceFlags").getAsInt():0,door,List.copyOf(dormant)))!=null)throw new IOException("Duplicate component "+id);
            }
        }
    }
    private static AABB box(JsonObject object){return new AABB(vector(object.getAsJsonArray("from")),vector(object.getAsJsonArray("to")));}
    public static Vec3 vector(JsonArray a){return new Vec3(a.get(0).getAsDouble(),a.get(1).getAsDouble(),a.get(2).getAsDouble());}
    public static Asset get(String id){var result=ASSETS.get(id);if(result==null)throw new IllegalArgumentException("Unknown source-game component: "+id);return result;}
    public static net.minecraft.network.chat.Component displayName(String id){return net.minecraft.network.chat.Component.translatableWithFallback("component.gamesave2mc."+id,get(id).label());}
    public static AABB bounds(Asset asset){if(asset.visualBounds()!=null)return asset.visualBounds();AABB box=null;for(var b:asset.collisions())box=box==null?b:box.minmax(b);return box==null?new AABB(0,0,0,1,1,1):box;}
    private ModelCatalog(){}
}
