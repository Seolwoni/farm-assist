package com.seolwoni.farmassist.hud;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.seolwoni.assistcore.hud.AssistHud;
import com.seolwoni.assistcore.hud.HudBox;
import com.seolwoni.assistcore.hud.HudBox.Seg;
import com.seolwoni.farmassist.config.FarmConfig;
import com.seolwoni.farmassist.farming.Farmer;
import com.seolwoni.farmassist.farming.Field;

/** Two HUD boxes: status and harvest statistics. Drawing, moving and hiding come from Assist Core. */
public final class FarmHud {
	private static final int GOLD = HudBox.GOLD, WHITE = HudBox.WHITE, GRAY = HudBox.GRAY, GREEN = HudBox.GREEN, RED = HudBox.RED;

	public static final HudBox STATUS = new HudBox() {
		@Override
		public boolean enabled() {
			return FarmConfig.get().modEnabled && FarmConfig.get().statusHud;
		}

		@Override
		public List<List<Seg>> lines() {
			return statusLines();
		}

		@Override
		public float x() {
			return FarmConfig.get().hudX;
		}

		@Override
		public float y() {
			return FarmConfig.get().hudY;
		}

		@Override
		public float scale() {
			return FarmConfig.get().hudScale;
		}

		@Override
		public void setPos(float x, float y) {
			FarmConfig.get().hudX = x;
			FarmConfig.get().hudY = y;
		}

		@Override
		protected void storeScale(float s) {
			FarmConfig.get().hudScale = s;
		}

		@Override
		public int bgAlpha() {
			return FarmConfig.get().hudBgAlpha;
		}

		@Override
		public void save() {
			FarmConfig.save();
		}
	};

	public static final HudBox STATS = new HudBox() {
		@Override
		public boolean enabled() {
			return FarmConfig.get().modEnabled && FarmConfig.get().statsHud;
		}

		@Override
		public List<List<Seg>> lines() {
			return statsLines();
		}

		@Override
		public float x() {
			return FarmConfig.get().statsX;
		}

		@Override
		public float y() {
			return FarmConfig.get().statsY;
		}

		@Override
		public float scale() {
			return FarmConfig.get().statsScale;
		}

		@Override
		public void setPos(float x, float y) {
			FarmConfig.get().statsX = x;
			FarmConfig.get().statsY = y;
		}

		@Override
		protected void storeScale(float s) {
			FarmConfig.get().statsScale = s;
		}

		@Override
		public int bgAlpha() {
			return FarmConfig.get().hudBgAlpha;
		}

		@Override
		public void save() {
			FarmConfig.save();
		}
	};

	private FarmHud() {
	}

	public static void register() {
		AssistHud.register(STATUS);
		AssistHud.register(STATS);
	}

	private static List<List<Seg>> statusLines() {
		FarmConfig cfg = FarmConfig.get();
		List<List<Seg>> out = new ArrayList<>();
		out.add(List.of(new Seg("Farm Assist", GOLD)));
		out.add(List.of(new Seg(com.seolwoni.assistcore.i18n.Tr.t("상태: "), GOLD), new Seg(Farmer.status(), cfg.running ? GREEN : RED)));
		out.add(List.of(new Seg(com.seolwoni.assistcore.i18n.Tr.t("밭: "), GOLD), new Seg(cfg.fields.size() + com.seolwoni.assistcore.i18n.Tr.t("개"), WHITE),
				new Seg(com.seolwoni.assistcore.i18n.Tr.t("  상자: "), GOLD), new Seg(cfg.chests.size() + com.seolwoni.assistcore.i18n.Tr.t("개"), WHITE)));
		if (cfg.running && Farmer.totalStops() > 0 && Farmer.waitSecondsLeft() < 0) {
			out.add(List.of(new Seg(com.seolwoni.assistcore.i18n.Tr.t("진행: "), GOLD), new Seg(Math.min(Farmer.progress(), Farmer.totalStops()) + "/" + Farmer.totalStops(), WHITE)));
		}
		if (Field.hasPending()) out.add(List.of(new Seg(com.seolwoni.assistcore.i18n.Tr.t("반대쪽 모서리를 지정하세요"), 0xFF55FFFF)));
		return out;
	}

	private static List<List<Seg>> statsLines() {
		FarmConfig cfg = FarmConfig.get();
		FarmStats st = FarmStats.shown();
		List<List<Seg>> out = new ArrayList<>();
		out.add(List.of(new Seg(com.seolwoni.assistcore.i18n.Tr.t("수확 통계 "), GOLD), new Seg(cfg.statsTotals ? com.seolwoni.assistcore.i18n.Tr.t("(누적)") : com.seolwoni.assistcore.i18n.Tr.t("(이번 세션)"), GRAY)));
		long total = st.totalHarvest();
		long now = System.currentTimeMillis();
		List<Map.Entry<String, FarmStats.Entry>> list = new ArrayList<>(st.crops.entrySet());
		list.sort(Comparator.comparingLong((Map.Entry<String, FarmStats.Entry> e) -> e.getValue().count).reversed());
		if (list.isEmpty()) out.add(List.of(new Seg(com.seolwoni.assistcore.i18n.Tr.t("  아직 수확한 게 없어요"), GRAY)));
		for (Map.Entry<String, FarmStats.Entry> e : list) {
			FarmStats.Entry v = e.getValue();
			out.add(List.of(new Seg(ago(now - v.lastMs) + " | ", GRAY),
					new Seg(FarmStats.label(e.getKey()), FarmStats.color(e.getKey())),
					new Seg(": " + v.count, WHITE),
					new Seg(" (" + (total <= 0 ? "0%" : String.format("%.1f%%", v.count * 100.0 / total)) + ")", GRAY)));
		}
		double hours = st.ticks / 20.0 / 3600.0;
		long perHour = hours > 0.01 ? Math.round(total / hours) : 0;
		out.add(List.of(new Seg(com.seolwoni.assistcore.i18n.Tr.t("총 수확: "), GOLD), new Seg(total + "", WHITE), new Seg(" [" + perHour + com.seolwoni.assistcore.i18n.Tr.t("/시간]"), GRAY)));
		out.add(List.of(new Seg(com.seolwoni.assistcore.i18n.Tr.t("바퀴: "), GOLD), new Seg(st.rounds + "", WHITE), new Seg(com.seolwoni.assistcore.i18n.Tr.t("  보관: "), GOLD), new Seg(st.trips + com.seolwoni.assistcore.i18n.Tr.t("번"), WHITE)));
		out.add(List.of(new Seg(com.seolwoni.assistcore.i18n.Tr.t("농사 시간: "), GOLD), new Seg(time(st.ticks / 20), WHITE)));
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
