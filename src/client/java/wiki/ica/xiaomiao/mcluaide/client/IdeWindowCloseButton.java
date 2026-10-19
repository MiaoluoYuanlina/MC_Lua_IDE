package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Standard right-header close affordance, matched by drawing and pointer hit testing. */
final class IdeWindowCloseButton {
	private IdeWindowCloseButton() { }
	static boolean contains(double x, double y, int right, int top) {
		return x >= right - 26 && x < right - 6 && y >= top + 4 && y < top + 24;
	}
	static void render(GuiGraphicsExtractor graphics, Font font, int right, int top, int mouseX, int mouseY) {
		graphics.fill(right - 26, top + 4, right - 6, top + 24, contains(mouseX, mouseY, right, top) ? 0xFFB83A46 : 0xFF394858);
		graphics.centeredText(font, "×", right - 16, top + 10, 0xFFFFFFFF);
	}
}
