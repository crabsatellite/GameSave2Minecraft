package dev.alex.gamesave2minecraft;

import com.google.gson.JsonObject;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** A lossless source inventory lot; source amounts are not Minecraft stack counts. */
public final class SourceResources {
    public static ItemStack stack(JsonObject item){
        long amount=item.get("amount").getAsLong();
        if(amount<=0)throw new IllegalArgumentException("Invalid source resource amount");
        var stack=new ItemStack(GameSaveMod.SOURCE_RESOURCE.get());
        var data=new CompoundTag();
        data.putString("SourceId",item.get("source_id").getAsString());
        data.putString("SourcePrefabName",item.get("name").getAsString());
        data.putLong("SourceAmount",amount);
        data.putString("SourceCategory",item.get("category").getAsString());
        data.putInt("SourceSlot",item.get("slot").getAsInt());
        stack.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
        // Text-only format arguments use their canonical serialized form so an
        // NBT round trip retains complete ItemStack component equality.
        var name=item.get("name").getAsString();
        stack.set(DataComponents.CUSTOM_NAME,Component.translatableWithFallback("gamesave2mc.source_lot","%s × %s",name,Long.toString(amount)));
        return stack;
    }
    private SourceResources(){}
}
