package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Loads small client-local PNGs and renders them only behind the code editor. */
final class LuaEditorBackground {
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("mc_lua_ide", "editor_background");
	private static String loadedFile = "";
	private static int imageWidth;
	private static int imageHeight;
	private static String error = "";
	private static boolean registered;

	private LuaEditorBackground() { }

	static List<String> files() {
		try {
			Files.createDirectories(LuaPersonalizationSettings.BACKGROUNDS);
			try (Stream<Path> paths = Files.list(LuaPersonalizationSettings.BACKGROUNDS)) {
				return paths.filter(Files::isRegularFile)
						.map(path -> path.getFileName().toString())
						.filter(name -> name.equals(LuaPersonalizationSettings.safeBackgroundName(name)))
						.sorted(String.CASE_INSENSITIVE_ORDER).toList();
			}
		} catch (Exception exception) {
			error = "无法读取背景图片目录：" + exception.getMessage();
			return List.of();
		}
	}

	static String error() { return error; }

	static void reload() { loadedFile = ""; error = ""; }
	static void renderImGui(LuaPersonalizationSettings settings,float left,float top,float right,float bottom){
		var client=Minecraft.getInstance();if(settings.backgroundFile.isEmpty()||settings.backgroundOpacity<=0||!ensureLoaded(client,settings.backgroundFile))return;
		float width=right-left,height=bottom-top,w=width,h=height,u0=0,v0=0,u1=1,v1=1;
		float horizontal=settings.backgroundAlignment.horizontal(),vertical=settings.backgroundAlignment.vertical();
		if(settings.backgroundFit==LuaPersonalizationSettings.BackgroundFit.NONE||settings.backgroundFit==LuaPersonalizationSettings.BackgroundFit.CONTAIN){
			float scale=settings.backgroundFit==LuaPersonalizationSettings.BackgroundFit.NONE?1:Math.min(width/imageWidth,height/imageHeight);w=imageWidth*scale;h=imageHeight*scale;left+=(width-w)*horizontal;top+=(height-h)*vertical;
		}else if(settings.backgroundFit==LuaPersonalizationSettings.BackgroundFit.COVER){
			float source=imageWidth/(float)imageHeight,target=width/height;if(source>target){float visible=target/source;u0=(1-visible)*horizontal;u1=u0+visible;}else{float visible=source/target;v0=(1-visible)*vertical;v1=v0+visible;}
		}
		var texture=client.getTextureManager().getTexture(TEXTURE).getTexture();
		imgui.ImGui.getWindowDrawList().addImage(((com.mojang.blaze3d.opengl.GlTexture)texture).glId(),left,top,left+w,top+h,u0,v0,u1,v1,settings.backgroundOpacity*255/100<<24|0xFFFFFF);
	}

	static void render(GuiGraphicsExtractor graphics, Minecraft minecraft,
			LuaPersonalizationSettings settings, int left, int top, int right, int bottom) {
		if (settings.backgroundFile.isEmpty() || settings.backgroundOpacity <= 0
				|| right <= left || bottom <= top) return;
		if (!ensureLoaded(minecraft, settings.backgroundFile)) return;
		int width = right - left, height = bottom - top;
		float horizontal = settings.backgroundAlignment.horizontal();
		float vertical = settings.backgroundAlignment.vertical();
		graphics.enableScissor(left, top, right, bottom);
		if (settings.backgroundFit == LuaPersonalizationSettings.BackgroundFit.NONE
				|| settings.backgroundFit == LuaPersonalizationSettings.BackgroundFit.CONTAIN) {
			double scale = settings.backgroundFit == LuaPersonalizationSettings.BackgroundFit.NONE
					? 1.0D / Math.max(1, minecraft.getWindow().getGuiScale())
					: Math.min(width / (double) imageWidth, height / (double) imageHeight);
			int drawnWidth = Math.max(1, (int) Math.round(imageWidth * scale));
			int drawnHeight = Math.max(1, (int) Math.round(imageHeight * scale));
			int x = left + Math.round((width - drawnWidth) * horizontal);
			int y = top + Math.round((height - drawnHeight) * vertical);
			// GuiGraphicsExtractor.blit uses x0, y0, x1, y1, u0, u1, v0, v1.
			graphics.blit(TEXTURE, x, y, x + drawnWidth, y + drawnHeight, 0, 1, 0, 1);
		} else if (settings.backgroundFit == LuaPersonalizationSettings.BackgroundFit.COVER) {
			double sourceAspect = imageWidth / (double) imageHeight;
			double targetAspect = width / (double) height;
			float u0 = 0, v0 = 0, u1 = 1, v1 = 1;
			if (sourceAspect > targetAspect) {
				float visible = (float) (targetAspect / sourceAspect);
				u0 = (1 - visible) * horizontal; u1 = u0 + visible;
			} else {
				float visible = (float) (sourceAspect / targetAspect);
				v0 = (1 - visible) * vertical; v1 = v0 + visible;
			}
			graphics.blit(TEXTURE, left, top, right, bottom, u0, u1, v0, v1);
		} else {
			graphics.blit(TEXTURE, left, top, right, bottom, 0, 1, 0, 1);
		}
		graphics.disableScissor();
		int alpha = 255 - Math.round(settings.backgroundOpacity * 255 / 100.0F);
		graphics.fill(left, top, right, bottom,
				(alpha << 24) | (settings.theme.background() & 0xFFFFFF));
	}

	private static boolean ensureLoaded(Minecraft minecraft, String name) {
		if (name.equals(loadedFile)) return registered;
		if (registered) minecraft.getTextureManager().release(TEXTURE);
		registered = false;
		loadedFile = name;
		error = "";
		if (!name.equals(LuaPersonalizationSettings.safeBackgroundName(name))) return false;
		Path path = LuaPersonalizationSettings.BACKGROUNDS.resolve(name).normalize();
		if (!path.getParent().equals(LuaPersonalizationSettings.BACKGROUNDS)) return false;
		try {
			if (Files.size(path) > 16 * 1024 * 1024L) throw new IllegalArgumentException("PNG 不能超过 16 MB");
			NativeImage image;
			try (var stream = Files.newInputStream(path)) { image = NativeImage.read(stream); }
			if (image.getWidth() > 4096 || image.getHeight() > 4096) {
				image.close();
				throw new IllegalArgumentException("图片宽高不能超过 4096 像素");
			}
			imageWidth = image.getWidth(); imageHeight = image.getHeight();
			DynamicTexture texture = new DynamicTexture(() -> "MC Lua IDE 背景", image);
			minecraft.getTextureManager().register(TEXTURE, texture);
			registered = true;
			texture.upload();
			return true;
		} catch (Exception exception) {
			error = "背景图片加载失败：" + exception.getMessage();
			return false;
		}
	}
}
