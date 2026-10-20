package com.seolwoni.farmassist.farming;

import com.seolwoni.farmassist.config.FarmConfig;
import com.seolwoni.assistcore.util.Human;
import com.seolwoni.farmassist.util.Inv;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;

/** Eating, and going to the chests when the inventory fills up or seeds run out. */
final class Chores {
	static boolean seedTripDone;

	private Chores() {
	}

	static boolean plan(Minecraft mc, LocalPlayer p, FarmConfig cfg) {
		if (cfg.autoEat && p.getFoodData().getFoodLevel() <= cfg.eatThreshold && Inv.count(p, Inv::isFood) > 0) {
			Farmer.status("먹는 중");
			Farmer.add(new EatAction());
			return true;
		}
		boolean store = cfg.storeEnabled && !cfg.chests.isEmpty();
		if (Inv.freeSlots(p) <= 1) {
			// A shulker box or an ender chest carried along: store right here.
			var box = cfg.storeEnabled ? com.seolwoni.assistcore.storage.PortableStorage.trip("farm", p, cfg.storeMode, i -> !Storage.keepSet(p).contains(i), null, true) : null;
			if (box != null) {
				Farmer.status(box.label());
				Farmer.addCore(box);
			} else if (store) {
				Farmer.status("인벤토리가 차서 보관하러 가는 중");
				Farmer.add(new Storage.TripAction());
			} else if (cfg.storeEnabled && cfg.storeMode != com.seolwoni.assistcore.storage.PortableStorage.CHEST) {
				Farmer.stop("인벤토리 가득 참!", com.seolwoni.assistcore.storage.PortableStorage.whyNone(p, cfg.storeMode), true);
			} else {
				Farmer.stop("인벤토리 가득 참!", "보관 상자를 지정하면 자동으로 보관해요", true);
			}
			return true;
		}
		// Ran out of something to plant: fetch it from the chests once per round.
		if (Farmer.seedShort && store && !seedTripDone) {
			seedTripDone = true;
			Farmer.seedShort = false;
			Farmer.status("씨앗 가지러 가는 중");
			Farmer.add(new Storage.TripAction());
			return true;
		}
		return false;
	}

	static final class EatAction implements Farmer.Action {
		private int stage, delay, ticks, bites, slot;

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			switch (stage) {
				case 0 -> {
					// Prefer real food over the crops we replant.
					slot = Inv.bringToHotbar(mc, p, s -> Inv.isFood(s) && !Inv.isSeed(s));
					if (slot == -1) slot = Inv.bringToHotbar(mc, p, Inv::isFood);
					if (slot == -1) return Farmer.DONE;
					delay = Human.delay(2, 8);
					stage = 1;
					return Farmer.RUN;
				}
				case 1 -> {
					if (delay-- > 0) return Farmer.RUN;
					Inv.select(p, slot);
					delay = Human.delay(2, 6);
					stage = 2;
					return Farmer.RUN;
				}
				case 2 -> {
					if (delay-- > 0) return Farmer.RUN;
					mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
					mc.options.keyUse.setDown(true);
					ticks = 0;
					stage = 3;
					return Farmer.RUN;
				}
				default -> {
					ticks++;
					if (!((ticks > 5 && !p.isUsingItem()) || ticks > 80)) return Farmer.RUN;
					mc.options.keyUse.setDown(false);
					if (++bites < 3 && p.getFoodData().getFoodLevel() <= FarmConfig.get().eatThreshold && Inv.isFood(p.getMainHandItem())) {
						delay = Human.delay(3, 8);
						stage = 2;
						return Farmer.RUN;
					}
					return Farmer.DONE;
				}
			}
		}
	}
}
