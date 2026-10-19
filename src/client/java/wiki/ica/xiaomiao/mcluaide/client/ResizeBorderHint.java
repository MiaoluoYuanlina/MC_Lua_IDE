package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Draws the same resize feedback for every floating tool window. */
final class ResizeBorderHint {
	private ResizeBorderHint() { }

	static void render(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom,
			int edges, int color) {
		if ((edges & 1) != 0) graphics.fill(left - 1, top, left + 2, bottom, color);
		if ((edges & 2) != 0) graphics.fill(right - 2, top, right + 1, bottom, color);
		if ((edges & 4) != 0) graphics.fill(left, top - 1, right, top + 2, color);
		if ((edges & 8) != 0) graphics.fill(left, bottom - 2, right, bottom + 1, color);
	}
}
