package com.seolwoni.farmassist.hud;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.seolwoni.farmassist.FarmAssistClient;
import com.seolwoni.farmassist.config.FarmConfig;

import net.fabricmc.loader.api.FabricLoader;

/** Harvest statistics per crop, plus rounds, trips and time. */
public class FarmStats {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("farmassist-stats.json");

	public static FarmStats session = new FarmStats();
	public static FarmStats total = new FarmStats();

	public static class Entry {
		public long count;
		public long lastMs;
	}

	public Map<String, Entry> crops = new LinkedHashMap<>();
	public long ticks;
	public long rounds;
	public long trips;

	public static FarmStats shown() {
		return FarmConfig.get().statsTotals ? total : session;
	}

	public long totalHarvest() {
		long n = 0;
		for (Entry e : crops.values()) n += e.count;
		return n;
	}

	public void reset() {
		crops.clear();
		ticks = rounds = trips = 0;
	}

	public static void harvest(String crop) {
		if (crop == null) return;
		long now = System.currentTimeMillis();
		for (FarmStats s : new FarmStats[] {session, total}) {
			Entry e = s.crops.computeIfAbsent(crop, k -> new Entry());
			e.count++;
			e.lastMs = now;
		}
	}

	public static void tick() {
		session.ticks++;
		total.ticks++;
		if (total.ticks % 1200 == 0) saveTotal();
	}

	public static void round() {
		session.rounds++;
		total.rounds++;
		saveTotal();
	}

	public static void trip() {
		session.trips++;
		total.trips++;
		saveTotal();
	}

	public static String label(String crop) {
		return switch (crop) {
			case "wheat" -> "밀";
			case "carrots" -> "당근";
			case "potatoes" -> "감자";
			case "beetroots" -> "비트";
			case "nether_wart" -> "네더 사마귀";
			case "sugar_cane" -> "사탕수수";
			case "bamboo" -> "대나무";
			case "pumpkin" -> "호박";
			case "melon" -> "수박";
			case "cocoa" -> "코코아";
			case "cactus" -> "선인장";
			case "sweet_berry_bush" -> "달콤한 열매";
			default -> crop;
		};
	}

	public static int color(String crop) {
		return switch (crop) {
			case "wheat" -> 0xFFE8C76A;
			case "carrots", "pumpkin" -> 0xFFFF9A3C;
			case "potatoes" -> 0xFFD9B26F;
			case "beetroots", "nether_wart", "sweet_berry_bush" -> 0xFFE04A5A;
			case "sugar_cane", "bamboo", "cactus", "melon" -> 0xFF7EDB6A;
			case "cocoa" -> 0xFFB07B4F;
			default -> 0xFFFFFFFF;
		};
	}

	public static void loadTotal() {
		try {
			if (Files.exists(PATH)) {
				try (Reader r = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
					FarmStats s = GSON.fromJson(r, FarmStats.class);
					if (s != null) {
						if (s.crops == null) s.crops = new LinkedHashMap<>();
						total = s;
					}
				}
			}
		} catch (Exception e) {
			FarmAssistClient.LOGGER.warn("Failed to load stats", e);
		}
	}

	public static void saveTotal() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer w = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(total, w);
			}
		} catch (Exception e) {
			FarmAssistClient.LOGGER.warn("Failed to save stats", e);
		}
	}
}
