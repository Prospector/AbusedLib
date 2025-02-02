package abused_master.abusedlib.blocks.multiblock;

import abused_master.abusedlib.AbusedLib;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.*;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.registry.CommandRegistry;
import net.fabricmc.fabric.api.tag.TagRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormat;
import net.minecraft.block.Block;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.structure.Structure;
import net.minecraft.tag.Tag;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A builder class used for saving, creating, loading, and checking custom MultiBlocks using a custom JSON format
 */
public class MultiBlockBuilder {

    //The Map temporarily caching the BlockPos for the center blocks for when saving a MultiBlock
    public static Map<UUID, BlockPos> playerCommandCache = Maps.newHashMap();
    //The List used for saving a players UUID to when `/createmultiblock` is ran so we know what to do on right click
    public static List<UUID> activatedCommands = Lists.newArrayList();
    public static Map<Identifier, MultiBlock> loadedMultiblocks = Maps.newHashMap();

    /**
     * Registering the features used for creating the MultiBlocks
     * 'createmultiblock' - The command used to specify a MultiBlock before saving the structure file
     * UseBlockCallback - Registering a callback to check whenever a block is activated to save as a center
     */
    public static void registerMultiBlockFunctions() {
        CommandRegistry.INSTANCE.register(false, serverCommandSourceCommandDispatcher -> serverCommandSourceCommandDispatcher.register(
                CommandManager.literal("createmultiblock")
                .executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();

                    if(!activatedCommands.contains(player.getUuid())) {
                        activatedCommands.add(player.getUuid());
                        player.addChatMessage(new TextComponent("Right click the center MultiBlock!").setStyle(new Style().setColor(ChatFormat.GOLD)), false);
                    }else {
                        activatedCommands.remove(player.getUuid());
                        player.addChatMessage(new TextComponent("Canceled MultiBlock creation!").setStyle(new Style().setColor(ChatFormat.GOLD)), false);
                    }

                    return 1;
                })
        ));

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {

            if(activatedCommands.contains(player.getUuid())) {
                playerCommandCache.put(player.getUuid(), hitResult.getBlockPos());
                activatedCommands.remove(player.getUuid());
                player.addChatMessage(new TextComponent("Saved block as center for MultiBlock!").setStyle(new Style().setColor(ChatFormat.GOLD)), false);
                return ActionResult.SUCCESS;
            }

            return ActionResult.PASS;
        });
    }

    /**
     * Get a MultiBlock from the map of loaded MultiBlock
     * @param identifier - The identifier to look for, ex: new Identifier(MODID, "MultiBlockName.json");
     * @return - The MultiBlock from the map
     */
    public static MultiBlock getMultiBlock(Identifier identifier) {
        return loadedMultiblocks.getOrDefault(identifier, null);
    }

    /**
     * Get a MultiBlock using its inputStream
     * @param multiblockJsonFile - The InputStream that the multiblock will be created from
     * @return - The MultiBlock grabbed, returns an empty MultiBlock if not found
     */
    public static MultiBlock getMultiBlock(Identifier identifier, InputStream multiblockJsonFile) throws IOException {
        MultiBlock multiblock = new MultiBlock();

        if (multiblockJsonFile == null) {
            AbusedLib.LOGGER.warn("The defined MultiBlock structure location cannot be null! Source: " + identifier.toString());
            return multiblock;
        }

        JsonObject multiblockJsonObject = new JsonParser().parse(IOUtils.toString(multiblockJsonFile, StandardCharsets.UTF_8)).getAsJsonObject();
        if (multiblockJsonObject == null) {
            AbusedLib.LOGGER.warn("Invalid MultiBlock structure file, unable to parse! Source: " + identifier.toString());
            return multiblock;
        }

        JsonObject multiblockData = multiblockJsonObject.getAsJsonObject("multiblock_data");

        if (multiblockData == null) {
            AbusedLib.LOGGER.warn("Invalid MultiBlock structure data, unable to find required components! Source: " + identifier.toString());
            return multiblock;
        }

        for (Map.Entry<String, JsonElement> entry : multiblockData.entrySet()) {
            JsonObject componentObject = entry.getValue().getAsJsonObject();
            JsonArray blockPosArray = componentObject.get("pos").getAsJsonArray();
            BlockPos blockPos = new BlockPos(blockPosArray.get(0).getAsInt(), blockPosArray.get(1).getAsInt(), blockPosArray.get(2).getAsInt());
            String blockName = componentObject.get("block").getAsString();
            Object block = blockName.startsWith("#") ? TagRegistry.block(new Identifier(blockName.replace("#", ""))) : Registry.BLOCK.get(new Identifier(blockName));

            if (blockPosArray == null || blockPos == null || block == null) {
                AbusedLib.LOGGER.warn("NULL! A component in the MultiBlock structure has returned null! BlockPosArray: " + blockPosArray.getAsString() + ", BlockPos: " + blockPos.toString() + " Component Block: " + blockName);
                System.out.println(blockPosArray);
                System.out.println(blockPos);
                System.out.println(block);

                return multiblock;
            }

            if (entry.getKey().contains("center")) {
                JsonArray sizeArray = componentObject.get("size").getAsJsonArray();
                multiblock.setSize(new BlockPos(sizeArray.get(0).getAsInt(), sizeArray.get(1).getAsInt(), sizeArray.get(2).getAsInt()));
                multiblock.setCentralPoint(blockPos, block);
                continue;
            }

            multiblock.addComponent(blockPos, block);
        }

        return multiblock;
    }

    /**
     * Used to create a new MultiBlock json using a structure file
     * @param world - The world used to grab the blocks
     * @param centerPlayerPos - The BlockPos for the main block to check for when validating the MultiBlock - Set using /createmultiblock
     * @param minCorner - The minimum corner in the structure that's used to determine the offset for the center position
     * @param structure - The structure that will be converted into a MultiBlock json
     * @param multiblockName - The name that the multiblock will be saved as
     * @return - The written and completed json file
     */
    public static File createMultiBlock(BlockView world, BlockPos centerPlayerPos, BlockPos minCorner, Structure structure, String multiblockName) {
        BlockPos centerPos = centerPlayerPos.subtract(minCorner);

        File file = new File(FabricLoader.getInstance().getConfigDirectory() + "/multiblocks/" + multiblockName);
        file.getParentFile().mkdirs();
        try(FileWriter fileWriter = new FileWriter(file)) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            JsonObject jsonObject = new JsonObject();
            JsonObject multiblockData = new JsonObject();

            file.createNewFile();
            Field field = Structure.class.getDeclaredField("blocks");
            field.setAccessible(true);
            List<List<Structure.StructureBlockInfo>> list = (List<List<Structure.StructureBlockInfo>>) field.get(structure);

            JsonObject centerData = new JsonObject();
            JsonArray centerPosArray = new JsonArray();

            JsonArray sizeArray = new JsonArray();
            sizeArray.add(structure.getSize().getX());
            sizeArray.add(structure.getSize().getY());
            sizeArray.add(structure.getSize().getZ());

            Block centerBlock = world.getBlockState(centerPlayerPos).getBlock();

            centerPosArray.add(centerPos.getX());
            centerPosArray.add(centerPos.getY());
            centerPosArray.add(centerPos.getZ());

            centerData.addProperty("block", Registry.BLOCK.getId(centerBlock).toString());
            centerData.add("pos", centerPosArray);
            centerData.add("size", sizeArray);
            multiblockData.add("center", centerData);

            int i = 0;
            for (List<Structure.StructureBlockInfo> structureBlockInfoList : list) {
                for (Structure.StructureBlockInfo structureBlockInfo : structureBlockInfoList) {
                    if(structureBlockInfo.pos.equals(centerPos)) {
                        continue;
                    }

                    JsonObject componentData = new JsonObject();
                    JsonArray posArray = new JsonArray();
                    posArray.add(structureBlockInfo.pos.getX());
                    posArray.add(structureBlockInfo.pos.getY());
                    posArray.add(structureBlockInfo.pos.getZ());

                    componentData.addProperty("block", Registry.BLOCK.getId(structureBlockInfo.state.getBlock()).toString());
                    componentData.add("pos", posArray);

                    multiblockData.add(String.valueOf(i), componentData);
                    i++;
                }
            }

            jsonObject.add("multiblock_data", multiblockData);
            gson.toJson(jsonObject, fileWriter);
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return file;
    }

    /**
     * Check if a certain MultiBlock is valid in an area
     * @param world - The world
     * @param direction - The direction to check in
     * @param centerPos - The central position that will be checked from
     * @param multiBlock - The MultiBlock instance to check for
     * @return - True if successful
     */
    public static boolean isValidMultiblock(World world, Direction direction, BlockPos centerPos, MultiBlock multiBlock) {
        if(world.getBlockState(centerPos).getBlock() == multiBlock.getCentralPointBlock()) {
            for (Iterator<BlockPos> it = multiBlock.getMultiblockComponents().keySet().iterator(); it.hasNext();) {
                BlockPos templatePos = it.next();
                Object block = multiBlock.getComponent(templatePos);
                BlockPos offsetPos;

                if(direction == Direction.UP || direction == Direction.DOWN) {
                    offsetPos = centerPos.subtract(multiBlock.getCentralPoint()).add(templatePos);
                }else {
                    offsetPos = Structure.method_15168(centerPos.subtract(multiBlock.getCentralPoint()).add(templatePos), BlockMirror.NONE, toRotation(direction.getOpposite()), centerPos);
                }

                if(block instanceof Block) {
                    if(!(world.getBlockState(offsetPos).getBlock() == block)) {
                        break;
                    }
                }else if(block instanceof Tag) {
                    if(!((Tag<Block>) block).contains(world.getBlockState(offsetPos).getBlock())) {
                        break;
                    }
                }

                if(!it.hasNext()) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Used to test and place multiblocks
     * @param world - The world used to set blocks
     * @param centerPos - The center block position to build around
     * @param multiBlock - The multiblock to place
     * @param direction - The direction
     */
    public static void placeMultiBlock(World world, BlockPos centerPos, Direction direction, MultiBlock multiBlock) {
        if (multiBlock.getCentralPoint() != null && toRotation(direction) != null) {
            for (Iterator<BlockPos> it = multiBlock.getMultiblockComponents().keySet().iterator(); it.hasNext(); ) {
                BlockPos pos = it.next();
                BlockPos offsetPos = Structure.method_15168(centerPos.subtract(multiBlock.getCentralPoint()).add(pos), BlockMirror.NONE, toRotation(direction.getOpposite()), centerPos);
                Object block = multiBlock.getComponent(pos);

                if (block instanceof Block) {
                    world.setBlockState(offsetPos, ((Block) block).getDefaultState());
                } else if (block instanceof Tag) {
                    world.setBlockState(offsetPos, ((Tag<Block>) block).getRandom(new Random()).getDefaultState());
                }

                if (!it.hasNext()) {
                    return;
                }
            }
        }
    }

    /**
     * Get a BlockRotation from a direction
     * @param direction - The direction used
     * @return - Returns the appropriate BlockRotation, null if Top/Bottom Directions
     */
    public static BlockRotation toRotation(Direction direction) {
        switch(direction) {
            case NORTH:
                return BlockRotation.CLOCKWISE_90;
            case EAST:
                return BlockRotation.CLOCKWISE_180;
            case SOUTH:
                return BlockRotation.COUNTERCLOCKWISE_90;
            case WEST:
                return BlockRotation.NONE;
            default:
                return null;
        }
    }
}
