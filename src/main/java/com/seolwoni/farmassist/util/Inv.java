package com.seolwoni.farmassist.util;

import java.util.function.Predicate;

import com.seolwoni.assistcore.util.InvBase;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Farm item kinds on top of the shared inventory helpers. */
public final class Inv extends InvBase {
	private Inv() {
	}

	public static boolean isHoe(ItemStack s) {
		return !s.isEmpty() && s.is(ItemTags.HOES);
	}

	/** Items used to (re)plant: seeds, carrots, potatoes, nether wart, cocoa beans. */
	public static boolean isSeed(ItemStack s) {
		return !s.isEmpty() && (s.is(Items.WHEAT_SEEDS) || s.is(Items.BEETROOT_SEEDS) || s.is(Items.CARROT)
				|| s.is(Items.POTATO) || s.is(Items.NETHER_WART) || s.is(Items.COCOA_BEANS));
	}

	public static boolean isTool(ItemStack s) {
		return !s.isEmpty() && s.isDamageableItem();
	}

	/** Brings a matching item to the hotbar without pushing out tools or food. */
	public static int bringToHotbar(Minecraft mc, LocalPlayer p, Predicate<ItemStack> f) {
		return InvBase.bringToHotbar(mc, p, f, s -> isHoe(s) || isTool(s) || isFood(s));
	}
}
