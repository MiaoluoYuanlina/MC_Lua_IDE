package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/** Editable PNG shown in the IDE's main editor viewport, not in a separate screen. */
final class LuaResourcePngCanvas {
	record PixelBounds(int left, int top, int right, int bottom) { }
	private static final AtomicInteger NEXT_TEXTURE = new AtomicInteger();
	private final Minecraft minecraft;
	private final String path;
	private final LuaPngLayerDocument document;
	private Identifier texture;
	private DynamicTexture dynamic;
	private boolean textureDirty = true;
	private double zoom = 1, panX, panY, dragX, dragY;
	private boolean painting, panning;
	private int left, top, right, bottom, imageX, imageY, imageWidth, imageHeight;
	private int color = 0xFFEECC55;
	private int pencilSize = 1, brushSize = 1, eraserSize = 1;
	private LuaPngLayerDocument.Tool tool = LuaPngLayerDocument.Tool.BRUSH;

	LuaResourcePngCanvas(Minecraft minecraft, String path, LuaPngLayerDocument.RecoveryImage recovery) throws IOException {
		this.minecraft = minecraft; this.path = path;
		WindowsPenPressure.install(minecraft);
		document = new LuaPngLayerDocument(LuaResourcePackWorkspace.diskPath(path), recovery);
	}

	LuaResourcePngCanvas(Minecraft minecraft, String path) throws IOException {
		this.minecraft = minecraft;
		this.path = path;
		WindowsPenPressure.install(minecraft);
		document = new LuaPngLayerDocument(LuaResourcePackWorkspace.diskPath(path),
				LuaResourcePackWorkspace.readPng(path));
	}

	LuaPngLayerDocument document() { return document; }
	String path() { return path; }
	int color() { return color; }
	int toolSize() {
		return switch (tool) {
			case PENCIL -> pencilSize;
			case BRUSH, FILL, PICKER -> brushSize;
			case ERASER -> eraserSize;
		};
	}
	LuaPngLayerDocument.Tool tool() { return tool; }
	void setColor(int value) { color = value; }
	void setToolSize(int value) {
		int size = Math.clamp(value, 1, 32);
		switch (tool) {
			case PENCIL -> pencilSize = size;
			case BRUSH -> brushSize = size;
			case ERASER -> eraserSize = size;
			case FILL, PICKER -> { }
		}
	}
	void setTool(LuaPngLayerDocument.Tool value) { tool = value; }
	void markDirty() { textureDirty = true; }
	void save() throws IOException { document.save(path); }
	void undo() { document.undo(); textureDirty = true; }
	void redo() { document.redo(); textureDirty = true; }

	void close() {
		if (texture != null) minecraft.getTextureManager().release(texture);
		texture = null;
		dynamic = null;
	}

	void render(GuiGraphicsExtractor graphics, Font font, int left, int top, int right, int bottom,
			int mouseX, int mouseY, boolean hoverAllowed) {
		this.left = left; this.top = top; this.right = right; this.bottom = bottom;
		graphics.fill(left, top, right, bottom, 0xFF18212B);
		int availableWidth = Math.max(1, right - left - 26);
		int availableHeight = Math.max(1, bottom - top - 54);
		double fitted = Math.min(availableWidth / (double) document.width(), availableHeight / (double) document.height());
		double pixelScale = Math.max(0.25, Math.min(32, fitted * zoom));
		imageWidth = Math.max(1, (int) Math.round(document.width() * pixelScale));
		imageHeight = Math.max(1, (int) Math.round(document.height() * pixelScale));
		imageX = (left + right - imageWidth) / 2 + (int) panX;
		imageY = (top + bottom - imageHeight) / 2 + (int) panY + 12;
		graphics.enableScissor(left + 2, top + 25, right - 2, bottom - 2);
		// The transparency grid is baked into the preview at source resolution, one cell per
		// image pixel. A single blit keeps the grid and painted pixels aligned at every zoom.
		updateTexture();
		if (texture != null) graphics.blit(texture, imageX, imageY, imageX + imageWidth, imageY + imageHeight,
				0, 1, 0, 1);
		if (hoverAllowed) renderHoverFrame(graphics, mouseX, mouseY);
		graphics.disableScissor();
		graphics.outline(imageX, imageY, imageWidth, imageHeight, 0xFFB9CCD6);
		graphics.text(font, document.width() + " × " + document.height() + " · 滚轮缩放 · 中键平移 · Ctrl+S 保存"
				+ (WindowsPenPressure.detected() ? " · 数位笔压感 " + (int) Math.round(WindowsPenPressure.currentPressure() * 100) + "%" : ""),
				left + 8, top + 8, 0xFFE5F4FA);
	}

	static int projectedPixelEdge(int pixel, int displaySize, int sourceSize) {
		return (int) (((long) pixel * displaySize + sourceSize - 1) / sourceSize);
	}

	static PixelBounds previewPixelBounds(int pixelX, int pixelY, int size, int sourceWidth, int sourceHeight) {
		int footprint = Math.max(1, size);
		int left = pixelX - footprint / 2, top = pixelY - footprint / 2;
		return new PixelBounds(Math.clamp(left, 0, sourceWidth), Math.clamp(top, 0, sourceHeight),
				Math.clamp(left + footprint, 0, sourceWidth), Math.clamp(top + footprint, 0, sourceHeight));
	}

	private void renderHoverFrame(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!inside(mouseX, mouseY) || mouseX < imageX || mouseX >= imageX + imageWidth
				|| mouseY < imageY || mouseY >= imageY + imageHeight) return;
		int pixelX = (int) ((long) (mouseX - imageX) * document.width() / imageWidth);
		int pixelY = (int) ((long) (mouseY - imageY) * document.height() / imageHeight);
		int size = switch (tool) {
			case PENCIL, BRUSH, ERASER -> LuaPngLayerDocument.pressureSize(toolSize(), WindowsPenPressure.currentPressure());
			case FILL, PICKER -> 1;
		};
		PixelBounds pixels = previewPixelBounds(pixelX, pixelY, size, document.width(), document.height());
		int x0 = imageX + projectedPixelEdge(pixels.left(), imageWidth, document.width());
		int y0 = imageY + projectedPixelEdge(pixels.top(), imageHeight, document.height());
		int x1 = imageX + projectedPixelEdge(pixels.right(), imageWidth, document.width());
		int y1 = imageY + projectedPixelEdge(pixels.bottom(), imageHeight, document.height());
		x0 = Math.max(x0, Math.max(imageX, left + 2));
		y0 = Math.max(y0, Math.max(imageY, top + 25));
		x1 = Math.min(x1, Math.min(imageX + imageWidth, right - 2));
		y1 = Math.min(y1, Math.min(imageY + imageHeight, bottom - 2));
		if (x1 <= x0 || y1 <= y0) return;
		graphics.outline(x0, y0, x1 - x0, y1 - y0, 0xFF14212A);
		if (x1 - x0 > 2 && y1 - y0 > 2)
			graphics.outline(x0 + 1, y0 + 1, x1 - x0 - 2, y1 - y0 - 2, 0xFFF5FAFD);
	}

	static int checkerPreviewPixel(int x, int y, int foreground) {
		int background = ((x + y) & 1) == 0 ? 0xFFAFB8BC : 0xFF79838A;
		int alpha = foreground >>> 24;
		if (alpha == 0) return background;
		if (alpha == 255) return foreground;
		int inverse = 255 - alpha;
		int red = (((foreground >>> 16) & 255) * alpha + ((background >>> 16) & 255) * inverse + 127) / 255;
		int green = (((foreground >>> 8) & 255) * alpha + ((background >>> 8) & 255) * inverse + 127) / 255;
		int blue = ((foreground & 255) * alpha + (background & 255) * inverse + 127) / 255;
		return 0xFF000000 | red << 16 | green << 8 | blue;
	}

	private void updateTexture() {
		if (!textureDirty) return;
		BufferedImage image = document.composite();
		NativeImage pixels = dynamic == null ? null : dynamic.getPixels();
		if (pixels == null || pixels.getWidth() != image.getWidth() || pixels.getHeight() != image.getHeight()) {
			close();
			pixels = new NativeImage(image.getWidth(), image.getHeight(), true);
			dynamic = new DynamicTexture(() -> "MC Lua IDE 资源包画布", pixels);
			texture = Identifier.fromNamespaceAndPath("mc_lua_ide", "resource_canvas/" + NEXT_TEXTURE.incrementAndGet());
			minecraft.getTextureManager().register(texture, dynamic);
		}
		for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
			pixels.setPixel(x, y, checkerPreviewPixel(x, y, image.getRGB(x, y)));
		dynamic.upload();
		textureDirty = false;
	}

	boolean click(double x, double y, int button) {
		if (!inside(x, y)) return false;
		if (button == 2) { panning = true; dragX = x; dragY = y; return true; }
		if (button != 0) return false;
		painting = true;
		if (tool == LuaPngLayerDocument.Tool.PENCIL || tool == LuaPngLayerDocument.Tool.BRUSH
				|| tool == LuaPngLayerDocument.Tool.ERASER)
			document.beginStroke();
		paint(x, y);
		return true;
	}

	boolean drag(double x, double y) {
		if (panning) { panX += x - dragX; panY += y - dragY; dragX = x; dragY = y; return true; }
		if (painting) { paint(x, y); return true; }
		return false;
	}

	boolean release() {
		if (!painting && !panning) return false;
		if (painting && (tool == LuaPngLayerDocument.Tool.PENCIL || tool == LuaPngLayerDocument.Tool.BRUSH
				|| tool == LuaPngLayerDocument.Tool.ERASER))
			document.endStroke();
		painting = panning = false;
		return true;
	}

	boolean scroll(double x, double y, double amount) {
		if (!inside(x, y)) return false;
		zoom = Math.clamp(zoom * (amount > 0 ? 1.2 : 1 / 1.2), 0.05, 32);
		return true;
	}

	private boolean inside(double x, double y) { return x >= left + 2 && x < right - 2 && y >= top + 25 && y < bottom - 2; }
	private void paint(double x, double y) {
		if (x < imageX || y < imageY || x >= imageX + imageWidth || y >= imageY + imageHeight) return;
		int px = Math.clamp((int) ((x - imageX) * document.width() / imageWidth), 0, document.width() - 1);
		int py = Math.clamp((int) ((y - imageY) * document.height() / imageHeight), 0, document.height() - 1);
		switch (tool) {
			case PICKER -> color = document.pick(px, py);
			case FILL -> { document.fill(px, py, color); textureDirty = true; }
			case PENCIL, BRUSH, ERASER -> {
				document.stroke(px, py, tool, color, toolSize(), WindowsPenPressure.currentPressure());
				textureDirty = true;
			}
		}
	}
}
