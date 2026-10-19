package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/** Lightweight parser/projection test; accepts an optional real structure NBT path. */
public final class LuaStructurePreviewProbe {
	private LuaStructurePreviewProbe() { }

	public static void main(String[] args) throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		if (LuaStructurePreview.detect(new CompoundTag()) != null)
			throw new AssertionError("普通 NBT 不应识别为结构模板");
		CompoundTag root = args.length == 0 ? syntheticStructure()
				: NbtIo.readCompressed(Path.of(args[0]), NbtAccounter.create(32L * 1024 * 1024));
		LuaStructurePreview preview = LuaStructurePreview.detect(root);
		if (preview == null) throw new AssertionError("结构模板未识别：" + (args.length == 0 ? "合成样本" : args[0]));
		BufferedImage initial = preview.renderFrame();
		long initialSignature = signature(initial);
		preview.beginDrag(100, 100);
		preview.drag(155, 124);
		preview.release();
		if (initialSignature == signature(preview.renderFrame()))
			throw new AssertionError("旋转后视图未变化");
		preview.zoom(3);
		BufferedImage zoomed = preview.renderFrame();
		if (initialSignature == signature(zoomed))
			throw new AssertionError("缩放后视图未变化");
		if (args.length > 1) ImageIO.write(initial, "png", Path.of(args[1]).toFile());
		System.out.println("Structure preview smoke passed: " + preview.summary());
	}

	private static long signature(BufferedImage image) {
		long result = 0;
		for (int y = 0; y < image.getHeight(); y += 7)
			for (int x = 0; x < image.getWidth(); x += 7)
				result = result * 31 + image.getRGB(x, y);
		return result;
	}

	private static CompoundTag syntheticStructure() {
		CompoundTag root = new CompoundTag();
		ListTag size = new ListTag();
		for (int value : new int[] {2, 2, 2}) size.add(IntTag.valueOf(value));
		root.put("size", size);
		ListTag palette = new ListTag();
		CompoundTag stone = new CompoundTag();
		stone.putString("Name", "minecraft:stone");
		palette.add(stone);
		root.put("palette", palette);
		ListTag blocks = new ListTag();
		for (int[] position : new int[][] {{0, 0, 0}, {1, 0, 0}, {0, 1, 0}}) {
			CompoundTag block = new CompoundTag();
			ListTag pos = new ListTag();
			for (int coordinate : position) pos.add(IntTag.valueOf(coordinate));
			block.put("pos", pos);
			block.putInt("state", 0);
			blocks.add(block);
		}
		root.put("blocks", blocks);
		return root;
	}
}
