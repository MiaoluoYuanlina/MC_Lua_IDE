package wiki.ica.xiaomiao.mcluaide.client;

import net.fabricmc.loader.api.FabricLoader;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Non-destructive in-memory PNG layers; the PNG stays flat and project layers live in client config. */
final class LuaPngLayerDocument {
	enum Tool { PENCIL, BRUSH, ERASER, FILL, PICKER }
	record Layer(String name, BufferedImage image, boolean visible, float opacity) {
		Layer(String name, BufferedImage image, boolean visible) { this(name, image, visible, 1f); }
	}
	record RecoveryImage(List<Layer> layers, int selected) { }
	private record Snapshot(List<Layer> layers, int selected) { }
	private static final int MAX_LAYERS = 16;
	private static final int MAX_UNDO = 24;
	private final Path png;
	private final Path projectFile;
	private int width, height;
	private final List<Layer> layers = new ArrayList<>();
	private final Deque<Snapshot> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
	private int selected;
	private boolean dirty;
	private long recoveryRevision;
	private boolean strokeChanged;
	private int previousX = -1, previousY = -1;

	LuaPngLayerDocument(Path png, BufferedImage initial) throws IOException {
		this(png, initial, projectPathFor(png));
	}

	LuaPngLayerDocument(Path png, RecoveryImage recovery) {
		this.png = png; this.projectFile = projectPathFor(png);
		restoreRecovery(recovery);
	}

	LuaPngLayerDocument(Path png, BufferedImage initial, Path projectFile) throws IOException {
		this.png = png;
		this.projectFile = projectFile;
		width = initial.getWidth(); height = initial.getHeight();
		layers.add(new Layer("背景", copy(initial), true));
		loadProjectIfCurrent();
	}

	int width() { return width; }
	int height() { return height; }
	List<Layer> layers() { return List.copyOf(layers); }
	int selected() { return selected; }
	boolean dirty() { return dirty; }
	long recoveryRevision() { return recoveryRevision; }
	private void changed() { dirty = true; recoveryRevision++; }
	RecoveryImage recoveryImage() {
		Snapshot snapshot = capture(); // detach mutable pixels before background encoding
		return new RecoveryImage(snapshot.layers(), snapshot.selected());
	}
	void restoreRecovery(RecoveryImage image) {
		layers.clear(); layers.addAll(image.layers()); selected = image.selected();
		width = layers.getFirst().image().getWidth(); height = layers.getFirst().image().getHeight();
		undo.clear(); redo.clear(); changed();
	}
	boolean canUndo() { return !undo.isEmpty(); }
	boolean canRedo() { return !redo.isEmpty(); }

	void beginStroke() { snapshot(); strokeChanged = false; previousX = previousY = -1; }

	void stroke(int x, int y, Tool tool, int argb, int radius) {
		stroke(x, y, tool, argb, radius, 1.0);
	}

	static int pressureSize(int size, double pressure) {
		return Math.max(1, (int) Math.round(Math.max(1, size) * Math.clamp(pressure, 0.05, 1.0)));
	}

	void stroke(int x, int y, Tool tool, int argb, int radius, double pressure) {
		if (tool == Tool.PICKER || tool == Tool.FILL) return;
		if (x < 0 || y < 0 || x >= width || y >= height) return;
		double strength = Math.clamp(pressure, 0.05, 1.0);
		int size = pressureSize(radius, strength);
		int steps = previousX < 0 ? 0 : Math.max(Math.abs(x - previousX), Math.abs(y - previousY));
		for (int step = 0; step <= steps; step++) {
			int px = steps == 0 ? x : previousX + (x - previousX) * step / steps;
			int py = steps == 0 ? y : previousY + (y - previousY) * step / steps;
			switch (tool) {
				case PENCIL -> stampPencil(px, py, argb, size);
				case BRUSH -> stampBrush(px, py, argb, size, strength);
				case ERASER -> stampEraser(px, py, size, strength);
				case FILL, PICKER -> { }
			}
		}
		previousX = x; previousY = y;
		strokeChanged = true; changed();
	}

	void endStroke() {
		previousX = previousY = -1;
		if (!strokeChanged && !undo.isEmpty()) undo.removeLast();
	}

	int pick(int x, int y) { return composite().getRGB(Math.clamp(x, 0, width - 1), Math.clamp(y, 0, height - 1)); }

	void fill(int x, int y, int color) {
		if (x < 0 || y < 0 || x >= width || y >= height) return;
		BufferedImage image = layers.get(selected).image();
		int old = image.getRGB(x, y);
		if (old == color) return;
		snapshot();
		boolean[] seen = new boolean[width * height];
		int[] queue = new int[width * height];
		int front = 0, back = 0;
		int start = y * width + x;
		queue[back++] = start;
		seen[start] = true;
		while (front < back) {
			int position = queue[front++];
			int px = position % width, py = position / width;
			if (image.getRGB(px, py) != old) continue;
			image.setRGB(px, py, color);
			if (px > 0 && !seen[position - 1]) { seen[position - 1] = true; queue[back++] = position - 1; }
			if (px + 1 < width && !seen[position + 1]) { seen[position + 1] = true; queue[back++] = position + 1; }
			if (py > 0 && !seen[position - width]) { seen[position - width] = true; queue[back++] = position - width; }
			if (py + 1 < height && !seen[position + width]) { seen[position + width] = true; queue[back++] = position + width; }
		}
		changed();
	}

	void addLayer() {
		if (layers.size() >= MAX_LAYERS) return;
		snapshot();
		selected++;
		layers.add(selected, new Layer("图层 " + (layers.size() + 1), blank(), true));
		changed();
	}

	void deleteLayer() {
		if (layers.size() <= 1) return;
		snapshot();
		layers.remove(selected);
		selected = Math.min(selected, layers.size() - 1);
		changed();
	}

	void select(int index) { if (index >= 0 && index < layers.size()) selected = index; }
	void toggleVisible(int index) {
		if (index < 0 || index >= layers.size()) return;
		snapshot();
		Layer old = layers.get(index);
		layers.set(index, new Layer(old.name(), old.image(), !old.visible(), old.opacity()));
		changed();
	}

	static float parseOpacity(String raw) {
		try { float value = Float.parseFloat(raw); return Float.isFinite(value) ? Math.clamp(value, 0f, 1f) : 1f; }
		catch (NumberFormatException ignored) { return 1f; }
	}

	void renameLayer(int index, String name) {
		if (index < 0 || index >= layers.size()) return;
		String clean = name == null ? "" : name.strip();
		if (clean.isEmpty()) clean = "图层 " + (index + 1);
		if (clean.length() > 48) clean = clean.substring(0, 48);
		Layer old = layers.get(index);
		if (old.name().equals(clean)) return;
		snapshot();
		layers.set(index, new Layer(clean, old.image(), old.visible(), old.opacity()));
		changed();
	}

	/** Pass beginChange=true on the first frame of a slider drag so the whole drag is one undo step. */
	void setOpacity(int index, float opacity, boolean beginChange) {
		if (index < 0 || index >= layers.size()) return;
		float clamped = Math.clamp(opacity, 0f, 1f);
		Layer old = layers.get(index);
		if (old.opacity() == clamped) return;
		if (beginChange) snapshot();
		layers.set(index, new Layer(old.name(), old.image(), old.visible(), clamped));
		changed();
	}

	void duplicateLayer() {
		if (layers.size() >= MAX_LAYERS) return;
		snapshot();
		Layer source = layers.get(selected);
		selected++;
		layers.add(selected, new Layer(source.name() + " 副本", copy(source.image()), source.visible(), source.opacity()));
		changed();
	}

	/** Flattens the selected layer onto the one below it, honouring both opacities. */
	void mergeDown() {
		if (selected <= 0) return;
		snapshot();
		Layer top = layers.get(selected), bottom = layers.get(selected - 1);
		BufferedImage merged = copy(bottom.image());
		Graphics2D graphics = merged.createGraphics();
		try {
			graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, top.visible() ? Math.clamp(top.opacity(), 0f, 1f) : 0f));
			graphics.drawImage(top.image(), 0, 0, null);
		} finally { graphics.dispose(); }
		layers.remove(selected);
		selected--;
		layers.set(selected, new Layer(bottom.name(), merged, bottom.visible(), bottom.opacity()));
		changed();
	}

	void moveLayer(int delta) {
		int target = selected + delta;
		if (target < 0 || target >= layers.size()) return;
		snapshot();
		Layer moving = layers.remove(selected);
		layers.add(target, moving);
		selected = target;
		changed();
	}

	void resizeCanvas(int newWidth, int newHeight) {
		if (newWidth < 1 || newHeight < 1 || newWidth > 2048 || newHeight > 2048
				|| (long) newWidth * newHeight > 1_048_576)
			throw new IllegalArgumentException("分辨率不能超过 2048 边长或 1048576 像素");
		if (newWidth == width && newHeight == height) return;
		List<Layer> resized = new ArrayList<>(layers.size());
		for (Layer layer : layers) {
			BufferedImage image = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
			Graphics2D graphics = image.createGraphics();
			try {
				graphics.setComposite(AlphaComposite.Src);
				graphics.drawImage(layer.image(), 0, 0, null);
			} finally { graphics.dispose(); }
			resized.add(new Layer(layer.name(), image, layer.visible(), layer.opacity()));
		}
		layers.clear(); layers.addAll(resized);
		width = newWidth; height = newHeight;
		undo.clear(); redo.clear();
		changed();
	}

	void undo() {
		if (undo.isEmpty()) return;
		redo.addLast(capture());
		restore(undo.removeLast());
		changed();
	}

	void redo() {
		if (redo.isEmpty()) return;
		undo.addLast(capture());
		restore(redo.removeLast());
		changed();
	}

	BufferedImage composite() {
		BufferedImage result = blank();
		Graphics2D graphics = result.createGraphics();
		try {
			graphics.setComposite(AlphaComposite.SrcOver);
			for (Layer layer : layers) if (layer.visible() && layer.opacity() > 0f) {
					graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.clamp(layer.opacity(), 0f, 1f)));
					graphics.drawImage(layer.image(), 0, 0, null);
				}
		} finally { graphics.dispose(); }
		return result;
	}

	void save(String logicalPath) throws IOException {
		LuaResourcePackWorkspace.savePng(logicalPath, composite());
		saveProject();
		dirty = false;
	}

	void saveProject() throws IOException { writeProject(); }

	private void stampPencil(int x, int y, int color, int size) {
		BufferedImage image = layers.get(selected).image();
		int left = x - size / 2, top = y - size / 2;
		for (int iy = 0; iy < size; iy++) for (int ix = 0; ix < size; ix++) {
			int px = left + ix, py = top + iy;
			if (px >= 0 && px < width && py >= 0 && py < height) image.setRGB(px, py, color);
		}
	}

	private void stampBrush(int x, int y, int color, int size, double strength) {
		BufferedImage image = layers.get(selected).image();
		if (size == 1) { image.setRGB(x, y, blendOver(image.getRGB(x, y), color, strength)); return; }
		double center = (size - 1) / 2.0;
		double radius = size / 2.0;
		double innerRadius = radius * 0.62;
		for (int iy = 0; iy < size; iy++) for (int ix = 0; ix < size; ix++) {
			int px = x + ix - size / 2, py = y + iy - size / 2;
			if (px < 0 || px >= width || py < 0 || py >= height) continue;
			double distance = Math.hypot(ix - center, iy - center);
			double coverage = Math.clamp((radius - distance) / (radius - innerRadius), 0.0, 1.0);
			if (coverage > 0) image.setRGB(px, py, blendOver(image.getRGB(px, py), color, coverage * strength));
		}
	}

	private void stampEraser(int x, int y, int size, double strength) {
		BufferedImage image = layers.get(selected).image();
		double center = (size - 1) / 2.0;
		double radiusSquared = size * size / 4.0;
		for (int iy = 0; iy < size; iy++) for (int ix = 0; ix < size; ix++) {
			int px = x + ix - size / 2, py = y + iy - size / 2;
			if (px < 0 || px >= width || py < 0 || py >= height) continue;
			double dx = ix - center, dy = iy - center;
			if (dx * dx + dy * dy <= radiusSquared) {
				int original = image.getRGB(px, py);
				int alpha = (int) Math.round((original >>> 24) * (1.0 - strength));
				image.setRGB(px, py, alpha == 0 ? 0 : (alpha << 24) | (original & 0xFFFFFF));
			}
		}
	}

	private static int blendOver(int destination, int source, double coverage) {
		double sourceAlpha = (source >>> 24) / 255.0 * coverage;
		double destinationAlpha = (destination >>> 24) / 255.0;
		double destinationContribution = destinationAlpha * (1 - sourceAlpha);
		double outputAlpha = sourceAlpha + destinationContribution;
		if (outputAlpha <= 0) return destination;
		int red = blendChannel(destination, source, 16, sourceAlpha, destinationContribution, outputAlpha);
		int green = blendChannel(destination, source, 8, sourceAlpha, destinationContribution, outputAlpha);
		int blue = blendChannel(destination, source, 0, sourceAlpha, destinationContribution, outputAlpha);
		return Math.round((float) (outputAlpha * 255)) << 24 | red << 16 | green << 8 | blue;
	}

	private static int blendChannel(int destination, int source, int shift,
			double sourceAlpha, double destinationContribution, double outputAlpha) {
		int sourceChannel = (source >>> shift) & 255;
		int destinationChannel = (destination >>> shift) & 255;
		return Math.clamp((int) Math.round((sourceChannel * sourceAlpha
				+ destinationChannel * destinationContribution) / outputAlpha), 0, 255);
	}

	private BufferedImage blank() { return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB); }
	private static BufferedImage copy(BufferedImage source) {
		BufferedImage image = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try { graphics.setComposite(AlphaComposite.Src); graphics.drawImage(source, 0, 0, null); }
		finally { graphics.dispose(); }
		return image;
	}

	private void snapshot() {
		undo.addLast(capture());
		long bytesPerSnapshot = Math.max(1L, (long) width * height * 4 * layers.size());
		int limit = (int) Math.min(MAX_UNDO, Math.max(2L, (128L * 1024 * 1024) / bytesPerSnapshot));
		while (undo.size() > limit) undo.removeFirst();
		redo.clear();
	}

	private Snapshot capture() {
		return new Snapshot(layers.stream().map(layer -> new Layer(layer.name(), copy(layer.image()), layer.visible(), layer.opacity())).toList(), selected);
	}

	private void restore(Snapshot snapshot) {
		layers.clear(); layers.addAll(snapshot.layers()); selected = snapshot.selected();
	}

	private static Path projectPathFor(Path png) {
		byte[] bytes;
		try { bytes = MessageDigest.getInstance("SHA-256").digest(png.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
		catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
		String key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		return FabricLoader.getInstance().getConfigDir().resolve("mc_lua_ide/resource_layer_projects")
				.resolve(key + ".zip");
	}

	private void writeProject() throws IOException {
		Path destination = projectFile;
		Files.createDirectories(destination.getParent());
		Path temporary = Files.createTempFile(destination.getParent(), ".layers_", ".tmp");
		try {
			Properties properties = new Properties();
			properties.setProperty("pngHash", hash(Files.readAllBytes(png)));
			properties.setProperty("layerCount", Integer.toString(layers.size()));
			properties.setProperty("selected", Integer.toString(selected));
			try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
				for (int index = 0; index < layers.size(); index++) {
					Layer layer = layers.get(index);
					properties.setProperty("layer." + index + ".name", layer.name());
					properties.setProperty("layer." + index + ".visible", Boolean.toString(layer.visible()));
					properties.setProperty("layer." + index + ".opacity", Float.toString(layer.opacity()));
					ByteArrayOutputStream encoded = new ByteArrayOutputStream();
					if (!ImageIO.write(layer.image(), "PNG", encoded)) throw new IOException("图层 PNG 编码不可用");
					zip.putNextEntry(new ZipEntry("layer_" + index + ".png"));
					zip.write(encoded.toByteArray());
					zip.closeEntry();
				}
				zip.putNextEntry(new ZipEntry("project.properties"));
				properties.store(zip, "MC Lua IDE image layers");
				zip.closeEntry();
			}
			Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
		} finally { Files.deleteIfExists(temporary); }
	}

	private void loadProjectIfCurrent() throws IOException {
		Path project = projectFile;
		if (!Files.isRegularFile(project) || Files.size(project) > 32L * 1024 * 1024) return;
		Properties properties = new Properties();
		List<BufferedImage> images = new ArrayList<>();
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(project))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				byte[] buffer = new byte[8192];
				int count;
				while ((count = zip.read(buffer)) != -1) {
					bytes.write(buffer, 0, count);
					if (bytes.size() > 16 * 1024 * 1024) return;
				}
				if (entry.getName().equals("project.properties")) properties.load(new ByteArrayInputStream(bytes.toByteArray()));
				else if (entry.getName().matches("layer_[0-9]{1,2}\\.png") && images.size() < MAX_LAYERS)
					images.add(ImageIO.read(new ByteArrayInputStream(bytes.toByteArray())));
				zip.closeEntry();
			}
		} catch (RuntimeException | IOException ignored) { return; }
		if (!hash(Files.readAllBytes(png)).equals(properties.getProperty("pngHash"))) return;
		int count;
		try { count = Integer.parseInt(properties.getProperty("layerCount", "0")); }
		catch (NumberFormatException ignored) { return; }
		if (count < 1 || count > MAX_LAYERS || images.size() != count
				|| images.stream().anyMatch(image -> image == null || image.getWidth() != width || image.getHeight() != height)) return;
		layers.clear();
		for (int index = 0; index < count; index++) layers.add(new Layer(
				properties.getProperty("layer." + index + ".name", "图层 " + (index + 1)), images.get(index),
				Boolean.parseBoolean(properties.getProperty("layer." + index + ".visible", "true")),
					parseOpacity(properties.getProperty("layer." + index + ".opacity", "1"))));
		try { selected = Math.clamp(Integer.parseInt(properties.getProperty("selected", "0")), 0, count - 1); }
		catch (NumberFormatException ignored) { selected = 0; }
	}

	private static String hash(byte[] bytes) {
		try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes)); }
		catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
	}
}
