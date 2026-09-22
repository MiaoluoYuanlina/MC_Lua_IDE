package wiki.ica.xiaomiao.mcluaide.client;

/** Placement of an IDE tool window relative to the code editor. */
enum DockSide {
	FLOATING,
	LEFT,
	RIGHT,
	BOTTOM;

	boolean docked() {
		return this != FLOATING;
	}

	static DockSide atEdge(double mouseX, double mouseY, int screenWidth, int screenHeight) {
		int threshold = Math.max(24, Math.min(42, Math.min(screenWidth, screenHeight) / 14));
		if (mouseY >= screenHeight - threshold) return BOTTOM;
		if (mouseX <= threshold) return LEFT;
		if (mouseX >= screenWidth - threshold) return RIGHT;
		return FLOATING;
	}
}
