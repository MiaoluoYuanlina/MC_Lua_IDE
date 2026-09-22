package wiki.ica.xiaomiao.mcluaide.client;

/** Small critically-damped style transition used by movable IDE tool windows. */
final class WindowBoundsAnimation {
	private static final float RESPONSE = 0.34F;
	private int targetX;
	private int targetY;
	private int targetWidth;
	private int targetHeight;
	private boolean active;

	void animateTo(int currentX, int currentY, int currentWidth, int currentHeight,
			int x, int y, int width, int height) {
		targetX = x;
		targetY = y;
		targetWidth = Math.max(1, width);
		targetHeight = Math.max(1, height);
		active = currentX != targetX || currentY != targetY
				|| currentWidth != targetWidth || currentHeight != targetHeight;
	}

	void snapTo(int x, int y, int width, int height) {
		targetX = x;
		targetY = y;
		targetWidth = Math.max(1, width);
		targetHeight = Math.max(1, height);
		active = false;
	}

	Bounds tick(int x, int y, int width, int height) {
		if (!active) return new Bounds(x, y, width, height, false);
		int nextX = approach(x, targetX);
		int nextY = approach(y, targetY);
		int nextWidth = approach(width, targetWidth);
		int nextHeight = approach(height, targetHeight);
		active = nextX != targetX || nextY != targetY
				|| nextWidth != targetWidth || nextHeight != targetHeight;
		return new Bounds(nextX, nextY, nextWidth, nextHeight, active);
	}

	boolean active() {
		return active;
	}

	private static int approach(int current, int target) {
		int difference = target - current;
		if (Math.abs(difference) <= 1) return target;
		int step = Math.max(1, Math.round(Math.abs(difference) * RESPONSE));
		return current + Integer.signum(difference) * Math.min(Math.abs(difference), step);
	}

	record Bounds(int x, int y, int width, int height, boolean moving) {
	}
}
