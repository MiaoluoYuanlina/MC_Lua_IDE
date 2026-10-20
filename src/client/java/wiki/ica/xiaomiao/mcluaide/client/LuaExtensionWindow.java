package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

import wiki.ica.xiaomiao.mcluaide.McLuaIdeMod;
import wiki.ica.xiaomiao.mcluaide.api.client.McLuaIdeWindowApi;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.CharacterEvent;
import java.util.Properties;

/** Host-managed frame; third-party code owns only the clipped content area. */
final class LuaExtensionWindow {
    void renderImGui() {
        if(content instanceof McLuaIdeWindowApi.NativeContent nativeContent && !failed) {
            int w=(int)imgui.ImGui.getContentRegionAvailX(),h=(int)imgui.ImGui.getContentRegionAvailY();
            if(w!=contentWidth||h!=contentHeight){contentWidth=w;contentHeight=h;call(()->content.resize(w,h));}
            call(nativeContent::renderImGui);
        } else if(content!=null&&!failed) renderLegacy();
        else imgui.ImGui.textWrapped(LuaUiText.text("扩展窗口发生错误，请查看客户端日志","The extension window failed; see the client log"));
    }
    private static final java.util.Set<String> WARNED=new java.util.HashSet<>();
    private final boolean[] legacyPressed=new boolean[3];
    /** One log line and one chat message per window id and game run, however many times it is opened. */
    private void warnLegacy(){
        if(!WARNED.add(id()))return;
        String en="IDE window \"" + id() + "\" uses the legacy Content.render(GuiGraphicsExtractor) API and runs in a compatibility layer. Update it to McLuaIdeWindowApi.NativeContent (extension API version " + McLuaIdeWindowApi.EXTENSION_API_VERSION + ").";
        McLuaIdeMod.LOGGER.warn(en);
        var player=net.minecraft.client.Minecraft.getInstance().player;
        if(player!=null)player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[MC Lua IDE] ⚠ "+LuaUiText.text("IDE 窗口“"+title()+"”("+id()+")仍使用旧版 Content 接口，已启用兼容显示；请扩展作者更新为 NativeContent。",en)).withStyle(net.minecraft.ChatFormatting.YELLOW));
    }
    /**
     * Old extensions draw with Minecraft widgets. Their drawing is rasterized into a texture at the old GUI scale, shown in the
     * window, and mouse and keyboard input is translated back into the content-local events the old interface expects.
     */
    private void renderLegacy(){
        warnLegacy();
        imgui.ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text,1f,.72f,.25f,1f);
        imgui.ImGui.textWrapped("⚠ "+LuaUiText.text("此扩展仍使用旧版 Content 接口，已通过兼容层显示（外观与输入可能与新版控件不同）。请更新为 NativeContent。","This extension uses the legacy Content API and is shown through a compatibility layer (look and input may differ). Please update it to NativeContent."));
        imgui.ImGui.popStyleColor();
        imgui.ImGui.separator();
        float scale=Math.max(1,net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale());
        float availableWidth=Math.max(8,imgui.ImGui.getContentRegionAvailX()),availableHeight=Math.max(8,imgui.ImGui.getContentRegionAvailY());
        int logicalWidth=Math.max(1,(int)(availableWidth/scale)),logicalHeight=Math.max(1,(int)(availableHeight/scale));
        if(logicalWidth!=contentWidth||logicalHeight!=contentHeight){contentWidth=logicalWidth;contentHeight=logicalHeight;call(()->content.resize(logicalWidth,logicalHeight));}
        focus(imgui.ImGui.isWindowFocused(imgui.flag.ImGuiFocusedFlags.ChildWindows));
        float left=imgui.ImGui.getCursorScreenPosX(),top=imgui.ImGui.getCursorScreenPosY();
        float localX=(imgui.ImGui.getMousePosX()-left)/scale,localY=(imgui.ImGui.getMousePosY()-top)/scale;
        int mouseX=(int)Math.floor(localX),mouseY=(int)Math.floor(localY);
        NativeUi.mediaScaled("ext:"+id(),logicalWidth,logicalHeight,scale,mouseX,mouseY,graphics->call(()->content.render(graphics,mouseX,mouseY)));
        imgui.ImGui.setCursorScreenPos(left,top);
        imgui.ImGui.invisibleButton("##legacy_surface",logicalWidth*scale,logicalHeight*scale,imgui.flag.ImGuiButtonFlags.MouseButtonLeft|imgui.flag.ImGuiButtonFlags.MouseButtonRight|imgui.flag.ImGuiButtonFlags.MouseButtonMiddle);
        boolean hovered=imgui.ImGui.isItemHovered();
        var io=imgui.ImGui.getIO();
        for(int button=0;button<3;button++){
            final int b=button;
            MouseButtonEvent event=new MouseButtonEvent(localX,localY,new net.minecraft.client.input.MouseButtonInfo(b,legacyModifiers(io)));
            if(hovered&&imgui.ImGui.isMouseClicked(b)){legacyPressed[b]=true;boolean twice=imgui.ImGui.isMouseDoubleClicked(b);call(()->content.mouseClicked(event,twice));}
            if(!legacyPressed[b])continue;
            if(imgui.ImGui.isMouseDown(b)){
                float dx=io.getMouseDeltaX()/scale,dy=io.getMouseDeltaY()/scale;
                if(dx!=0||dy!=0)call(()->content.mouseDragged(event,dx,dy));
            } else { legacyPressed[b]=false; call(()->content.mouseReleased(event)); }
        }
        if(hovered&&(io.getMouseWheel()!=0||io.getMouseWheelH()!=0))call(()->content.mouseScrolled(localX,localY,io.getMouseWheelH(),io.getMouseWheel()));
    }
    private static int legacyModifiers(imgui.ImGuiIO io){
        return (io.getKeyCtrl()?org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL:0)|(io.getKeyShift()?org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT:0)|(io.getKeyAlt()?org.lwjgl.glfw.GLFW.GLFW_MOD_ALT:0);
    }
    /** True when this window shows old-style content that expects keyboard events. */
    boolean legacyContent(){ return content!=null&&!failed&&!(content instanceof McLuaIdeWindowApi.NativeContent); }
	private static final int TITLE_HEIGHT = 28;
	private final int MIN_WIDTH;
	private final int MIN_HEIGHT;
	private static final int RESIZE_MARGIN = 6;
	private final Font font;
	private final WindowBoundsAnimation boundsAnimation = new WindowBoundsAnimation();
	private int screenWidth;
	private int screenHeight;
	private int x = -1;
	private int y = 40;
	private int width = -1;
	private int height = -1;
	private int floatingX = -1;
	private int floatingY = 40;
	private int floatingWidth = -1;
	private int floatingHeight = -1;
	private final McLuaIdeWindowApi.Definition definition;
	private final McLuaIdeWindowApi.Context context;
	private McLuaIdeWindowApi.Content content;
	private boolean failed;
	private boolean focused;
	private boolean contentPressed;
	private int contentWidth = -1, contentHeight = -1;
	private int resizeEdges;
	private int resizeStartX;
	private int resizeStartY;
	private int resizeStartWidth;
	private int resizeStartHeight;
	private double startMouseX;
	private double startMouseY;
	private double dragOffsetX;
	private double dragOffsetY;
	private boolean open;
	private boolean dragging;
	private boolean layoutLocked;
	private DockSide dockSide = DockSide.FLOATING;
	private float progress;

	LuaExtensionWindow(Font font, McLuaIdeWindowApi.Definition definition, McLuaIdeWindowApi.Context context) {
		this.font = font; this.definition = definition; this.context = context;
		MIN_WIDTH = definition.minWidth(); MIN_HEIGHT = definition.minHeight();
		width = definition.width(); height = definition.height();
	}
	String id() { return definition.id(); }
	String title() { return definition.title().getString(); }
	int preferredWidth() { return definition.width(); }
	int preferredHeight() { return definition.height(); }
	int minimumWidth() { return MIN_WIDTH; }
	int minimumHeight() { return MIN_HEIGHT; }

	private void call(Runnable action) {
		if (failed) return;
		try { action.run(); }
		catch (Exception | LinkageError error) {
			failed = true;
			McLuaIdeMod.LOGGER.error("IDE extension window {} failed", id(), error);
		}
	}

	void focus(boolean value) {
		if (focused == value) return;
		focused = value;
		if (content != null) call(() -> content.focusChanged(value));
	}
	boolean hasFocus() { return open && focused; }
	boolean keyPressed(KeyEvent event) {
		if (!hasFocus() || content == null || failed) return false;
		final boolean[] result = {false}; call(() -> result[0] = content.keyPressed(event)); return result[0];
	}
	boolean keyReleased(KeyEvent event) {
		if (!hasFocus() || content == null || failed) return false;
		call(() -> content.keyReleased(event)); return true;
	}
	boolean charTyped(CharacterEvent event) {
		if (!hasFocus() || content == null || failed) return false;
		call(() -> content.charTyped(event)); return true;
	}
	void dispose() {
		focus(false);
		if (content != null) {
			try { content.disposed(); } catch (Exception | LinkageError error) {
				McLuaIdeMod.LOGGER.error("Could not dispose IDE extension window {}", id(), error);
			}
		}
		content = null; failed = false; contentWidth = -1; contentHeight = -1;
	}

	void loadLayout(Properties properties) {
		String prefix = id() + ".";
		try {
			x = Integer.parseInt(properties.getProperty(prefix + "x", "-1"));
			y = Integer.parseInt(properties.getProperty(prefix + "y", "40"));
			width = Integer.parseInt(properties.getProperty(prefix + "width", "" + definition.width()));
			height = Integer.parseInt(properties.getProperty(prefix + "height", "" + definition.height()));
			ensureGeometry();
			setDockSide(DockSide.valueOf(properties.getProperty(prefix + "dock", "FLOATING")));
		} catch (IllegalArgumentException error) { setDockSide(DockSide.FLOATING); ensureGeometry(); }
		if (Boolean.parseBoolean(properties.getProperty(prefix + "open", "false"))) open();
	}
	void saveLayout(Properties properties) {
		String prefix = id() + ".";
		properties.setProperty(prefix + "x", "" + (dockSide.docked() ? floatingX : x));
		properties.setProperty(prefix + "y", "" + (dockSide.docked() ? floatingY : y));
		properties.setProperty(prefix + "width", "" + (dockSide.docked() ? floatingWidth : width));
		properties.setProperty(prefix + "height", "" + (dockSide.docked() ? floatingHeight : height));
		properties.setProperty(prefix + "dock", dockSide.name());
		properties.setProperty(prefix + "open", "" + open);
	}

	void resize(int screenWidth, int screenHeight) {
		this.screenWidth = screenWidth;
		this.screenHeight = screenHeight;
		ensureGeometry();
	}

	void open() {
		if (open && content != null) return;
		open = true;
		if (content == null && !failed) call(() -> {
			content = java.util.Objects.requireNonNull(definition.factory().apply(context), "Window factory returned null");
		});
		if (content != null) call(() -> content.opened());
	}

	void close() {
		if (!open) return;
		open = false; dragging = false; resizeEdges = 0; contentPressed = false;
		focus(false);
		if (content != null) call(() -> content.closed());
	}

	boolean isOpen() { return open; }
	boolean isVisible() { return progress > 0.01F || open; }
	boolean isInteracting() { return dragging || resizeEdges != 0 || contentPressed; }
	boolean isDragging() { return dragging; }
	DockSide dockSide() { return dockSide; }
	void setLayoutLocked(boolean locked) { layoutLocked = locked; }

	void setDockSide(DockSide side) {
		if (side == null || side == dockSide) return;
		if (!dockSide.docked() && side.docked()) {
			floatingX = x; floatingY = y; floatingWidth = width; floatingHeight = height;
		}
		DockSide previous = dockSide;
		dockSide = side;
		resizeEdges = 0;
		if (previous.docked() && !side.docked()) {
			x = floatingX; y = floatingY; width = floatingWidth; height = floatingHeight;
			ensureGeometry();
			boundsAnimation.snapTo(x, y, width, height);
		}
	}

	void setDockBounds(int x, int y, int width, int height) {
		if (WindowBoundsAnimation.isImmediate()) {
			this.x = x; this.y = y; this.width = width; this.height = height; progress = 1;
			boundsAnimation.snapTo(x,y,width,height); return;
		}
		if (dockSide.docked()) boundsAnimation.animateTo(this.x, this.y, this.width, this.height,
				x, y, width, height);
	}

	void tick() {
		if (open && content == null) open();
		if (open && content != null) call(() -> content.tick());
		float target = open ? 1.0F : 0.0F;
		progress += (target - progress) * 0.28F;
		if (Math.abs(target - progress) < 0.01F) progress = target;
		if (!isInteracting()) {
			WindowBoundsAnimation.Bounds bounds = boundsAnimation.tick(x, y, width, height);
			x = bounds.x(); y = bounds.y(); width = bounds.width(); height = bounds.height();
		}
	}

	void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!isVisible()) return;
		ensureGeometry();
		int left = panelX();
		int right = left + width;
		int bottom = y + height;
		graphics.fill(left - 4, y + 4, right + 4, bottom + 4, 0x70000000);
		graphics.fill(left, y, right, bottom, 0xFE101821);
		graphics.outline(left, y, width, height, 0xFF657F9C);
		graphics.fill(left + 1, y + 1, right - 1, y + TITLE_HEIGHT, 0xFF223448);
		graphics.text(font, font.plainSubstrByWidth(title(), Math.max(1, width - 42)), left + 9, y + 9, 0xFFE4F0FF);
		boolean closeHover = inside(mouseX, mouseY, right - 25, y + 4, right - 5, y + 24);
		graphics.fill(right - 25, y + 4, right - 5, y + 24, closeHover ? 0xFFB34851 : 0xFF385067);
		graphics.centeredText(font, "×", right - 15, y + 10, 0xFFFFFFFF);

		int contentLeft = left + 6, contentTop = y + TITLE_HEIGHT + 4;
		int cw = Math.max(1, width - 12), ch = Math.max(1, height - TITLE_HEIGHT - 10);
		if (content != null && (cw != contentWidth || ch != contentHeight)) {
			contentWidth = cw; contentHeight = ch;
			call(() -> content.resize(cw, ch));
		}
		graphics.enableScissor(contentLeft, contentTop, contentLeft + cw, contentTop + ch);
		graphics.pose().pushMatrix();
		graphics.pose().translate(contentLeft, contentTop);
		try {
			if (content != null) call(() -> content.render(graphics, mouseX - contentLeft, mouseY - contentTop));
			if (failed) graphics.text(font, "扩展窗口异常，请查看游戏日志", 4, 4, 0xFFFF7777);
		} finally {
			graphics.pose().popMatrix();
			graphics.disableScissor();
		}
		int edges = resizeEdges != 0 ? resizeEdges : resizeEdgesAt(mouseX, mouseY);
		int resizeColor = 0xFF97C8F7;
		if ((edges & 1) != 0) graphics.fill(left - 1, y, left + 2, bottom, resizeColor);
		if ((edges & 2) != 0) graphics.fill(right - 2, y, right + 1, bottom, resizeColor);
		if ((edges & 4) != 0) graphics.fill(left, y - 1, right, y + 2, resizeColor);
		if ((edges & 8) != 0) graphics.fill(left, bottom - 2, right, bottom + 1, resizeColor);
	}

	boolean contains(double mouseX, double mouseY) {
		return isVisible() && inside(mouseX, mouseY, panelX(), y, panelX() + width, y + height);
	}
	boolean hitFrame(double mouseX, double mouseY) {
		return open && progress >= 0.5F && (contains(mouseX, mouseY) || resizeEdgesAt(mouseX, mouseY) != 0);
	}

	boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (!open || !contains(mouseX, mouseY)) return false;
		if (content != null) call(() -> content.mouseScrolled(mouseX - panelX() - 6,
				mouseY - y - TITLE_HEIGHT - 4, horizontal, vertical));
		return true;
	}
	private MouseButtonEvent localEvent(MouseButtonEvent event) {
		return new MouseButtonEvent(event.x() - panelX() - 6, event.y() - y - TITLE_HEIGHT - 4, event.buttonInfo());
	}
	boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (!open || progress < 0.5F) return false;
		int left = panelX();
		int right = left + width;
		if (inside(event.x(), event.y(), right - 25, y + 4, right - 5, y + 24)) {
			close(); return true;
		}
		if (event.button() == 0) {
			int edges = resizeEdgesAt(event.x(), event.y());
			if (edges != 0) {
				resizeEdges = edges;
				startMouseX = event.x(); startMouseY = event.y();
				resizeStartX = left; resizeStartY = y;
				resizeStartWidth = width; resizeStartHeight = height;
				return true;
			}
		}
		if (!contains(event.x(), event.y())) return false;
		focus(true);
		if (event.button() == 0 && event.y() <= y + TITLE_HEIGHT) {
			if (dockSide.docked() && layoutLocked) return true;
			dragging = true;
			dragOffsetX = event.x() - left;
			dragOffsetY = event.y() - y;
		} else if (event.y() > y + TITLE_HEIGHT && content != null) {
			contentPressed = true;
			call(() -> content.mouseClicked(localEvent(event), doubleClick));
		}
		return true;
	}

	boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (resizeEdges != 0) {
			int dx = (int) Math.round(event.x() - startMouseX);
			int dy = (int) Math.round(event.y() - startMouseY);
			int left = resizeStartX;
			int right = left + resizeStartWidth;
			int top = resizeStartY;
			int bottom = top + resizeStartHeight;
			int minimumWidth = Math.min(MIN_WIDTH, screenWidth);
			int minimumHeight = Math.min(MIN_HEIGHT, screenHeight);
			if ((resizeEdges & 1) != 0) left = Math.max(0, Math.min(right - minimumWidth, left + dx));
			if ((resizeEdges & 2) != 0) right = Math.min(screenWidth, Math.max(left + minimumWidth, right + dx));
			if ((resizeEdges & 4) != 0) top = Math.max(0, Math.min(bottom - minimumHeight, top + dy));
			if ((resizeEdges & 8) != 0) bottom = Math.min(screenHeight, Math.max(top + minimumHeight, bottom + dy));
			x = left; y = top; width = right - left; height = bottom - top;
			boundsAnimation.snapTo(x, y, width, height);
			return true;
		}
		if (!dragging) {
			if (!contentPressed) return false;
			if (content != null) call(() -> content.mouseDragged(localEvent(event), dragX, dragY));
			return true;
		}
		if (dockSide.docked()) {
			setDockSide(DockSide.FLOATING);
			dragOffsetX = Math.min(Math.max(18, dragOffsetX), Math.max(18, width - 18));
			dragOffsetY = 12;
		}
		x = Math.clamp((int) Math.round(event.x() - dragOffsetX), 0, Math.max(0, screenWidth - width));
		y = Math.clamp((int) Math.round(event.y() - dragOffsetY), 0, Math.max(0, screenHeight - height));
		boundsAnimation.snapTo(x, y, width, height);
		return true;
	}

	boolean mouseReleased(MouseButtonEvent event) {
		if (contentPressed) {
			contentPressed = false;
			if (content != null) call(() -> content.mouseReleased(localEvent(event)));
			return true;
		}
		if (!isInteracting()) return false;
		dragging = false;
		resizeEdges = 0;
		return true;
	}

	private int panelX() {
		float eased = 1.0F - (1.0F - progress) * (1.0F - progress);
		return Math.round(screenWidth + (x - screenWidth) * eased);
	}

	private int resizeEdgesAt(double mouseX, double mouseY) {
		if (dockSide.docked()) return 0;
		int left = panelX();
		int right = left + width;
		int bottom = y + height;
		if (!inside(mouseX, mouseY, left - RESIZE_MARGIN, y - RESIZE_MARGIN,
				right + RESIZE_MARGIN, bottom + RESIZE_MARGIN)) return 0;
		int edges = 0;
		if (Math.abs(mouseX - left) <= RESIZE_MARGIN) edges |= 1;
		if (Math.abs(mouseX - right) <= RESIZE_MARGIN) edges |= 2;
		if (Math.abs(mouseY - y) <= RESIZE_MARGIN) edges |= 4;
		if (Math.abs(mouseY - bottom) <= RESIZE_MARGIN) edges |= 8;
		return edges;
	}

	private void ensureGeometry() {
		if (screenWidth <= 0 || screenHeight <= 0 || dockSide.docked()) return;
		int minWidth = Math.min(MIN_WIDTH, screenWidth);
		int minHeight = Math.min(MIN_HEIGHT, screenHeight);
		if (width < 0) width = Math.min(screenWidth, Math.max(minWidth, (int) (screenWidth * 0.33F)));
		if (height < 0) height = Math.min(screenHeight, Math.max(minHeight, (int) (screenHeight * 0.48F)));
		width = Math.clamp(width, minWidth, screenWidth);
		height = Math.clamp(height, minHeight, screenHeight);
		if (x < 0) x = Math.max(0, screenWidth - width - 20);
		x = Math.clamp(x, 0, screenWidth - width);
		y = Math.clamp(y, 0, screenHeight - height);
	}

	private static boolean inside(double mouseX, double mouseY, int left, int top, int right, int bottom) {
		return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom;
	}
}
