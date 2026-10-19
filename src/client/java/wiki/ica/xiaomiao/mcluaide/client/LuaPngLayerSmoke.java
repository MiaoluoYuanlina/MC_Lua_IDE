package wiki.ica.xiaomiao.mcluaide.client;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/** Verifies pixel tools, undo/redo, layer compositing, and project reopening. */
public final class LuaPngLayerSmoke {
	private LuaPngLayerSmoke() { }

	public static void main(String[] args) throws Exception {
		if (LuaPngLayerDocument.pressureSize(20, 1.0) != 20
				|| LuaPngLayerDocument.pressureSize(20, 0.5) != 10
				|| LuaPngLayerDocument.pressureSize(1, 0.1) != 1)
			throw new AssertionError("数位笔压感尺寸映射不正确");
		if (new WindowsPenPressure.PointerPenInfo().size() != 120)
			throw new AssertionError("Windows Ink 压感数据结构布局不正确");
		for (int sourceSize : new int[] { 16, 32, 64, 256 }) {
			for (int displaySize : new int[] { 7, 13, 64, 127, 319, 1024 }) {
				for (int screenPixel = 0; screenPixel < displaySize; screenPixel++) {
					int imagePixel = screenPixel * sourceSize / displaySize;
					if (LuaResourcePngCanvas.projectedPixelEdge(imagePixel, displaySize, sourceSize) > screenPixel
							|| LuaResourcePngCanvas.projectedPixelEdge(imagePixel + 1, displaySize, sourceSize) <= screenPixel)
						throw new AssertionError("棋盘格与缩放后的图片像素边界不一致");
				}
			}
		}
		if (!LuaResourcePngCanvas.previewPixelBounds(8, 8, 1, 16, 16)
				.equals(new LuaResourcePngCanvas.PixelBounds(8, 8, 9, 9))
				|| !LuaResourcePngCanvas.previewPixelBounds(8, 8, 5, 16, 16)
				.equals(new LuaResourcePngCanvas.PixelBounds(6, 6, 11, 11))
				|| !LuaResourcePngCanvas.previewPixelBounds(0, 0, 5, 16, 16)
				.equals(new LuaResourcePngCanvas.PixelBounds(0, 0, 3, 3)))
			throw new AssertionError("悬浮笔尖预览框与实际绘制范围不一致");
		for (int[] dimensions : new int[][] { { 4, 4 }, { 6, 5 }, { 16, 32 } }) {
			int light = 0, dark = 0;
			for (int y = 0; y < dimensions[1]; y++) for (int x = 0; x < dimensions[0]; x++) {
				int actual = LuaResourcePngCanvas.checkerPreviewPixel(x, y, 0);
				int expected = ((x + y) & 1) == 0 ? 0xFFAFB8BC : 0xFF79838A;
				if (actual != expected) throw new AssertionError("透明棋盘格没有逐像素对齐");
				if (actual == 0xFFAFB8BC) light++; else dark++;
			}
			if (light + dark != dimensions[0] * dimensions[1] || Math.abs(light - dark) > 1)
				throw new AssertionError("分辨率变化后棋盘格数量不正确");
		}
		if (LuaResourcePngCanvas.checkerPreviewPixel(1, 1, 0xFFFF0000) != 0xFFFF0000
				|| LuaResourcePngCanvas.checkerPreviewPixel(0, 0, 0x80FF0000) >>> 24 != 255)
			throw new AssertionError("透明棋盘格与绘制像素的混合不正确");
		Path directory = Files.createTempDirectory("mc-lua-ide-png-smoke-");
		Path png = directory.resolve("pixels.png");
		Path project = directory.resolve("layers.zip");
		try {
			BufferedImage blank = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
			ImageIO.write(blank, "PNG", png.toFile());
			LuaPngLayerDocument document = new LuaPngLayerDocument(png, blank, project);
			document.beginStroke();
			document.stroke(1, 1, LuaPngLayerDocument.Tool.BRUSH, 0xFFFF0000, 1);
			document.endStroke();
			assertPixel(document, 1, 1, 0xFFFF0000);
			document.undo();
			assertPixel(document, 1, 1, 0);
			document.redo();
			assertPixel(document, 1, 1, 0xFFFF0000);
			document.beginStroke();
			document.stroke(2, 2, LuaPngLayerDocument.Tool.PENCIL, 0xFF00FF00, 1);
			document.endStroke();
			assertPixel(document, 2, 2, 0xFF00FF00);
			assertPixel(document, 3, 2, 0);
			document.undo();
			assertPixel(document, 2, 2, 0);
			document.beginStroke();
			document.stroke(2, 2, LuaPngLayerDocument.Tool.PENCIL, 0xFF00FF00, 3);
			document.endStroke();
			assertPixel(document, 1, 2, 0xFF00FF00);
			assertPixel(document, 3, 2, 0xFF00FF00);
			document.undo();
			assertPixel(document, 1, 2, 0);
			document.beginStroke();
			document.stroke(2, 2, LuaPngLayerDocument.Tool.BRUSH, 0xFF0000FF, 3);
			document.endStroke();
			int softEdgeAlpha = document.composite().getRGB(3, 2) >>> 24;
			if (softEdgeAlpha <= 0 || softEdgeAlpha >= 255)
				throw new AssertionError("可调尺寸画笔没有生成柔和边缘");
			document.undo();
			document.beginStroke();
			document.stroke(0, 0, LuaPngLayerDocument.Tool.BRUSH, 0xFFFF0000, 1, 0.5);
			document.endStroke();
			if (document.composite().getRGB(0, 0) >>> 24 != 128)
				throw new AssertionError("低压画笔没有改变透明度");
			document.undo();
			document.beginStroke();
			document.stroke(1, 1, LuaPngLayerDocument.Tool.ERASER, 0, 1, 0.5);
			document.endStroke();
			if (document.composite().getRGB(1, 1) >>> 24 != 128)
				throw new AssertionError("低压橡皮没有改变擦除强度");
			document.undo();
			document.addLayer();
			document.fill(0, 0, 0xFF0000FF);
			assertPixel(document, 2, 2, 0xFF0000FF);
			document.undo();
			assertPixel(document, 2, 2, 0);
			document.redo();
			assertPixel(document, 2, 2, 0xFF0000FF);
			document.toggleVisible(1);
			assertPixel(document, 1, 1, 0xFFFF0000);
			assertPixel(document, 2, 2, 0);
			document.resizeCanvas(6, 5);
			if (document.width() != 6 || document.height() != 5 || document.layers().size() != 2)
				throw new AssertionError("画布分辨率或图层数量不正确");
			assertPixel(document, 1, 1, 0xFFFF0000);
			assertPixel(document, 5, 4, 0);
			ImageIO.write(document.composite(), "PNG", png.toFile());
			document.saveProject();
			LuaPngLayerDocument reloaded = new LuaPngLayerDocument(png, ImageIO.read(png.toFile()), project);
			if (reloaded.layers().size() != 2 || reloaded.layers().get(1).visible())
				throw new AssertionError("图层工程没有正确恢复");
			assertPixel(reloaded, 1, 1, 0xFFFF0000);
			System.out.println("Resource PNG layer smoke passed");
		} finally {
			Files.deleteIfExists(project);
			Files.deleteIfExists(png);
			Files.deleteIfExists(directory);
		}
	}

	private static void assertPixel(LuaPngLayerDocument document, int x, int y, int expected) {
		int actual = document.composite().getRGB(x, y);
		if (actual != expected)
			throw new AssertionError("像素 (" + x + "," + y + ") 预期 " + expected + "，实际 " + actual);
	}
}
