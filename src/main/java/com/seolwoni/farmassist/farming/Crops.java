package com.seolwoni.farmassist.farming;

import com.seolwoni.farmassist.config.FarmConfig;
import com.seolwoni.assistcore.util.Blocks2;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;

/** What to do with each crop block: harvest + replant, cut the top, pick berries, ... */
final class Crops {
	enum Kind { REPLANT, CUT_TOP, BREAK, PICK, COCOA }

	/** A job on one block. {@code seed} is what to plant back (null = nothing). */
	record Task(BlockPos pos, Kind kind, String crop, Item seed, BlockPos plantOn, Direction plantFace) {
	}

	private Crops() {
	}

	static boolean enabled(String crop) {
		return FarmConfig.get().crops.contains(crop);
	}

	static Item seedOf(Block b) {
		if (b == Blocks.WHEAT) return Items.WHEAT_SEEDS;
		if (b == Blocks.CARROTS) return Items.CARROT;
		if (b == Blocks.POTATOES) return Items.POTATO;
		if (b == Blocks.BEETROOTS) return Items.BEETROOT_SEEDS;
		return null;
	}

	static String cropName(Block b) {
		if (b == Blocks.WHEAT) return "wheat";
		if (b == Blocks.CARROTS) return "carrots";
		if (b == Blocks.POTATOES) return "potatoes";
		if (b == Blocks.BEETROOTS) return "beetroots";
		if (b == Blocks.NETHER_WART) return "nether_wart";
		if (b == Blocks.SUGAR_CANE) return "sugar_cane";
		if (b == Blocks.BAMBOO) return "bamboo";
		if (b == Blocks.PUMPKIN) return "pumpkin";
		if (b == Blocks.MELON) return "melon";
		if (b == Blocks.COCOA) return "cocoa";
		if (b == Blocks.CACTUS) return "cactus";
		if (b == Blocks.SWEET_BERRY_BUSH) return "sweet_berry_bush";
		return null;
	}

	/** Can soil at {@code soil} take a crop planted from this seed? */
	static boolean soilFor(Item seed, BlockPos soil) {
		BlockState s = Blocks2.state(soil);
		if (seed == Items.NETHER_WART) return s.is(Blocks.SOUL_SAND);
		return s.is(Blocks.FARMLAND);
	}

	/** Looks at one crop-layer position (and above it for tall plants). Returns null if nothing to do. */
	static Task check(BlockPos pos) {
		BlockState s = Blocks2.state(pos);
		Block b = s.getBlock();
		String crop = cropName(b);
		if (crop != null && !enabled(crop)) return null;

		if (b instanceof CropBlock c) {
			Item seed = seedOf(b);
			if (seed == null || !c.isMaxAge(s)) return null;
			return new Task(pos, Kind.REPLANT, crop, seed, pos.below(), Direction.UP);
		}
		if (b instanceof NetherWartBlock) {
			if (s.getValue(NetherWartBlock.AGE) < NetherWartBlock.MAX_AGE) return null;
			return new Task(pos, Kind.REPLANT, crop, Items.NETHER_WART, pos.below(), Direction.UP);
		}
		if (b instanceof SugarCaneBlock || b instanceof BambooStalkBlock || b instanceof CactusBlock) {
			// Keep the bottom block so it grows back; cut from the second block up.
			if (Blocks2.state(pos.above()).getBlock() != b) return null;
			return new Task(pos.above(), Kind.CUT_TOP, crop, null, null, null);
		}
		if (b == Blocks.PUMPKIN || b == Blocks.MELON) {
			return new Task(pos, Kind.BREAK, crop, null, null, null);
		}
		if (b instanceof SweetBerryBushBlock) {
			if (s.getValue(SweetBerryBushBlock.AGE) < SweetBerryBushBlock.MAX_AGE) return null;
			return new Task(pos, Kind.PICK, crop, null, null, null);
		}
		return null;
	}

	/** Cocoa grows on the side of jungle logs, so it is looked for at several heights. */
	static Task checkCocoa(BlockPos pos) {
		BlockState s = Blocks2.state(pos);
		if (!(s.getBlock() instanceof CocoaBlock) || !enabled("cocoa")) return null;
		if (s.getValue(CocoaBlock.AGE) < CocoaBlock.MAX_AGE) return null;
		Direction facing = s.getValue(CocoaBlock.FACING);
		BlockPos log = pos.relative(facing);
		return new Task(pos, Kind.COCOA, "cocoa", Items.COCOA_BEANS, log, facing.getOpposite());
	}

	/** Can the player stand here (feet block)? Crops and stems are fine, solid blocks, water, cactus and berry bushes are not. */
	static boolean standable(BlockPos feet) {
		BlockState s = Blocks2.state(feet);
		BlockState head = Blocks2.state(feet.above());
		if (!s.getFluidState().isEmpty() || !head.getFluidState().isEmpty()) return false;
		if (s.blocksMotion() || head.blocksMotion()) return false;
		if (s.getBlock() instanceof SweetBerryBushBlock || s.getBlock() instanceof CactusBlock) return false;
		return Blocks2.isSolid(feet.below()) || Blocks2.state(feet.below()).is(Blocks.FARMLAND)
				|| Blocks2.state(feet.below()).is(Blocks.SOUL_SAND);
	}
}
