package com.seolwoni.farmassist.gametest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.seolwoni.farmassist.config.FarmConfig;
import com.seolwoni.farmassist.farming.Farmer;
import com.seolwoni.farmassist.hud.FarmStats;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One full round over two fields with every crop type, then the storage trip.
 *
 * Field 1 (x 0..8, z 0..8): wheat, carrots, potatoes, beetroots, all ripe; row z=8 is unripe wheat (must be left alone).
 * Field 2 (x 12..20, z 0..8): sugar cane, pumpkin, melon, sweet berries, nether wart, cocoa, cactus, bamboo.
 * Chest at (-2, 101, 4).
 */
public class FarmGameTest implements FabricClientGameTest {
	private static final int Y = 101;
	private final List<String> log = new ArrayList<>();
	private final List<String> failures = new ArrayList<>();

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			buildWorld(sp);
			context.waitTicks(40);
			sp.getConnection().waitForChunksRender();

			context.runOnClient(mc -> {
				FarmConfig c = FarmConfig.get();
				c.modEnabled = true;
				c.waitMinutes = 5;
				c.seedKeep = 64;
				c.stopOnMob = false;
				c.crops = new ArrayList<>(FarmConfig.CROPS);
				c.fields.clear();
				c.fields.add(field("밭 1", 0, 0, 8, 8));
				c.fields.add(field("밭 2", 12, 0, 20, 8));
				c.storeEnabled = true;
				c.chests.clear();
				com.seolwoni.assistcore.storage.ChestEntry ch = new com.seolwoni.assistcore.storage.ChestEntry();
				ch.x = -2;
				ch.y = Y;
				ch.z = 4;
				c.chests.add(ch);
				mc.player.setYRot(0f);
				if (!Farmer.start(mc)) failures.add("Farmer.start returned false");
			});
			context.takeScreenshot("farm-00-start");

			int ticks = 0, shots = 1;
			final int limit = 20 * 60 * 8;
			boolean running = true, waiting = false;
			while (running && !waiting && ticks < limit) {
				context.waitTicks(40);
				ticks += 40;
				final int t = ticks;
				log.add(context.computeOnClient(mc -> "t=" + t + " pos=" + mc.player.blockPosition().toShortString()
						+ " status=" + Farmer.status() + " progress=" + Farmer.progress() + "/" + Farmer.totalStops()));
				running = context.computeOnClient(mc -> FarmConfig.get().running);
				waiting = context.computeOnClient(mc -> Farmer.waitSecondsLeft() >= 0);
				if (ticks % 400 == 0 && shots < 20) context.takeScreenshot(String.format("farm-%02d-t%d", shots++, ticks));
			}
			context.takeScreenshot("farm-99-end");
			log.add("ended after " + ticks + " ticks, running=" + running + " waiting=" + waiting);
			if (!running) failures.add("farmer stopped by itself before finishing the round");
			else if (!waiting) failures.add("round not finished within " + limit + " ticks");
			final boolean stillRunning = running;
			context.runOnClient(mc -> {
				FarmStats st = FarmStats.session;
				log.add("rounds=" + st.rounds + " trips=" + st.trips + " total=" + st.totalHarvest());
				if (st.trips > 2) failures.add("went to the chest " + st.trips + " times in one round (expected at most 2)");
				for (Map.Entry<String, FarmStats.Entry> e : st.crops.entrySet()) log.add("  harvested " + e.getKey() + ": " + e.getValue().count);
				int seeds = 0;
				for (int i = 0; i < 36; i++) {
					var s = mc.player.getInventory().getItem(i);
					if (s.is(net.minecraft.world.item.Items.WHEAT_SEEDS)) seeds += s.getCount();
				}
				log.add("wheat seeds kept in inventory: " + seeds);
				if (stillRunning) Farmer.stop(null, null, false);
			});
			checkWorld(sp);
		} catch (Throwable t) {
			failures.add("exception: " + t);
			throw t;
		} finally {
			writeReport();
		}
		if (!failures.isEmpty()) throw new AssertionError("Farm Assist test failed: " + String.join("; ", failures));
	}

	private static FarmConfig.FieldEntry field(String name, int x1, int z1, int x2, int z2) {
		FarmConfig.FieldEntry f = new FarmConfig.FieldEntry();
		f.name = name;
		f.x1 = x1;
		f.z1 = z1;
		f.x2 = x2;
		f.z2 = z2;
		f.y = Y;
		return f;
	}

	private static void cmd(TestSingleplayerContext sp, String c) {
		sp.getServer().runCommand(c);
	}

	private void buildWorld(TestSingleplayerContext sp) {
		cmd(sp, "forceload add -8 -8 32 20");
		cmd(sp, "difficulty peaceful");
		cmd(sp, "time set noon");
		cmd(sp, "gamerule randomTickSpeed 0");
		cmd(sp, "gamerule random_tick_speed 0");
		cmd(sp, "fill -6 96 -6 26 99 14 minecraft:stone");
		cmd(sp, "fill -6 100 -6 26 100 14 minecraft:grass_block");
		cmd(sp, "fill -6 101 -6 26 110 14 minecraft:air");

		// Field 1
		cmd(sp, "fill 0 100 0 8 100 8 minecraft:farmland[moisture=7]");
		cmd(sp, "fill 0 101 0 8 101 1 minecraft:wheat[age=7]");
		cmd(sp, "fill 0 101 2 8 101 3 minecraft:carrots[age=7]");
		cmd(sp, "fill 0 101 4 8 101 5 minecraft:potatoes[age=7]");
		cmd(sp, "fill 0 101 6 8 101 7 minecraft:beetroots[age=3]");
		cmd(sp, "fill 0 101 8 8 101 8 minecraft:wheat[age=3]");

		// Field 2: sugar cane by water
		cmd(sp, "fill 12 100 0 12 100 2 minecraft:water");
		cmd(sp, "fill 13 101 0 13 103 2 minecraft:sugar_cane");
		// pumpkin and melon with stems
		cmd(sp, "setblock 15 100 0 minecraft:farmland[moisture=7]");
		cmd(sp, "setblock 15 101 0 minecraft:attached_pumpkin_stem[facing=east]");
		cmd(sp, "setblock 16 101 0 minecraft:pumpkin");
		cmd(sp, "setblock 15 100 2 minecraft:farmland[moisture=7]");
		cmd(sp, "setblock 15 101 2 minecraft:attached_melon_stem[facing=east]");
		cmd(sp, "setblock 16 101 2 minecraft:melon");
		// sweet berries
		cmd(sp, "setblock 18 101 0 minecraft:sweet_berry_bush[age=3]");
		cmd(sp, "setblock 18 101 1 minecraft:sweet_berry_bush[age=3]");
		// nether wart
		cmd(sp, "fill 20 100 0 20 100 1 minecraft:soul_sand");
		cmd(sp, "fill 20 101 0 20 101 1 minecraft:nether_wart[age=3]");
		// cocoa on a jungle log
		cmd(sp, "setblock 19 101 5 minecraft:jungle_log");
		cmd(sp, "setblock 19 101 4 minecraft:cocoa[age=2,facing=south]");
		cmd(sp, "setblock 18 101 5 minecraft:cocoa[age=2,facing=east]");
		// cactus and bamboo, 2-3 tall
		cmd(sp, "setblock 14 100 6 minecraft:sand");
		cmd(sp, "fill 14 101 6 14 102 6 minecraft:cactus");
		cmd(sp, "fill 16 101 6 16 103 6 minecraft:bamboo");

		// storage chest
		cmd(sp, "setblock -2 101 4 minecraft:chest[facing=east]");

		cmd(sp, "gamemode survival @a");
		cmd(sp, "clear @a");
		cmd(sp, "give @a minecraft:bread 16");
		cmd(sp, "effect give @a minecraft:saturation infinite 0 true");
		cmd(sp, "tp @a 4.5 101 -2.5 0 20");
	}

	private void checkWorld(TestSingleplayerContext sp) {
		List<String> out = sp.getServer().computeOnServer(server -> {
			List<String> o = new ArrayList<>();
			ServerLevel level = server.overworld();
			for (int x = 0; x <= 8; x++) {
				for (int z = 0; z <= 8; z++) {
					BlockPos p = new BlockPos(x, Y, z);
					BlockState s = level.getBlockState(p);
					if (z == 8) {
						if (!(s.getBlock() instanceof CropBlock c) || c.getAge(s) != 3) o.add("unripe wheat at " + x + "," + z + " was touched (" + s + ")");
						continue;
					}
					if (!(s.getBlock() instanceof CropBlock c)) o.add("not replanted at " + x + "," + z + " (" + s.getBlock() + ")");
					else if (c.isMaxAge(s)) o.add("ripe crop left at " + x + "," + z);
				}
			}
			for (int z = 0; z <= 2; z++) {
				if (!level.getBlockState(new BlockPos(13, Y, z)).is(Blocks.SUGAR_CANE)) o.add("sugar cane base gone at z=" + z);
				if (level.getBlockState(new BlockPos(13, Y + 1, z)).is(Blocks.SUGAR_CANE)) o.add("sugar cane not cut at z=" + z);
			}
			if (level.getBlockState(new BlockPos(16, Y, 0)).is(Blocks.PUMPKIN)) o.add("pumpkin not harvested");
			if (level.getBlockState(new BlockPos(16, Y, 2)).is(Blocks.MELON)) o.add("melon not harvested");
			for (int z = 0; z <= 1; z++) {
				BlockState b = level.getBlockState(new BlockPos(18, Y, z));
				if (b.getBlock() instanceof SweetBerryBushBlock && b.getValue(SweetBerryBushBlock.AGE) == 3) o.add("berries not picked at z=" + z);
				BlockState w = level.getBlockState(new BlockPos(20, Y, z));
				if (!(w.getBlock() instanceof NetherWartBlock)) o.add("nether wart not replanted at z=" + z);
				else if (w.getValue(NetherWartBlock.AGE) == 3) o.add("nether wart not harvested at z=" + z);
			}
			for (BlockPos c : List.of(new BlockPos(19, Y, 4), new BlockPos(18, Y, 5))) {
				BlockState s = level.getBlockState(c);
				if (!(s.getBlock() instanceof CocoaBlock)) o.add("cocoa not replanted at " + c.toShortString());
				else if (s.getValue(CocoaBlock.AGE) == 2) o.add("cocoa not harvested at " + c.toShortString());
			}
			if (!level.getBlockState(new BlockPos(14, Y, 6)).is(Blocks.CACTUS)) o.add("cactus base gone");
			if (level.getBlockState(new BlockPos(14, Y + 1, 6)).is(Blocks.CACTUS)) o.add("cactus not cut");
			Block bam = level.getBlockState(new BlockPos(16, Y, 6)).getBlock();
			if (bam != Blocks.BAMBOO) o.add("bamboo base gone");
			if (level.getBlockState(new BlockPos(16, Y + 1, 6)).is(Blocks.BAMBOO)) o.add("bamboo not cut");
			int stored = 0;
			if (level.getBlockEntity(new BlockPos(-2, Y, 4)) instanceof Container ct) {
				for (int i = 0; i < ct.getContainerSize(); i++) stored += ct.getItem(i).getCount();
			}
			o.add("info: items in chest = " + stored);
			int lying = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
					new net.minecraft.world.phys.AABB(-1, 99, -1, 22, 106, 10)).size();
			o.add("info: drops left lying in the fields = " + lying);
			if (lying > 2) o.add(lying + " drops left lying in the fields");
			if (stored == 0) o.add("nothing was stored in the chest");
			return o;
		});
		for (String p : out) {
			if (p.startsWith("info: ")) log.add(p.substring(6));
			else failures.add(p);
		}
	}

	private void writeReport() {
		try {
			List<String> out = new ArrayList<>();
			out.add(failures.isEmpty() ? "RESULT: PASS" : "RESULT: FAIL (" + failures.size() + ")");
			for (String f : failures) out.add("  FAIL " + f);
			out.add("");
			out.addAll(log);
			Path p = FabricLoader.getInstance().getGameDir().resolve("farmassist-test-result.txt");
			Files.write(p, out, StandardCharsets.UTF_8);
		} catch (Exception ignored) {
		}
	}
}
