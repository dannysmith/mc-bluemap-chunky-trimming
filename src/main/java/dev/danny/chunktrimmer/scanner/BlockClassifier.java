package dev.danny.chunktrimmer.scanner;

import java.util.Set;

/**
 * Classifies block IDs as player-placed vs naturally generated.
 *
 * The default set errs on the side of caution — blocks that appear in
 * generated structures (villages, temples, mineshafts) are included because
 * we combine this with InhabitedTime to filter out unvisited structures.
 */
public class BlockClassifier {

    /**
     * Default set of block IDs that strongly indicate player activity.
     * These are blocks that rarely or never appear in natural terrain generation.
     */
    private static final Set<String> DEFAULT_PLAYER_BLOCKS = Set.of(
            // Storage
            "minecraft:chest",
            "minecraft:trapped_chest",
            "minecraft:barrel",
            "minecraft:shulker_box",
            "minecraft:white_shulker_box",
            "minecraft:orange_shulker_box",
            "minecraft:magenta_shulker_box",
            "minecraft:light_blue_shulker_box",
            "minecraft:yellow_shulker_box",
            "minecraft:lime_shulker_box",
            "minecraft:pink_shulker_box",
            "minecraft:gray_shulker_box",
            "minecraft:light_gray_shulker_box",
            "minecraft:cyan_shulker_box",
            "minecraft:purple_shulker_box",
            "minecraft:blue_shulker_box",
            "minecraft:brown_shulker_box",
            "minecraft:green_shulker_box",
            "minecraft:red_shulker_box",
            "minecraft:black_shulker_box",

            // Crafting & processing
            "minecraft:crafting_table",
            "minecraft:furnace",
            "minecraft:blast_furnace",
            "minecraft:smoker",
            "minecraft:enchanting_table",
            "minecraft:anvil",
            "minecraft:chipped_anvil",
            "minecraft:damaged_anvil",
            "minecraft:brewing_stand",
            "minecraft:cartography_table",
            "minecraft:fletching_table",
            "minecraft:smithing_table",
            "minecraft:loom",
            "minecraft:stonecutter",
            "minecraft:grindstone",

            // Redstone
            "minecraft:redstone_wire",
            "minecraft:repeater",
            "minecraft:comparator",
            "minecraft:piston",
            "minecraft:sticky_piston",
            "minecraft:observer",
            "minecraft:hopper",
            "minecraft:dropper",
            "minecraft:dispenser",
            "minecraft:redstone_lamp",
            "minecraft:note_block",
            "minecraft:target",
            "minecraft:daylight_detector",
            "minecraft:redstone_torch",
            "minecraft:redstone_wall_torch",

            // Rails
            "minecraft:rail",
            "minecraft:powered_rail",
            "minecraft:detector_rail",
            "minecraft:activator_rail",

            // Signs
            "minecraft:oak_sign",
            "minecraft:spruce_sign",
            "minecraft:birch_sign",
            "minecraft:jungle_sign",
            "minecraft:acacia_sign",
            "minecraft:dark_oak_sign",
            "minecraft:mangrove_sign",
            "minecraft:cherry_sign",
            "minecraft:bamboo_sign",
            "minecraft:crimson_sign",
            "minecraft:warped_sign",
            "minecraft:oak_wall_sign",
            "minecraft:spruce_wall_sign",
            "minecraft:birch_wall_sign",
            "minecraft:jungle_wall_sign",
            "minecraft:acacia_wall_sign",
            "minecraft:dark_oak_wall_sign",
            "minecraft:mangrove_wall_sign",
            "minecraft:cherry_wall_sign",
            "minecraft:bamboo_wall_sign",
            "minecraft:crimson_wall_sign",
            "minecraft:warped_wall_sign",
            "minecraft:oak_hanging_sign",
            "minecraft:spruce_hanging_sign",
            "minecraft:birch_hanging_sign",
            "minecraft:jungle_hanging_sign",
            "minecraft:acacia_hanging_sign",
            "minecraft:dark_oak_hanging_sign",
            "minecraft:mangrove_hanging_sign",
            "minecraft:cherry_hanging_sign",
            "minecraft:bamboo_hanging_sign",
            "minecraft:crimson_hanging_sign",
            "minecraft:warped_hanging_sign",

            // Decorative / player-built
            "minecraft:beacon",
            "minecraft:conduit",
            "minecraft:end_crystal",
            "minecraft:item_frame",
            "minecraft:glow_item_frame",
            "minecraft:armor_stand",
            "minecraft:painting",
            "minecraft:jukebox",
            "minecraft:lectern",
            "minecraft:scaffolding",
            "minecraft:ladder",
            "minecraft:campfire",
            "minecraft:soul_campfire",
            "minecraft:lantern",
            "minecraft:soul_lantern",

            // Concrete (never natural)
            "minecraft:white_concrete",
            "minecraft:orange_concrete",
            "minecraft:magenta_concrete",
            "minecraft:light_blue_concrete",
            "minecraft:yellow_concrete",
            "minecraft:lime_concrete",
            "minecraft:pink_concrete",
            "minecraft:gray_concrete",
            "minecraft:light_gray_concrete",
            "minecraft:cyan_concrete",
            "minecraft:purple_concrete",
            "minecraft:blue_concrete",
            "minecraft:brown_concrete",
            "minecraft:green_concrete",
            "minecraft:red_concrete",
            "minecraft:black_concrete",

            // Glazed terracotta (never natural)
            "minecraft:white_glazed_terracotta",
            "minecraft:orange_glazed_terracotta",
            "minecraft:magenta_glazed_terracotta",
            "minecraft:light_blue_glazed_terracotta",
            "minecraft:yellow_glazed_terracotta",
            "minecraft:lime_glazed_terracotta",
            "minecraft:pink_glazed_terracotta",
            "minecraft:gray_glazed_terracotta",
            "minecraft:light_gray_glazed_terracotta",
            "minecraft:cyan_glazed_terracotta",
            "minecraft:purple_glazed_terracotta",
            "minecraft:blue_glazed_terracotta",
            "minecraft:brown_glazed_terracotta",
            "minecraft:green_glazed_terracotta",
            "minecraft:red_glazed_terracotta",
            "minecraft:black_glazed_terracotta",

            // Stripped wood (strong player signal)
            "minecraft:stripped_oak_log",
            "minecraft:stripped_spruce_log",
            "minecraft:stripped_birch_log",
            "minecraft:stripped_jungle_log",
            "minecraft:stripped_acacia_log",
            "minecraft:stripped_dark_oak_log",
            "minecraft:stripped_mangrove_log",
            "minecraft:stripped_cherry_log",
            "minecraft:stripped_bamboo_block",
            "minecraft:stripped_crimson_stem",
            "minecraft:stripped_warped_stem",
            "minecraft:stripped_oak_wood",
            "minecraft:stripped_spruce_wood",
            "minecraft:stripped_birch_wood",
            "minecraft:stripped_jungle_wood",
            "minecraft:stripped_acacia_wood",
            "minecraft:stripped_dark_oak_wood",
            "minecraft:stripped_mangrove_wood",
            "minecraft:stripped_cherry_wood",
            "minecraft:stripped_crimson_hyphae",
            "minecraft:stripped_warped_hyphae",

            // Glass (rarely natural)
            "minecraft:glass",
            "minecraft:glass_pane",
            "minecraft:tinted_glass",

            // Misc strong signals
            "minecraft:torch",
            "minecraft:wall_torch",
            "minecraft:soul_torch",
            "minecraft:soul_wall_torch",
            "minecraft:end_rod",
            "minecraft:sea_lantern",
            "minecraft:respawn_anchor",
            "minecraft:lodestone"
    );

    private final Set<String> playerBlocks;

    public BlockClassifier() {
        this(DEFAULT_PLAYER_BLOCKS);
    }

    public BlockClassifier(Set<String> playerBlocks) {
        this.playerBlocks = playerBlocks;
    }

    /** Returns true if the block ID is considered a player-placed block. */
    public boolean isPlayerBlock(String blockId) {
        return playerBlocks.contains(blockId);
    }

    /** Returns the full set of player block IDs. */
    public Set<String> getPlayerBlocks() {
        return playerBlocks;
    }
}
