package com.seolwoni.farmassist.gametest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.seolwoni.assistcore.storage.ChestEntry;
import com.seolwoni.farmassist.config.FarmConfig;
import com.seolwoni.farmassist.farming.Farmer;
import com.seolwoni.farmassist.hud.FarmStats;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Real-map tests for Farm Assist (generated world, fixed seed, peaceful).
 *
 * real-village: the farms of the nearest village, marked the way a player would (corner to corner), crops grown ripe.
 * real-terrace: a field built in steps on a plains hillside (three terraces, one block apart, L-shaped top),
 * marked from the lowest corner to the highest one.
 */
public class FarmRealTest implements FabricClientGameTest {
	private final List<String> log = new ArrayList<>();
	private final List<String> failures = new ArrayList<>();
	private final RealWorld.Timing timing = new RealWorld.Timing();
	private String suite;

	@Override
	public void runTest(ClientGameTestContext context) {
		suite = RealWorld.suite();
		if (!suite.equals("real-village") && !suite.equals("real-terrace")) return;
		try (TestSingleplayerContext sp = RealWorld.create(context)) {
			sp.getConnection().waitForChunksRender();
			RealWorld.setup(sp);
			List<FarmConfig.FieldEntry> fields = new ArrayList<>();
			BlockPos chest = suite.equals("real-village") ? prepareVillage(context, sp, fields) : prepareTerrace(context, sp, fields);
			if (chest == null) return;
			RealWorld.cmd(sp, "give @a minecraft:bread 16");
			RealWorld.cmd(sp, "effect give @a minecraft:saturation infinite 0 true");
			context.waitTicks(40);
			sp.getConnection().waitForChunksRender();

			int[] before = count(sp, fields);
			log.add("before: farmland=" + before[0] + " ripe=" + before[1] + " crops=" + before[2]);
			for (FarmConfig.FieldEntry f : fields) {
				log.add("field " + f.name + ": x " + f.minX() + ".." + f.maxX() + ", z " + f.minZ() + ".." + f.maxZ() + ", y " + f.minY() + ".." + f.maxY());
			}
			context.runOnClient(mc -> {
				FarmConfig c = FarmConfig.get();
				c.modEnabled = true;
				c.waitMinutes = 5;
				c.seedKeep = 64;
				c.stopOnMob = false;
				c.crops = new ArrayList<>(FarmConfig.CROPS);
				c.fields.clear();
				c.fields.addAll(fields);
				c.storeEnabled = true;
				c.chests.clear();
				ChestEntry ch = new ChestEntry();
				ch.x = chest.getX();
				ch.y = chest.getY();
				ch.z = chest.getZ();
				c.chests.add(ch);
				if (!Farmer.start(mc)) failures.add("Farmer.start returned false");
				log.add("stops planned: " + Farmer.totalStops());
			});
			context.takeScreenshot(suite + "-00-start");

			int ticks = 0, shots = 1;
			final int limit = 20 * 60 * 20;
			boolean running = true, waiting = false;
			while (running && !waiting && ticks < limit) {
				long t0 = System.nanoTime();
				context.waitTicks(40);
				timing.add(System.nanoTime() - t0);
				ticks += 40;
				final int t = ticks;
				log.add(context.computeOnClient(mc -> "t=" + t + " pos=" + mc.player.blockPosition().toShortString()
						+ " status=" + Farmer.status() + " progress=" + Farmer.progress() + "/" + Farmer.totalStops()));
				running = context.computeOnClient(mc -> FarmConfig.get().running);
				waiting = context.computeOnClient(mc -> Farmer.waitSecondsLeft() >= 0);
				if (ticks % 1200 == 0) context.takeScreenshot(String.format("%s-%02d-min%d", suite, shots++, ticks / 1200));
			}
			context.takeScreenshot(suite + "-99-end");
			log.add("ended after " + ticks + " ticks (" + ticks / 20 + " s), running=" + running + " waiting=" + waiting);
			log.add(timing.summary() + ", server tick " + String.format("%.1f", RealWorld.mspt(sp)) + " ms");
			log.add(com.seolwoni.assistpath.AssistPath.Stats.summary());
			if (!running) failures.add("farmer stopped by itself: " + context.computeOnClient(mc -> Farmer.status()));
			else if (!waiting) failures.add("round not finished within " + limit + " ticks");
			final boolean stillRunning = running;
			long harvested = context.computeOnClient(mc -> {
				FarmStats st = FarmStats.session;
				log.add("rounds=" + st.rounds + " trips=" + st.trips + " harvested=" + st.totalHarvest());
				for (Map.Entry<String, FarmStats.Entry> e : st.crops.entrySet()) log.add("  harvested " + e.getKey() + ": " + e.getValue().count);
				log.add("player at " + mc.player.blockPosition().toShortString() + " health " + mc.player.getHealth());
				if (stillRunning) Farmer.stop(null, null, false);
				return st.totalHarvest();
			});
			int[] after = count(sp, fields);
			log.add("after: farmland=" + after[0] + " ripe=" + after[1] + " crops=" + after[2]);
			int stored = sp.getServer().computeOnServer(server -> {
				int n = 0;
				if (server.overworld().getBlockEntity(chest) instanceof Container ct) {
					for (int i = 0; i < ct.getContainerSize(); i++) n += ct.getItem(i).getCount();
				}
				return n;
			});
			int lying = sp.getServer().computeOnServer(server -> {
				int n = 0;
				for (FarmConfig.FieldEntry f : fields) {
					n += server.overworld().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
							new net.minecraft.world.phys.AABB(f.minX() - 1, f.minY() - 2, f.minZ() - 1, f.maxX() + 2, f.maxY() + 3, f.maxZ() + 2)).size();
				}
				return n;
			});
			log.add("items in chest = " + stored + ", drops left lying in the fields = " + lying);
			// Villagers farm too, so the ripe count is only an upper bound; most of it must be ours.
			if (harvested < before[1] * 0.6) failures.add("harvested only " + harvested + " of " + before[1] + " ripe crops");
			if (after[1] > Math.max(2, before[1] / 10)) failures.add(after[1] + " ripe crops left in the fields");
			if (after[0] < before[0] - 1) failures.add("farmland trampled: " + before[0] + " -> " + after[0]);
			if (after[2] < before[2] * 0.9) failures.add("not replanted: crops " + before[2] + " -> " + after[2]);
			if (stored == 0) failures.add("nothing was stored in the chest");
			if (lying > 3) failures.add(lying + " drops left lying in the fields");
		} catch (Throwable t) {
			failures.add("exception: " + t);
			throw t;
		} finally {
			RealWorld.writeReport("farmassist-" + suite, failures, log);
		}
		if (!failures.isEmpty()) throw new AssertionError("Farm Assist " + suite + " failed: " + String.join("; ", failures));
	}

	/** farmland count, ripe crops, all crops (wheat, carrots, potatoes, beetroots) in the fields. */
	private static int[] count(TestSingleplayerContext sp, List<FarmConfig.FieldEntry> fields) {
		return sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			int farmland = 0, ripe = 0, crops = 0;
			for (FarmConfig.FieldEntry f : fields) {
				for (int x = f.minX(); x <= f.maxX(); x++) {
					for (int z = f.minZ(); z <= f.maxZ(); z++) {
						for (int y = f.minY() - 4; y <= f.maxY() + 4; y++) {
							BlockPos p = new BlockPos(x, y, z);
							BlockState s = level.getBlockState(p);
							if (s.is(Blocks.FARMLAND)) farmland++;
							if (s.getBlock() instanceof CropBlock c) {
								crops++;
								if (c.isMaxAge(s)) ripe++;
							}
						}
					}
				}
			}
			return new int[] {farmland, ripe, crops};
		});
	}

	private static FarmConfig.FieldEntry field(String name, int x1, int y1, int z1, int x2, int y2, int z2) {
		FarmConfig.FieldEntry f = new FarmConfig.FieldEntry();
		f.name = name;
		f.x1 = x1;
		f.z1 = z1;
		f.x2 = x2;
		f.z2 = z2;
		f.y = y1;
		f.y2 = y2;
		return f;
	}

	/** Village farms: find the farmland patches, ripen them, mark each like a player would. Returns the chest. */
	private BlockPos prepareVillage(ClientGameTestContext context, TestSingleplayerContext sp, List<FarmConfig.FieldEntry> fields) {
		BlockPos v = RealWorld.findVillage(sp, BlockPos.ZERO);
		if (v == null) {
			failures.add("no village found near spawn (seed " + RealWorld.SEED + ")");
			return null;
		}
		log.add("village at " + v.getX() + ", " + v.getZ());
		RealWorld.goTo(context, sp, v.getX(), v.getZ(), 88);
		context.waitTicks(60);
		// Patches of farmland (water channels inside count as part of the patch).
		List<List<BlockPos>> patches = sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			Set<BlockPos> farmland = new HashSet<>();
			int cy = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, v.getX(), v.getZ());
			for (int x = v.getX() - 72; x <= v.getX() + 72; x++) {
				for (int z = v.getZ() - 72; z <= v.getZ() + 72; z++) {
					for (int y = cy - 24; y <= cy + 24; y++) {
						BlockPos p = new BlockPos(x, y, z);
						if (level.getBlockState(p).is(Blocks.FARMLAND)) farmland.add(p);
					}
				}
			}
			List<List<BlockPos>> out = new ArrayList<>();
			Set<BlockPos> seen = new HashSet<>();
			for (BlockPos start : farmland) {
				if (seen.contains(start)) continue;
				List<BlockPos> patch = new ArrayList<>();
				ArrayDeque<BlockPos> q = new ArrayDeque<>();
				q.add(start);
				seen.add(start);
				while (!q.isEmpty()) {
					BlockPos p = q.poll();
					patch.add(p);
					for (int dx = -2; dx <= 2; dx++) {
						for (int dz = -2; dz <= 2; dz++) {
							for (int dy = -1; dy <= 1; dy++) {
								BlockPos n = p.offset(dx, dy, dz);
								if (farmland.contains(n) && seen.add(n)) q.add(n);
							}
						}
					}
				}
				out.add(patch);
			}
			out.sort((a, b) -> b.size() - a.size());
			return out;
		});
		log.add("farmland patches: " + patches.stream().map(List::size).toList());
		context.takeScreenshot(suite + "-village");
		log.add(sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			int bells = 0, hay = 0, beds = 0, farmland = 0;
			List<String> cropSample = new ArrayList<>();
			for (int x = v.getX() - 72; x <= v.getX() + 72; x++) {
				for (int z = v.getZ() - 72; z <= v.getZ() + 72; z++) {
					for (int y = level.getMinY(); y < level.getMaxY(); y += 1) {
						if (y < 40 || y > 140) continue;
						BlockState st = level.getBlockState(new BlockPos(x, y, z));
						if (st.is(Blocks.BELL)) bells++;
						if (st.is(Blocks.HAY_BLOCK)) hay++;
						if (st.is(net.minecraft.tags.BlockTags.BEDS)) beds++;
						if (st.is(Blocks.FARMLAND)) farmland++;
						if (st.getBlock() instanceof CropBlock && cropSample.size() < 6) {
							cropSample.add(st.getBlock() + " on " + level.getBlockState(new BlockPos(x, y - 1, z)).getBlock() + " at " + x + "," + y + "," + z);
						}
					}
				}
			}
			return "debug around the village: bells=" + bells + " hay=" + hay + " beds=" + beds + " farmland=" + farmland + " crops=" + cropSample
					+ " biome=" + level.getBiome(v).unwrapKey().map(Object::toString).orElse("?") + " ground=" + level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, v.getX(), v.getZ());
		}));
		for (int i = 0; i < Math.min(2, patches.size()); i++) {
			List<BlockPos> patch = patches.get(i);
			if (patch.size() < 12) break;
			int x1 = Integer.MAX_VALUE, z1 = Integer.MAX_VALUE, x2 = Integer.MIN_VALUE, z2 = Integer.MIN_VALUE, ylo = Integer.MAX_VALUE, yhi = Integer.MIN_VALUE;
			for (BlockPos p : patch) {
				x1 = Math.min(x1, p.getX());
				z1 = Math.min(z1, p.getZ());
				x2 = Math.max(x2, p.getX());
				z2 = Math.max(z2, p.getZ());
				ylo = Math.min(ylo, p.getY() + 1);
				yhi = Math.max(yhi, p.getY() + 1);
			}
			fields.add(field("마을 밭 " + (i + 1), x1, ylo, z1, x2, yhi, z2));
			// Grow what is planted (like bone meal), and plant wheat where nothing grows yet.
			for (BlockPos p : patch) {
				BlockPos c = p.above();
				String id = sp.getServer().computeOnServer(server -> {
					BlockState s = server.overworld().getBlockState(c);
					if (s.is(Blocks.WHEAT)) return "wheat[age=7]";
					if (s.is(Blocks.CARROTS)) return "carrots[age=7]";
					if (s.is(Blocks.POTATOES)) return "potatoes[age=7]";
					if (s.is(Blocks.BEETROOTS)) return "beetroots[age=3]";
					if (s.isAir()) return "wheat[age=7]";
					return null;
				});
				if (id != null) RealWorld.cmd(sp, "setblock " + c.getX() + " " + c.getY() + " " + c.getZ() + " minecraft:" + id);
			}
		}
		if (fields.isEmpty()) {
			failures.add("the village has no farm big enough");
			return null;
		}
		// Chest on the ground a little west of the first field, player next to it.
		FarmConfig.FieldEntry f = fields.get(0);
		int cx = f.minX() - 3, cz = (f.minZ() + f.maxZ()) / 2;
		int gy = RealWorld.surface(sp, cx, cz);
		RealWorld.cmd(sp, "setblock " + cx + " " + gy + " " + cz + " minecraft:chest[facing=east]");
		RealWorld.cmd(sp, "tp @a " + (cx + 1.5) + " " + RealWorld.surface(sp, cx + 1, cz) + " " + (cz + 0.5) + " -90 20");
		log.add("chest at " + cx + ", " + gy + ", " + cz);
		return new BlockPos(cx, gy, cz);
	}

	/**
	 * Terraced field on plains: three 9x3 steps, each one block higher, the top one L-shaped (the last three
	 * columns are grass). Wheat, carrots, potatoes, all ripe. Marked from the lowest corner to the highest one.
	 */
	private BlockPos prepareTerrace(ClientGameTestContext context, TestSingleplayerContext sp, List<FarmConfig.FieldEntry> fields) {
		BlockPos b = RealWorld.findBiome(sp, Biomes.PLAINS, BlockPos.ZERO);
		if (b == null) {
			failures.add("no plains found");
			return null;
		}
		int x0 = b.getX(), z0 = b.getZ();
		int g = RealWorld.goTo(context, sp, x0 - 4, z0 + 6, 32);
		log.add("plains at " + x0 + ", " + z0 + ", ground " + g);
		// Level a 16x16 base at the ground height, then build the steps up from it.
		RealWorld.cmd(sp, "fill " + (x0 - 4) + " " + g + " " + (z0 - 2) + " " + (x0 + 11) + " " + (g + 10) + " " + (z0 + 13) + " minecraft:air");
		RealWorld.cmd(sp, "fill " + (x0 - 4) + " " + (g - 4) + " " + (z0 - 2) + " " + (x0 + 11) + " " + (g - 1) + " " + (z0 + 13) + " minecraft:dirt");
		RealWorld.cmd(sp, "fill " + (x0 - 4) + " " + (g - 1) + " " + (z0 - 2) + " " + (x0 + 11) + " " + (g - 1) + " " + (z0 + 13) + " minecraft:grass_block");
		String[] crops = {"wheat[age=7]", "carrots[age=7]", "potatoes[age=7]"};
		for (int k = 0; k < 3; k++) {
			int soil = g - 1 + k; // farmland height of this step
			int za = z0 + 3 * k, zb = za + 2;
			int xb = k == 2 ? x0 + 5 : x0 + 8; // the top step is shorter: an L-shaped field
			// Everything from this step back is raised to the step's height.
			if (k > 0) {
				RealWorld.cmd(sp, "fill " + x0 + " " + (soil - 1) + " " + za + " " + (x0 + 8) + " " + (soil - 1) + " " + (z0 + 11) + " minecraft:dirt");
				RealWorld.cmd(sp, "fill " + x0 + " " + soil + " " + za + " " + (x0 + 8) + " " + soil + " " + (z0 + 11) + " minecraft:grass_block");
			}
			RealWorld.cmd(sp, "fill " + x0 + " " + soil + " " + za + " " + xb + " " + soil + " " + zb + " minecraft:farmland[moisture=7]");
			RealWorld.cmd(sp, "fill " + x0 + " " + (soil + 1) + " " + za + " " + xb + " " + (soil + 1) + " " + zb + " minecraft:" + crops[k]);
		}
		// Marked from the lowest near corner to the highest far corner (crop layer heights g and g + 2).
		fields.add(field("계단식 밭", x0, g, z0, x0 + 8, g + 2, z0 + 8));
		int cx = x0 - 3, cz = z0 + 4;
		RealWorld.cmd(sp, "setblock " + cx + " " + g + " " + cz + " minecraft:chest[facing=east]");
		RealWorld.cmd(sp, "tp @a " + (cx + 1.5) + " " + g + " " + (cz + 0.5) + " -90 20");
		log.add("terraces: steps at crop layer y " + g + ", " + (g + 1) + ", " + (g + 2) + "; chest at " + cx + ", " + g + ", " + cz);
		return new BlockPos(cx, g, cz);
	}
}
