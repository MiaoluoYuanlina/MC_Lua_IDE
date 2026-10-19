package wiki.ica.xiaomiao.mcluaide.client;

import java.util.Locale;

/** Full 24-bit RGB; HSV controls are a view over exact RGB channels. */
final class LuaColorModel {
	enum Format { HEX("十六进制"), BYTE("RGB · 0～255"), UNIT("RGB · 0.00～1.00");
		final String label; Format(String label) { this.label = label; } }
	float hue, saturation = 1, brightness = 1;
	Format format = Format.HEX;
	boolean hexNumber;
	int rgb() { return java.awt.Color.HSBtoRGB(hue, saturation, brightness) & 0xFFFFFF; }
	int channel(int index) { return rgb() >>> (16 - index * 8) & 255; }
	void rgb(int color) {
		float[] hsv = java.awt.Color.RGBtoHSB(color >>> 16 & 255, color >>> 8 & 255, color & 255, null);
		if (hsv[1] > 0 && hsv[2] > 0) hue = hsv[0];
		saturation = hsv[1]; brightness = hsv[2];
	}
	void channel(int index, int value) {
		int shift = 16 - index * 8;
		rgb(rgb() & ~(255 << shift) | Math.clamp(value, 0, 255) << shift);
	}
	void hue(float value) { hue = Math.clamp(value, 0, 1); }
	void square(float s, float v) { saturation = Math.clamp(s, 0, 1); brightness = Math.clamp(v, 0, 1); }
	String text() {
		return switch (format) {
			case HEX -> String.format(Locale.ROOT, hexNumber ? "0x%06X" : "#%06X", rgb());
			case BYTE -> channel(0) + ", " + channel(1) + ", " + channel(2);
			case UNIT -> String.format(Locale.ROOT, "%.2f, %.2f, %.2f", channel(0) / 255D, channel(1) / 255D, channel(2) / 255D);
		};
	}
	boolean stringOutput() { return format == Format.HEX && !hexNumber; }
}
