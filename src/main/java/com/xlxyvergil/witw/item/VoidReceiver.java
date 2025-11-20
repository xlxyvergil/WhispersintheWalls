package com.xlxyvergil.witw.item;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xlxyvergil.witw.WhispersInTheWalls;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class VoidReceiver extends Item {
    // 存储玩家冷却时间的映射（60秒CD）
    private static final Map<String, Long> playerCooldowns = new HashMap<>();
    // 存储待处理任务的映射（5秒延迟生效）
    private static final Map<String, Long> pendingTasks = new HashMap<>();
    // 存储待处理任务的相关数据
    private static final Map<String, TaskData> taskDataMap = new HashMap<>();

    public VoidReceiver(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level world, Player player, InteractionHand hand) {
        if (!world.isClientSide && player instanceof ServerPlayer) {
            String playerId = player.getStringUUID();
            long currentTime = System.currentTimeMillis();

            // 检查玩家是否在冷却中 (15秒CD)
            if (playerCooldowns.containsKey(playerId) && (currentTime - playerCooldowns.get(playerId)) < 15000) {
                long remainingTime = (15000 - (currentTime - playerCooldowns.get(playerId))) / 1000;
                player.sendSystemMessage(Component.literal("设备冷却中，剩余时间: " + remainingTime + "秒"));
                return InteractionResultHolder.fail(player.getItemInHand(hand));
            }

            // 设置冷却时间
            playerCooldowns.put(playerId, currentTime);
            
            // 添加到待处理任务中（5秒后生效）
            String taskId = UUID.randomUUID().toString();
            pendingTasks.put(taskId, currentTime + 5000); // 5秒后生效
            taskDataMap.put(taskId, new TaskData(world, player, playerId));
            
            player.sendSystemMessage(Component.literal("设备已激活，5秒后生效"));

        }

        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    /**
     * 处理待完成的任务
     */
    public static void processPendingTasks() {
        long currentTime = System.currentTimeMillis();
        Map<String, Long> tasksToProcess = new HashMap<>(pendingTasks);
        
        for (Map.Entry<String, Long> entry : tasksToProcess.entrySet()) {
            String taskId = entry.getKey();
            long executeTime = entry.getValue();
            
            if (currentTime >= executeTime) {
                // 时间到了，执行任务
                TaskData data = taskDataMap.get(taskId);
                if (data != null) {
                    // 检查文件是否被锁定
                    if (isFileLocked(getWhisperPath().resolve("index.json").toString())) {
                        // 文件被锁定，取消CD，允许重新使用
                        playerCooldowns.remove(data.playerId);
                        data.player.sendSystemMessage(Component.literal("检测到文件被锁定，CD已取消，可以重新使用设备"));
                    } else {
                        // 文件未被锁定，执行任务
                        data.player.sendSystemMessage(Component.literal("设备开始执行..."));
                        receiveAndPlaceBarrels(data.world, data.player);
                    }
                }
                
                // 移除已完成的任务
                pendingTasks.remove(taskId);
                taskDataMap.remove(taskId);
            }
        }
    }

    /**
     * 检查文件是否被锁定
     */
    private static boolean isFileLocked(String filePath) {
        Path path = Paths.get(filePath);
        // 先检查文件是否存在
        if (!Files.exists(path)) {
            return false; // 文件不存在，未被锁定
        }
        
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            return lock == null;
        } catch (IOException e) {
            // 如果无法访问文件，可能也被锁定
            return true;
        }
    }

    /**
     * 接收并放置木桶
     */
    private static void receiveAndPlaceBarrels(Level world, Player player) {
        try {
            Path indexPath = getWhisperPath().resolve("index.json");
            File indexFile = indexPath.toFile();
            if (!indexFile.exists()) {
                player.sendSystemMessage(Component.literal("未找到索引文件"));
                return;
            }

            JsonObject indexData;
            try (FileReader reader = new FileReader(indexFile)) {
                indexData = JsonParser.parseReader(reader).getAsJsonObject();
            }

            // 查找属于该玩家且未读取的记录
            String selectedUUID = null;
            String selectedPlayerName = null;

            for (String uuid : indexData.keySet()) {
                JsonObject entry = indexData.getAsJsonObject(uuid);
                if (entry.has("playerName") && entry.get("playerName").getAsString().equals(player.getName().getString())) {
                    // 检查是否有已读取状态
                    if (!entry.has("read") || !entry.get("read").getAsBoolean()) {
                        selectedUUID = uuid;
                        selectedPlayerName = entry.get("playerName").getAsString();
                        break;
                    }
                }
            }

            if (selectedUUID == null) {
                player.sendSystemMessage(Component.literal("未找到可用于恢复的数据"));
                return;
            }

            // 读取对应的JSON文件
            Path playerFilePath = getWhisperPath().resolve(selectedPlayerName).resolve(selectedUUID + ".json");
            File playerFile = playerFilePath.toFile();
            if (!playerFile.exists()) {
                player.sendSystemMessage(Component.literal("未找到对应的数据文件"));
                return;
            }

            JsonObject jsonData;
            try (FileReader reader = new FileReader(playerFile)) {
                jsonData = JsonParser.parseReader(reader).getAsJsonObject();
            }

            // 获取原始玩家位置信息
            JsonObject originalPlayerPos = jsonData.getAsJsonObject("playerPos");
            int originalPlayerX = originalPlayerPos.get("x").getAsInt();
            int originalPlayerY = originalPlayerPos.get("y").getAsInt();
            int originalPlayerZ = originalPlayerPos.get("z").getAsInt();
            
            // 获取当前玩家位置
            BlockPos currentPlayerPos = player.blockPosition();

            // 计算位置偏移
            int offsetX = currentPlayerPos.getX() - originalPlayerX;
            int offsetY = currentPlayerPos.getY() - originalPlayerY;
            int offsetZ = currentPlayerPos.getZ() - originalPlayerZ;

            // 恢复木桶
            JsonObject barrelsData = jsonData.getAsJsonObject("barrels");

            boolean placedAnyBarrel = false;
            int skippedItemsCount = 0;

            for (String positionKey : barrelsData.keySet()) {
                JsonObject barrelJson = barrelsData.getAsJsonObject(positionKey);
                
                // 获取相对于原始玩家位置的坐标偏移
                int relativeX = barrelJson.get("offsetX").getAsInt();
                int relativeY = barrelJson.get("offsetY").getAsInt();
                int relativeZ = barrelJson.get("offsetZ").getAsInt();

                // 计算在当前位置应该放置木桶的坐标
                int x = originalPlayerX + relativeX + offsetX;
                int y = originalPlayerY + relativeY + offsetY;
                int z = originalPlayerZ + relativeZ + offsetZ;

                BlockPos blockPos = new BlockPos(x, y, z);

                // 检查位置是否为空气或其他可替换方块
                if (world.isEmptyBlock(blockPos) || world.getBlockState(blockPos).canBeReplaced()) {
                    // 放置木桶
                    world.setBlock(blockPos, Blocks.BARREL.defaultBlockState(), 3);
                    placedAnyBarrel = true;

                    // 恢复木桶内容
                    if (world.getBlockEntity(blockPos) instanceof BarrelBlockEntity barrelEntity) {
                        // 从JSON数据恢复NBT
                        String nbtData = barrelJson.get("nbt_data").getAsString();
                        
                        // 解析NBT数据
                        CompoundTag nbtTag = parseNbtData(nbtData);
                        if (nbtTag != null) {
                            // 检查并过滤掉不存在的物品
                            CompoundTag filteredNbt = filterItemsByModAvailability(nbtTag, player);
                            if (filteredNbt != null) {
                                // 应用NBT数据到木桶实体
                                barrelEntity.load(filteredNbt);
                                // 统计被跳过的物品数量
                                skippedItemsCount += filteredNbt.getInt("SkippedItemsCount");
                            }
                        }
                    }
                }
                // 如果位置被占用，则在附近寻找合适的位置放置木桶
                else if (!world.isEmptyBlock(blockPos)) {
                    // 在附近寻找合适的位置
                    boolean placed = false;
                    for (int dx = -2; dx <= 2 && !placed; dx++) {
                        for (int dy = -2; dy <= 2 && !placed; dy++) {
                            for (int dz = -2; dz <= 2 && !placed; dz++) {
                                BlockPos nearbyPos = blockPos.offset(dx, dy, dz);
                                if (world.isEmptyBlock(nearbyPos) || world.getBlockState(nearbyPos).canBeReplaced()) {
                                    world.setBlock(nearbyPos, Blocks.BARREL.defaultBlockState(), 3);
                                    placedAnyBarrel = true;
                                    placed = true;
                                    
                                    // 恢复木桶内容
                                    if (world.getBlockEntity(nearbyPos) instanceof BarrelBlockEntity barrelEntity) {
                                        // 从JSON数据恢复NBT
                                        String nbtData = barrelJson.get("nbt_data").getAsString();
                                        
                                        // 解析NBT数据
                                        CompoundTag nbtTag = parseNbtData(nbtData);
                                        if (nbtTag != null) {
                                            // 检查并过滤掉不存在的物品
                                            CompoundTag filteredNbt = filterItemsByModAvailability(nbtTag, player);
                                            if (filteredNbt != null) {
                                                // 应用NBT数据到木桶实体
                                                barrelEntity.load(filteredNbt);
                                                // 统计被跳过的物品数量
                                                skippedItemsCount += filteredNbt.getInt("SkippedItemsCount");
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (placedAnyBarrel) {
                // 标记该记录为已读取
                JsonObject entry = indexData.getAsJsonObject(selectedUUID);
                entry.addProperty("read", true);

                // 更新索引文件
                try (FileWriter writer = new FileWriter(indexFile)) {
                    new Gson().toJson(indexData, writer);
                }

                // 删除数据文件
                playerFilePath.toFile().delete();

                String message = "已恢复木桶数据";
                if (skippedItemsCount > 0) {
                    message += "，由于缺少Mod，已跳过 " + skippedItemsCount + " 个物品";
                }
                player.sendSystemMessage(Component.literal(message));
            } else {
                player.sendSystemMessage(Component.literal("无法放置木桶，请确保周围有足够空间"));
            }
        } catch (Exception e) {
            player.sendSystemMessage(Component.literal("恢复数据时出错: " + e.getMessage()));
            e.printStackTrace();
        }
    }
    
    /**
     * 检查并过滤掉不存在的物品
     */
    private static CompoundTag filterItemsByModAvailability(CompoundTag nbtTag, Player player) {
        try {
            // 检查物品栏数据
            if (nbtTag.contains("Items", 9)) { // 9 表示 ListTag
                CompoundTag newNbt = nbtTag.copy();
                ListTag itemsList = nbtTag.getList("Items", 10); // 10 表示 CompoundTag
                ListTag filteredItemsList = new ListTag();
                int skippedItemsCount = 0;
                
                // 遍历每个物品
                for (int i = 0; i < itemsList.size(); i++) {
                    CompoundTag itemTag = itemsList.getCompound(i);
                    String itemId = itemTag.getString("id");
                    
                    // 检查物品ID是否包含命名空间（modid:itemname格式）
                    if (itemId.contains(":")) {
                        String modId = itemId.split(":")[0];
                        
                        // 检查该mod是否存在
                        if (isModAvailable(modId)) {
                            // Mod存在，保留该物品
                            filteredItemsList.add(itemTag);
                        } else {
                            // Mod不存在，跳过该物品
                            skippedItemsCount++;
                            player.sendSystemMessage(Component.literal("跳过物品: " + itemId + " (缺少Mod: " + modId + ")"));
                        }
                    } else {
                        // 没有命名空间的物品ID，假设为Minecraft原版物品，保留
                        filteredItemsList.add(itemTag);
                    }
                }
                
                // 更新过滤后的物品列表
                newNbt.put("Items", filteredItemsList);
                newNbt.putInt("SkippedItemsCount", skippedItemsCount);
                return newNbt;
            }
            return nbtTag;
        } catch (Exception e) {
            player.sendSystemMessage(Component.literal("过滤物品时出错: " + e.getMessage()));
            e.printStackTrace();
            CompoundTag errorNbt = nbtTag.copy();
            errorNbt.putInt("SkippedItemsCount", 0);
            return errorNbt;
        }
    }
    
    /**
     * 检查指定的Mod是否可用
     */
    private static boolean isModAvailable(String modId) {
        // Minecraft原版物品不需要检查
        if ("minecraft".equals(modId)) {
            return true;
        }
        
        // 检查是否是当前Mod
        if ("witw".equals(modId)) {
            return true;
        }
        
        // 检查Forge注册表中是否有该Mod的物品
        for (Item item : ForgeRegistries.ITEMS) {
            ResourceLocation registryName = ForgeRegistries.ITEMS.getKey(item);
            if (registryName != null && modId.equals(registryName.getNamespace())) {
                return true;
            }
        }
        
        // 如果没有找到该Mod的物品，则认为Mod不可用
        return false;
    }
    
    /**
     * 解析NBT数据字符串
     */
    private static CompoundTag parseNbtData(String nbtData) {
        try {
            // 使用TagParser解析NBT数据
            return TagParser.parseTag(nbtData);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * 获取Whisper目录路径
     */
    private static Path getWhisperPath() {
        if (WhispersInTheWalls.WHISPER_PATH != null) {
            return WhispersInTheWalls.WHISPER_PATH;
        }
        return Paths.get("Whisper");
    }
    
    /**
     * 任务数据类
     */
    private static class TaskData {
        Level world;
        Player player;
        String playerId;
        
        TaskData(Level world, Player player, String playerId) {
            this.world = world;
            this.player = player;
            this.playerId = playerId;
        }
    }
    
    // 用于存储玩家冷却的映射，供外部访问
    public static Map<String, Long> getPlayerCooldowns() {
        return playerCooldowns;
    }
}