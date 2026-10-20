package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Read-only editor surface for files that must never pass through the text editor. */
final class LuaBinaryPreview {
    void renderImGui(Minecraft client) {
        imgui.ImGui.textWrapped(detail);if(!error.isEmpty())imgui.ImGui.textWrapped(error);
        if(audio!=null){
            LuaOggPreviewPlayer.poll();
            NativeUi.action(LuaOggPreviewPlayer.playing()?"停止":"播放",()->{if(LuaOggPreviewPlayer.playing())LuaOggPreviewPlayer.stop();else try{LuaOggPreviewPlayer.play(client,bytes,audio.duration());}catch(IOException e){error=e.getMessage();}});
            float[] position={(float)LuaOggPreviewPlayer.position()};
            if(imgui.ImGui.sliderFloat("##position",position,0,(float)audio.duration(),LuaOggMetadata.time(position[0])+" / "+LuaOggMetadata.time(audio.duration())))LuaOggPreviewPlayer.seek(client,bytes,audio.duration(),position[0]);
            NativeUi.readOnly("##metadata",String.join("\n",audioDetails)+"\n"+LuaOggPreviewPlayer.state()+"\n"+LuaOggPreviewPlayer.error(),Math.max(100,imgui.ImGui.getContentRegionAvailY()));
        }else if(root!=null){
            if(structure!=null){showNbtTree=NativeUi.check("显示 NBT 树",showNbtTree);imgui.ImGui.sameLine();NativeUi.action("重置视角",structure::reset);}
            if(structure!=null&&!showNbtTree){
                int w=Math.max(40,(int)imgui.ImGui.getContentRegionAvailX()),h=Math.max(40,(int)imgui.ImGui.getContentRegionAvailY());
                NativeUi.media("structure:"+name,w,h,g->structure.render(g,client,0,0,w,h));
                if(imgui.ImGui.isItemHovered()){
                    if(imgui.ImGui.isMouseClicked(0))structure.beginDrag(imgui.ImGui.getMousePosX(),imgui.ImGui.getMousePosY());
                    if(imgui.ImGui.getIO().getMouseWheel()!=0)structure.zoom(imgui.ImGui.getIO().getMouseWheel());
                }
                if(imgui.ImGui.isMouseDown(0))structure.drag(imgui.ImGui.getMousePosX(),imgui.ImGui.getMousePosY());else structure.release();
            }else{
                for(var row:List.copyOf(rows)){
                    imgui.ImGui.pushID(row.path);imgui.ImGui.indent(row.depth*12);
                    if(row.branch){boolean before=expanded.contains(row.path);imgui.ImGui.setNextItemOpen(before);boolean now=imgui.ImGui.treeNodeEx(row.text,imgui.flag.ImGuiTreeNodeFlags.NoTreePushOnOpen);if(now!=before){if(now)expanded.add(row.path);else expanded.remove(row.path);rebuildRows();}}
                    else if(imgui.ImGui.selectable(row.text))NativeUi.copy(row.text);
                    imgui.ImGui.unindent(row.depth*12);imgui.ImGui.popID();
                }
            }
        }else if(texture!=null){float scale=Math.min(imgui.ImGui.getContentRegionAvailX()/imageWidth,imgui.ImGui.getContentRegionAvailY()/imageHeight);var gpu=client.getTextureManager().getTexture(texture).getTexture();imgui.ImGui.image(((com.mojang.blaze3d.opengl.GlTexture)gpu).glId(),imageWidth*scale,imageHeight*scale);}
    }
	private static final AtomicInteger NEXT_TEXTURE = new AtomicInteger();
	private final String name;
	private final byte[] bytes;
	private final Set<String> expanded = new HashSet<>();
	private final List<Row> rows = new ArrayList<>();
	private Identifier texture;
	private int imageWidth;
	private int imageHeight;
	private Tag root;
	private LuaStructurePreview structure;
	private boolean showNbtTree;
	private String detail = "";
	private String error = "";
	private int scroll;
	private LuaOggMetadata audio;
	private final List<String> audioDetails = new ArrayList<>();
	private boolean audioDragging;
	private double audioDragPosition;
	private int audioBarLeft;
	private int audioBarWidth = 1;

	LuaBinaryPreview(Minecraft minecraft, String name, byte[] bytes) {
		this.name = name;
		this.bytes = bytes;
		try {
			if (name.endsWith(".png")) loadPng(minecraft);
			else if (name.endsWith(".ogg")) loadOgg();
			else if (name.endsWith(".nbt")) loadNbt();
		} catch (Exception exception) {
			error = "无法预览：" + exception.getMessage();
		}
	}

	private void loadPng(Minecraft minecraft) throws IOException {
		NativeImage image;
		try (var input = new ByteArrayInputStream(bytes)) { image = NativeImage.read(input); }
		if (image.getWidth() > 4096 || image.getHeight() > 4096) {
			image.close();
			throw new IOException("PNG 宽高不能超过 4096 像素");
		}
		imageWidth = image.getWidth();
		imageHeight = image.getHeight();
		texture = Identifier.fromNamespaceAndPath("mc_lua_ide", "preview/" + NEXT_TEXTURE.incrementAndGet());
		DynamicTexture dynamic = new DynamicTexture(() -> "MC Lua IDE PNG 预览", image);
		minecraft.getTextureManager().register(texture, dynamic);
		dynamic.upload();
		detail = imageWidth + " × " + imageHeight + " 像素 · " + readableSize(bytes.length);
	}

	private void loadOgg() throws IOException {
		audio = LuaOggMetadata.read(bytes);
		detail = "OGG Vorbis · " + audio.channels() + " 声道 · " + audio.sampleRate() + " Hz · " + readableSize(bytes.length);
		audioDetails.add("格式：Ogg / Vorbis（有损压缩）");
		audioDetails.add("总时长：" + LuaOggMetadata.time(audio.duration()));
		audioDetails.add("采样率：" + audio.sampleRate() + " Hz");
		audioDetails.add("声道：" + audio.channels() + (audio.channels() == 1 ? "（单声道）" : audio.channels() == 2 ? "（立体声）" : ""));
		audioDetails.add("文件大小：" + readableSize(bytes.length) + "（" + bytes.length + " 字节）");
		double bitrate = audio.averageBitrate(bytes.length);
		audioDetails.add("平均码率（含容器）：" + (bitrate < 0 ? "未知" : String.format(java.util.Locale.ROOT, "%.1f kbps", bitrate / 1000)));
		if (audio.nominalBitrate() > 0) audioDetails.add("标称码率：" + String.format(java.util.Locale.ROOT, "%.1f kbps", audio.nominalBitrate() / 1000.0));
		if (!audio.encoder().isBlank()) audioDetails.add("编码器：" + audio.encoder());
		for (String comment : audio.comments()) {
			int separator = comment.indexOf('=');
			String key = separator < 0 ? "注释" : comment.substring(0, separator);
			String value = separator < 0 ? comment : comment.substring(separator + 1);
			String label = switch (key.toUpperCase(java.util.Locale.ROOT)) {
				case "TITLE" -> "标题"; case "ARTIST" -> "艺术家"; case "ALBUM" -> "专辑";
				case "DATE" -> "日期"; case "GENRE" -> "流派"; case "COMMENT", "DESCRIPTION" -> "备注";
				case "TRACKNUMBER" -> "曲目编号"; case "COPYRIGHT" -> "版权"; case "ENCODER" -> "编码软件";
				default -> key;
			};
			audioDetails.add(label + "：" + value);
		}
	}

	private void loadNbt() throws IOException {
		try {
			root = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(32L * 1024 * 1024));
			detail = "压缩 NBT · " + readableSize(bytes.length);
		} catch (IOException compressedError) {
			try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
				root = NbtIo.read(input, NbtAccounter.create(32L * 1024 * 1024));
				detail = "未压缩 NBT · " + readableSize(bytes.length);
			}
		}
		structure = LuaStructurePreview.detect((CompoundTag) root);
		if (structure != null) detail = structure.summary() + " · " + readableSize(bytes.length);
		expanded.add("root");
		rebuildRows();
	}

	private void rebuildRows() {
		rows.clear();
		addRow("root", "根标签", root, 0);
		scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - 1)));
	}

	private void addRow(String path, String label, Tag tag, int depth) {
		if (rows.size() >= 10000 || depth > 64) return;
		boolean branch = tag instanceof CompoundTag || tag instanceof ListTag;
		String suffix = branch ? tag instanceof CompoundTag compound ? " {" + compound.size() + "}"
				: " [" + ((ListTag) tag).size() + "]" : " = " + leafValue(tag);
		rows.add(new Row(path, label + "  " + typeName(tag) + suffix, depth, branch));
		if (!branch || !expanded.contains(path)) return;
		if (tag instanceof CompoundTag compound) {
			List<String> keys = new ArrayList<>(compound.keySet());
			keys.sort(String.CASE_INSENSITIVE_ORDER);
			for (int index = 0; index < keys.size() && rows.size() < 10000; index++) {
				String key = keys.get(index);
				addRow(path + "/" + index, key, compound.get(key), depth + 1);
			}
		} else if (tag instanceof ListTag list) {
			for (int index = 0; index < list.size() && rows.size() < 10000; index++)
				addRow(path + "/" + index, "[" + index + "]", list.get(index), depth + 1);
		}
	}

	private static String leafValue(Tag tag) {
		if (tag.getId() == Tag.TAG_BYTE_ARRAY)
			return "[" + tag.asByteArray().map(value -> value.length).orElse(0) + " bytes]";
		if (tag.getId() == Tag.TAG_INT_ARRAY)
			return "[" + tag.asIntArray().map(value -> value.length).orElse(0) + " ints]";
		if (tag.getId() == Tag.TAG_LONG_ARRAY)
			return "[" + tag.asLongArray().map(value -> value.length).orElse(0) + " longs]";
		if (tag.getId() == Tag.TAG_STRING) {
			String value = tag.asString().orElse("");
			return "\"" + (value.length() > 237 ? value.substring(0, 237) + "…" : value) + "\"";
		}
		String value = tag.toString();
		return value.length() > 240 ? value.substring(0, 237) + "…" : value;
	}

	private static String typeName(Tag tag) {
		return switch (tag.getId()) {
			case Tag.TAG_BYTE -> "byte";
			case Tag.TAG_SHORT -> "short";
			case Tag.TAG_INT -> "int";
			case Tag.TAG_LONG -> "long";
			case Tag.TAG_FLOAT -> "float";
			case Tag.TAG_DOUBLE -> "double";
			case Tag.TAG_BYTE_ARRAY -> "byte[]";
			case Tag.TAG_STRING -> "string";
			case Tag.TAG_LIST -> "list";
			case Tag.TAG_COMPOUND -> "compound";
			case Tag.TAG_INT_ARRAY -> "int[]";
			case Tag.TAG_LONG_ARRAY -> "long[]";
			default -> "tag";
		};
	}

	void render(GuiGraphicsExtractor graphics, Font font, Minecraft minecraft,
			int left, int top, int right, int bottom) {
		graphics.fill(left, top, right, bottom, 0xFF18232B);
		graphics.fill(left, top, right, top + 28, 0xFF263744);
		graphics.text(font, font.plainSubstrByWidth(name, Math.max(20, right - left - 160)),
				left + 10, top + 9, 0xFFE6F5F3);
		graphics.text(font, "只读预览", right - 72, top + 9, 0xFF8CCFC6);
		if (!error.isEmpty()) {
			graphics.text(font, font.plainSubstrByWidth(error, right - left - 24),
					left + 12, top + 44, 0xFFFF8888);
			return;
		}
		graphics.text(font, font.plainSubstrByWidth(detail,
				Math.max(20, right - left - (structure == null ? 24 : 126))),
				left + 12, top + 38, 0xFF9BB8C0);
		if (structure != null) {
			int switchLeft = right - 105;
			graphics.fill(switchLeft, top + 32, right - 10, top + 53, 0xFF315D66);
			graphics.centeredText(font, showNbtTree ? "切回 3D" : "查看 NBT",
					switchLeft + 47, top + 39, 0xFFFFFFFF);
		}
		if (texture != null) {
			int availableWidth = Math.max(1, right - left - 24);
			int availableHeight = Math.max(1, bottom - top - 72);
			double scale = Math.min(1.0, Math.min(availableWidth / (double) imageWidth,
					availableHeight / (double) imageHeight));
			int drawnWidth = Math.max(1, (int) Math.round(imageWidth * scale));
			int drawnHeight = Math.max(1, (int) Math.round(imageHeight * scale));
			int x = left + (right - left - drawnWidth) / 2;
			int y = top + 62 + (availableHeight - drawnHeight) / 2;
			graphics.enableScissor(left + 1, top + 60, right - 1, bottom - 1);
			graphics.blit(texture, x, y, x + drawnWidth, y + drawnHeight, 0, 1, 0, 1);
			graphics.disableScissor();
		} else if (name.endsWith(".ogg")) {
			renderOgg(graphics, font, left, top, right, bottom);
		} else if (structure != null && !showNbtTree) {
			structure.render(graphics, minecraft, left + 3, top + 58, right - 3, bottom - 2);
		} else if (root != null) {
			int rowTop = top + 61;
			int visible = Math.max(1, (bottom - rowTop - 6) / 14);
			scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visible)));
			graphics.enableScissor(left + 2, rowTop, right - 2, bottom - 2);
			for (int index = scroll; index < Math.min(rows.size(), scroll + visible); index++) {
				Row row = rows.get(index);
				int y = rowTop + (index - scroll) * 14;
				if ((index & 1) == 0) graphics.fill(left + 3, y, right - 3, y + 14, 0xFF1E2B33);
				int x = left + 12 + row.depth * 14;
				if (row.branch) graphics.text(font, expanded.contains(row.path) ? "▾" : "▸",
							x, y + 3, 0xFF75CEC4);
				graphics.text(font, font.plainSubstrByWidth(row.text, Math.max(12, right - x - 24)),
						x + 13, y + 3, row.branch ? 0xFFD6EBE8 : 0xFFB9CED4);
			}
			graphics.disableScissor();
		}
	}

	boolean click(Minecraft minecraft, int button, double mouseX, double mouseY,
			int left, int top, int right, int bottom) {
		if (mouseX < left || mouseX > right || mouseY < top || mouseY > bottom) return false;
		if (!error.isEmpty()) return true;
		if (structure != null && button == 0 && mouseX >= right - 105 && mouseX <= right - 10
				&& mouseY >= top + 32 && mouseY <= top + 53) {
			showNbtTree = !showNbtTree;
			structure.release();
		} else if (structure != null && !showNbtTree && mouseY >= top + 58) {
			if (button == 1) structure.reset();
			else if (button == 0) structure.beginDrag(mouseX, mouseY);
		} else if (button == 0 && audio != null && audio.duration() > 0
				&& mouseX >= left + 12 && mouseX <= right - 12
				&& mouseY >= top + 113 && mouseY <= top + 129) {
			audioBarLeft = left + 12;
			audioBarWidth = Math.max(1, right - left - 24);
			audioDragging = true;
			audioDragPosition = audioPosition(mouseX, audioBarLeft, audioBarWidth, audio.duration());
		} else if (button == 0 && name.endsWith(".ogg") && mouseX >= left + 12 && mouseX <= left + 114
				&& mouseY >= top + 69 && mouseY <= top + 94) {
			audioDragging = false;
			if (LuaOggPreviewPlayer.playing()) LuaOggPreviewPlayer.stop();
			else try { LuaOggPreviewPlayer.play(minecraft, bytes, audio.duration()); }
			catch (IOException exception) { error = "播放失败：" + exception.getMessage(); }
		} else if (button == 0 && root != null && mouseY >= top + 61) {
			int index = scroll + (int) ((mouseY - top - 61) / 14);
			if (index >= 0 && index < rows.size()) {
				Row row = rows.get(index);
				if (row.branch) {
					if (!expanded.add(row.path)) expanded.remove(row.path);
					rebuildRows();
				}
			}
		}
		return true;
	}

	boolean scroll(double amount, int top, int bottom) {
		if (audio != null) {
			int visible = Math.max(1, (bottom - top - 175) / 16);
			scroll = Math.max(0, Math.min(Math.max(0, audioDetails.size() - visible), scroll - (int) Math.signum(amount) * 3));
			return true;
		}
		if (structure != null && !showNbtTree) {
			structure.zoom(amount);
			return true;
		}
		if (root == null) return false;
		int visible = Math.max(1, (bottom - top - 67) / 14);
		scroll = Math.max(0, Math.min(Math.max(0, rows.size() - visible), scroll - (int) Math.signum(amount) * 3));
		return true;
	}

	boolean drag(double x, double y) {
		if (audioDragging) {
			audioDragPosition = audioPosition(x, audioBarLeft, audioBarWidth, audio.duration());
			return true;
		}
		return structure != null && structure.drag(x, y);
	}

	boolean release(Minecraft minecraft, int button, double mouseX) {
		if (audioDragging) {
			if (button != 0) return true;
			audioDragPosition = audioPosition(mouseX, audioBarLeft, audioBarWidth, audio.duration());
			audioDragging = false;
			LuaOggPreviewPlayer.seek(minecraft, bytes, audio.duration(), audioDragPosition);
			return true;
		}
		return structure != null && structure.release();
	}

	void activate(Minecraft minecraft) {
		audioDragging = false;
		if (name.endsWith(".png") && texture == null && error.isEmpty()) {
			try { loadPng(minecraft); }
			catch (IOException exception) { error = "无法预览：" + exception.getMessage(); }
		}
	}

	void close(Minecraft minecraft) {
		audioDragging = false;
		if (structure != null) structure.close(minecraft);
		if (texture != null) {
			minecraft.getTextureManager().release(texture);
			texture = null;
		}
	}

	private static String readableSize(int bytes) {
		return bytes < 1024 ? bytes + " B" : bytes < 1024 * 1024
				? String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024.0)
				: String.format(java.util.Locale.ROOT, "%.2f MiB", bytes / (1024.0 * 1024));
	}

	private void renderOgg(GuiGraphicsExtractor graphics, Font font, int left, int top, int right, int bottom) {
		LuaOggPreviewPlayer.poll();
		int x = left + 12, width = Math.max(1, right - x - 12), y = top + 69;
		audioBarLeft = x;
		audioBarWidth = width;
		graphics.enableScissor(left + 1, top + 58, right - 1, bottom - 1);
		int buttonWidth = Math.min(102, width);
		graphics.fill(x, y, x + buttonWidth, y + 25, 0xFF315D66);
		graphics.centeredText(font, LuaOggPreviewPlayer.playing() ? "■ 停止" : "▶ 播放", x + buttonWidth / 2, y + 8, 0xFFFFFFFF);
		if (width > 190) graphics.text(font, audioDragging ? "松开跳播" : LuaOggPreviewPlayer.state(), x + 116, y + 8, 0xFF8CCFC6);
		double position = audioDragging ? audioDragPosition : LuaOggPreviewPlayer.position();
		double fraction = audio.duration() > 0 ? Math.clamp(position / audio.duration(), 0, 1) : 0;
		String progress = LuaOggMetadata.time(position) + " / " + LuaOggMetadata.time(audio.duration())
				+ (audio.duration() > 0 ? "   " + (int) (fraction * 100) + "%" : "");
		graphics.text(font, font.plainSubstrByWidth(progress, width), x, y + 33, 0xFFD6EBE8);
		int barY = y + 48;
		graphics.fill(x, barY, x + width, barY + 8, 0xFF35434E);
		int filled = (int) Math.round(width * fraction);
		if (filled > 0) graphics.fill(x, barY, x + filled, barY + 8, audioDragging ? 0xFFFFC873 : 0xFF75CEC4);
		int marker = Math.clamp(x + filled, x + 1, x + Math.max(1, width - 1));
		graphics.fill(marker - 2, barY - 3, marker + 2, barY + 11, audioDragging ? 0xFFFFC873 : 0xFFE6F5F3);
		String notice = LuaOggPreviewPlayer.error().isBlank()
				? audio.duration() > 0 ? "点击／拖动进度条跳播 · 滚轮查看详情" : "总时长未知，无法拖动定位 · 滚轮查看详情"
				: "播放失败：" + LuaOggPreviewPlayer.error();
		graphics.text(font, font.plainSubstrByWidth(notice, width), x, y + 68,
				LuaOggPreviewPlayer.error().isBlank() ? 0xFF95AAB1 : 0xFFFF8888);
		graphics.disableScissor();
		int rowTop = top + 169;
		if (bottom <= rowTop + 3) return;
		int visible = Math.max(1, (bottom - rowTop - 6) / 16);
		scroll = Math.clamp(scroll, 0, Math.max(0, audioDetails.size() - visible));
		graphics.enableScissor(left + 2, rowTop, right - 2, bottom - 2);
		for (int index = scroll; index < Math.min(audioDetails.size(), scroll + visible); index++) {
			int rowY = rowTop + (index - scroll) * 16;
			if ((index & 1) == 0) graphics.fill(left + 3, rowY, right - 3, rowY + 16, 0xFF1E2B33);
			graphics.text(font, font.plainSubstrByWidth(audioDetails.get(index), width), x, rowY + 4, 0xFFB9CED4);
		}
		graphics.disableScissor();
	}

	static double audioPosition(double mouseX, int left, int width, double duration) {
		if (!Double.isFinite(mouseX) || !Double.isFinite(duration) || duration <= 0) return 0;
		return Math.clamp((mouseX - left) / Math.max(1, width), 0, 1) * duration;
	}

	private record Row(String path, String text, int depth, boolean branch) { }
}
