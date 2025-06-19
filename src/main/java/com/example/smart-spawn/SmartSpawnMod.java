package com.example.smartspawn;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

import java.util.Random;

@Mod(modid = SmartSpawnMod.MODID, version = SmartSpawnMod.VERSION, name = SmartSpawnMod.NAME)
public class SmartSpawnMod {
    public static final String MODID = "smartspawn";
    public static final String VERSION = "1.0";
    public static final String NAME = "Smart Spawn Mod";
    
    private static final int WATER_CHECK_RADIUS = 10;
    private static final int MAX_SPAWN_ATTEMPTS = 100;
    private static final int SPAWN_SEARCH_RADIUS = 1000;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // Initialization logic if needed
    }

    @SubscribeEvent
    public void onPlayerJoinWorld(EntityJoinWorldEvent event) {
        if (event.getEntity() instanceof EntityPlayer && !event.getWorld().isRemote) {
            EntityPlayer player = (EntityPlayer) event.getEntity();
            World world = event.getWorld();
            
            // Check if this is a new player or respawn
            if (shouldRelocatePlayer(player, world)) {
                BlockPos newSpawn = findSafeSmartSpawn(world, player.getPosition());
                if (newSpawn != null) {
                    player.setPositionAndUpdate(newSpawn.getX() + 0.5, newSpawn.getY(), newSpawn.getZ() + 0.5);
                }
            }
        }
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!event.player.world.isRemote) {
            World world = event.player.world;
            BlockPos currentPos = event.player.getPosition();
            
            if (isNearWater(world, currentPos)) {
                BlockPos newSpawn = findSafeSmartSpawn(world, currentPos);
                if (newSpawn != null) {
                    event.player.setPositionAndUpdate(newSpawn.getX() + 0.5, newSpawn.getY(), newSpawn.getZ() + 0.5);
                }
            }
        }
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (event.getWorld() instanceof WorldServer) {
            WorldServer worldServer = (WorldServer) event.getWorld();
            BlockPos spawnPoint = worldServer.getSpawnPoint();
            
            if (isNearWater(worldServer, spawnPoint)) {
                BlockPos newSpawn = findSafeSmartSpawn(worldServer, spawnPoint);
                if (newSpawn != null) {
                    worldServer.setSpawnPoint(newSpawn);
                }
            }
        }
    }

    private boolean shouldRelocatePlayer(EntityPlayer player, World world) {
        BlockPos playerPos = player.getPosition();
        return isNearWater(world, playerPos) || isInWater(world, playerPos);
    }

    private boolean isInWater(World world, BlockPos pos) {
        Block block = world.getBlockState(pos).getBlock();
        return block == Blocks.WATER || block == Blocks.FLOWING_WATER;
    }

    private boolean isNearWater(World world, BlockPos centerPos) {
        for (int x = -WATER_CHECK_RADIUS; x <= WATER_CHECK_RADIUS; x++) {
            for (int z = -WATER_CHECK_RADIUS; z <= WATER_CHECK_RADIUS; z++) {
                for (int y = -3; y <= 3; y++) {
                    BlockPos checkPos = centerPos.add(x, y, z);
                    Block block = world.getBlockState(checkPos).getBlock();
                    
                    if (block == Blocks.WATER || block == Blocks.FLOWING_WATER) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private BlockPos findSafeSmartSpawn(World world, BlockPos startPos) {
        Random random = new Random();
        
        for (int attempts = 0; attempts < MAX_SPAWN_ATTEMPTS; attempts++) {
            int x = startPos.getX() + (random.nextInt(SPAWN_SEARCH_RADIUS * 2) - SPAWN_SEARCH_RADIUS);
            int z = startPos.getZ() + (random.nextInt(SPAWN_SEARCH_RADIUS * 2) - SPAWN_SEARCH_RADIUS);
            
            // Find the top solid block
            BlockPos testPos = world.getHeight(new BlockPos(x, 0, z));
            
            if (isSafeSmartSpawn(world, testPos)) {
                return testPos;
            }
        }
        
        // Fallback: spiral search from start position
        return spiralSearch(world, startPos);
    }

    private BlockPos spiralSearch(World world, BlockPos center) {
        for (int radius = 1; radius <= 50; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.abs(x) == radius || Math.abs(z) == radius) {
                        BlockPos testPos = world.getHeight(center.add(x, 0, z));
                        if (isSafeSmartSpawn(world, testPos)) {
                            return testPos;
                        }
                    }
                }
            }
        }
        
        // Ultimate fallback - return a position high above the original
        return new BlockPos(center.getX(), 100, center.getZ());
    }

    private boolean isSafeSmartSpawn(World world, BlockPos pos) {
        // Check if the spawn location itself is safe
        Block groundBlock = world.getBlockState(pos.down()).getBlock();
        Block spawnBlock = world.getBlockState(pos).getBlock();
        Block aboveBlock = world.getBlockState(pos.up()).getBlock();
        
        // Must have solid ground
        if (!groundBlock.getMaterial(groundBlock.getDefaultState()).isSolid()) {
            return false;
        }
        
        // Must not be water or lava
        if (groundBlock.getMaterial(groundBlock.getDefaultState()) == Material.WATER ||
            groundBlock.getMaterial(groundBlock.getDefaultState()) == Material.LAVA) {
            return false;
        }
        
        // Spawn position and above must be air or replaceable
        if (!spawnBlock.isReplaceable(world, pos) && spawnBlock != Blocks.AIR) {
            return false;
        }
        
        if (!aboveBlock.isReplaceable(world, pos.up()) && aboveBlock != Blocks.AIR) {
            return false;
        }
        
        // Check that there's no water nearby
        if (isNearWater(world, pos)) {
            return false;
        }
        
        // Additional safety checks
        if (pos.getY() < 1 || pos.getY() > 250) {
            return false;
        }
        
        return true;
    }
}
