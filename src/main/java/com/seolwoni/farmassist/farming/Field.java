package com.seolwoni.farmassist.farming;

import java.util.ArrayList;
import java.util.List;

import com.seolwoni.farmassist.config.FarmConfig;
import com.seolwoni.farmassist.config.FarmConfig.FieldEntry;
import com.seolwoni.assistcore.util.Alerts;
import com.seolwoni.assistcore.util.Blocks2;

import net.minecraft.core.BlockPos;
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
			Alerts.actionBar("첫 번째 모서리 지정: " + layer.getX() + ", " + layer.getZ() + " — 반대쪽 모서리를 Shift + 우클릭하세요", 0xFF55FFFF);
			Alerts.click();
			return;
		}
		BlockPos a = pending;
		pending = null;
		int sx = Math.abs(a.getX() - layer.getX()) + 1, sz = Math.abs(a.getZ() - layer.getZ()) + 1;
		if (sx > MAX_SIZE || sz > MAX_SIZE) {
			Alerts.actionBar("밭이 너무 커요 (최대 " + MAX_SIZE + "x" + MAX_SIZE + ")", 0xFFFF5555);
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
		f.name = "밭 " + (cfg.fields.size() + 1);
		cfg.fields.add(f);
		FarmConfig.save();
		Alerts.actionBar(f.name + " 지정 완료: " + sx + "x" + sz + " (Y " + f.y + ")", 0xFF55FF55);
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

	/**
	 * Standing points: lanes every 3 rows along the longer side, a stop every 3 blocks,
	 * walked back and forth (snake order). Each stop covers the 3x3 around it.
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
				BlockPos p = standableNear(new BlockPos(x, f.y, z), f);
				if (p != null) out.add(p);
			}
		}
		return out;
	}

	private static BlockPos standableNear(BlockPos p, FieldEntry f) {
		if (Crops.standable(p)) return p;
		int[][] offs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
		for (int[] o : offs) {
			BlockPos q = p.offset(o[0], 0, o[1]);
			if (q.getX() < f.minX() - 1 || q.getX() > f.maxX() + 1 || q.getZ() < f.minZ() - 1 || q.getZ() > f.maxZ() + 1) continue;
			if (Crops.standable(q)) return q;
		}
		return null;
	}
}
