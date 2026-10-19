package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import wiki.ica.xiaomiao.mcluaide.client.mixin.SpriteContentsAccessor;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Arrays;

/** Interactive, read-only voxel projection of vanilla structure-template NBT. */
final class LuaStructurePreview {
	private static final int IMAGE_SIZE = 512;
	private static final int MAX_BLOCKS = 25_000;
	private static final AtomicInteger NEXT_TEXTURE = new AtomicInteger();
	private static final int[][] NEIGHBORS = {
			{0, 1, 0}, {0, -1, 0}, {-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1}
	};
	private static final int[][][] FACES = {
			{{0, 1, 0}, {1, 1, 0}, {1, 1, 1}, {0, 1, 1}},
			{{0, 0, 1}, {1, 0, 1}, {1, 0, 0}, {0, 0, 0}},
			{{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}},
			{{1, 0, 1}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}},
			{{1, 0, 0}, {0, 0, 0}, {0, 1, 0}, {1, 1, 0}},
			{{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}}
	};
	private static final double[] SHADE = {1.12, 0.48, 0.70, 0.90, 0.78, 0.84};
	private final int sizeX, sizeY, sizeZ;
	private final List<Voxel> voxels;
	private final List<PaletteEntry> palette;
	private final int[] occupied;
	private double yaw = 0.72;
	private double pitch = 0.52;
	private double zoom = 1.0;
	private double lastMouseX, lastMouseY;
	private boolean dragging;
	private boolean dirty = true;
	private Identifier texture;
	private DynamicTexture dynamicTexture;
	private Object loadedModelSet;

	private LuaStructurePreview(int sizeX, int sizeY, int sizeZ,
			List<Voxel> voxels, List<PaletteEntry> palette, int[] occupied) {
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.voxels = List.copyOf(voxels);
		this.palette = List.copyOf(palette);
		this.occupied = occupied;
	}

	/** Returns null for ordinary NBT or structures too large for a responsive preview. */
	static LuaStructurePreview detect(CompoundTag root) {
		if (!root.contains("size") || !root.contains("blocks")) return null;
		ListTag size = root.getListOrEmpty("size");
		ListTag blocks = root.getListOrEmpty("blocks");
		ListTag palette = root.getListOrEmpty("palette");
		if (palette.isEmpty()) {
			ListTag palettes = root.getListOrEmpty("palettes");
			if (!palettes.isEmpty()) palette = palettes.getListOrEmpty(0);
		}
		if (size.size() != 3 || palette.isEmpty() || blocks.size() > MAX_BLOCKS) return null;
		int sx = size.getIntOr(0, 0), sy = size.getIntOr(1, 0), sz = size.getIntOr(2, 0);
		if (sx < 1 || sy < 1 || sz < 1 || sx > 128 || sy > 128 || sz > 128
				|| (long) sx * sy * sz > 2_000_000) return null;
		List<PaletteEntry> states = new ArrayList<>(palette.size());
		for (int index = 0; index < palette.size(); index++) {
			CompoundTag definition = palette.getCompoundOrEmpty(index);
			String id = definition.getStringOr("Name", "");
			if (Identifier.tryParse(id) == null) return null;
			states.add(new PaletteEntry(id, definition.getCompoundOrEmpty("Properties")));
		}
		int[] occupied = new int[sx * sy * sz];
		Arrays.fill(occupied, -1);
		List<Voxel> voxels = new ArrayList<>();
		for (int index = 0; index < blocks.size(); index++) {
			CompoundTag block = blocks.getCompoundOrEmpty(index);
			ListTag pos = block.getListOrEmpty("pos");
			int state = block.getIntOr("state", -1);
			if (pos.size() != 3 || state < 0 || state >= states.size()) return null;
			int x = pos.getIntOr(0, -1), y = pos.getIntOr(1, -1), z = pos.getIntOr(2, -1);
			if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return null;
			String id = states.get(state).id;
			if (id.endsWith(":air") || id.endsWith(":cave_air") || id.endsWith(":void_air")) continue;
			voxels.add(new Voxel(x, y, z, state));
			occupied[index(x, y, z, sx, sz)] = state;
		}
		return new LuaStructurePreview(sx, sy, sz, voxels, states, occupied);
	}

	String summary() {
		return "结构模板 · " + sizeX + "×" + sizeY + "×" + sizeZ + " · " + voxels.size() + " 个非空气方块";
	}

	void render(GuiGraphicsExtractor graphics, Minecraft minecraft,
			int left, int top, int right, int bottom) {
		int available = Math.min(right - left - 16, bottom - top - 30);
		if (available < 60) return;
		Object currentModelSet = minecraft.getModelManager().getBlockStateModelSet();
		if (loadedModelSet != currentModelSet) {
			loadedModelSet = currentModelSet;
			dirty = true;
		}
		if (dirty || texture == null) redraw(minecraft);
		int x = left + (right - left - available) / 2;
		int y = top + (bottom - top - available) / 2 - 7;
		graphics.enableScissor(left + 2, top, right - 2, bottom - 21);
		graphics.blit(texture, x, y, x + available, y + available, 0, 1, 0, 1);
		graphics.disableScissor();
		graphics.text(minecraft.font, "拖动旋转 · 滚轮缩放 · 右键重置",
				left + 12, bottom - 16, 0xFF9BB8C0);
		String zoomText = Math.round(zoom * 100) + "%";
		graphics.text(minecraft.font, zoomText, right - 12 - minecraft.font.width(zoomText),
				bottom - 16, 0xFF8CCFC6);
	}

	private void redraw(Minecraft minecraft) {
		BufferedImage image = renderTexturedFrame(minecraft);
		if (dynamicTexture == null) {
			NativeImage pixels = new NativeImage(IMAGE_SIZE, IMAGE_SIZE, true);
			dynamicTexture = new DynamicTexture(() -> "MC Lua IDE 结构预览", pixels);
			texture = Identifier.fromNamespaceAndPath("mc_lua_ide",
					"structure_preview/" + NEXT_TEXTURE.incrementAndGet());
			minecraft.getTextureManager().register(texture, dynamicTexture);
		}
		NativeImage pixels = dynamicTexture.getPixels();
		if (pixels != null) {
			for (int y = 0; y < IMAGE_SIZE; y++)
				for (int x = 0; x < IMAGE_SIZE; x++) pixels.setPixel(x, y, image.getRGB(x, y));
			dynamicTexture.upload();
		}
		dirty = false;
	}

	private BufferedImage renderTexturedFrame(Minecraft minecraft) {
		int[] pixels = new int[IMAGE_SIZE * IMAGE_SIZE];
		Arrays.fill(pixels, 0xFF101A22);
		double[] depths = new double[pixels.length];
		Arrays.fill(depths, Double.NEGATIVE_INFINITY);
		List<TexturedFace> faces = new ArrayList<>();
		BlockState[] states = new BlockState[palette.size()];
		for (int index = 0; index < palette.size(); index++) states[index] = stateFor(palette.get(index));
		double unit = Math.min(330.0 / Math.max(sizeX, sizeZ),
				340.0 / Math.max(1.0, sizeY + Math.max(sizeX, sizeZ) * 0.45)) * zoom;
		double cosYaw = Math.cos(yaw), sinYaw = Math.sin(yaw);
		double cosPitch = Math.cos(pitch), sinPitch = Math.sin(pitch);
		var modelSet = minecraft.getModelManager().getBlockStateModelSet();
		for (Voxel voxel : voxels) {
			BlockState state = states[voxel.stateIndex];
			var model = modelSet.get(state);
			List<BlockStateModelPart> parts = new ArrayList<>();
			model.collectParts(RandomSource.create(index(voxel.x, voxel.y, voxel.z, sizeX, sizeZ)), parts);
			for (BlockStateModelPart part : parts) {
				for (Direction direction : Direction.values()) {
					int nx = voxel.x + direction.getStepX();
					int ny = voxel.y + direction.getStepY();
					int nz = voxel.z + direction.getStepZ();
					if (inside(nx, ny, nz)) {
						int neighbor = occupied[index(nx, ny, nz, sizeX, sizeZ)];
						if (neighbor >= 0 && !Block.shouldRenderFace(state, states[neighbor], direction)) continue;
					}
					for (BakedQuad quad : part.getQuads(direction)) addTexturedFace(
							faces, quad, state, voxel, minecraft, unit,
							cosYaw, sinYaw, cosPitch, sinPitch);
				}
				for (BakedQuad quad : part.getQuads(null)) addTexturedFace(
						faces, quad, state, voxel, minecraft, unit,
						cosYaw, sinYaw, cosPitch, sinPitch);
			}
			if (faces.size() > 80_000) return renderFrame();
		}
		if (faces.isEmpty()) return renderFrame();
		faces.sort(Comparator.comparingDouble(TexturedFace::depth));
		for (TexturedFace face : faces) {
			drawTriangle(pixels, depths, face, 0, 1, 2);
			drawTriangle(pixels, depths, face, 0, 2, 3);
		}
		BufferedImage image = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, IMAGE_SIZE, IMAGE_SIZE, pixels, 0, IMAGE_SIZE);
		return image;
	}

	private void addTexturedFace(List<TexturedFace> faces, BakedQuad quad, BlockState state,
			Voxel voxel, Minecraft minecraft, double unit,
			double cosYaw, double sinYaw, double cosPitch, double sinPitch) {
		TextureAtlasSprite sprite = quad.materialInfo().sprite();
		if (sprite == null) return;
		NativeImage source = ((SpriteContentsAccessor) sprite.contents()).mcLuaIde$getOriginalImage();
		if (source == null || source.getWidth() < 1 || source.getHeight() < 1) return;
		int tint = 0xFFFFFF;
		if (quad.materialInfo().isTinted()) {
			var tintSource = minecraft.getBlockColors().getTintSource(state,
					quad.materialInfo().tintIndex());
			if (tintSource != null) {
				try { tint = tintSource.color(state) & 0xFFFFFF; }
				catch (RuntimeException ignored) { }
			}
		}
		double uRange = sprite.getU1() - sprite.getU0();
		double vRange = sprite.getV1() - sprite.getV0();
		if (uRange == 0 || vRange == 0) return;
		TexVertex[] vertices = new TexVertex[4];
		double depth = 0;
		for (int index = 0; index < 4; index++) {
			var position = quad.position(index);
			Point point = project(voxel.x + position.x(), voxel.y + position.y(),
					voxel.z + position.z(), unit, cosYaw, sinYaw, cosPitch, sinPitch);
			long packedUv = quad.packedUV(index);
			vertices[index] = new TexVertex(point.x, point.y, point.depth,
					(UVPair.unpackU(packedUv) - sprite.getU0()) / uRange,
					(UVPair.unpackV(packedUv) - sprite.getV0()) / vRange);
			depth += point.depth;
		}
		Direction facing = quad.direction();
		double light = !quad.materialInfo().shade() || facing == null ? 1.0 : switch (facing) {
			case UP -> 1.0;
			case DOWN -> 0.55;
			case NORTH, SOUTH -> 0.82;
			case EAST, WEST -> 0.70;
		};
		faces.add(new TexturedFace(vertices, source,
				Math.min(sprite.contents().width(), source.getWidth()),
				Math.min(sprite.contents().height(), source.getHeight()),
				tint, light, depth / 4.0));
	}

	private static void drawTriangle(int[] pixels, double[] depths, TexturedFace face,
			int first, int second, int third) {
		TexVertex a = face.vertices[first], b = face.vertices[second], c = face.vertices[third];
		double area = edge(a.x, a.y, b.x, b.y, c.x, c.y);
		if (Math.abs(area) < 0.0001) return;
		int left = Math.max(0, (int) Math.floor(Math.min(a.x, Math.min(b.x, c.x))));
		int right = Math.min(IMAGE_SIZE - 1, (int) Math.ceil(Math.max(a.x, Math.max(b.x, c.x))));
		int top = Math.max(0, (int) Math.floor(Math.min(a.y, Math.min(b.y, c.y))));
		int bottom = Math.min(IMAGE_SIZE - 1, (int) Math.ceil(Math.max(a.y, Math.max(b.y, c.y))));
		for (int y = top; y <= bottom; y++) {
			for (int x = left; x <= right; x++) {
				double px = x + 0.5, py = y + 0.5;
				double wa = edge(b.x, b.y, c.x, c.y, px, py) / area;
				double wb = edge(c.x, c.y, a.x, a.y, px, py) / area;
				double wc = 1.0 - wa - wb;
				if (wa < -0.00001 || wb < -0.00001 || wc < -0.00001) continue;
				int target = y * IMAGE_SIZE + x;
				double depth = wa * a.depth + wb * b.depth + wc * c.depth;
				if (depth <= depths[target] + 0.00001) continue;
				double u = wa * a.u + wb * b.u + wc * c.u;
				double v = wa * a.v + wb * b.v + wc * c.v;
				int tx = Math.max(0, Math.min(face.width - 1, (int) (u * face.width)));
				int ty = Math.max(0, Math.min(face.height - 1, (int) (v * face.height)));
				int texel = face.source.getPixel(tx, ty);
				int alpha = (texel >>> 24) & 255;
				if (alpha < 16) continue;
				int red = (int) ((((texel >>> 16) & 255) * ((face.tint >>> 16) & 255) / 255.0) * face.light);
				int green = (int) ((((texel >>> 8) & 255) * ((face.tint >>> 8) & 255) / 255.0) * face.light);
				int blue = (int) (((texel & 255) * (face.tint & 255) / 255.0) * face.light);
				int color = 0xFF000000 | (Math.min(255, red) << 16)
						| (Math.min(255, green) << 8) | Math.min(255, blue);
				pixels[target] = alpha >= 250 ? color : blend(color, pixels[target], alpha);
				depths[target] = depth;
			}
		}
	}

	private static double edge(double ax, double ay, double bx, double by, double px, double py) {
		return (px - ax) * (by - ay) - (py - ay) * (bx - ax);
	}

	private static int blend(int foreground, int background, int alpha) {
		int reverse = 255 - alpha;
		int red = (((foreground >>> 16) & 255) * alpha + ((background >>> 16) & 255) * reverse) / 255;
		int green = (((foreground >>> 8) & 255) * alpha + ((background >>> 8) & 255) * reverse) / 255;
		int blue = ((foreground & 255) * alpha + (background & 255) * reverse) / 255;
		return 0xFF000000 | (red << 16) | (green << 8) | blue;
	}

	private static BlockState stateFor(PaletteEntry entry) {
		Identifier id = Identifier.tryParse(entry.id);
		var block = id == null ? Blocks.BARRIER : BuiltInRegistries.BLOCK.getOptional(id).orElse(Blocks.BARRIER);
		BlockState state = block.defaultBlockState();
		for (String name : entry.properties.keySet()) {
			Property<?> property = block.getStateDefinition().getProperty(name);
			if (property == null) continue;
			try { state = applyProperty(state, property, entry.properties.getStringOr(name, "")); }
			catch (RuntimeException ignored) { }
		}
		return state;
	}

	private static <T extends Comparable<T>> BlockState applyProperty(
			BlockState state, Property<T> property, String value) {
		return property.getValue(value).map(parsed -> state.setValue(property, parsed)).orElse(state);
	}

	BufferedImage renderFrame() {
		BufferedImage image = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D canvas = image.createGraphics();
		try {
			canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			canvas.setColor(new Color(16, 26, 34));
			canvas.fillRect(0, 0, IMAGE_SIZE, IMAGE_SIZE);
			canvas.setStroke(new BasicStroke(0.8F));
			List<Face> faces = new ArrayList<>();
			Map<String, Integer> colors = new HashMap<>();
			double unit = Math.min(330.0 / Math.max(sizeX, sizeZ),
					340.0 / Math.max(1.0, sizeY + Math.max(sizeX, sizeZ) * 0.45)) * zoom;
			double cosYaw = Math.cos(yaw), sinYaw = Math.sin(yaw);
			double cosPitch = Math.cos(pitch), sinPitch = Math.sin(pitch);
			for (Voxel voxel : voxels) {
				String id = palette.get(voxel.stateIndex).id;
				int rgb = colors.computeIfAbsent(id, LuaStructurePreview::colorFor);
				for (int face = 0; face < FACES.length; face++) {
					int nx = voxel.x + NEIGHBORS[face][0];
					int ny = voxel.y + NEIGHBORS[face][1];
					int nz = voxel.z + NEIGHBORS[face][2];
					if (inside(nx, ny, nz) && occupied[index(nx, ny, nz, sizeX, sizeZ)] >= 0) continue;
					Polygon polygon = new Polygon();
					double depth = 0;
					for (int[] vertex : FACES[face]) {
						Point point = project(voxel.x + vertex[0], voxel.y + vertex[1],
								voxel.z + vertex[2], unit, cosYaw, sinYaw, cosPitch, sinPitch);
						polygon.addPoint((int) Math.round(point.x), (int) Math.round(point.y));
						depth += point.depth;
					}
					faces.add(new Face(polygon, depth / 4.0, shade(rgb, SHADE[face])));
				}
			}
			faces.sort(Comparator.comparingDouble(Face::depth));
			for (Face face : faces) {
				canvas.setColor(new Color(face.color));
				canvas.fillPolygon(face.polygon);
				canvas.setColor(new Color(shade(face.color, 0.56)));
				canvas.drawPolygon(face.polygon);
			}
		} finally {
			canvas.dispose();
		}
		return image;
	}

	private Point project(double x, double y, double z, double unit,
			double cosYaw, double sinYaw, double cosPitch, double sinPitch) {
		x -= sizeX / 2.0;
		y -= sizeY / 2.0;
		z -= sizeZ / 2.0;
		double rx = x * cosYaw - z * sinYaw;
		double rz = x * sinYaw + z * cosYaw;
		double ry = y * cosPitch - rz * sinPitch;
		return new Point(IMAGE_SIZE / 2.0 + rx * unit,
				IMAGE_SIZE / 2.0 - ry * unit, y * sinPitch + rz * cosPitch);
	}

	private boolean inside(int x, int y, int z) {
		return x >= 0 && y >= 0 && z >= 0 && x < sizeX && y < sizeY && z < sizeZ;
	}

	private static int index(int x, int y, int z, int sx, int sz) {
		return x + sx * (z + sz * y);
	}

	private static int colorFor(String name) {
		Identifier id = Identifier.tryParse(name);
		if (id != null) {
			try {
				var block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
				if (block != null) {
					int color = block.defaultBlockState().getMapColor(null, BlockPos.ZERO).col;
					if ((color & 0xFFFFFF) != 0) return color & 0xFFFFFF;
				}
			} catch (RuntimeException ignored) { }
		}
		float hue = ((name.hashCode() & 0x7FFFFFFF) % 360) / 360.0F;
		return Color.HSBtoRGB(hue, 0.45F, 0.73F) & 0xFFFFFF;
	}

	private static int shade(int rgb, double factor) {
		int r = Math.min(255, (int) Math.round(((rgb >> 16) & 255) * factor));
		int g = Math.min(255, (int) Math.round(((rgb >> 8) & 255) * factor));
		int b = Math.min(255, (int) Math.round((rgb & 255) * factor));
		return (r << 16) | (g << 8) | b;
	}

	void beginDrag(double x, double y) {
		dragging = true;
		lastMouseX = x;
		lastMouseY = y;
	}

	boolean drag(double x, double y) {
		if (!dragging) return false;
		yaw -= (x - lastMouseX) * 0.012;
		pitch = Math.max(-1.45, Math.min(1.45, pitch + (y - lastMouseY) * 0.010));
		lastMouseX = x;
		lastMouseY = y;
		dirty = true;
		return true;
	}

	boolean release() {
		boolean wasDragging = dragging;
		dragging = false;
		return wasDragging;
	}

	void zoom(double amount) {
		zoom = Math.max(0.18, Math.min(10.0, zoom * Math.pow(1.16, amount)));
		dirty = true;
	}

	void reset() {
		yaw = 0.72;
		pitch = 0.52;
		zoom = 1.0;
		dirty = true;
	}

	void close(Minecraft minecraft) {
		if (texture != null) minecraft.getTextureManager().release(texture);
		texture = null;
		dynamicTexture = null;
		dragging = false;
		dirty = true;
		loadedModelSet = null;
	}

	private record Voxel(int x, int y, int z, int stateIndex) { }
	private record PaletteEntry(String id, CompoundTag properties) { }
	private record Point(double x, double y, double depth) { }
	private record Face(Polygon polygon, double depth, int color) { }
	private record TexVertex(double x, double y, double depth, double u, double v) { }
	private record TexturedFace(TexVertex[] vertices, NativeImage source,
			int width, int height, int tint, double light, double depth) { }
}
