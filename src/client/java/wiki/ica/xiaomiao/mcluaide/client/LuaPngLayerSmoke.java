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
			layerPropertiesSmoke(directory);
				System.out.println("Resource PNG layer smoke passed");
		} finally {
			Files.deleteIfExists(project);
			Files.deleteIfExists(png);
			Files.deleteIfExists(directory);
		}
	}

	/** Covers layer rename, opacity, duplicate, merge-down, undo of each, and project persistence. */
	private static void layerPropertiesSmoke(Path directory) throws Exception {
		Path png = directory.resolve("properties.png");
		Path project = directory.resolve("properties-layers.zip");
		try {
			BufferedImage blank = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
			ImageIO.write(blank, "PNG", png.toFile());
			LuaPngLayerDocument document = new LuaPngLayerDocument(png, blank, project);

			document.renameLayer(0, "  底图  ");
			requireName(document, 0, "底图");
			document.renameLayer(0, "   ");
			requireName(document, 0, "图层 1");
			document.undo();
			requireName(document, 0, "底图");
			document.renameLayer(0, "x".repeat(100));
			if (document.layers().get(0).name().length() != 48)
				throw new AssertionError("图层名称没有限制长度");
			document.undo();
			document.renameLayer(5, "越界");
			document.renameLayer(-1, "越界");
			if (document.layers().size() != 1) throw new AssertionError("越界重命名改变了图层数量");

			document.fill(0, 0, 0xFFFF0000);
			assertPixel(document, 2, 2, 0xFFFF0000);
			document.setOpacity(0, 0.5f, true);
			requireAlpha(document, 2, 2, 128, 2, "半透明图层合成不正确");
			document.undo();
			requireAlpha(document, 2, 2, 255, 0, "撤销不透明度后没有恢复");
			document.redo();
			document.setOpacity(0, 0f, true);
			requireAlpha(document, 2, 2, 0, 0, "完全透明图层仍然可见");
			document.setOpacity(0, 2f, true);
			if (document.layers().get(0).opacity() != 1f) throw new AssertionError("不透明度没有限制在 0..1");
			document.setOpacity(0, 0.5f, true);
			// A continued drag (beginChange=false) must not add a second undo step.
			document.setOpacity(0, 0.75f, false);
			document.undo();
			if (document.layers().get(0).opacity() != 1f) throw new AssertionError("连续拖动不透明度应只产生一次撤销");
			document.redo();
			if (LuaPngLayerDocument.parseOpacity("nan") != 1f || LuaPngLayerDocument.parseOpacity("-3") != 0f
					|| LuaPngLayerDocument.parseOpacity("abc") != 1f)
				throw new AssertionError("不透明度解析没有处理非法值");
			document.setOpacity(0, 1f, true);

			document.duplicateLayer();
			if (document.layers().size() != 2 || document.selected() != 1)
				throw new AssertionError("复制图层后图层数量或选中项不正确");
			requireName(document, 1, "底图 副本");
			document.fill(0, 0, 0xFF0000FF);
			if (document.layers().get(0).image().getRGB(1, 1) != 0xFFFF0000)
				throw new AssertionError("复制出的图层与原图层共享了像素");
			if (document.layers().get(1).image().getRGB(1, 1) != 0xFF0000FF)
				throw new AssertionError("复制图层没有独立绘制");

			// Merge a half-opaque blue layer onto the red layer.
			document.setOpacity(1, 0.5f, true);
			document.mergeDown();
			if (document.layers().size() != 1 || document.selected() != 0)
				throw new AssertionError("向下合并后图层数量或选中项不正确");
			requireName(document, 0, "底图");
			int merged = document.layers().get(0).image().getRGB(1, 1);
			if (merged >>> 24 != 255 || Math.abs((merged >> 16 & 255) - 128) > 2 || Math.abs((merged & 255) - 128) > 2)
				throw new AssertionError("向下合并没有按不透明度混合：" + Integer.toHexString(merged));
			document.undo();
			if (document.layers().size() != 2 || document.selected() != 1)
				throw new AssertionError("撤销向下合并后图层没有恢复");
			document.select(0);
			document.mergeDown();
			if (document.layers().size() != 2) throw new AssertionError("最底层不应该能向下合并");
			// A hidden layer contributes nothing when merged.
			document.select(1);
			document.toggleVisible(1);
			document.mergeDown();
			if (document.layers().size() != 1 || document.layers().get(0).image().getRGB(1, 1) != 0xFFFF0000)
				throw new AssertionError("隐藏图层合并时不应改变下方图层");

			document.setOpacity(0, 0.25f, true);
			ImageIO.write(document.composite(), "PNG", png.toFile());
			document.saveProject();
			LuaPngLayerDocument reloaded = new LuaPngLayerDocument(png, ImageIO.read(png.toFile()), project);
			if (reloaded.layers().size() != 1 || Math.abs(reloaded.layers().get(0).opacity() - 0.25f) > 0.001f)
				throw new AssertionError("图层不透明度没有保存到工程");
			requireName(reloaded, 0, "底图");
			for (int copies = reloaded.layers().size(); copies < 16; copies++) reloaded.duplicateLayer();
			reloaded.duplicateLayer();
			if (reloaded.layers().size() != 16) throw new AssertionError("复制图层超过了图层数量上限");
		} finally {
			Files.deleteIfExists(project);
			Files.deleteIfExists(png);
		}
	}

	private static void requireName(LuaPngLayerDocument document, int index, String expected) {
		String actual = document.layers().get(index).name();
		if (!actual.equals(expected))
			throw new AssertionError("图层 " + index + " 名称预期 " + expected + "，实际 " + actual);
	}

	private static void requireAlpha(LuaPngLayerDocument document, int x, int y, int expected, int tolerance, String message) {
		int actual = document.composite().getRGB(x, y) >>> 24;
		if (Math.abs(actual - expected) > tolerance) throw new AssertionError(message + "（alpha " + actual + "）");
	}

	private static void assertPixel(LuaPngLayerDocument document, int x, int y, int expected) {
		int actual = document.composite().getRGB(x, y);
		if (actual != expected)
			throw new AssertionError("像素 (" + x + "," + y + ") 预期 " + expected + "，实际 " + actual);
	}
}
