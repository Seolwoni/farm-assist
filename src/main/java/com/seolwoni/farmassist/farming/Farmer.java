package com.seolwoni.farmassist.farming;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.seolwoni.farmassist.config.FarmConfig;
import com.seolwoni.farmassist.config.FarmConfig.FieldEntry;
import com.seolwoni.farmassist.hud.FarmStats;
import com.seolwoni.farmassist.util.Alerts;
import com.seolwoni.farmassist.util.Blocks2;
import com.seolwoni.farmassist.util.Body;
import com.seolwoni.farmassist.util.Human;
import com.seolwoni.farmassist.util.Inv;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Runs farming rounds as a queue of small actions: walk to a stop, harvest what is ripe around it,
 * plant it back. After every field is done: store loot, wait, and go again.
 */
public final class Farmer {
	public static final int RUN = 0, DONE = 1, FAIL = 2;

	public interface Action {
		int tick(Minecraft mc, LocalPlayer p);

		/** true = a failure just skips this action instead of stopping everything. */
		default boolean skippable() {
			return true;
		}

		default String failReason() {
			return "";
		}
	}

	private enum Phase { ROUND, STORE, WAIT }

	private static final Deque<Action> queue = new ArrayDeque<>();
	private static Phase phase = Phase.ROUND;
	private static String status = "꺼짐";
	private static List<BlockPos> stops = new ArrayList<>();
	private static List<Integer> stopField = new ArrayList<>();
	private static int stopIndex;
	private static long waitUntil;
	private static long ticks;
	private static int nextPause;
	private static int walkFails;
	private static int nextLook;
	/** Most common seed seen per field, used to plant empty farmland. */
	static final Map<Integer, Map<Item, Integer>> seen = new HashMap<>();
	static boolean seedShort;
	/** Spots that could not be replanted yet (no seed in hand at the time): retried once before the round ends. */
	record Missed(BlockPos stand, Item seed, BlockPos on, Direction face, BlockPos target) {
	}

	static final List<Missed> missed = new ArrayList<>();
	private static boolean retryDone;

	private Farmer() {
	}

	public static String status() {
		return status;
	}

	static void status(String s) {
		status = s;
	}

	public static long waitSecondsLeft() {
		return phase == Phase.WAIT ? Math.max(0, (waitUntil - ticks) / 20) : -1;
	}

	public static int progress() {
		return stopIndex;
	}

	public static int totalStops() {
		return stops.size();
	}

	static void pushFront(List<Action> list) {
		for (int i = list.size() - 1; i >= 0; i--) queue.addFirst(list.get(i));
	}

	static void add(Action a) {
		queue.addLast(a);
	}

	// ------------------------------------------------------------------ start / stop

	public static boolean start(Minecraft mc) {
		FarmConfig cfg = FarmConfig.get();
		if (mc.player == null || mc.level == null) return false;
		if (cfg.fields.isEmpty()) {
			Alerts.title("밭이 없어요", "괭이를 들고 Shift + 우클릭으로 밭 모서리를 지정하세요", 0xFFFF5555);
			Alerts.warning();
			return false;
		}
		queue.clear();
		seen.clear();
		beginRound();
		nextPause = Human.rand(1500, 4000);
		cfg.running = true;
		FarmConfig.save();
		Alerts.actionBar("농사 시작: 밭 " + cfg.fields.size() + "개", 0xFF55FF55);
		return true;
	}

	public static void stop(String title, String sub, boolean alert) {
		Minecraft mc = Minecraft.getInstance();
		FarmConfig cfg = FarmConfig.get();
		cfg.running = false;
		FarmConfig.save();
		queue.clear();
		Body.release(mc);
		mc.options.keyUse.setDown(false);
		if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
		if (mc.player != null && mc.gui.screen() instanceof AbstractContainerScreen<?> s && !(s instanceof InventoryScreen)) {
			mc.player.closeContainer();
		}
		status("꺼짐");
		if (title != null) {
			if (alert) {
				Alerts.title(title, sub, 0xFFFF5555);
				Alerts.warning();
			} else {
				Alerts.actionBar(title + (sub == null ? "" : " - " + sub), 0xFFFFAA00);
			}
		}
	}

	private static void beginRound() {
		phase = Phase.ROUND;
		stopIndex = 0;
		walkFails = 0;
		seedShort = false;
		Chores.seedTripDone = false;
		missed.clear();
		retryDone = false;
		stops = new ArrayList<>();
		stopField = new ArrayList<>();
		List<FieldEntry> fields = FarmConfig.get().fields;
		for (int f = 0; f < fields.size(); f++) {
			for (BlockPos b : Field.stops(fields.get(f))) {
				stops.add(b);
				stopField.add(f);
			}
		}
		FarmStats.round();
	}

	// ------------------------------------------------------------------ tick

	public static void tick(Minecraft mc) {
		FarmConfig cfg = FarmConfig.get();
		LocalPlayer p = mc.player;
		if (!cfg.running) return;
		if (p == null || mc.level == null || mc.gameMode == null) {
			stop(null, null, false);
			return;
		}
		if (mc.isPaused()) return;
		if (!safety(mc, p, cfg)) return;
		ticks++;
		if (phase != Phase.WAIT) FarmStats.tick();

		Action a = queue.peekFirst();
		if (a != null) {
			int r = a.tick(mc, p);
			if (r == DONE) {
				queue.remove(a);
			} else if (r == FAIL) {
				queue.remove(a);
				if (!a.skippable()) stop("농사를 멈췄어요", a.failReason(), true);
			}
			return;
		}
		plan(mc, p, cfg);
	}

	private static boolean safety(Minecraft mc, LocalPlayer p, FarmConfig cfg) {
		if (p.isDeadOrDying()) {
			BlockPos b = p.blockPosition();
			Alerts.chat("사망 위치: " + b.getX() + ", " + b.getY() + ", " + b.getZ());
			stop("죽었어요", "사망 위치를 채팅에 남겼어요", true);
			return false;
		}
		if (p.isInLava() || p.isOnFire()) {
			stop("위험! 불이나 용암", "농사를 멈췄어요", true);
			return false;
		}
		if (p.getHealth() <= cfg.minHealth) {
			stop("체력이 낮아요", "농사를 멈췄어요", true);
			return false;
		}
		if (cfg.stopOnMob && ticks % 10 == 0) {
			AABB box = p.getBoundingBox().inflate(cfg.mobRange);
			for (Entity e : mc.level.getEntities(p, box)) {
				if (e instanceof Enemy && e.isAlive()) {
					stop("몹이 다가와요", e.getName().getString() + " 근처에 있어요", true);
					return false;
				}
			}
		}
		return true;
	}

	// ------------------------------------------------------------------ planning

	private static void plan(Minecraft mc, LocalPlayer p, FarmConfig cfg) {
		switch (phase) {
			case WAIT -> {
				long left = waitUntil - ticks;
				status("다음 바퀴까지 " + Math.max(0, left / 20 / 60) + "분 " + Math.max(0, left / 20 % 60) + "초");
				// Look around a little now and then while waiting.
				if (--nextLook <= 0) {
					nextLook = Human.rand(200, 600);
					float yaw = p.getYRot() + (float) Human.jitter(8);
					float pitch = p.getXRot() + (float) Human.jitter(4);
					Body.newTurn();
					add((m, pl) -> Body.turnTo(pl, yaw, pitch, 1.5f) ? DONE : RUN);
					return;
				}
				if (left <= 0) beginRound();
				return;
			}
			case STORE -> {
				phase = Phase.WAIT;
				waitUntil = ticks + (long) cfg.waitMinutes * 1200L;
				nextLook = Human.rand(100, 300);
				return;
			}
			default -> {
			}
		}

		if (--nextPause <= 0) {
			nextPause = Human.rand(1500, 4000);
			add(new WaitAction(Human.rand(30, 100)));
			status("잠깐 쉬는 중");
			return;
		}
		if (Chores.plan(mc, p, cfg)) return;

		if (stopIndex >= stops.size() && !retryDone) {
			// Go back once to the spots that could not be replanted (the seeds are in the inventory by now).
			retryDone = true;
			if (!missed.isEmpty()) {
				status("못 심은 곳 다시 심는 중 (" + missed.size() + "곳)");
				List<Missed> list = new ArrayList<>(missed);
				missed.clear();
				for (Missed m : list) {
					add(new WalkAction(m.stand()));
					add(new PlantAction(m.seed(), m.on(), m.face(), m.target(), true));
				}
				return;
			}
		}
		if (stopIndex >= stops.size()) {
			// Round done: store, then wait.
			phase = Phase.STORE;
			if (cfg.storeEnabled && !cfg.chests.isEmpty()) {
				status("상자에 보관하러 가는 중");
				add(new Storage.TripAction());
			}
			return;
		}
		int i = stopIndex++;
		BlockPos stop = stops.get(i);
		int field = stopField.get(i);
		status("밭 " + (field + 1) + " 도는 중 (" + (i + 1) + "/" + stops.size() + ")");
		add(new WalkAction(stop));
		add(new HarvestAction(FarmConfig.get().fields.get(field), field));
	}

	static void walkFailed() {
		if (++walkFails >= 6) stop("길이 막혔어요", "밭 사이에 막힌 곳이 있는지 확인해주세요", true);
	}

	static void walkOk() {
		walkFails = 0;
	}

	// ------------------------------------------------------------------ helpers

	static Direction faceToward(Vec3 eye, BlockPos pos) {
		double dx = eye.x - (pos.getX() + 0.5), dy = eye.y - (pos.getY() + 0.5), dz = eye.z - (pos.getZ() + 0.5);
		double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
		if (ay >= ax && ay >= az) return dy > 0 ? Direction.UP : Direction.DOWN;
		if (ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
		return dz > 0 ? Direction.SOUTH : Direction.NORTH;
	}

	static Vec3 facePoint(BlockPos pos, Direction face, double spread) {
		double x = pos.getX() + 0.5 + face.getStepX() * 0.5;
		double y = pos.getY() + 0.5 + face.getStepY() * 0.5;
		double z = pos.getZ() + 0.5 + face.getStepZ() * 0.5;
		if (face.getAxis() != Direction.Axis.X) x += Human.jitter(spread);
		if (face.getAxis() != Direction.Axis.Y) y += Human.jitter(spread);
		if (face.getAxis() != Direction.Axis.Z) z += Human.jitter(spread);
		return new Vec3(x, y, z);
	}

	/** A point inside a (possibly small) plant's box, for aiming at crops. */
	static Vec3 plantPoint(BlockPos pos) {
		return new Vec3(pos.getX() + 0.5 + Human.jitter(0.08), pos.getY() + 0.3 + Human.RANDOM.nextDouble() * 0.15,
				pos.getZ() + 0.5 + Human.jitter(0.08));
	}

	// ------------------------------------------------------------------ actions

	static final class WaitAction implements Action {
		private int t;

		WaitAction(int t) {
			this.t = t;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			return --t <= 0 ? DONE : RUN;
		}
	}

	static final class WalkAction implements Action {
		private final BlockPos target;
		private boolean started;
		private int pre, post = -1, limit;

		WalkAction(BlockPos target) {
			this.target = target;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			if (!started) {
				started = true;
				pre = Human.delay(0, 4);
				Body.resetWalk();
				Body.newTurn();
				double dist = Math.sqrt(p.blockPosition().distSqr(target));
				limit = (int) (60 + dist * 14);
			}
			if (pre-- > 0) return RUN;
			if (post >= 0) return post-- <= 0 ? DONE : RUN;
			if (Body.walkTo(p, target)) {
				walkOk();
				post = Human.delay(0, 3);
				return RUN;
			}
			if (Body.walkTicks() > limit) {
				Body.release(mc);
				walkFailed();
				return FAIL;
			}
			return RUN;
		}
	}

	/** Collects everything ripe in the 3x3 around the stop and queues the work for it. */
	static final class HarvestAction implements Action {
		private final FieldEntry field;
		private final int fieldIndex;
		private boolean scanned;

		HarvestAction(FieldEntry field, int fieldIndex) {
			this.field = field;
			this.fieldIndex = fieldIndex;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			if (scanned) return DONE;
			scanned = true;
			BlockPos me = p.blockPosition();
			Map<Item, Integer> counts = seen.computeIfAbsent(fieldIndex, k -> new HashMap<>());
			List<Action> todo = new ArrayList<>();
			List<BlockPos> empty = new ArrayList<>();
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					int x = me.getX() + dx, z = me.getZ() + dz;
					if (x < field.minX() || x > field.maxX() || z < field.minZ() || z > field.maxZ()) continue;
					BlockPos pos = new BlockPos(x, field.y, z);
					Crops.Task t = Crops.check(pos);
					if (t != null) {
						if (t.seed() != null) counts.merge(t.seed(), 1, Integer::sum);
						queueTask(todo, t);
					} else {
						var b = Blocks2.state(pos).getBlock();
						Item seed = Crops.seedOf(b);
						if (seed != null) counts.merge(seed, 1, Integer::sum);
						if (b == Blocks.NETHER_WART) counts.merge(Items.NETHER_WART, 1, Integer::sum);
						if (Blocks2.state(pos).isAir()) empty.add(pos);
					}
					for (int dy = -1; dy <= 3; dy++) {
						Crops.Task c = Crops.checkCocoa(pos.above(dy));
						if (c != null) queueTask(todo, c);
					}
				}
			}
			// Empty farmland: plant what this field mostly grows.
			Item main = null;
			int best = 0;
			for (Map.Entry<Item, Integer> e : counts.entrySet()) {
				if (e.getValue() > best) {
					best = e.getValue();
					main = e.getKey();
				}
			}
			if (main != null) {
				for (BlockPos pos : empty) {
					if (Crops.soilFor(main, pos.below())) todo.add(new PlantAction(main, pos.below(), Direction.UP, pos));
				}
			}
			if (!todo.isEmpty()) {
				status("수확하는 중");
				pushFront(todo);
				return RUN;
			}
			return DONE;
		}

		private static void queueTask(List<Action> todo, Crops.Task t) {
			switch (t.kind()) {
				case PICK -> todo.add(new UseAction(t.pos(), t.crop()));
				case REPLANT, COCOA -> {
					todo.add(new BreakAction(t.pos(), t.crop()));
					todo.add(new PlantAction(t.seed(), t.plantOn(), t.plantFace(), t.pos()));
				}
				default -> todo.add(new BreakAction(t.pos(), t.crop()));
			}
		}
	}

	/** Breaks one crop block. */
	static final class BreakAction implements Action {
		private final BlockPos pos;
		private final String crop;
		private int stage, delay, ticks;
		private Vec3 aim;
		private Direction face;

		BreakAction(BlockPos pos, String crop) {
			this.pos = pos;
			this.crop = crop;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			switch (stage) {
				case 0 -> {
					if (Blocks2.state(pos).isAir()) return DONE;
					if (p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > 4.5) return FAIL;
					face = faceToward(p.getEyePosition(), pos);
					aim = plantPoint(pos);
					Body.newTurn();
					delay = Human.delay(1, 5);
					stage = 1;
					return RUN;
				}
				case 1 -> {
					if (Body.lookAt(p, aim, 3f) && delay-- <= 0) stage = 2;
					return RUN;
				}
				default -> {
					Body.lookAt(p, aim, 2f);
					mc.gameMode.continueDestroyBlock(pos, face);
					p.swing(InteractionHand.MAIN_HAND);
					if (Blocks2.state(pos).isAir() || !Blocks2.state(pos).getFluidState().isEmpty()) {
						FarmStats.harvest(crop);
						return DONE;
					}
					if (++ticks > 200) {
						mc.gameMode.stopDestroyBlock();
						return FAIL;
					}
					return RUN;
				}
			}
		}
	}

	/** Right-clicks a block (sweet berries). */
	static final class UseAction implements Action {
		private final BlockPos pos;
		private final String crop;
		private int stage, delay, wait;
		private Vec3 aim;

		UseAction(BlockPos pos, String crop) {
			this.pos = pos;
			this.crop = crop;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			switch (stage) {
				case 0 -> {
					aim = plantPoint(pos);
					Body.newTurn();
					delay = Human.delay(1, 5);
					stage = 1;
					return RUN;
				}
				case 1 -> {
					if (!Body.lookAt(p, aim, 3f) || delay-- > 0) return RUN;
					mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
							new BlockHitResult(aim, faceToward(p.getEyePosition(), pos), pos, false));
					p.swing(InteractionHand.MAIN_HAND);
					FarmStats.harvest(crop);
					wait = Human.rand(2, 5);
					stage = 2;
					return RUN;
				}
				default -> {
					return wait-- <= 0 ? DONE : RUN;
				}
			}
		}
	}

	/** Plants a seed on a soil block (or cocoa beans on a jungle log). */
	static final class PlantAction implements Action {
		private final Item seed;
		private final BlockPos on, target;
		private final Direction face;
		private final boolean retry;
		private int stage, delay, wait, slot, tries, pickupWait;
		private Vec3 hit;

		PlantAction(Item seed, BlockPos on, Direction face, BlockPos target) {
			this(seed, on, face, target, false);
		}

		PlantAction(Item seed, BlockPos on, Direction face, BlockPos target, boolean retry) {
			this.seed = seed;
			this.on = on;
			this.face = face;
			this.target = target;
			this.retry = retry;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			switch (stage) {
				case 0 -> {
					if (!Blocks2.state(target).isAir()) return DONE;
					slot = Inv.bringToHotbar(mc, p, s -> s.is(seed));
					if (slot == -1) {
						// The seed we just harvested may still be lying on the ground: give it a moment.
						if (pickupWait++ < 16) return RUN;
						if (retry) seedShort = true;
						else missed.add(new Missed(p.blockPosition(), seed, on, face, target));
						return DONE;
					}
					hit = facePoint(on, face, 0.1);
					if (p.getEyePosition().distanceTo(hit) > 4.5) return FAIL;
					delay = Human.delay(1, 5);
					Body.newTurn();
					stage = 1;
					return RUN;
				}
				case 1 -> {
					if (delay-- > 0) return RUN;
					Inv.select(p, slot);
					stage = 2;
					return RUN;
				}
				case 2 -> {
					if (!Body.lookAt(p, hit, 3f)) return RUN;
					mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, on, false));
					p.swing(InteractionHand.MAIN_HAND);
					wait = Human.rand(2, 4);
					stage = 3;
					return RUN;
				}
				default -> {
					if (wait-- > 0) return RUN;
					if (!Blocks2.state(target).isAir()) return DONE;
					if (++tries >= 3) return FAIL;
					stage = 0;
					return RUN;
				}
			}
		}
	}
}
