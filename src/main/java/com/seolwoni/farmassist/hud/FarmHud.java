package com.seolwoni.farmassist.hud;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.seolwoni.farmassist.config.FarmConfig;
import com.seolwoni.farmassist.farming.Farmer;
import com.seolwoni.farmassist.farming.Field;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Two HUD boxes: status and harvest statistics. */
public final class FarmHud {
	public enum Box { STATUS, STATS }

	private static final int PAD = 3;
	private static final int GOLD = 0xFFFFAA00, WHITE = 0xFFFFFFFF, GRAY = 0xFFAAAAAA, GREEN = 0xFF55FF55, RED = 0xFFFF5555;
	private static final int[] W = {140, 140}, H = {60, 60};

	private record Seg(String t, int c) {
	}

	private FarmHud() {
	}

	public static boolean enabled(Box b) {
		FarmConfig c = FarmConfig.get();
		return b == Box.STATUS ? c.statusHud : c.statsHud;
	}

	public static void render(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !FarmConfig.get().modEnabled) return;
		if (mc.gui.screen() instanceof HudEditScreen) return;
		for (Box b : Box.values()) if (enabled(b)) draw(g, b, false);
	}

	public static float scale(Box b) {
		FarmConfig c = FarmConfig.get();
		return b == Box.STATUS ? c.hudScale : c.statsScale;
	}

	public static void setScale(Box b, float s) {
		FarmConfig c = FarmConfig.get();
		s = Math.round(Math.max(0.5f, Math.min(3f, s)) * 100f) / 100f;
		if (b == Box.STATUS) c.hudScale = s;
		else c.statsScale = s;
	}

	public static void setPos(Box b, float x, float y) {
		FarmConfig c = FarmConfig.get();
		if (b == Box.STATUS) {
			c.hudX = x;
			c.hudY = y;
		} else {
			c.statsX = x;
			c.statsY = y;
		}
	}

	public static int baseWidth(Box b) {
		return W[b.ordinal()];
	}

	public static void draw(GuiGraphicsExtractor g, Box box, boolean editing) {
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		FarmConfig cfg = FarmConfig.get();
		List<List<Seg>> lines = box == Box.STATUS ? statusLines() : statsLines();
		int w = 0;
		for (List<Seg> l : lines) {
			int lw = 0;
			for (Seg s : l) lw += font.width(s.t());
			w = Math.max(w, lw);
		}
		int lh = font.lineHeight + 1;
		W[box.ordinal()] = w + PAD * 2;
		H[box.ordinal()] = lines.size() * lh + PAD * 2 - 1;
		float[] b = bounds(box);
		g.pose().pushMatrix();
		g.pose().translate(b[0], b[1]);
		g.pose().scale(scale(box), scale(box));
		if (cfg.hudBgAlpha > 0) g.fill(0, 0, W[box.ordinal()], H[box.ordinal()], (cfg.hudBgAlpha & 0xFF) << 24);
		if (editing) g.outline(0, 0, W[box.ordinal()], H[box.ordinal()], 0xFFFFFF55);
		int y = PAD;
		for (List<Seg> l : lines) {
			int x = PAD;
			for (Seg s : l) {
				g.text(font, Component.literal(s.t()).getVisualOrderText(), x, y, s.c(), true);
				x += font.width(s.t());
			}
			y += lh;
		}
		g.pose().popMatrix();
	}

	public static float[] bounds(Box box) {
		Minecraft mc = Minecraft.getInstance();
		FarmConfig cfg = FarmConfig.get();
		int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
		float s = scale(box);
		float w = W[box.ordinal()] * s, h = H[box.ordinal()] * s;
		float fx = box == Box.STATUS ? cfg.hudX : cfg.statsX;
		float fy = box == Box.STATUS ? cfg.hudY : cfg.statsY;
		return new float[] {Math.max(0, Math.min(fx * sw, sw - w)), Math.max(0, Math.min(fy * sh, sh - h)), w, h};
	}

	private static List<List<Seg>> statusLines() {
		FarmConfig cfg = FarmConfig.get();
		List<List<Seg>> out = new ArrayList<>();
		out.add(List.of(new Seg("Farm Assist", GOLD)));
		out.add(List.of(new Seg("상태: ", GOLD), new Seg(Farmer.status(), cfg.running ? GREEN : RED)));
		out.add(List.of(new Seg("밭: ", GOLD), new Seg(cfg.fields.size() + "개", WHITE),
				new Seg("  상자: ", GOLD), new Seg(cfg.chests.size() + "개", WHITE)));
		if (cfg.running && Farmer.totalStops() > 0 && Farmer.waitSecondsLeft() < 0) {
			out.add(List.of(new Seg("진행: ", GOLD), new Seg(Math.min(Farmer.progress(), Farmer.totalStops()) + "/" + Farmer.totalStops(), WHITE)));
		}
		if (Field.hasPending()) out.add(List.of(new Seg("반대쪽 모서리를 지정하세요", 0xFF55FFFF)));
		return out;
	}

	private static List<List<Seg>> statsLines() {
		FarmConfig cfg = FarmConfig.get();
		FarmStats st = FarmStats.shown();
		List<List<Seg>> out = new ArrayList<>();
		out.add(List.of(new Seg("수확 통계 ", GOLD), new Seg(cfg.statsTotals ? "(누적)" : "(이번 세션)", GRAY)));
		long total = st.totalHarvest();
		long now = System.currentTimeMillis();
		List<Map.Entry<String, FarmStats.Entry>> list = new ArrayList<>(st.crops.entrySet());
		list.sort(Comparator.comparingLong((Map.Entry<String, FarmStats.Entry> e) -> e.getValue().count).reversed());
		if (list.isEmpty()) out.add(List.of(new Seg("  아직 수확한 게 없어요", GRAY)));
		for (Map.Entry<String, FarmStats.Entry> e : list) {
			FarmStats.Entry v = e.getValue();
			out.add(List.of(new Seg(ago(now - v.lastMs) + " | ", GRAY),
					new Seg(FarmStats.label(e.getKey()), FarmStats.color(e.getKey())),
					new Seg(": " + v.count, WHITE),
					new Seg(" (" + (total <= 0 ? "0%" : String.format("%.1f%%", v.count * 100.0 / total)) + ")", GRAY)));
		}
		double hours = st.ticks / 20.0 / 3600.0;
		long perHour = hours > 0.01 ? Math.round(total / hours) : 0;
		out.add(List.of(new Seg("총 수확: ", GOLD), new Seg(total + "", WHITE), new Seg(" [" + perHour + "/시간]", GRAY)));
		out.add(List.of(new Seg("바퀴: ", GOLD), new Seg(st.rounds + "", WHITE), new Seg("  보관: ", GOLD), new Seg(st.trips + "번", WHITE)));
		out.add(List.of(new Seg("농사 시간: ", GOLD), new Seg(time(st.ticks / 20), WHITE)));
		return out;
	}

	private static String ago(long ms) {
		long s = ms / 1000;
		if (s < 60) return s + "s";
		if (s < 3600) return String.format("%.1fm", s / 60.0);
		if (s < 86400) return String.format("%.1fh", s / 3600.0);
		return String.format("%.1fd", s / 86400.0);
	}

	private static String time(long s) {
		long h = s / 3600, m = (s % 3600) / 60;
		return h > 0 ? h + "h " + m + "m" : m + "m " + (s % 60) + "s";
	}
}
