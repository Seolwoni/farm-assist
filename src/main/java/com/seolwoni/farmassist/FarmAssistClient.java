package com.seolwoni.farmassist;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.InputConstants;
import com.seolwoni.farmassist.config.FarmConfig;
import com.seolwoni.farmassist.farming.Farmer;
import com.seolwoni.farmassist.farming.Field;
import com.seolwoni.farmassist.farming.Storage;
import com.seolwoni.farmassist.hud.FarmHud;
import com.seolwoni.farmassist.hud.FarmStats;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

public class FarmAssistClient implements ClientModInitializer {
	public static final String MOD_ID = "farmassist";
	public static final Logger LOGGER = LoggerFactory.getLogger("Farm Assist");

	public static KeyMapping markChestKey;

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitializeClient() {
		FarmConfig.load();
		FarmStats.loadTotal();

		KeyMapping.Category category = KeyMapping.Category.register(id("main"));
		markChestKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.farmassist.mark_chest", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (markChestKey.consumeClick()) {
				if (FarmConfig.get().modEnabled) Storage.markLookedAt(client);
			}
			if (FarmConfig.get().modEnabled) Farmer.tick(client);
		});

		// Hoe + Shift + right-click marks a field corner, and does not till the ground.
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!level.isClientSide() || player != Minecraft.getInstance().player) return InteractionResult.PASS;
			if (!FarmConfig.get().modEnabled || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
			if (!player.isShiftKeyDown() || !player.getMainHandItem().is(ItemTags.HOES)) return InteractionResult.PASS;
			Field.click(hit.getBlockPos());
			// FAIL = handled here and nothing is sent to the server, so the ground is not tilled.
			player.swing(hand);
			return InteractionResult.FAIL;
		});

		HudElementRegistry.addLast(id("hud"), (graphics, delta) -> FarmHud.render(graphics));

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			if (FarmConfig.get().running) Farmer.stop(null, null, false);
			Field.cancelPending();
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			if (FarmConfig.get().running) Farmer.stop(null, null, false);
			FarmConfig.save();
			FarmStats.saveTotal();
		});

		LOGGER.info("Farm Assist loaded - made by seolwoni");
	}
}
