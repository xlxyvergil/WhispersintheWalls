package com.xlxyvergil.witw.item;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xlxyvergil.witw.WhispersInTheWalls;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

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

public class VoidTransmitter extends Item {
    // 存储玩家冷却时间的映射（60秒CD）
    private static final Map<String, Long> playerCooldowns = new HashMap<>();
    // 存储待处理任务的映射（5秒延迟生效）
    private static final Map<String, Long> pendingTasks = new HashMap<>();
    // 存储待处理任务的相关数据
    private static final Map<String, TaskData> taskDataMap = new HashMap<>();

    public VoidTransmitter(Properties properties) {
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
                        scanAndProcessBarrels(data.world, data.player);
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
     * 扫描玩家周围的木桶并处理数据
     */
    private static void scanAndProcessBarrels(Level world, Player player) {
        BlockPos playerPos = player.blockPosition();
        JsonObject jsonData = new JsonObject();

        // 添加玩家ID
        jsonData.addProperty("playerId", player.getStringUUID());
        
        // 添加玩家位置信息
        JsonObject playerPosJson = new JsonObject();
        playerPosJson.addProperty("x", playerPos.getX());
        playerPosJson.addProperty("y", playerPos.getY());
        playerPosJson.addProperty("z", playerPos.getZ());
        jsonData.add("playerPos", playerPosJson);

        // 添加唯一UUID
        String jsonUUID = UUID.randomUUID().toString();
        jsonData.addProperty("uuid", jsonUUID);

        // 创建存储木桶数据的数组
        JsonObject barrelsData = new JsonObject();

        // 扫描5x5x5范围内的木桶
        for (int x = playerPos.getX() - 2; x <= playerPos.getX() + 2; x++) {
            for (int y = playerPos.getY() - 2; y <= playerPos.getY() + 2; y++) {
                for (int z = playerPos.getZ() - 2; z <= playerPos.getZ() + 2; z++) {
                    BlockPos blockPos = new BlockPos(x, y, z);
                    if (world.getBlockState(blockPos).getBlock() instanceof BarrelBlock) {
                        BlockEntity blockEntity = world.getBlockEntity(blockPos);
                        if (blockEntity instanceof BarrelBlockEntity) {
                            // 获取木桶位置
                            String positionKey = x + "," + y + "," + z;

                            // 获取木桶的完整NBT数据（包括物品）
                            CompoundTag barrelTag = blockEntity.saveWithFullMetadata();

                            // 将NBT转换为JSON格式
                            JsonObject barrelJson = new JsonObject();
                            // 保存相对于玩家的坐标偏移
                            barrelJson.addProperty("offsetX", x - playerPos.getX());
                            barrelJson.addProperty("offsetY", y - playerPos.getY());
                            barrelJson.addProperty("offsetZ", z - playerPos.getZ());

                            // 将NBT数据转换为字符串存储
                            barrelJson.addProperty("nbt_data", barrelTag.toString());

                            barrelsData.add(positionKey, barrelJson);

                            // 清空木桶内容物后再删除木桶
                            if (blockEntity instanceof BarrelBlockEntity barrelBlockEntity) {
                                // 清空木桶内的物品
                                barrelBlockEntity.clearContent();
                            }
                            // 删除空的木桶方块
                            world.removeBlock(blockPos, false);
                        }
                    }
                }
            }
        }

        jsonData.add("barrels", barrelsData);

        // 保存JSON文件
        saveJsonFile(jsonData, jsonUUID, player);
    }

    /**
     * 保存JSON文件
     */
    private static void saveJsonFile(JsonObject jsonData, String uuid, Player player) {
        try {
            // 创建Whisper文件夹
            Path whisperPath = getWhisperPath();
            if (!Files.exists(whisperPath)) {
                Files.createDirectories(whisperPath);
            }

            // 创建玩家名称文件夹
            String playerName = player.getName().getString();
            Path playerPath = whisperPath.resolve(playerName);
            if (!Files.exists(playerPath)) {
                Files.createDirectories(playerPath);
            }

            // 创建JSON文件
            Path jsonFilePath = playerPath.resolve(uuid + ".json");
            File jsonFile = jsonFilePath.toFile();

            FileWriter writer = new FileWriter(jsonFile);
            new GsonBuilder().setPrettyPrinting().create().toJson(jsonData, writer);
            writer.close();

            // 更新Whisper文件夹中的索引文件
            updateIndexFile(uuid, player.getStringUUID(), playerName);

            player.sendSystemMessage(Component.literal("数据已保存至: " + jsonFile.getAbsolutePath()));
        } catch (IOException e) {
            player.sendSystemMessage(Component.literal("保存文件时出错: " + e.getMessage()));
            e.printStackTrace();
        }
    }

    /**
     * 更新Whisper文件夹中的索引文件
     */
    private static void updateIndexFile(String uuid, String playerId, String playerName) {
        try {
            Path indexPath = getWhisperPath().resolve("index.json");
            File indexFile = indexPath.toFile();
            JsonObject indexData = new JsonObject();

            // 如果索引文件存在，读取现有数据
            if (indexFile.exists()) {
                try (FileReader reader = new FileReader(indexFile)) {
                    indexData = JsonParser.parseReader(reader).getAsJsonObject();
                } catch (Exception e) {
                    // 如果解析失败，使用空的JsonObject
                    indexData = new JsonObject();
                }
            }

            // 检查是否已经存在相同的UUID
            if (!indexData.has(uuid)) {
                // 添加新的UUID和玩家名称映射
                JsonObject entry = new JsonObject();
                entry.addProperty("playerName", playerName);
                indexData.add(uuid, entry);

                // 写入更新后的索引文件
                FileWriter writer = new FileWriter(indexFile);
                new GsonBuilder().setPrettyPrinting().create().toJson(indexData, writer);
                writer.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
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