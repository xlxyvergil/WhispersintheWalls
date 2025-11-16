package com.xlxyvergil.witw.tab;

import com.xlxyvergil.witw.WhispersInTheWalls;
import com.xlxyvergil.witw.item.ModItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public class ModCreativeTabs {
    // 创建一个DeferredRegister来注册创造模式标签页
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, WhispersInTheWalls.MOD_ID);

    // 注册我们的创造模式标签页
    public static final RegistryObject<CreativeModeTab> WITW_TAB = CREATIVE_MODE_TABS.register("witw_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.witw"))
                    .icon(() -> new ItemStack(ModItems.MOD_ICON.get())) // 使用mod_icon作为图标
                    .displayItems((parameters, output) -> {
                        // 添加物品到标签页（不包括图标物品本身）
                        output.accept(ModItems.VOID_TRANSMITTER.get());
                        output.accept(ModItems.VOID_RECEIVER.get());
                    })
                    .build());
}