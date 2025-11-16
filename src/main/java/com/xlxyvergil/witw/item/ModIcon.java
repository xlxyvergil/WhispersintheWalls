package com.xlxyvergil.witw.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

public class ModIcon extends Item {
    public ModIcon(Properties properties) {
        super(properties.rarity(Rarity.EPIC)); // 设置为特殊稀有度
    }
}