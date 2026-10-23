package com.seolwoni.farmassist.gui;

import com.seolwoni.assistcore.gui.PickerScreen;

import com.seolwoni.assistcore.gui.IntSlider;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.seolwoni.farmassist.config.FarmConfig;
import com.seolwoni.assistcore.storage.ChestEntry;
import com.seolwoni.farmassist.config.FarmConfig.FieldEntry;
import com.seolwoni.farmassist.farming.Farmer;
import com.seolwoni.farmassist.hud.FarmStats;
import com.seolwoni.assistcore.hud.HudEditScreen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Farm Assist settings (opened from Mod Menu). Start/stop at the very top. */
public class FarmConfigScreen extends Screen {
	private static final String[] TABS = {"기본", "밭", "작물", "보관", "안전·음식", "HUD"};
	private static final List<String> ITEM_PRESETS = List.of(
			"minecraft:wheat", "minecraft:wheat_seeds", "minecraft:carrot", "minecraft:potato", "minecraft:poisonous_potato",
			"minecraft:beetroot", "minecraft:beetroot_seeds", "minecraft:nether_wart", "minecraft:sugar_cane",
			"minecraft:bamboo", "minecraft:pumpkin", "minecraft:melon_slice", "minecraft:cocoa_beans", "minecraft:cactus",
			"minecraft:sweet_berries");
	private static final int ROW = 24;
	private static int tab = 0;

	private final Screen parent;
	private final List<Object[]> labels = new ArrayList<>();
	private boolean confirmFields, confirmChests, confirmStats;

	public FarmConfigScreen(Screen parent) {
		super(Component.literal("Farm Assist 설정"));
		this.parent = parent;
	}

	private FarmConfig c() {
		return FarmConfig.get();
	}

	@Override
	protected void init() {
		labels.clear();
		int cx = this.width / 2, left = cx - 155, right = cx + 5;

		addRenderableWidget(Button.builder(toggleText("모드 전체", c().modEnabled), b -> {
			c().modEnabled = !c().modEnabled;
			if (!c().modEnabled && c().running) Farmer.stop("모드를 껐어요", null, false);
			FarmConfig.save();
			rebuildWidgets();
		}).bounds(left, 22, 100, 20).build());
		Button run = Button.builder(Component.literal(c().running ? "농사 정지" : "농사 시작").withColor(c().running ? 0xFFFF7777 : 0xFF77FF77), b -> {
			if (c().running) {
				Farmer.stop("농사를 멈췄어요", null, false);
				rebuildWidgets();
			} else if (Farmer.start(this.minecraft)) {
				this.minecraft.gui.setScreen(null);
			}
		}).bounds(left + 105, 22, 100, 20).build();
		run.active = c().modEnabled && this.minecraft.player != null;
		addRenderableWidget(run);

		int tw = (310 - 2 * 2) / 3;
		for (int i = 0; i < TABS.length; i++) {
			final int idx = i;
			Button tb = Button.builder(Component.literal(TABS[i]), b -> {
				tab = idx;
				rebuildWidgets();
			}).bounds(left + (i % 3) * (tw + 2), i < 3 ? 46 : 68, tw, 20).build();
			tb.active = i != tab;
			addRenderableWidget(tb);
		}

		int y = 96;
		switch (tab) {
			case 0 -> basic(left, right, y);
			case 1 -> fields(left, right, y);
			case 2 -> crops(left, right, y);
			case 3 -> storage(left, right, y);
			case 4 -> safety(left, right, y);
			default -> hud(left, right, y);
		}
		// The worker's journal (Assist Core): this mod's jobs, newest first.
		addRenderableWidget(Button.builder(Component.literal("일지"), b -> this.minecraft.gui.setScreen(new com.seolwoni.assistcore.gui.JournalScreen(this, "farm")))
				.bounds(left + 210, 22, 48, 20).build());
		addRenderableWidget(Button.builder(Component.literal("완료"), b -> onClose())
				.bounds(left + 262, 22, 48, 20).build()); // up top: at the bottom it covered the last rows on small screens
	}

	private void basic(int left, int right, int y) {
		FarmConfig c = c();
		slider(left, y, 150, "바퀴 사이 대기", "분", 0, 60, c.waitMinutes, v -> c.waitMinutes = v);
		slider(right, y, 150, "남길 씨앗", "개", 0, 256, c.seedKeep, v -> c.seedKeep = v);
		y += ROW;
		toggle(left, y, 150, "사람 같은 딜레이", () -> c.humanDelay, v -> c.humanDelay = v);
		y += ROW + 4;
		note("밭을 한 바퀴 돌며 다 자란 것만 수확하고 다시 심어요. 끝나면 상자에 넣고 대기해요.", y);
		note("다시 심을 씨앗은 종류별로 '남길 씨앗'만큼 남기고, 모자라면 상자에서 꺼내와요.", y + 12);
	}

	private void fields(int left, int right, int y) {
		FarmConfig c = c();
		addRenderableWidget(Button.builder(Component.literal(confirmFields ? "한 번 더 누르면 초기화" : "밭 전체 초기화"), b -> {
			if (!confirmFields) {
				confirmFields = true;
				rebuildWidgets();
				return;
			}
			confirmFields = false;
			c.fields.clear();
			FarmConfig.save();
			rebuildWidgets();
		}).bounds(left, y, 310, 20).build());
		y += ROW;
		if (c.fields.isEmpty()) note("괭이를 들고 밭의 두 모서리를 Shift + 우클릭하세요", y + 6);
		int max = Math.max(1, (this.height - y - 40) / 22);
		for (int i = 0; i < Math.min(c.fields.size(), max); i++) {
			FieldEntry f = c.fields.get(i);
			labels.add(new Object[] {f.name + "  " + f.sizeX() + "x" + f.sizeZ() + "  (" + f.minX() + ", " + f.y + ", " + f.minZ() + ")",
					left, y + 6, 0xFFFFFFFF, false});
			addRenderableWidget(Button.builder(Component.literal("삭제"), b -> {
				c.fields.remove(f);
				FarmConfig.save();
				rebuildWidgets();
			}).bounds(left + 260, y, 50, 20).build());
			y += 22;
		}
	}

	private void crops(int left, int right, int y) {
		FarmConfig c = c();
		for (int i = 0; i < FarmConfig.CROPS.size(); i++) {
			String crop = FarmConfig.CROPS.get(i);
			int x = i % 2 == 0 ? left : right;
			toggle(x, y + (i / 2) * 22, 150, FarmStats.label(crop), () -> c.crops.contains(crop), v -> {
				if (v && !c.crops.contains(crop)) c.crops.add(crop);
				if (!v) c.crops.remove(crop);
			});
		}
	}

	private void storage(int left, int right, int y) {
		FarmConfig c = c();
		toggle(left, y, 150, "보관하기", () -> c.storeEnabled, v -> c.storeEnabled = v);
		addRenderableWidget(Button.builder(Component.literal(confirmChests ? "한 번 더 누르면 초기화" : "상자 전체 초기화"), b -> {
			if (!confirmChests) {
				confirmChests = true;
				rebuildWidgets();
				return;
			}
			confirmChests = false;
			c.chests.clear();
			FarmConfig.save();
			rebuildWidgets();
		}).bounds(right, y, 150, 20).build());
		y += ROW;
		addRenderableWidget(Button.builder(Component.literal("보관 방식: " + com.seolwoni.assistcore.storage.PortableStorage.LABELS[Math.max(0, Math.min(3, c.storeMode))]), b -> {
			c.storeMode = (c.storeMode + 1) % 4;
			FarmConfig.save();
			rebuildWidgets();
		}).bounds(left, y, 150, 20).build());
		labels.add(new Object[] {switch (c.storeMode) {
			case 1 -> "엔더 상자를 그 자리에 놓고 담아요 (실크터치로 회수)";
			case 2 -> "셜커 상자를 그 자리에 놓고 담은 뒤 다시 주워요";
			case 3 -> "엔더(실크터치) → 셜커 → 지정한 상자 순서";
			default -> "지정한 상자까지 걸어가서 담아요";
		}, right, y + 6, 0xFFAAAAAA, false});
		y += ROW;
		if (c.chests.isEmpty()) note(c.storeMode == 0 ? "상자를 바라보고 K로 지정하세요 (최대 10개)" : "상자도 지정해 두면 들고 다니는 보관함이 없을 때 써요", y + 6);
		int max = Math.max(1, (this.height - y - 40) / 22);
		int cx = this.width / 2;
		for (int i = 0; i < Math.min(c.chests.size(), max); i++) {
			ChestEntry e = c.chests.get(i);
			int idx = i;
			labels.add(new Object[] {(i + 1) + "번  " + e.x + ", " + e.y + ", " + e.z, left, y + 6, 0xFFFFFFFF, false});
			String what = e.items.isEmpty() ? "나머지 전부" : e.items.size() + "종류";
			addRenderableWidget(Button.builder(Component.literal("담을 것: " + what), b ->
					this.minecraft.gui.setScreen(new PickerScreen(this, (idx + 1) + "번 상자에 담을 아이템", e.items, false, ITEM_PRESETS,
							"아무것도 고르지 않으면 나머지를 차례대로 담아요", FarmConfig::save))).bounds(cx - 20, y, 120, 20).build());
			addRenderableWidget(Button.builder(Component.literal("삭제"), b -> {
				c.chests.remove(e);
				FarmConfig.save();
				rebuildWidgets();
			}).bounds(cx + 105, y, 50, 20).build());
			y += 22;
		}
	}

	private void safety(int left, int right, int y) {
		FarmConfig c = c();
		addRenderableWidget(net.minecraft.client.gui.components.Button.builder(net.minecraft.network.chat.Component.literal("몹: " + new String[] {"피하기", "피하고 싸우기", "멈추기", "무시"}[Math.max(0, Math.min(3, c.mobMode))]), b -> {
			c.mobMode = (c.mobMode + 1) % 4;
			c.stopOnMob = c.mobMode == 2;
			com.seolwoni.farmassist.config.FarmConfig.save();
			rebuildWidgets();
		}).bounds(left, y, 150, 20).build());
		slider(right, y, 150, "감지 거리", "칸", 3, 24, c.mobRange, v -> c.mobRange = v);
		y += ROW;
		slider(left, y, 150, "체력 이하면 정지", "", 1, 19, c.minHealth, v -> c.minHealth = v);
		y += ROW;
		toggle(left, y, 150, "자동 밥", () -> c.autoEat, v -> c.autoEat = v);
		slider(right, y, 150, "배고픔 기준", "/20", 1, 19, c.eatThreshold, v -> c.eatThreshold = v);
	}

	private void hud(int left, int right, int y) {
		FarmConfig c = c();
		addRenderableWidget(Button.builder(Component.literal("HUD 위치 편집"),
				b -> this.minecraft.gui.setScreen(new HudEditScreen(this))).bounds(left, y, 310, 20).build());
		y += ROW;
		toggle(left, y, 150, "상태 HUD", () -> c.statusHud, v -> c.statusHud = v);
		toggle(right, y, 150, "통계 HUD", () -> c.statsHud, v -> c.statsHud = v);
		y += ROW;
		slider(left, y, 150, "상태 크기", "%", 50, 300, Math.round(c.hudScale * 100), v -> c.hudScale = v / 100f);
		slider(right, y, 150, "통계 크기", "%", 50, 300, Math.round(c.statsScale * 100), v -> c.statsScale = v / 100f);
		y += ROW;
		slider(left, y, 150, "배경 투명도", "", 0, 255, c.hudBgAlpha, v -> c.hudBgAlpha = v);
		addRenderableWidget(Button.builder(Component.literal("통계 표시: " + (c.statsTotals ? "누적" : "이번 세션")), b -> {
			c.statsTotals = !c.statsTotals;
			FarmConfig.save();
			rebuildWidgets();
		}).bounds(right, y, 150, 20).build());
		y += ROW;
		addRenderableWidget(Button.builder(Component.literal("세션 통계 초기화"), b -> {
			FarmStats.session.reset();
			rebuildWidgets();
		}).bounds(left, y, 150, 20).build());
		addRenderableWidget(Button.builder(Component.literal(confirmStats ? "한 번 더 누르면 초기화" : "누적 통계 초기화"), b -> {
			if (!confirmStats) {
				confirmStats = true;
				rebuildWidgets();
				return;
			}
			confirmStats = false;
			FarmStats.total.reset();
			FarmStats.saveTotal();
			rebuildWidgets();
		}).bounds(right, y, 150, 20).build());
	}

	private void slider(int x, int y, int w, String label, String unit, int min, int max, int cur, Consumer<Integer> set) {
		addRenderableWidget(new IntSlider(x, y, w, label, unit, min, max, cur, v -> {
			set.accept(v);
			FarmConfig.save();
		}));
	}

	private void toggle(int x, int y, int w, String label, Supplier<Boolean> get, Consumer<Boolean> set) {
		addRenderableWidget(Button.builder(toggleText(label, get.get()), b -> {
			boolean v = !get.get();
			set.accept(v);
			FarmConfig.save();
			b.setMessage(toggleText(label, v));
		}).bounds(x, y, w, 20).build());
	}

	private static Component toggleText(String label, boolean on) {
		return Component.literal(label + ": ").append(on
				? Component.literal("켜짐").withColor(0xFF55FF55)
				: Component.literal("꺼짐").withColor(0xFFFF5555));
	}

	private void note(String text, int y) {
		labels.add(new Object[] {text, this.width / 2, y, 0xFFAAAAAA, true});
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		super.extractRenderState(g, mx, my, delta);
		g.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
		for (Object[] l : labels) {
			if ((boolean) l[4]) g.centeredText(this.font, Component.literal((String) l[0]), (int) l[1], (int) l[2], (int) l[3]);
			else g.text(this.font, Component.literal((String) l[0]).getVisualOrderText(), (int) l[1], (int) l[2], (int) l[3], true);
		}
	}

	@Override
	public void onClose() {
		FarmConfig.save();
		this.minecraft.gui.setScreen(parent);
	}
}
