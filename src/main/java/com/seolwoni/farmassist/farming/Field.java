package com.seolwoni.farmassist.farming;

import java.util.ArrayList;
import java.util.List;

import com.seolwoni.farmassist.config.FarmConfig;
import com.seolwoni.farmassist.config.FarmConfig.FieldEntry;
import com.seolwoni.assistcore.util.Alerts;
import com.seolwoni.assistcore.util.Blocks2;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Field marking (hoe + Shift + right-click) and the walking stops through a field. */
public final class Field {
	public static final int MAX_SIZE = 96;
	private static BlockPos pending;

	private Field() {
	}

	/** Called for Shift + right-click with a hoe. */
	public static void click(BlockPos clicked) {
		BlockState s = Blocks2.state(clicked);
		// Clicking the soil means the crop layer is one above; clicking a plant means it is that block.
		boolean plant = Crops.cropName(s.getBlock()) != null || s.isAir() || !s.blocksMotion();
		BlockPos layer = plant ? clicked : clicked.above();
		if (pending == null) {
			pending = layer;
			Alerts.actionBar(com.seolwoni.assistcore.i18n.Tr.t("첫 번째 모서리 지정: ") + layer.getX() + ", " + layer.getZ() + com.seolwoni.assistcore.i18n.Tr.t(" — 반대쪽 모서리를 Shift + 우클릭하세요"), 0xFF55FFFF);
			Alerts.click();
			return;
		}
		BlockPos a = pending;
		pending = null;
		int sx = Math.abs(a.getX() - layer.getX()) + 1, sz = Math.abs(a.getZ() - layer.getZ()) + 1;
		if (sx > MAX_SIZE || sz > MAX_SIZE) {
			Alerts.actionBar(com.seolwoni.assistcore.i18n.Tr.t("밭이 너무 커요 (최대 ") + MAX_SIZE + "x" + MAX_SIZE + ")", 0xFFFF5555);
			Alerts.warning();
			return;
		}
		FarmConfig cfg = FarmConfig.get();
		FieldEntry f = new FieldEntry();
		f.x1 = a.getX();
		f.z1 = a.getZ();
		f.x2 = layer.getX();
		f.z2 = layer.getZ();
		f.y = a.getY();
		f.y2 = layer.getY();
		f.name = com.seolwoni.assistcore.i18n.Tr.t("밭 ") + (cfg.fields.size() + 1);
		cfg.fields.add(f);
		FarmConfig.save();
		String ys = f.minY() == f.maxY() ? "Y " + f.y : "Y " + f.minY() + "~" + f.maxY();
		Alerts.actionBar(f.name + com.seolwoni.assistcore.i18n.Tr.t(" 지정 완료: ") + sx + "x" + sz + " (" + ys + com.seolwoni.assistcore.i18n.Tr.t(", 높낮이 ±") + MARGIN + com.seolwoni.assistcore.i18n.Tr.t("칸까지 찾아요)"), 0xFF55FF55);
		Alerts.ding();
	}

	public static boolean hasPending() {
		return pending != null;
	}

	public static void cancelPending() {
		pending = null;
	}

	private static List<Integer> lanes(int size) {
		List<Integer> out = new ArrayList<>();
		if (size <= 3) {
			out.add(size / 2);
			return out;
		}
		for (int i = 1; i < size; i += 3) out.add(i);
		int last = out.get(out.size() - 1);
		if (size - 1 - last > 1) out.add(size - 2);
		return out;
	}

	/** How far above and below the marked heights a crop layer is still looked for (steps, slopes). */
	public static final int MARGIN = 3;

	/** Is {@code pos} where a crop of this field sits (the bottom block of a plant, or empty farmland / soul sand)? */
	static boolean isLayer(BlockPos pos) {
		BlockState s = Blocks2.state(pos);
		Block b = s.getBlock();
		BlockState below = Blocks2.state(pos.below());
		if (b == Blocks.COCOA) return false;
		if (Crops.cropName(b) != null || b instanceof StemBlock || b instanceof AttachedStemBlock) {
			return below.getBlock() != b; // the base of a tall plant (cane, bamboo, cactus)
		}
		if ((s.isAir() || s.canBeReplaced() && s.getFluidState().isEmpty()) && (below.is(Blocks.FARMLAND) || below.is(Blocks.SOUL_SAND))) return true;
		return false;
	}

	/**
	 * The crop layer height in column x/z of this field, or null if nothing grows there (fields need not be
	 * rectangular, nor flat). If several fit, the one closest to the marked heights wins (stacked farms marked
	 * floor by floor keep to their own floor).
	 */
	public static Integer layerAt(FieldEntry f, int x, int z) {
		Integer best = null;
		int bestD = Integer.MAX_VALUE;
		for (int y = f.maxY() + MARGIN; y >= f.minY() - MARGIN; y--) {
			if (!isLayer(new BlockPos(x, y, z))) continue;
			int d = y < f.minY() ? f.minY() - y : y > f.maxY() ? y - f.maxY() : 0;
			if (d < bestD) {
				bestD = d;
				best = y;
			}
		}
		return best;
	}

	/** Some crop layer in the 3x3 around x/z (for a stop in a corner of an odd-shaped field). */
	private static Integer layerNear(FieldEntry f, int x, int z) {
		Integer here = layerAt(f, x, z);
		if (here != null) return here;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				int cx = x + dx, cz = z + dz;
				if (cx < f.minX() || cx > f.maxX() || cz < f.minZ() || cz > f.maxZ()) continue;
				Integer y = layerAt(f, cx, cz);
				if (y != null) return y;
			}
		}
		// No ground crops around, but cocoa on a log is still worth the stop.
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int y = f.minY() - 1; y <= f.maxY() + 3; y++) {
					if (Blocks2.state(new BlockPos(x + dx, y, z + dz)).is(Blocks.COCOA)) return Math.max(f.minY(), Math.min(f.maxY(), y));
				}
			}
		}
		return null;
	}

	/**
	 * Standing points: lanes every 3 rows along the longer side, a stop every 3 blocks,
	 * walked back and forth (snake order). Each stop covers the 3x3 around it; stops with nothing
	 * growing around them are left out, and each stands at the height of the crops there.
	 */
	public static List<BlockPos> stops(FieldEntry f) {
		boolean alongX = f.sizeX() >= f.sizeZ();
		List<Integer> laneIdx = lanes(alongX ? f.sizeZ() : f.sizeX());
		List<Integer> colIdx = lanes(alongX ? f.sizeX() : f.sizeZ());
		List<BlockPos> out = new ArrayList<>();
		for (int li = 0; li < laneIdx.size(); li++) {
			int lane = laneIdx.get(li);
			for (int ci = 0; ci < colIdx.size(); ci++) {
				int col = colIdx.get(li % 2 == 0 ? ci : colIdx.size() - 1 - ci);
				int x = alongX ? f.minX() + col : f.minX() + lane;
				int z = alongX ? f.minZ() + lane : f.minZ() + col;
				Integer y = layerNear(f, x, z);
				if (y == null) continue;
				BlockPos p = standableNear(new BlockPos(x, y, z), f);
				if (p != null) out.add(p);
			}
		}
		return out;
	}

	private static BlockPos standableNear(BlockPos p, FieldEntry f) {
		if (Crops.standable(p)) return p;
		int[][] offs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
		for (int dy : new int[] {0, 1, -1}) {
			for (int[] o : offs) {
				BlockPos q = p.offset(o[0], dy, o[1]);
				if (q.getX() < f.minX() - 1 || q.getX() > f.maxX() + 1 || q.getZ() < f.minZ() - 1 || q.getZ() > f.maxZ() + 1) continue;
				if (Crops.standable(q)) return q;
			}
		}
		return null;
	}
}
