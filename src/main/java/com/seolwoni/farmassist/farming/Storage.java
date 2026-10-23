package com.seolwoni.farmassist.farming;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.seolwoni.farmassist.config.FarmConfig;
import com.seolwoni.assistcore.storage.ChestEntry;
import com.seolwoni.assistcore.storage.Chests;
import com.seolwoni.farmassist.hud.FarmStats;
import com.seolwoni.assistcore.util.Alerts;
import com.seolwoni.assistcore.util.Body;
import com.seolwoni.assistcore.util.Human;
import com.seolwoni.farmassist.util.Inv;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Marking storage chests (K) and the trip that sorts the harvest into them. */
public final class Storage {
	private Storage() {
	}

	public static void markLookedAt(Minecraft mc) {
		Chests.toggleLookedAt(mc, FarmConfig.get().chests, FarmConfig::save);
	}

	static BlockPos pos(ChestEntry e) {
		return e.pos();
	}

	/** Inventory indices to keep: tools, real food, and up to seedKeep of each seed type. */
	static Set<Integer> keepSet(LocalPlayer p) {
		FarmConfig cfg = FarmConfig.get();
		Inventory inv = p.getInventory();
		Set<Integer> keep = new HashSet<>();
		keep.add(inv.getSelectedSlot());
		// Shulker boxes and ender chests we carry to store into stay with us (never put into the chest).
		if (FarmConfig.get().storeMode != com.seolwoni.assistcore.storage.PortableStorage.CHEST) {
			for (int i = 0; i < 36; i++) if (com.seolwoni.assistcore.storage.PortableStorage.isPortable(inv.getItem(i))) keep.add(i);
		}
		Map<Item, List<Integer>> seeds = new HashMap<>();
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) continue;
			if (Inv.isSeed(s)) seeds.computeIfAbsent(s.getItem(), k -> new ArrayList<>()).add(i);
			else if (Inv.isTool(s) || Inv.isFood(s) || s.is(net.minecraft.world.item.Items.GOLDEN_CARROT) || s.is(net.minecraft.world.item.Items.GOLDEN_APPLE) || s.is(net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE)) keep.add(i); // golden food: never eaten automatically, but kept
		}
		for (List<Integer> list : seeds.values()) {
			list.sort(Comparator.comparingInt((Integer i) -> inv.getItem(i).getCount()).reversed());
			int kept = 0;
			for (int i : list) {
				if (kept >= cfg.seedKeep) break;
				keep.add(i);
				kept += inv.getItem(i).getCount();
			}
		}
		return keep;
	}

	static int target(String id, boolean[] full) {
		return Chests.target(FarmConfig.get().chests, id, full);
	}

	/** Seeds this round planted, that we now have fewer than 16 of. */
	static List<Item> seedsWanted(LocalPlayer p) {
		List<Item> out = new ArrayList<>();
		Set<Item> used = new HashSet<>();
		for (Map<Item, Integer> m : Farmer.seen.values()) used.addAll(m.keySet());
		for (Item it : used) if (Inv.count(p, s -> s.is(it)) < 16) out.add(it);
		return out;
	}

	static final class TripAction implements Farmer.Action {
		private final List<Integer> order = new ArrayList<>();
		private boolean[] full;
		private int stage, idx, delay, waitTicks, moved, limit;
		/** Assist Path walk to the chest (or back), then the last bit directly. */
		private com.seolwoni.assistpath.walk.PathWalker walker;
		private BlockPos walkerFor;
		private Vec3 aim;
		private Direction face;
		private String reason = "";

		@Override
		public int failMode() {
			return STOP;
		}

		@Override
		public String failReason() {
			return reason;
		}

		private ChestEntry chest() {
			return FarmConfig.get().chests.get(order.get(idx));
		}

		private boolean hasWork(LocalPlayer p, int chestIndex) {
			Set<Integer> keep = keepSet(p);
			for (int i = 0; i < 36; i++) {
				ItemStack s = p.getInventory().getItem(i);
				if (!s.isEmpty() && !keep.contains(i) && target(Inv.id(s), full) == chestIndex) return true;
			}
			return !seedsWanted(p).isEmpty() || (FarmConfig.get().autoEat && Inv.count(p, Inv::isFood) == 0);
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			FarmConfig cfg = FarmConfig.get();
			switch (stage) {
				case 0 -> {
					if (cfg.chests.isEmpty()) return Farmer.DONE;
					full = new boolean[cfg.chests.size()];
					order.clear();
					for (int i = 0; i < cfg.chests.size(); i++) if (!cfg.chests.get(i).items.isEmpty()) order.add(i);
					for (int i = 0; i < cfg.chests.size(); i++) if (cfg.chests.get(i).items.isEmpty()) order.add(i);
					idx = 0;
					stage = 1;
					Farmer.status(com.seolwoni.assistcore.i18n.Tr.t("상자에 보관하는 중"));
					return Farmer.RUN;
				}
				case 1 -> {
					while (idx < order.size() && !hasWork(p, order.get(idx))) idx++;
					if (idx >= order.size()) {
						if (Inv.freeSlots(p) <= 1) {
							Farmer.stop(com.seolwoni.assistcore.i18n.Tr.t("보관 상자가 가득 찼어요"), com.seolwoni.assistcore.i18n.Tr.t("상자를 비우거나 더 지정해주세요"), true);
							return Farmer.RUN;
						}
						Alerts.actionBar(com.seolwoni.assistcore.i18n.Tr.t("보관 완료 (") + moved + com.seolwoni.assistcore.i18n.Tr.t("칸)"), 0xFF55FF55);
						FarmStats.trip();
						return Farmer.DONE;
					}
					BlockPos c = pos(chest());
					if (mc.level.getBlockEntity(c) == null) {
						full[order.get(idx)] = true;
						idx++;
						return Farmer.RUN;
					}
					Body.resetWalk();
					Body.newTurn();
					limit = (int) (80 + Math.sqrt(p.blockPosition().distSqr(c)) * 14);
					stage = 2;
					return Farmer.RUN;
				}
				case 2 -> {
					BlockPos c = pos(chest());
					if (p.getEyePosition().distanceTo(Vec3.atCenterOf(c)) <= 4.0) {
						Body.release(mc);
						face = Farmer.faceToward(p.getEyePosition(), c);
						aim = Farmer.facePoint(c, face, 0.1);
						delay = Human.delay(2, 6);
						stage = 3;
						return Farmer.RUN;
					}
					if (!c.equals(walkerFor)) {
						walkerFor = c;
						walker = new com.seolwoni.assistpath.walk.PathWalker(new com.seolwoni.assistpath.goals.GoalNear(c, 2), com.seolwoni.assistpath.PathOptions.WALK_DRY, 6);
					}
					if (walker != null) {
						if (walker.tick(mc, p) == RUN) return RUN;
						walker = null;
						Body.resetWalk();
						Body.newTurn();
						return RUN;
					}
					Body.walkTo(p, c);
					if (Body.walkTicks() > limit) {
						Body.release(mc);
						reason = com.seolwoni.assistcore.i18n.Tr.t("보관 상자까지 갈 수 없어요");
						return Farmer.FAIL;
					}
					return Farmer.RUN;
				}
				case 3 -> {
					if (!Body.lookAt(p, aim, 3f) || delay-- > 0) return Farmer.RUN;
					mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(aim, face, pos(chest()), false));
					p.swing(InteractionHand.MAIN_HAND);
					waitTicks = 40;
					stage = 4;
					return Farmer.RUN;
				}
				case 4 -> {
					if (open(mc) != null) {
						delay = Human.delay(3, 8);
						stage = 5;
						return Farmer.RUN;
					}
					if (--waitTicks <= 0) {
						reason = com.seolwoni.assistcore.i18n.Tr.t("상자를 열지 못했어요");
						return Farmer.FAIL;
					}
					return Farmer.RUN;
				}
				case 5 -> {
					AbstractContainerScreen<?> s = open(mc);
					if (s == null) {
						idx++;
						stage = 1;
						return Farmer.RUN;
					}
					if (delay-- > 0) return Farmer.RUN;
					AbstractContainerMenu menu = s.getMenu();
					if (depositOne(mc, p, menu) || takeOne(mc, p, menu)) {
						delay = Human.delay(1, 4);
						return Farmer.RUN;
					}
					delay = Human.delay(3, 8);
					stage = 6;
					return Farmer.RUN;
				}
				default -> {
					if (delay-- > 0) return Farmer.RUN;
					if (open(mc) != null) p.closeContainer();
					idx++;
					stage = 1;
					return Farmer.RUN;
				}
			}
		}

		private static AbstractContainerScreen<?> open(Minecraft mc) {
			return Chests.openScreen(mc);
		}

		private boolean depositOne(Minecraft mc, LocalPlayer p, AbstractContainerMenu menu) {
			int here = order.get(idx);
			Set<Integer> keep = keepSet(p);
			for (int i = 0; i < menu.slots.size(); i++) {
				Slot slot = menu.slots.get(i);
				if (!(slot.container instanceof Inventory)) continue;
				int inv = slot.getContainerSlot();
				if (inv >= 36 || keep.contains(inv)) continue;
				ItemStack s = slot.getItem();
				if (s.isEmpty() || target(Inv.id(s), full) != here) continue;
				int before = s.getCount();
				ItemStack copy = s.copy();
				mc.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.QUICK_MOVE, p);
				ItemStack after = slot.getItem();
				if (!after.isEmpty() && after.getCount() == before && ItemStack.isSameItemSameComponents(after, copy)) {
					full[here] = true;
					return false;
				}
				moved++;
				return true;
			}
			return false;
		}

		private boolean takeOne(Minecraft mc, LocalPlayer p, AbstractContainerMenu menu) {
			if (Inv.freeSlots(p) <= 1) return false;
			List<java.util.function.Predicate<ItemStack>> wants = new ArrayList<>();
			for (Item it : seedsWanted(p)) wants.add(s -> s.is(it));
			if (FarmConfig.get().autoEat && Inv.count(p, Inv::isFood) == 0) wants.add(Inv::isFood);
			for (java.util.function.Predicate<ItemStack> want : wants) {
				for (int i = 0; i < menu.slots.size(); i++) {
					Slot slot = menu.slots.get(i);
					if (slot.container instanceof Inventory || !want.test(slot.getItem())) continue;
					mc.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.QUICK_MOVE, p);
					return true;
				}
			}
			return false;
		}
	}
}
