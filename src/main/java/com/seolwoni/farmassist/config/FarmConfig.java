package com.seolwoni.farmassist.config;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.seolwoni.assistcore.storage.ChestEntry;
import com.seolwoni.farmassist.FarmAssistClient;

import net.fabricmc.loader.api.FabricLoader;

/** All Farm Assist settings. Saved to config/farmassist.json. */
public class FarmConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("farmassist.json");

	/** Crop kinds that can be switched on/off. */
	public static final List<String> CROPS = List.of(
			"wheat", "carrots", "potatoes", "beetroots", "nether_wart", "sugar_cane", "bamboo",
			"pumpkin", "melon", "cocoa", "cactus", "sweet_berry_bush");

	// Keep after the static lists above: field initialisers below copy them.
	private static FarmConfig instance = new FarmConfig();

	public boolean modEnabled = true;
	public boolean running = false;

	// ---- rounds ----
	public int waitMinutes = 5;
	public int seedKeep = 64;
	public boolean humanDelay = true;

	// ---- crops ----
	public List<String> crops = new ArrayList<>(CROPS);

	// ---- fields ----
	public List<FieldEntry> fields = new ArrayList<>();

	// ---- safety / food ----
	public boolean stopOnMob = true;
	/** Mobs: 0 = avoid them (Defense), 1 = avoid and fight back, 2 = stop the job, 3 = ignore. */
	public int mobMode = 0;
	public int mobRange = 8;
	public int minHealth = 8;
	public boolean autoEat = true;
	public int eatThreshold = 14;

	// ---- storage ----
	public boolean storeEnabled = true;
	public List<ChestEntry> chests = new ArrayList<>();

	// ---- HUD ----
	public boolean statusHud = true;
	public float hudX = 0.01f, hudY = 0.2f, hudScale = 1.0f;
	public boolean statsHud = true;
	public float statsX = 0.01f, statsY = 0.45f, statsScale = 1.0f;
	public boolean statsTotals = false;
	public int hudBgAlpha = 110;

	public static class FieldEntry {
		/** Crop layer corners (the block the crop itself sits in). {@code y} is the first corner's height. */
		public int x1, z1, x2, z2, y;
		/** The second corner's height (fields on a slope or in steps); null for fields marked before 1.4. */
		public Integer y2;
		public String name = "";

		public int minY() {
			return y2 == null ? y : Math.min(y, y2);
		}

		public int maxY() {
			return y2 == null ? y : Math.max(y, y2);
		}

		public int minX() {
			return Math.min(x1, x2);
		}

		public int maxX() {
			return Math.max(x1, x2);
		}

		public int minZ() {
			return Math.min(z1, z2);
		}

		public int maxZ() {
			return Math.max(z1, z2);
		}

		public int sizeX() {
			return maxX() - minX() + 1;
		}

		public int sizeZ() {
			return maxZ() - minZ() + 1;
		}
	}


	public static FarmConfig get() {
		return instance;
	}

	public static void load() {
		try {
			if (Files.exists(PATH)) {
				try (Reader r = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
					FarmConfig c = GSON.fromJson(r, FarmConfig.class);
					if (c != null) instance = c;
				}
			}
		} catch (Exception e) {
			FarmAssistClient.LOGGER.warn("Failed to load config", e);
		}
		FarmConfig c = instance;
		if (c.crops == null) c.crops = new ArrayList<>(CROPS);
		if (c.fields == null) c.fields = new ArrayList<>();
		if (c.chests == null) c.chests = new ArrayList<>();
		for (ChestEntry e : c.chests) if (e.items == null) e.items = new ArrayList<>();
		c.running = false;
		c.waitMinutes = Math.max(0, Math.min(120, c.waitMinutes));
		c.seedKeep = Math.max(0, Math.min(256, c.seedKeep));
		save();
	}

	public static void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer w = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(instance, w);
			}
		} catch (Exception e) {
			FarmAssistClient.LOGGER.warn("Failed to save config", e);
		}
	}
}
