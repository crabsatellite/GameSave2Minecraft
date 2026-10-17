package dev.alex.gamesave2minecraft.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.alex.gamesave2minecraft.GameSaveMod;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Lazily loads only the imported meshes visible in the current scene. */
public final class ImportedMeshes {
    public record Group(
            ResourceLocation texture,
            float[] positions,
            float[] normals,
            float[] uvs,
            int[] indices,
            boolean emissive,
            boolean wind,
            float minY,
            float maxY,
            int[][] conditions,
            boolean shadowOnly,
            List<ResourceLocation> trimTextures,
            float[] trimSelectors,
            int color,
            boolean overlayOnly
    ) {
    }

    public record Mesh(
            List<Group> groups,
            boolean placeholder,
            float originX,
            float originY,
            float originZ,
            float scale,
            String geometryKey
    ) {
    }

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long MAX_CACHED_BYTES = 256L * 1024 * 1024;
    private static long cachedBytes;
    private static final Map<String, Mesh> CACHE = new LinkedHashMap<>(512, .75f, true);
    private static final ThreadLocal<java.util.Set<String>> LOADING=ThreadLocal.withInitial(java.util.HashSet::new);

    public static synchronized Mesh get(String meshId) {
        String key = meshId == null ? "" : meshId.trim();
        Mesh mesh=CACHE.get(key);
        if(mesh!=null)return mesh;
        if(!LOADING.get().add(key))throw new IllegalArgumentException("Cyclic model alias: "+key);
        try{mesh=load(key);}finally{LOADING.get().remove(key);}
        CACHE.put(key,mesh);cachedBytes+=bytes(mesh);
        var entries=CACHE.entrySet().iterator();
        while(cachedBytes>MAX_CACHED_BYTES&&CACHE.size()>1&&entries.hasNext()){
            var entry=entries.next();cachedBytes-=bytes(entry.getValue());entries.remove();
        }
        return mesh;
    }

    public static synchronized void clear() {
        CACHE.clear();
        cachedBytes=0;
    }
    private static long bytes(Mesh mesh){long total=128;for(var group:mesh.groups())total+=256L+4L*(group.positions().length+group.normals().length+group.uvs().length+group.indices().length);return total;}
    public static synchronized long cachedBytes(){return cachedBytes;}

    public static boolean available(String mesh) {
        ResourceLocation resource = resource(mesh);
        return resource != null && Minecraft.getInstance().getResourceManager().getResource(resource).isPresent();
    }

    private static Mesh load(String mesh) {
        ResourceLocation resource = resource(mesh);
        if (resource == null) {
            LOGGER.warn("GameSave2Minecraft cannot load invalid imported mesh id '{}'; using placeholder", mesh);
            return placeholder();
        }
        var manager = Minecraft.getInstance().getResourceManager();
        if (manager.getResource(resource).isEmpty()) {
            LOGGER.warn(
                    "GameSave2Minecraft imported mesh '{}' is missing from {}; using placeholder",
                    mesh,
                    ExternalModelPack.directory()
            );
            return placeholder();
        }
        try (var input = manager.open(resource);
             var gzip = new GZIPInputStream(input);
             var reader = new InputStreamReader(gzip, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if(root.has("base"))return alias(mesh,root);
            JsonArray sourceGroups = requiredArray(root, "groups");
            float[] origin = root.has("origin") ? vector3(root, "origin") : new float[]{0, 0, 0};
            float scale = root.has("scale") ? root.get("scale").getAsFloat() : 1;
            if (!Float.isFinite(scale) || scale <= 0) {
                throw new IllegalArgumentException("scale must be finite and positive");
            }
            var groups = new ArrayList<Group>(sourceGroups.size());
            for (int groupIndex = 0; groupIndex < sourceGroups.size(); groupIndex++) {
                JsonObject group = sourceGroups.get(groupIndex).getAsJsonObject();
                float[] positions = floats(group, "positions");
                float[] normals = floats(group, "normals");
                float[] uvs = floats(group, "uvs");
                int[] indices = integers(group, "indices");
                validate(mesh, groupIndex, positions, normals, uvs, indices);
                float minY = Float.POSITIVE_INFINITY;
                float maxY = Float.NEGATIVE_INFINITY;
                for (int i = 1; i < positions.length; i += 3) {
                    float y = (positions[i] - origin[1]) * scale;
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
                groups.add(new Group(
                        ResourceLocation.parse(group.get("texture").getAsString()),
                        positions,
                        normals,
                        uvs,
                        indices,
                        group.has("emissive") && group.get("emissive").getAsBoolean(),
                        group.has("wind") && group.get("wind").getAsBoolean(),
                        minY,
                        maxY,
                        conditions(group),group.has("shadowOnly")&&group.get("shadowOnly").getAsBoolean(),
                        trimTextures(group),group.has("trimSelectors")?floats(group,"trimSelectors"):null,
                        group.has("color")?group.get("color").getAsInt():-1,
                        group.has("overlayOnly")&&group.get("overlayOnly").getAsBoolean()
                ));
            }
            if (groups.isEmpty()) {
                throw new IllegalArgumentException("mesh contains no groups");
            }
            return new Mesh(List.copyOf(groups), false, origin[0], origin[1], origin[2], scale,mesh);
        } catch (Exception error) {
            LOGGER.error("GameSave2Minecraft could not decode imported mesh '{}'; using placeholder", mesh, error);
            return placeholder();
        }
    }

    private static Mesh alias(String name,JsonObject root){
        String baseId=root.get("base").getAsString();
        if(baseId.equals(name))throw new IllegalArgumentException("Self-referencing model alias");
        Mesh base=get(baseId);if(base.placeholder())throw new IllegalArgumentException("Missing alias base: "+baseId);
        var groups=new ArrayList<>(base.groups());boolean changedUv=false;
        for(var element:root.getAsJsonArray("overrides")){
            var item=element.getAsJsonObject();int index=item.get("group").getAsInt();
            if(index<0||index>=groups.size())throw new IllegalArgumentException("Dye renderer index out of range");
            var old=groups.get(index);float[] uvs=old.uvs();
            if(item.has("uvs")){uvs=floats(item,"uvs");if(uvs.length!=old.uvs().length)throw new IllegalArgumentException("Dye UV cardinality mismatch");changedUv=true;}
            var textures=old.trimTextures();
            if(item.has("trimTextures")){
                var copy=new ArrayList<ResourceLocation>();for(var raw:item.getAsJsonArray("trimTextures"))copy.add(ResourceLocation.parse(raw.getAsString()));
                if(copy.size()!=old.trimTextures().size())throw new IllegalArgumentException("Dye trim layers mismatch");textures=List.copyOf(copy);
            }
            groups.set(index,new Group(item.has("texture")?ResourceLocation.parse(item.get("texture").getAsString()):old.texture(),old.positions(),old.normals(),uvs,old.indices(),item.has("emissive")?item.get("emissive").getAsBoolean():old.emissive(),old.wind(),old.minY(),old.maxY(),old.conditions(),old.shadowOnly(),textures,old.trimSelectors(),old.color(),old.overlayOnly()));
        }
        return new Mesh(List.copyOf(groups),false,base.originX(),base.originY(),base.originZ(),base.scale(),changedUv?name:base.geometryKey());
    }

    private static int[][] conditions(JsonObject group){
        if(!group.has("conditions")||group.get("conditions").isJsonNull())return null;
        var raw=group.getAsJsonArray("conditions");int[][] result=new int[raw.size()][2];
        for(int i=0;i<raw.size();i++){
            var condition=raw.get(i).getAsJsonArray();if(condition.size()!=2)throw new IllegalArgumentException("Invalid source visibility condition");
            result[i][0]=condition.get(0).getAsInt();result[i][1]=condition.get(1).getAsInt();
        }
        return result;
    }
    private static List<ResourceLocation> trimTextures(JsonObject group){
        if(!group.has("trimTextures"))return List.of();
        var textures=new ArrayList<ResourceLocation>();for(var raw:group.getAsJsonArray("trimTextures"))textures.add(ResourceLocation.parse(raw.getAsString()));
        if(textures.size()>8||!group.has("trimSelectors")||group.getAsJsonArray("trimSelectors").size()!=group.getAsJsonArray("positions").size()/3)throw new IllegalArgumentException("Invalid trim material selector");
        return List.copyOf(textures);
    }

    private static ResourceLocation resource(String mesh) {
        if (mesh == null) {
            return null;
        }
        String name = mesh.trim().replace('\\', '/');
        if (name.endsWith(".json.gz")) {
            name = name.substring(0, name.length() - ".json.gz".length());
        }
        if (name.isEmpty() || name.startsWith("/") || name.contains(":") || name.contains("..")) {
            return null;
        }
        try {
            return ResourceLocation.fromNamespaceAndPath(GameSaveMod.ID, "meshes/" + name + ".json.gz");
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static JsonArray requiredArray(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonArray()) {
            throw new IllegalArgumentException("missing array " + name);
        }
        return object.getAsJsonArray(name);
    }

    private static float[] floats(JsonObject object, String name) {
        JsonArray array = requiredArray(object, name);
        float[] values = new float[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i).getAsFloat();
            if (!Float.isFinite(values[i])) {
                throw new IllegalArgumentException(name + " contains a non-finite value");
            }
        }
        return values;
    }

    private static float[] vector3(JsonObject object, String name) {
        float[] value = floats(object, name);
        if (value.length != 3) {
            throw new IllegalArgumentException(name + " must contain exactly three values");
        }
        return value;
    }

    private static int[] integers(JsonObject object, String name) {
        JsonArray array = requiredArray(object, name);
        int[] values = new int[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i).getAsInt();
        }
        return values;
    }

    private static void validate(
            String mesh,
            int group,
            float[] positions,
            float[] normals,
            float[] uvs,
            int[] indices
    ) {
        if (positions.length == 0 || positions.length % 3 != 0) {
            throw invalid(mesh, group, "positions must contain complete float3 vertices");
        }
        int vertices = positions.length / 3;
        if (normals.length != positions.length) {
            throw invalid(mesh, group, "normals must match positions");
        }
        if (uvs.length != vertices * 2) {
            throw invalid(mesh, group, "uvs must contain one float2 per vertex");
        }
        if (indices.length == 0 || indices.length % 3 != 0) {
            throw invalid(mesh, group, "indices must contain complete triangles");
        }
        for (int index : indices) {
            if (index < 0 || index >= vertices) {
                throw invalid(mesh, group, "triangle index is outside the vertex buffer");
            }
        }
    }

    private static IllegalArgumentException invalid(String mesh, int group, String reason) {
        return new IllegalArgumentException(mesh + " group " + group + ": " + reason);
    }

    private static Mesh placeholder() {
        return new Mesh(List.of(), true, 0, 0, 0, 1,"placeholder");
    }

    private ImportedMeshes() {
    }
}
