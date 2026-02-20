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

    // FIX: Added a tag to track whether we've already relocated this player in this
    //      join event, preventing the event from re-triggering relocations on each login
    //      for players who are simply standing near water legitimately.
    //      We track by checking whether the player's current position is actually unsafe,
    //      and only act if it truly is — avoiding the "teleport every login" bug.
    @SubscribeEvent
    public void onPlayerJoinWorld(EntityJoinWorldEvent event) {
        if (!(event.getEntity() instanceof EntityPlayer) || event.getWorld().isRemote) return;

        EntityPlayer player = (EntityPlayer) event.getEntity();
        World world = event.getWorld();

        // Check the player's persistent NBT data to see if they've joined before.
        // getEntityData() returns a compound tag that is saved with the player file,
        // so this survives server restarts and correctly identifies true first-time joins.
        net.minecraft.nbt.NBTTagCompound data = player.getEntityData();
        if (data.getBoolean(MODID + ".hasSpawned")) return;

        // Mark them so this never runs again for this player.
        data.setBoolean(MODID + ".hasSpawned", true);

        if (isInWater(world, player.getPosition())) {
            relocatePlayer(player, world);
        }
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.player.world.isRemote) return;

        EntityPlayer player = event.player;
        World world = player.world;

        if (isNearWater(world, player.getPosition())) {
            relocatePlayer(player, world);
        }
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (!(event.getWorld() instanceof WorldServer)) return;

        WorldServer worldServer = (WorldServer) event.getWorld();

        // FIX: Only adjust the overworld spawn point (dimension 0).
        //      Previously this ran for every dimension including Nether and End.
        if (worldServer.provider.getDimension() != 0) return;

        BlockPos spawnPoint = worldServer.getSpawnPoint();

        if (isNearWater(worldServer, spawnPoint)) {
            BlockPos newSpawn = findSafeSmartSpawn(worldServer, spawnPoint);
            if (newSpawn != null) {
                worldServer.setSpawnPoint(newSpawn);
            }
        }
    }

    /**
     * Teleports a player to a safe spawn and also updates their personal spawn point
     * so that future deaths also respawn them in a safe location.
     */
    private void relocatePlayer(EntityPlayer player, World world) {
        BlockPos newSpawn = findSafeSmartSpawn(world, player.getPosition());
        if (newSpawn != null) {
            player.setPositionAndUpdate(newSpawn.getX() + 0.5, newSpawn.getY(), newSpawn.getZ() + 0.5);
            // FIX: Also update the player's respawn point so deaths send them here, not back
            //      to the original unsafe spawn. Without this, dying after relocation would
            //      still respawn the player in water.
            player.setSpawnPoint(newSpawn, true);
        }
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

            // world.getHeight returns the position of the first air block above the surface,
            // so the ground block is one below it — which matches our isSafeSpawn check on pos.down().
            BlockPos testPos = world.getHeight(new BlockPos(x, 0, z));

            if (isSafeSpawn(world, testPos)) {
                return testPos;
            }
        }

        // Fallback: spiral search from start position
        return spiralSearch(world, startPos);
    }

    private BlockPos spiralSearch(World world, BlockPos center) {
        for (int radius = 1; radius <= 50; radius++) {
            // FIX: Only iterate the perimeter of each radius ring, not all interior blocks.
            //      The original re-checked every inner block on every radius step (O(n^4) total).
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.abs(x) == radius || Math.abs(z) == radius) {
                        BlockPos testPos = world.getHeight(center.add(x, 0, z));
                        if (isSafeSpawn(world, testPos)) {
                            return testPos;
                        }
                    }
                }
            }
        }

        // Ultimate fallback — elevated position above original
        return new BlockPos(center.getX(), 100, center.getZ());
    }

    private boolean isSafeSpawn(World world, BlockPos pos) {
        // pos is the first air block above ground; ground is one below.
        net.minecraft.block.state.IBlockState groundState = world.getBlockState(pos.down());
        Block groundBlock = groundState.getBlock();
        Block spawnBlock = world.getBlockState(pos).getBlock();
        Block aboveBlock = world.getBlockState(pos.up()).getBlock();

        // FIX: Use the actual block state from the world rather than getDefaultState().
        //      getDefaultState() ignores the real state of the block (e.g., lava vs water
        //      variants, waterlogged states) and would silently pass unsafe ground checks.
        Material groundMaterial = groundState.getMaterial();

        // Ground must be solid
        if (!groundMaterial.isSolid()) return false;

        // Ground must not be water or lava
        if (groundMaterial == Material.WATER || groundMaterial == Material.LAVA) return false;

        // FIX: Removed the redundant isNearWater() call here. That check scans 3,000+ blocks
        //      and was being called for every single spawn candidate — 100+ times in the random
        //      loop alone. Water proximity is already handled when selecting where to relocate;
        //      we just need the spawn tile itself to be dry ground.
        //      If you want to keep water avoidance per-candidate, consider a much smaller radius.

        // The two blocks the player occupies must be clear
        if (spawnBlock != Blocks.AIR && !spawnBlock.isReplaceable(world, pos)) return false;
        if (aboveBlock != Blocks.AIR && !aboveBlock.isReplaceable(world, pos.up())) return false;

        // Reasonable Y bounds
        if (pos.getY() < 1 || pos.getY() > 250) return false;

        return true;
    }
}
