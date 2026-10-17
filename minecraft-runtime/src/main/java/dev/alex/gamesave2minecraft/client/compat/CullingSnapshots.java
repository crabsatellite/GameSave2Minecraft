package dev.alex.gamesave2minecraft.client.compat;

import dev.alex.gamesave2minecraft.CellEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;

/** Our cells render through WorldRoots; third-party BER snapshots have no work for them. */
public final class CullingSnapshots {
    private static long excluded;
    public static long excludedCells(){return excluded;}
    @SuppressWarnings("unchecked")
    public static <K,V> Map<K,V> renderable(Map<K,V> source){
        if(source.isEmpty())return source;
        Map<K,V> filtered=null;int skipped=0;
        // fastutil's ordinary entry iterator allocates a wrapper for every cell.
        // Consume the reusable fast entry immediately; never retain the entry itself.
        Iterator<? extends Map.Entry<K,V>> entries=source instanceof Object2ObjectMap<?,?>
                ?Object2ObjectMaps.fastIterator((Object2ObjectMap<K,V>)source):source.entrySet().iterator();
        while(entries.hasNext()){
            var entry=entries.next();
            if(entry.getValue() instanceof CellEntity){skipped++;continue;}
            if(filtered==null)filtered=new HashMap<>();
            filtered.put(entry.getKey(),entry.getValue());
        }
        if(skipped==0)return source;
        excluded+=skipped;
        return filtered==null?Map.of():filtered;
    }
    private CullingSnapshots(){}
}
