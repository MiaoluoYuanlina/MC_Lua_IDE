package wiki.ica.xiaomiao.mcluaide.client;

import java.util.Properties;

/** Floating bounds, independent of the temporary bounds of a docked/animated window. */
record WindowGeometry(int x, int y, int width, int height) {
	void write(Properties properties, String prefix) {
		properties.setProperty(prefix + ".x", Integer.toString(x));
		properties.setProperty(prefix + ".y", Integer.toString(y));
		properties.setProperty(prefix + ".width", Integer.toString(width));
		properties.setProperty(prefix + ".height", Integer.toString(height));
	}

	static WindowGeometry read(Properties properties, String prefix) {
		try {
			int x = Integer.parseInt(properties.getProperty(prefix + ".x"));
			int y = Integer.parseInt(properties.getProperty(prefix + ".y"));
			int width = Integer.parseInt(properties.getProperty(prefix + ".width"));
			int height = Integer.parseInt(properties.getProperty(prefix + ".height"));
			if (x < 0 || y < 0 || width <= 0 || height <= 0 || width > 32768 || height > 32768) return null;
			return new WindowGeometry(x, y, width, height);
		} catch (IllegalArgumentException ignored) { return null; }
	}
}
