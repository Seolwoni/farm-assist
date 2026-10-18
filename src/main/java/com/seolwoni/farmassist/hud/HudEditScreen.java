package com.seolwoni.farmassist.hud;

import com.seolwoni.farmassist.config.FarmConfig;
import com.seolwoni.farmassist.hud.FarmHud.Box;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Drag HUD boxes to move them; scroll over a box or drag its yellow corner to resize it. */
public class HudEditScreen extends Screen {
	private static final int HANDLE = 6;
	private final Screen parent;
	private Box dragging, resizing;
	private double grabDx, grabDy;

	public HudEditScreen(Screen parent) {
		super(Component.literal("HUD 위치 편집"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.literal("완료"), b -> onClose())
				.bounds(this.width / 2 - 50, this.height - 28, 100, 20).build());
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		super.extractRenderState(g, mx, my, delta);
		for (Box box : Box.values()) {
			if (!FarmHud.enabled(box)) continue;
			FarmHud.draw(g, box, true);
			float[] b = FarmHud.bounds(box);
			int hx = (int) (b[0] + b[2]) - HANDLE, hy = (int) (b[1] + b[3]) - HANDLE;
			g.fill(hx, hy, hx + HANDLE, hy + HANDLE, 0xFFFFFF55);
			g.text(this.font, Component.literal(Math.round(FarmHud.scale(box) * 100) + "%").getVisualOrderText(),
					(int) b[0], (int) (b[1] + b[3]) + 2, 0xFFFFFF55, true);
		}
		g.centeredText(this.font, Component.literal("드래그로 이동 · 노란 칸을 끌거나 휠로 크기 조절"), this.width / 2, 8, 0xFFFFFFFF);
	}

	private static Box hit(double mx, double my) {
		for (Box box : new Box[] {Box.STATUS, Box.STATS}) {
			if (!FarmHud.enabled(box)) continue;
			float[] b = FarmHud.bounds(box);
			if (mx >= b[0] && mx <= b[0] + b[2] && my >= b[1] && my <= b[1] + b[3]) return box;
		}
		return null;
	}

	private static Box handle(double mx, double my) {
		for (Box box : Box.values()) {
			if (!FarmHud.enabled(box)) continue;
			float[] b = FarmHud.bounds(box);
			double hx = b[0] + b[2], hy = b[1] + b[3];
			if (mx >= hx - HANDLE - 2 && mx <= hx + 2 && my >= hy - HANDLE - 2 && my <= hy + 2) return box;
		}
		return null;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
		if (super.mouseClicked(e, dbl)) return true;
		Box h = handle(e.x(), e.y());
		if (h != null) {
			resizing = h;
			return true;
		}
		Box b = hit(e.x(), e.y());
		if (b != null) {
			float[] r = FarmHud.bounds(b);
			dragging = b;
			grabDx = e.x() - r[0];
			grabDy = e.y() - r[1];
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (resizing != null) {
			float[] b = FarmHud.bounds(resizing);
			int base = FarmHud.baseWidth(resizing);
			if (base > 0) FarmHud.setScale(resizing, (float) ((e.x() - b[0]) / base));
			return true;
		}
		if (dragging != null) {
			FarmHud.setPos(dragging,
					(float) Math.max(0, Math.min(1, (e.x() - grabDx) / this.width)),
					(float) Math.max(0, Math.min(1, (e.y() - grabDy) / this.height)));
			return true;
		}
		return super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		if (dragging != null || resizing != null) {
			dragging = resizing = null;
			FarmConfig.save();
			return true;
		}
		return super.mouseReleased(e);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		Box b = hit(mx, my);
		if (b != null) {
			FarmHud.setScale(b, FarmHud.scale(b) + (sy > 0 ? 0.1f : -0.1f));
			FarmConfig.save();
			return true;
		}
		return super.mouseScrolled(mx, my, sx, sy);
	}

	@Override
	public void onClose() {
		FarmConfig.save();
		this.minecraft.gui.setScreen(parent);
	}
}
