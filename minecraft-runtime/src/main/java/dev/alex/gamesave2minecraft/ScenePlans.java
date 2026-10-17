package dev.alex.gamesave2minecraft;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.world.phys.Vec3;

/** Generated normalized scenes. Original game inputs are not runtime dependencies. */
public final class ScenePlans {
 public static final Vec3 ORIGIN=new Vec3(0,64,0);
 public static final List<String> IDS;
 static {var ids=new ArrayList<String>();for(var value:readJson("/data/gamesave2mc/scenes/index.json").getAsJsonArray("scenes"))ids.add(value.getAsString());IDS=List.copyOf(ids);}
 public record Plan(String id,JsonObject metadata,List<JsonObject> placements){}
 public static Plan read(String id){
  if(!IDS.contains(id))throw new IllegalArgumentException("Unknown scene "+id);
  String base="/data/gamesave2mc/scenes/"+id+"/";var index=readJson(base+"index.json");var rows=new ArrayList<JsonObject>();
  for(var name:index.getAsJsonArray("files")){String file=name.getAsString();if(!file.matches("part_[0-9]+\\.json"))throw new IllegalArgumentException("Unsafe scene part");for(var row:readJson(base+file).getAsJsonArray("placements"))rows.add(row.getAsJsonObject());}
  if(rows.size()!=index.get("placementCount").getAsInt())throw new IllegalArgumentException("Incomplete scene plan");
  return new Plan(id,index,List.copyOf(rows));
 }
 static JsonObject readJson(String path){try(var in=ScenePlans.class.getResourceAsStream(path)){if(in==null)throw new IOException("Missing scene metadata "+path);return JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();}catch(IOException e){throw new UncheckedIOException(e);}}
 public static Piece piece(Plan plan,JsonObject row){
  var piece=new Piece(row.get("asset").getAsString(),ModelCatalog.vector(row.getAsJsonArray("position")).add(ORIGIN),0,ModelCatalog.vector(row.getAsJsonArray("scale")));
  piece.id=UUID.fromString(row.get("targetId").getAsString());var q=row.getAsJsonArray("quaternion");piece.orientation.set(q.get(0).getAsFloat(),q.get(1).getAsFloat(),q.get(2).getAsFloat(),q.get(3).getAsFloat()).normalize();
  piece.active=row.get("active").getAsBoolean();piece.inventorySize(row.get("inventorySlots").getAsInt());
  for(var raw:row.getAsJsonArray("inventory")){var lot=raw.getAsJsonObject();int slot=lot.get("slot").getAsInt();if(slot<0||slot>=piece.inventory.size())throw new IllegalArgumentException("Inventory slot outside capacity");piece.inventory.set(slot,SourceResources.stack(lot));}
  piece.growAt=row.has("remainingGrowthTicks")?row.get("remainingGrowthTicks").getAsLong():ModelCatalog.get(piece.asset).growTicks();
  piece.sourceData=row.toString();return piece;
 }
 public static Vec3 heart(Plan plan){return ModelCatalog.vector(plan.metadata().getAsJsonArray("entryAnchor")).add(ORIGIN);}
 private ScenePlans(){}
}
