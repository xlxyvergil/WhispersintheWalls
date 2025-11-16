package com.xlxyvergil.witw;

import com.xlxyvergil.witw.item.ModItems;
import com.xlxyvergil.witw.item.VoidReceiver;
import com.xlxyvergil.witw.item.VoidTransmitter;
import com.xlxyvergil.witw.tab.ModCreativeTabs;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Mod("witw")
public class WhispersInTheWalls {
    public static final String MOD_ID = "witw";
    private static int tickCounter = 0;
    public static Path WHISPER_PATH;

    public WhispersInTheWalls() {
        // 构造函数，用于初始化模组
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 注册物品
        ModItems.ITEMS.register(modEventBus);

        // 注册创造模式标签页
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);

        // 注册事件监听器
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // 在服务器启动时创建Whisper文件夹
        try {
            WHISPER_PATH = Paths.get("Whisper");
            if (!Files.exists(WHISPER_PATH)) {
                Files.createDirectories(WHISPER_PATH);
            }
        } catch (Exception e) {
            System.err.println("Failed to create Whisper directory: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        // 每20 ticks（1秒）检查一次待处理任务
        if (event.phase == TickEvent.Phase.END) {
            tickCounter++;
            if (tickCounter >= 20) {
                tickCounter = 0;
                VoidReceiver.processPendingTasks();
                VoidTransmitter.processPendingTasks();
            }
        }
    }
}