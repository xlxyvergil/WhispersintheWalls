package com.xlxyvergil.witw.item;

import com.xlxyvergil.witw.WhispersInTheWalls;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    // 创建一个DeferredRegister来注册物品
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, WhispersInTheWalls.MOD_ID);

    // 注册物品：虚空发射器
    public static final RegistryObject<Item> VOID_TRANSMITTER = ITEMS.register("void_transmitter",
            () -> new VoidTransmitter(new Item.Properties()));

    // 注册物品：虚空接收器
    public static final RegistryObject<Item> VOID_RECEIVER = ITEMS.register("void_receiver",
            () -> new VoidReceiver(new Item.Properties()));
}