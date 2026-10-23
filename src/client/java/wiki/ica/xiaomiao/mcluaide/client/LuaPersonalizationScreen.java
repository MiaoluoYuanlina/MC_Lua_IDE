package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;

/** Client-only appearance page inspired by a terminal settings pane. */
final class LuaPersonalizationScreen extends Screen {
    private LuaWindowShortcuts.Target capturingTarget;
    /** One row per window: its current chord, a button that waits for the next key combination, and a clear button. */
    private void shortcutsImGui() {
        var shortcuts=draft.windowShortcuts;
        imgui.ImGui.textWrapped(LuaUiText.text("点击“设置”后直接按下新的快捷键：Ctrl / Shift / Alt + F1～F12。Esc 取消。"));
        imgui.ImGui.textDisabled(LuaUiText.text("窗口快捷键优先于同组合的调试快捷键。"));
        if(capturingTarget!=null) {
            LuaWindowShortcuts.capturing();
            var io=imgui.ImGui.getIO();
            if(imgui.ImGui.isKeyPressed(imgui.flag.ImGuiKey.Escape,false)) {
                capturingTarget=null; status=LuaUiText.text("已取消修改");
            } else for(int f=1;f<=12&&capturingTarget!=null;f++) {
                if(!imgui.ImGui.isKeyPressed(imgui.flag.ImGuiKey.F1+f-1,false))continue;
                int modifiers=(io.getKeyShift()?LuaWindowShortcuts.SHIFT:0)|(io.getKeyCtrl()?LuaWindowShortcuts.CTRL:0)|(io.getKeyAlt()?LuaWindowShortcuts.ALT:0);
                if(modifiers==0){status=LuaUiText.text("请同时按住 Ctrl、Shift 或 Alt，单独的 F 键不会被拦截");break;}
                var replaced=shortcuts.get(modifiers,f);
                shortcuts.bind(capturingTarget,modifiers,f);
                status=LuaUiText.text("已设置 ")+LuaWindowShortcuts.chordLabel(modifiers,f)+" → "+LuaUiText.text(capturingTarget.label)
                        +(replaced!=LuaWindowShortcuts.Target.NONE&&replaced!=capturingTarget?" · "+LuaUiText.text("取代了")+" "+LuaUiText.text(replaced.label):"")
                        +LuaUiText.text(" · 点击保存应用");
                capturingTarget=null;
            }
        }
        if(imgui.ImGui.beginTable("window_shortcuts",3,imgui.flag.ImGuiTableFlags.RowBg|imgui.flag.ImGuiTableFlags.BordersInnerH|imgui.flag.ImGuiTableFlags.SizingStretchProp)) {
            imgui.ImGui.tableSetupColumn(LuaUiText.text("窗口"),imgui.flag.ImGuiTableColumnFlags.WidthStretch,2);
            imgui.ImGui.tableSetupColumn(LuaUiText.text("快捷键"),imgui.flag.ImGuiTableColumnFlags.WidthStretch,1.4f);
            imgui.ImGui.tableSetupColumn(LuaUiText.text("操作"),imgui.flag.ImGuiTableColumnFlags.WidthFixed,170);
            imgui.ImGui.tableHeadersRow();
            for(var target:LuaWindowShortcuts.Target.values()) {
                if(target==LuaWindowShortcuts.Target.NONE)continue;
                imgui.ImGui.pushID(target.name());
                imgui.ImGui.tableNextRow(); imgui.ImGui.tableNextColumn();
                imgui.ImGui.alignTextToFramePadding(); imgui.ImGui.text(LuaUiText.text(target.label));
                imgui.ImGui.tableNextColumn();
                boolean waiting=capturingTarget==target;
                String chords=shortcuts.chordsOf(target);
                if(waiting)imgui.ImGui.textColored(1f,.83f,.4f,1f,LuaUiText.text("请按下新的快捷键…"));
                else if(chords.isEmpty())imgui.ImGui.textDisabled(LuaUiText.text("未绑定"));
                else imgui.ImGui.text(chords);
                imgui.ImGui.tableNextColumn();
                if(imgui.ImGui.button(waiting?LuaUiText.text("取消"):LuaUiText.text("设置")))capturingTarget=waiting?null:target;
                imgui.ImGui.sameLine();
                imgui.ImGui.beginDisabled(chords.isEmpty());
                if(imgui.ImGui.button(LuaUiText.text("清除"))) { shortcuts.unbind(target); if(waiting)capturingTarget=null; }
                imgui.ImGui.endDisabled();
                imgui.ImGui.popID();
            }
            imgui.ImGui.endTable();
        }
        NativeUi.action("恢复全部窗口快捷键默认值",()->{draft.windowShortcuts.reset();capturingTarget=null;});
    }
    void renderImGui() {
        for(var tab:Section.values()) { if(imgui.ImGui.button(LuaUiText.text(tab.label)))section=tab;NativeUi.next(tab.label); }
        imgui.ImGui.newLine();
        switch(section) {
            case TEXT -> {
                draft.theme=NativeUi.combo("配色方案",draft.theme,LuaSyntaxHighlighter.Theme.values());
                draft.fontFace=NativeUi.combo("字体",draft.fontFace,LuaPersonalizationSettings.FontFace.values());
                draft.fontPercent=NativeUi.integer("字号 %",draft.fontPercent,10,300);
                draft.lineHeight=(float)Math.clamp(NativeUi.number("行高",draft.lineHeight),.8,2);
                draft.cellWidth=(float)Math.clamp(NativeUi.number("字符宽度",draft.cellWidth),.5,2);
                draft.codeWheelSensitivity=(float)Math.clamp(NativeUi.number("代码滚轮灵敏度",draft.codeWheelSensitivity),.1,20);
                draft.codeWordWrap=NativeUi.check("过长代码自动换行",draft.codeWordWrap);
            }
            case CURSOR -> {
                draft.cursorShape=NativeUi.combo("光标形状",draft.cursorShape,LuaPersonalizationSettings.CursorShape.values());
                draft.cursorBlink=NativeUi.check("光标闪烁",draft.cursorBlink);
                draft.cursorWidth=NativeUi.integer("光标宽度",draft.cursorWidth,1,4);
                draft.cursorColor=NativeUi.color("光标颜色",draft.cursorColor);
            }
            case BACKGROUND -> {
                draft.backgroundFile=NativeUi.field("背景图片文件名",draft.backgroundFile);
                NativeUi.action("打开背景图片目录",()->{try{java.nio.file.Files.createDirectories(LuaPersonalizationSettings.BACKGROUNDS);Util.getPlatform().openPath(LuaPersonalizationSettings.BACKGROUNDS);}catch(Exception e){status=e.getMessage();}});
                draft.backgroundOpacity=NativeUi.integer("背景不透明度 %",draft.backgroundOpacity,0,80);
                draft.backgroundFit=NativeUi.combo("背景图像拉伸模式",draft.backgroundFit,LuaPersonalizationSettings.BackgroundFit.values());
                draft.backgroundAlignment=NativeUi.combo("背景图像对齐",draft.backgroundAlignment,List.of(LuaPersonalizationSettings.BackgroundAlignment.values()),v->LuaUiText.text(v.label));
            }
            case WINDOW -> {
                draft.resizeDockWhenLocked=NativeUi.check("固定布局后仍可调整边框",draft.resizeDockWhenLocked);
                imgui.ImGui.beginDisabled(!WindowChrome.supported());
                boolean merged=NativeUi.check("标题栏与菜单合并",draft.integratedTitleBar);
                if(WindowChrome.supported())draft.integratedTitleBar=merged;
                imgui.ImGui.endDisabled();
                imgui.ImGui.textDisabled(WindowChrome.supportNote());
            }
            case SYNTAX -> {
                for(var role:LuaSyntaxHighlighter.ColorRole.values()) {
                    int before=draft.syntaxColor(role),after=NativeUi.color(role.name(),0xFF000000|before);
                    if(after!=(0xFF000000|before))draft.syntaxColors.put(role,after&0xFFFFFF);
                }
                NativeUi.action("恢复默认配色",draft.syntaxColors::clear);
            }
            case SHORTCUTS -> shortcutsImGui();
        }
        if(section!=Section.SHORTCUTS)capturingTarget=null;
        imgui.ImGui.separator();
        NativeUi.action("保存并应用",()->{try{draft.save();previous.setPersonalization(draft.copy());status="已保存并应用";}catch(Exception e){status=e.getMessage();}});
        NativeUi.status(status);
    }
	private enum Section { TEXT("文本"), CURSOR("光标"), BACKGROUND("背景图"), WINDOW("窗口布局"), SHORTCUTS("快捷键"), SYNTAX("文本 / 语法配色");
		final String label; Section(String label) { this.label = label; } }
	private record Hit(int left, int top, int right, int bottom, Runnable action) {
		boolean contains(double x, double y) { return x >= left && x < right && y >= top && y < bottom; }
	}

	private final LuaIdeScreen previous;
	private final LuaPersonalizationSettings draft;
	private final List<Hit> hits = new ArrayList<>();
	private Section section = Section.TEXT;
	private int scroll;
	private int contentHeight;
	private int panelLeft, panelTop, panelRight, panelBottom, contentLeft, contentRight, viewTop, viewBottom;
	private int floatingX = -1, floatingY = -1;
	private int floatingWidth = -1, floatingHeight = -1;
	private DockSide dockSide = DockSide.FLOATING;
	private boolean layoutLocked;
	private int resizeEdges;
	private double resizeStartX, resizeStartY;
	private int resizeLeft, resizeTop, resizeRight, resizeBottom;
	private boolean embedded, dragging;
	private boolean closing, applyingAnimatedBounds;
	private final WindowBoundsAnimation boundsAnimation = new WindowBoundsAnimation();
	private double dragOffsetX, dragOffsetY;
	private Button saveButton, cancelButton;
	private boolean compact;
	private String status = LuaUiText.text("设置仅保存在当前客户端 · 保存后立即应用");
	private Font previewFont;
	private LuaPersonalizationSettings.FontFace previewFontFace;
	private LuaSyntaxHighlighter.ColorRole selectedColorRole = LuaSyntaxHighlighter.ColorRole.KEYWORD;
	private EditBox hexInput;
	private EditBox wheelSensitivityInput;
	private Button applyColorButton;
	private Button resetColorButton;
	private boolean loadingHex;
	private boolean hexDirty;
	private String syntaxMessage = LuaUiText.text("输入 #RRGGBB，按回车应用");
	private int shortcutModifiers = LuaWindowShortcuts.CTRL, shortcutFunction = 1;
	private boolean shortcutChoicesOpen;

	LuaPersonalizationScreen(LuaIdeScreen previous, LuaPersonalizationSettings current) {
		super(Component.literal(LuaUiText.text("MC Lua IDE · 个性化")));
		this.previous = previous;
		this.draft = current.copy();
	}

	void openEmbedded(int width, int height) {
		embedded = true;
		init(width, height);
		rememberFloatingBounds();
		int targetX = panelLeft, targetY = panelTop;
		int targetWidth = panelRight - panelLeft, targetHeight = panelBottom - panelTop;
		applyingAnimatedBounds = true;
		applyBounds(width + 12, targetY, targetWidth, targetHeight);
		applyingAnimatedBounds = false;
		boundsAnimation.animateTo(panelLeft, panelTop, panelRight - panelLeft, panelBottom - panelTop,
				targetX, targetY, targetWidth, targetHeight);
	}

	boolean isClosing() { return closing; }
	void reopen() {
		if (!closing) return;
		closing = false;
		if (!dockSide.docked()) boundsAnimation.animateTo(panelLeft, panelTop,
				panelRight - panelLeft, panelBottom - panelTop,
				floatingX, floatingY, floatingWidth, floatingHeight);
	}

	boolean contains(double x, double y) {
		int margin = dockSide.docked() ? 0 : 5;
		return embedded && !closing && x >= panelLeft - margin && x < panelRight + margin
				&& y >= panelTop - margin && y < panelBottom + margin;
	}

	boolean isDraggingPanel() { return dragging; }
	DockSide dockSide() { return dockSide; }
	void setLayoutLocked(boolean locked) { layoutLocked = locked; }
	WindowGeometry captureGeometry() {
		if (!dockSide.docked() && !boundsAnimation.active()) rememberFloatingBounds();
		return new WindowGeometry(floatingX, floatingY, floatingWidth, floatingHeight);
	}
	void restoreGeometry(WindowGeometry geometry) {
		if (geometry == null) return;
		floatingX = geometry.x(); floatingY = geometry.y();
		floatingWidth = geometry.width(); floatingHeight = geometry.height();
	}

	void setDockSide(DockSide side) {
		if (dockSide == side) return;
		if (!dockSide.docked() && !boundsAnimation.active()) rememberFloatingBounds();
		DockSide previous = dockSide;
		dockSide = side;
		resizeEdges = 0;
		if (previous.docked() && !side.docked() && floatingWidth > 0 && floatingHeight > 0)
			boundsAnimation.animateTo(panelLeft, panelTop, panelRight - panelLeft, panelBottom - panelTop,
					Math.clamp(floatingX, 0, Math.max(0, width - floatingWidth)),
					Math.clamp(floatingY, 0, Math.max(0, height - floatingHeight)),
					Math.min(floatingWidth, width), Math.min(floatingHeight, height));
	}
	void setDockBounds(int x, int y, int width, int height) {
		if (WindowBoundsAnimation.isImmediate() && !closing) {
			applyingAnimatedBounds = true;
			try { applyBounds(x,y,width,height); boundsAnimation.snapTo(x,y,width,height); }
			finally { applyingAnimatedBounds = false; } return;
		}
		if (dockSide.docked() && !closing) boundsAnimation.animateTo(panelLeft, panelTop,
				panelRight - panelLeft, panelBottom - panelTop, x, y, width, height);
	}

	@Override public void tick() {
		super.tick();
		if (dragging || resizeEdges != 0) return;
		WindowBoundsAnimation.Bounds bounds = boundsAnimation.tick(panelLeft, panelTop,
				panelRight - panelLeft, panelBottom - panelTop);
		applyingAnimatedBounds = true;
		applyBounds(bounds.x(), bounds.y(), bounds.width(), bounds.height());
		applyingAnimatedBounds = false;
		if (closing && !bounds.moving()) previous.closePersonalizationPanel(this);
	}

	void showBackgroundAlignmentForTesting() {
		section = Section.BACKGROUND;
		scroll = 245;
	}

	void showSyntaxColorsForTesting() {
		section = Section.SYNTAX;
		scroll = 0;
		selectColorRole(LuaSyntaxHighlighter.ColorRole.KEYWORD);
	}

	@Override
	protected void init() {
		int panelWidth = Math.min(floatingWidth > 0 ? floatingWidth : 800, width - 24);
		int panelHeight = embedded ? Math.min(floatingHeight > 0 ? floatingHeight : 470, height - 24)
				: height - (height < 350 ? 10 : 28);
		compact = panelHeight < 350;
		panelLeft = embedded && floatingX >= 0 ? Math.clamp(floatingX, 0, width - panelWidth)
				: (width - panelWidth) / 2;
		panelRight = panelLeft + panelWidth;
		panelTop = embedded && floatingY >= 0 ? Math.clamp(floatingY, 0, height - panelHeight)
				: embedded ? (height - panelHeight) / 2 : compact ? 5 : 14;
		panelBottom = panelTop + panelHeight;
		contentLeft = panelLeft + Math.min(124, Math.max(84, panelWidth / 6));
		contentRight = panelRight - 12;
		viewTop = panelTop + (compact ? 34 : 48);
		viewBottom = panelBottom - (panelWidth < 500 ? compact ? 66 : 79 : compact ? 39 : 52);
		saveButton = addRenderableWidget(Button.builder(Component.literal(LuaUiText.text("保存并应用")), ignored -> save())
				.bounds(panelRight - 114, panelBottom - (compact ? 29 : 37), 96, compact ? 20 : 22).build());
		cancelButton = addRenderableWidget(Button.builder(Component.literal(LuaUiText.text("放弃更改")), ignored -> onClose())
				.bounds(panelRight - 26, panelTop + 4, 20, 20).build());
		cancelButton.visible = false;
		int footerY = panelBottom - (compact ? 29 : 37);
		int colorY = panelWidth < 500 ? footerY - 27 : footerY;
		wheelSensitivityInput = new EditBox(font, 0, 0, 80, 22, Component.literal(LuaUiText.text("代码滚轮灵敏度倍率")));
		wheelSensitivityInput.setMaxLength(8);
		wheelSensitivityInput.setValue(Float.toString(draft.codeWheelSensitivity()));
		wheelSensitivityInput.setResponder(value -> {
			Float parsed = LuaPersonalizationSettings.parseCodeWheelSensitivity(value);
			if (parsed != null) draft.codeWheelSensitivity = parsed;
		});
		wheelSensitivityInput.visible = false;
		// Render inside the scroll viewport, not with the footer widgets.
		addWidget(wheelSensitivityInput);
		hexInput = new EditBox(font, panelLeft + 39, colorY, 73, compact ? 20 : 22,
				Component.literal(LuaUiText.text("语法颜色十六进制值")));
		hexInput.setMaxLength(7);
		hexInput.setResponder(value -> { if (!loadingHex) hexDirty = true; });
		addRenderableWidget(hexInput);
		applyColorButton = addRenderableWidget(Button.builder(Component.literal(LuaUiText.text("应用")), ignored -> applyHexColor())
				.bounds(panelLeft + 115, colorY, 36, compact ? 20 : 22).build());
		resetColorButton = addRenderableWidget(Button.builder(Component.literal(LuaUiText.text("重置")), ignored -> resetHexColor())
				.bounds(panelLeft + 154, colorY, 36, compact ? 20 : 22).build());
		selectColorRole(selectedColorRole);
	}

	private void rememberFloatingBounds() {
		floatingX = panelLeft; floatingY = panelTop;
		floatingWidth = panelRight - panelLeft; floatingHeight = panelBottom - panelTop;
	}

	private void applyBounds(int x, int y, int panelWidth, int panelHeight) {
		if (panelLeft == x && panelTop == y && panelRight - panelLeft == panelWidth
				&& panelBottom - panelTop == panelHeight) return;
		panelLeft = x; panelTop = y; panelRight = x + panelWidth; panelBottom = y + panelHeight;
		compact = panelHeight < 350;
		contentLeft = panelLeft + Math.min(124, Math.max(76, panelWidth / 6));
		contentRight = panelRight - 12;
		viewTop = panelTop + (compact ? 34 : 48);
		viewBottom = panelBottom - (panelWidth < 500 ? compact ? 66 : 79 : compact ? 39 : 52);
		if (saveButton != null) {
			int footerY = panelBottom - (compact ? 29 : 37);
			int colorY = panelWidth < 500 ? footerY - 27 : footerY;
			saveButton.setX(panelRight - 114); saveButton.setY(footerY);
			cancelButton.setX(panelRight - 26); cancelButton.setY(panelTop + 4);
			cancelButton.setWidth(20); cancelButton.setHeight(20); cancelButton.visible = false;
			hexInput.setX(panelLeft + 39); hexInput.setY(colorY);
			applyColorButton.setX(panelLeft + 115); applyColorButton.setY(colorY);
			resetColorButton.setX(panelLeft + 154); resetColorButton.setY(colorY);
		}
		if (!dockSide.docked() && !applyingAnimatedBounds) rememberFloatingBounds();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		hits.clear();
		wheelSensitivityInput.visible = false;
		wheelSensitivityInput.active = false;
		if (!embedded) graphics.fill(0, 0, width, height, 0xE9090C12);
		graphics.fill(panelLeft - 4, panelTop + 4, panelRight + 4, panelBottom + 4, 0x70000000);
		graphics.fill(panelLeft, panelTop, panelRight, panelBottom, 0xFE0E141D);
		graphics.outline(panelLeft, panelTop, panelRight - panelLeft, panelBottom - panelTop, 0xFFB084F5);
		graphics.fill(panelLeft + 1, panelTop + 1, panelRight - 1, panelTop + (compact ? 29 : 38), 0xFF2B2040);
		graphics.text(font, font.plainSubstrByWidth(LuaUiText.text("◆  外观  ›  个性化"), panelRight - panelLeft - 54), panelLeft + 15, panelTop + (compact ? 9 : 13), 0xFFD6B7FF);
		if (panelRight - panelLeft >= 450) graphics.text(font, LuaUiText.text("外观、布局与快捷键"), panelRight - 177, panelTop + (compact ? 9 : 14), 0xFF93A6B8);
		IdeWindowCloseButton.render(graphics, font, panelRight, panelTop, mouseX, mouseY);
		int tabY = viewTop + 9;
		int tabStep = Math.clamp((viewBottom - viewTop - 18) / 5, 18, 36), tabHeight = tabStep - 4;
		for (Section candidate : List.of(Section.TEXT, Section.CURSOR, Section.BACKGROUND, Section.WINDOW, Section.SHORTCUTS)) {
			int tabLeft = panelLeft + 10, tabRight = contentLeft - 8;
			graphics.fill(tabLeft, tabY, tabRight, tabY + tabHeight,
					(section == candidate || section == Section.SYNTAX && candidate == Section.TEXT)
								? 0xFF344C69 : hovered(mouseX, mouseY, tabLeft, tabY, tabRight, tabY + tabHeight)
							? 0xFF29384A : 0xFF1B2530);
			if (section == candidate || section == Section.SYNTAX && candidate == Section.TEXT)
				graphics.fill(tabLeft, tabY, tabLeft + 3, tabY + tabHeight, 0xFF86C5FF);
			graphics.text(font, LuaUiText.text(candidate.label), tabLeft + 12, tabY + (tabHeight - 9) / 2,
					section == candidate || section == Section.SYNTAX && candidate == Section.TEXT
							? 0xFFFFFFFF : 0xFFB0BFCE);
			hits.add(new Hit(tabLeft, tabY, tabRight, tabY + tabHeight, () -> {
				if (applyPendingHex()) { section = candidate; scroll = 0; }
			}));
			tabY += tabStep;
		}
		graphics.fill(contentLeft, viewTop, contentRight, viewBottom, 0xFF111820);
		graphics.enableScissor(contentLeft, viewTop, contentRight, viewBottom);
		int y = viewTop + 12 - scroll;
		if (!compact && section != Section.SHORTCUTS) {
			renderPreview(graphics, y);
			y += 105;
		}
		graphics.text(font, LuaUiText.text(section.label), contentLeft + 12, y + 1, 0xFFE9F3FF);
		y += 22;
			y = switch (section) {
			case TEXT -> renderTextRows(graphics, mouseX, mouseY, y);
			case CURSOR -> renderCursorRows(graphics, mouseX, mouseY, y);
			case BACKGROUND -> renderBackgroundRows(graphics, mouseX, mouseY, y);
			case WINDOW -> renderWindowRows(graphics, mouseX, mouseY, y);
			case SHORTCUTS -> renderShortcutRows(graphics, mouseX, mouseY, y);
			case SYNTAX -> renderSyntaxColorRows(graphics, mouseX, mouseY, y);
		};
		contentHeight = Math.max(0, y + scroll - viewTop + 10);
		if (getFocused() == wheelSensitivityInput && !wheelSensitivityInput.visible) setFocused(null);
		graphics.disableScissor();
		if (contentHeight > viewBottom - viewTop) {
			int track = viewBottom - viewTop - 6;
			int thumb = Math.max(18, track * (viewBottom - viewTop) / contentHeight);
			int thumbY = viewTop + 3 + scroll * (track - thumb) / Math.max(1, contentHeight - (viewBottom - viewTop));
			graphics.fill(contentRight - 4, viewTop + 3, contentRight - 2, viewBottom - 3, 0xFF263546);
			graphics.fill(contentRight - 5, thumbY, contentRight - 1, thumbY + thumb, 0xFF73B8F0);
		}
		boolean narrowFooter = panelRight - panelLeft < 500;
		graphics.fill(panelLeft + 1, panelBottom - (narrowFooter ? compact ? 61 : 74 : compact ? 34 : 48),
				panelRight - 1, panelBottom - 1, 0xFF202A36);
		String display = LuaEditorBackground.error().isEmpty() ? status : LuaEditorBackground.error();
		boolean syntax = section == Section.SYNTAX;
		hexInput.visible = syntax; hexInput.active = syntax;
		if (syntax) hexInput.setTextColor(LuaPersonalizationSettings.rgbValue(hexInput.getValue()) == null
				? 0xFFFF7777 : 0xFFFFFFFF);
		applyColorButton.visible = syntax; applyColorButton.active = syntax;
		resetColorButton.visible = syntax; resetColorButton.active = syntax;
		if (syntax) {
			graphics.text(font, LuaUiText.text("色值"), panelLeft + 11,
					panelBottom - (narrowFooter ? compact ? 49 : 55 : compact ? 22 : 28), 0xFFAFC4D7);
		} else {
			graphics.text(font, font.plainSubstrByWidth(display, Math.max(80, panelRight - panelLeft - 240)),
					panelLeft + 14, panelBottom - (narrowFooter ? compact ? 49 : 55 : compact ? 22 : 28),
					display.startsWith("无法") || display.startsWith("背景图片加载失败")
							? 0xFFFF9999 : 0xFFAFC4D7);
		}
		if (embedded && !dockSide.docked()) ResizeBorderHint.render(graphics,
				panelLeft, panelTop, panelRight, panelBottom,
				resizeEdges != 0 ? resizeEdges : resizeEdgesAt(mouseX, mouseY), 0xFFB084F5);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private int resizeEdgesAt(double x, double y) {
		if (dockSide.docked() || x < panelLeft - 5 || x > panelRight + 5
				|| y < panelTop - 5 || y > panelBottom + 5) return 0;
		int edges = 0;
		if (Math.abs(x - panelLeft) <= 5) edges |= 1;
		if (Math.abs(x - panelRight) <= 5) edges |= 2;
		if (Math.abs(y - panelTop) <= 5) edges |= 4;
		if (Math.abs(y - panelBottom) <= 5) edges |= 8;
		return edges;
	}

	private void renderPreview(GuiGraphicsExtractor graphics, int y) {
		int left = contentLeft + 10, right = contentRight - 12, bottom = y + 93;
		graphics.fill(left, y, right, bottom, draft.theme.background());
		LuaEditorBackground.render(graphics, minecraft, draft, left, y, right, bottom);
		graphics.outline(left, y, right - left, bottom - y, 0xFF52657B);
		graphics.text(font, LuaUiText.text("预览 · example.lua"), left + 12, y + 10, 0xFF86BDEB);
		if (previewFont == null || previewFontFace != draft.fontFace) {
			previewFont = draft.editorFont(minecraft);
			previewFontFace = draft.fontFace;
		}
		float scale = Math.clamp(draft.fontPercent / 100.0F, 0.7F, 1.6F);
		graphics.pose().pushMatrix();
		graphics.pose().translate(left + 13, y + 27);
		graphics.pose().scale(scale * draft.cellWidth, scale);
		graphics.text(previewFont, LuaSyntaxHighlighter.highlightSnippet(
				"local player = MC.Player.list()[1]", draft.theme, draft.syntaxColors).getVisualOrderText(),
				0, 0, 0xFFFFFFFF);
		graphics.text(previewFont, LuaSyntaxHighlighter.highlightSnippet(
				"print(\"Hello, Minecraft!\") -- 示例", draft.theme, draft.syntaxColors).getVisualOrderText(),
				0, Math.round(13 * draft.lineHeight), 0xFFFFFFFF);
		graphics.pose().popMatrix();
		int cursorX = left + 13 + Math.round(previewFont.width("local ") * scale * draft.cellWidth);
		int cursorY = y + 27;
		int cursorH = Math.max(5, Math.round(9 * scale));
		int cursorW = Math.max(1, draft.cursorWidth);
		if (draft.cursorShape == LuaPersonalizationSettings.CursorShape.BLOCK)
			graphics.fill(cursorX, cursorY, cursorX + Math.max(cursorW, 7), cursorY + cursorH,
					(draft.cursorColor & 0xFFFFFF) | 0x88000000);
		else if (draft.cursorShape == LuaPersonalizationSettings.CursorShape.UNDERLINE)
			graphics.fill(cursorX, cursorY + cursorH - 2, cursorX + 7, cursorY + cursorH, draft.cursorColor);
		else graphics.fill(cursorX, cursorY, cursorX + cursorW, cursorY + cursorH, draft.cursorColor);
	}

	private int renderTextRows(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int y) {
		y = renderWheelSensitivity(graphics, mouseX, mouseY, y);
		y = selector(graphics, mouseX, mouseY, y, LuaUiText.text("过长代码自动换行"),
				LuaUiText.text("仅调整显示；关闭后 Shift＋滚轮左右滚动。"),
				draft.codeWordWrap ? LuaUiText.text("开启") : LuaUiText.text("关闭", "Off"), () -> draft.codeWordWrap = !draft.codeWordWrap);
		y = selector(graphics, mouseX, mouseY, y, LuaUiText.text("配色方案"), LuaUiText.text("更改代码区的背景和语法颜色。"),
				switch (draft.theme) { case DARK -> LuaUiText.text("深色"); case LIGHT -> LuaUiText.text("浅色"); case HIGH_CONTRAST -> LuaUiText.text("高对比度"); },
				() -> draft.theme = next(draft.theme));
		y = selector(graphics, mouseX, mouseY, y, LuaUiText.text("字体"), LuaUiText.text("使用 Minecraft 默认或内置等宽字体。"),
				draft.fontFace == LuaPersonalizationSettings.FontFace.DEFAULT ? LuaUiText.text("Minecraft 默认") : LuaUiText.text("Minecraft 等宽"),
				() -> draft.fontFace = next(draft.fontFace));
		y = stepper(graphics, mouseX, mouseY, y, LuaUiText.text("字号"), LuaUiText.text("代码文字大小，范围 10%～300%。"),
				draft.fontPercent + "%", () -> draft.fontPercent = Math.max(10, draft.fontPercent - 10),
				() -> draft.fontPercent = Math.min(300, draft.fontPercent + 10));
		y = stepper(graphics, mouseX, mouseY, y, LuaUiText.text("行高"), LuaUiText.text("调整代码行之间的距离。"),
				String.format(java.util.Locale.ROOT, "%.1f×", draft.lineHeight),
				() -> draft.lineHeight = tenths(draft.lineHeight, -1, 8, 20),
				() -> draft.lineHeight = tenths(draft.lineHeight, 1, 8, 20));
		y = stepper(graphics, mouseX, mouseY, y, LuaUiText.text("字符宽度"), LuaUiText.text("拉宽或压缩代码字符；自动换行同步调整。"),
				String.format(java.util.Locale.ROOT, "%.1f×", draft.cellWidth),
				() -> draft.cellWidth = tenths(draft.cellWidth, -1, 5, 20),
				() -> draft.cellWidth = tenths(draft.cellWidth, 1, 5, 20));
		return selector(graphics, mouseX, mouseY, y, LuaUiText.text("自定义语法配色"), LuaUiText.text("逐项设置高亮色，支持 #RRGGBB。"),
				LuaUiText.text("编辑颜色  ›"), () -> { section = Section.SYNTAX; scroll = 0; selectColorRole(selectedColorRole); });
	}

	private int renderWheelSensitivity(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int y) {
		int left = contentLeft + 10, right = contentRight - 12;
		boolean narrow = right - left < 350;
		boolean valid = LuaPersonalizationSettings.parseCodeWheelSensitivity(wheelSensitivityInput.getValue()) != null;
		int controlTop = y + (narrow ? 43 : 14);
		int controlLeft = narrow ? left + 12 : right - 164;
		int controlRight = right - 12;
		graphics.fill(left, y, right, y + (narrow ? 77 : 57), 0xFF25303B);
		graphics.text(font, LuaUiText.text("代码滚轮灵敏度"), left + 12, y + 12, 0xFFF0F5FB);
		String description = valid ? LuaUiText.text("0.1～20 倍；1 为默认，越大越快。") : LuaUiText.text("请输入 0.1～20 的数字");
		graphics.text(font, font.plainSubstrByWidth(description, narrow ? right - left - 24 : right - left - 188),
				left + 12, y + 29, valid ? 0xFF9FB2C3 : 0xFFFF7777);
		wheelSensitivityInput.setX(controlLeft + 29);
		wheelSensitivityInput.setY(controlTop);
		wheelSensitivityInput.setWidth(controlRight - controlLeft - 58);
		wheelSensitivityInput.setTextColor(valid ? 0xFFFFFFFF : 0xFFFF7777);
		// Do not let a clipped input intercept clicks on the header or footer.
		wheelSensitivityInput.visible = controlTop >= viewTop && controlTop + 22 <= viewBottom;
		wheelSensitivityInput.active = wheelSensitivityInput.visible;
		if (wheelSensitivityInput.visible) wheelSensitivityInput.extractRenderState(graphics, mouseX, mouseY, 0);
		for (int direction : new int[]{-1, 1}) {
			int buttonLeft = direction < 0 ? controlLeft : controlRight - 26;
			graphics.fill(buttonLeft, controlTop, buttonLeft + 26, controlTop + 22,
					hovered(mouseX, mouseY, buttonLeft, controlTop, buttonLeft + 26, controlTop + 22)
							? 0xFF517391 : 0xFF394D60);
			graphics.centeredText(font, direction < 0 ? "−" : "+", buttonLeft + 13, controlTop + 7, 0xFFFFFFFF);
			hits.add(new Hit(buttonLeft, controlTop, buttonLeft + 26, controlTop + 22, () -> {
				float adjusted = new java.math.BigDecimal(Float.toString(draft.codeWheelSensitivity()))
						.add(java.math.BigDecimal.valueOf(direction, 1)).floatValue();
				draft.codeWheelSensitivity = Math.clamp(adjusted, LuaPersonalizationSettings.MIN_CODE_WHEEL_SENSITIVITY,
						LuaPersonalizationSettings.MAX_CODE_WHEEL_SENSITIVITY);
				wheelSensitivityInput.setValue(Float.toString(draft.codeWheelSensitivity));
			}));
		}
		return y + (narrow ? 85 : 65);
	}

	private int renderSyntaxColorRows(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int y) {
		int left = contentLeft + 10, right = contentRight - 12;
		graphics.fill(left, y, right, y + 34, 0xFF304254);
		graphics.text(font, LuaUiText.text("‹ 返回文本"), left + 10, y + 12, 0xFFCCE7FF);
		graphics.text(font, font.plainSubstrByWidth(syntaxMessage, Math.max(60, right - left - 101)),
				left + 91, y + 12, syntaxMessage.startsWith(LuaUiText.text("无效")) ? 0xFFFF9191 : 0xFF9DB8CE);
		hits.add(new Hit(left, y, left + 84, y + 34, () -> {
			if (applyPendingHex()) { section = Section.TEXT; scroll = 0; }
		}));
		y += 42;
		for (LuaSyntaxHighlighter.ColorRole role : LuaSyntaxHighlighter.ColorRole.values()) {
			int top = y;
			boolean selected = role == selectedColorRole;
			graphics.fill(left, top, right, top + 49, selected ? 0xFF374C65 : 0xFF25303B);
			if (selected) graphics.fill(left, top, left + 3, top + 49, 0xFF91C7FA);
			graphics.text(font, LuaUiText.text(role.label), left + 11, top + 9, 0xFFEFF5FC);
			int color = draft.syntaxColor(role) | 0xFF000000;
			graphics.text(font, role.sample, left + 11, top + 27, color);
			graphics.fill(right - 83, top + 13, right - 65, top + 32, color);
			graphics.outline(right - 83, top + 13, 18, 19, 0xFF8999AA);
			graphics.text(font, String.format(java.util.Locale.ROOT, "#%06X", color & 0xFFFFFF),
					right - 60, top + 18, 0xFFDBE7F4);
			hits.add(new Hit(left, top, right, top + 49, () -> selectColorRole(role)));
			y += 54;
		}
		return y;
	}

	private int renderCursorRows(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int y) {
		y = selector(graphics, mouseX, mouseY, y, LuaUiText.text("光标形状"), LuaUiText.text("竖线、下划线或半透明方块。"),
				switch (draft.cursorShape) { case BAR -> LuaUiText.text("竖线"); case UNDERLINE -> LuaUiText.text("下划线"); case BLOCK -> LuaUiText.text("方块"); },
				() -> draft.cursorShape = next(draft.cursorShape));
		y = stepper(graphics, mouseX, mouseY, y, LuaUiText.text("光标宽度"), LuaUiText.text("竖线粗细，范围 1～4 像素。"),
				draft.cursorWidth + " px", () -> draft.cursorWidth = Math.max(1, draft.cursorWidth - 1),
				() -> draft.cursorWidth = Math.min(4, draft.cursorWidth + 1));
		y = selector(graphics, mouseX, mouseY, y, LuaUiText.text("光标颜色"), LuaUiText.text("循环选择白、青、黄、紫四色。"),
				cursorColorName(draft.cursorColor), () -> draft.cursorColor = nextCursorColor(draft.cursorColor));
		return selector(graphics, mouseX, mouseY, y, LuaUiText.text("光标闪烁"), LuaUiText.text("关闭后光标保持显示。"),
				draft.cursorBlink ? LuaUiText.text("开启") : LuaUiText.text("关闭", "Off"), () -> draft.cursorBlink = !draft.cursorBlink);
	}

	private int renderWindowRows(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int y) {
		return selector(graphics, mouseX, mouseY, y, LuaUiText.text("固定布局时可调边框"),
				LuaUiText.text("允许拖动吸附区分隔线；窗口仍不可拖动换位。"),
				draft.resizeDockWhenLocked ? LuaUiText.text("开启") : LuaUiText.text("关闭", "Off"),
				() -> draft.resizeDockWhenLocked = !draft.resizeDockWhenLocked);
	}

	private int renderShortcutRows(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int y) {
		int left = contentLeft + 10, right = contentRight - 12;
		graphics.text(font, LuaUiText.text("选择组合与 F 键，再选择要打开的窗口。保存后生效。"), left + 8, y + 4, 0xFF9FB2C3);
		y += 24;
		int cellWidth = (right - left - 8) / 3;
		int[] modifiers = {LuaWindowShortcuts.CTRL, LuaWindowShortcuts.SHIFT, LuaWindowShortcuts.ALT};
		String[] labels = {"Ctrl", "Shift", "Alt"};
		for (int i = 0; i < 3; i++) {
			int bit = modifiers[i], x = left + i * (cellWidth + 4);
			boolean enabled = (shortcutModifiers & bit) != 0;
			shortcutButton(graphics, mouseX, mouseY, x, y, x + cellWidth, y + 27,
					(enabled ? "✓ " : "□ ") + labels[i], enabled, () -> {
						int next = shortcutModifiers ^ bit;
						if (next != 0) shortcutModifiers = next;
						else status = LuaUiText.text("请至少选择 Ctrl、Shift、Alt 中的一个");
						shortcutChoicesOpen = false;
					});
		}
		y += 35;
		// Full-width controls remain usable even in the minimum-width docked panel.
		int keyY = y;
		shortcutButton(graphics, mouseX, mouseY, left, y, left + 32, y + 27, "‹", false,
				() -> { shortcutFunction = Math.floorMod(shortcutFunction - 2, 12) + 1; shortcutChoicesOpen = false; });
		shortcutButton(graphics, mouseX, mouseY, right - 32, y, right, y + 27, "›", false,
				() -> { shortcutFunction = shortcutFunction % 12 + 1; shortcutChoicesOpen = false; });
		graphics.centeredText(font, LuaWindowShortcuts.modifierLabel(shortcutModifiers) + "F" + shortcutFunction,
				(left + right) / 2, keyY + 9, 0xFFE9F3FF);
		y += 35;
		shortcutButton(graphics, mouseX, mouseY, left, y, right, y + 28,
				LuaUiText.text("打开：") + LuaUiText.text(draft.windowShortcuts.get(shortcutModifiers, shortcutFunction).label) + "  ⌄", true,
				() -> shortcutChoicesOpen = !shortcutChoicesOpen);
		y += 35;
		if (shortcutChoicesOpen) {
			int columns = right - left >= 300 ? 2 : 1, choiceWidth = (right - left - (columns - 1) * 4) / columns;
			int index = 0;
			for (LuaWindowShortcuts.Target target : LuaWindowShortcuts.Target.values()) {
				int x = left + index % columns * (choiceWidth + 4), top = y + index / columns * 28;
				shortcutButton(graphics, mouseX, mouseY, x, top, x + choiceWidth, top + 25,
						LuaUiText.text(target.label), draft.windowShortcuts.get(shortcutModifiers, shortcutFunction) == target, () -> {
							draft.windowShortcuts.set(shortcutModifiers, shortcutFunction, target);
							shortcutChoicesOpen = false; scroll = 0;
							status = LuaUiText.text("已设置 ") + LuaWindowShortcuts.modifierLabel(shortcutModifiers)
									+ "F" + shortcutFunction + " → " + LuaUiText.text(target.label) + LuaUiText.text(" · 点击保存应用");
						});
				index++;
			}
			y += ((index + columns - 1) / columns) * 28 + 8;
		}
		shortcutButton(graphics, mouseX, mouseY, left, y, right, y + 25, LuaUiText.text("恢复当前快捷键默认值"), false,
				() -> draft.windowShortcuts.set(shortcutModifiers, shortcutFunction,
						LuaWindowShortcuts.defaultTarget(shortcutModifiers, shortcutFunction)));
		y += 29;
		shortcutButton(graphics, mouseX, mouseY, left, y, right, y + 25, LuaUiText.text("恢复全部窗口快捷键默认值"), false,
				() -> { draft.windowShortcuts.reset(); shortcutChoicesOpen = false; });
		y += 35;
		graphics.text(font, LuaUiText.text("F12 单独按下仍打开调试菜单。"), left + 8, y, 0xFF9FB2C3); y += 18;
		graphics.text(font, LuaUiText.text("窗口快捷键优先于同组合的调试快捷键。"), left + 8, y, 0xFFE8BC6D); y += 24;
		for (int f = 1; f <= 12; f++) {
			int function = f;
			shortcutButton(graphics, mouseX, mouseY, left, y, right, y + 25,
					LuaWindowShortcuts.modifierLabel(shortcutModifiers) + "F" + f + "  →  "
							+ LuaUiText.text(draft.windowShortcuts.get(shortcutModifiers, f).label), shortcutFunction == f,
					() -> { shortcutFunction = function; shortcutChoicesOpen = false; scroll = 0; });
			y += 29;
		}
		return y;
	}

	private void shortcutButton(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int left, int top, int right, int bottom, String text, boolean selected, Runnable action) {
		graphics.fill(left, top, right, bottom, selected ? 0xFF344C69
				: hovered(mouseX, mouseY, left, top, right, bottom) ? 0xFF405A72 : 0xFF25303B);
		graphics.text(font, font.plainSubstrByWidth(text, right - left - 16), left + 8,
				top + (bottom - top - 9) / 2, selected ? 0xFFFFFFFF : 0xFFB0BFCE);
		hits.add(new Hit(left, top, right, bottom, action));
	}

	private int renderBackgroundRows(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int y) {
		y = selector(graphics, mouseX, mouseY, y, LuaUiText.text("背景图片"), LuaUiText.text("从本地 backgrounds 文件夹选择 PNG。"),
				draft.backgroundFile.isEmpty() ? LuaUiText.text("无") : draft.backgroundFile,
				() -> {
					List<String> choices = new ArrayList<>(); choices.add(""); choices.addAll(LuaEditorBackground.files());
					int index = choices.indexOf(draft.backgroundFile);
					draft.backgroundFile = choices.get((index + 1) % choices.size());
					LuaEditorBackground.reload();
				});
		y = selector(graphics, mouseX, mouseY, y, LuaUiText.text("打开图片目录"), LuaUiText.text("将 PNG 放入此文件夹后重新选择。"),
				LuaUiText.text("打开文件夹 ↗"), () -> {
					try {
						java.nio.file.Files.createDirectories(LuaPersonalizationSettings.BACKGROUNDS);
						Util.getPlatform().openPath(LuaPersonalizationSettings.BACKGROUNDS);
					} catch (Exception error) { status = "无法打开图片目录：" + error.getMessage(); }
				});
		y = stepper(graphics, mouseX, mouseY, y, LuaUiText.text("图片可见度"), LuaUiText.text("0% 为关闭，最高 80% 以保持代码可读。"),
				draft.backgroundOpacity + "%",
				() -> draft.backgroundOpacity = Math.max(0, draft.backgroundOpacity - 10),
				() -> draft.backgroundOpacity = Math.min(80, draft.backgroundOpacity + 10));
		y = selector(graphics, mouseX, mouseY, y, LuaUiText.text("背景图像拉伸模式"), LuaUiText.text("原始大小、完整显示、裁剪铺满或拉伸。"),
				switch (draft.backgroundFit) {
					case NONE -> LuaUiText.text("原始大小"); case CONTAIN -> LuaUiText.text("完整显示");
					case COVER -> LuaUiText.text("裁剪铺满"); case STRETCH -> LuaUiText.text("拉伸填充");
				}, () -> draft.backgroundFit = next(draft.backgroundFit));
		return renderBackgroundAlignment(graphics, mouseX, mouseY, y);
	}

	private int renderBackgroundAlignment(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int y) {
		int left = contentLeft + 10, right = contentRight - 12;
		graphics.fill(left, y, right, y + 99, 0xFF25303B);
		graphics.text(font, LuaUiText.text("背景图像对齐"), left + 12, y + 12, 0xFFF0F5FB);
		graphics.text(font, LuaUiText.text(draft.backgroundAlignment.label), left + 12, y + 33, 0xFF9FB2C3);
		int gridLeft = right - 89;
		for (LuaPersonalizationSettings.BackgroundAlignment alignment
				: LuaPersonalizationSettings.BackgroundAlignment.values()) {
			int x = gridLeft + alignment.column * 27;
			int cellY = y + 10 + alignment.row * 27;
			boolean selected = alignment == draft.backgroundAlignment;
			graphics.fill(x, cellY, x + 25, cellY + 25,
					selected ? 0xFFB9B4F0 : hovered(mouseX, mouseY, x, cellY, x + 25, cellY + 25)
							? 0xFF4D6A83 : 0xFF344555);
			int markX = x + 4 + alignment.column * 5;
			int markY = cellY + 4 + alignment.row * 5;
			graphics.fill(markX, markY, markX + 7, markY + 7,
					selected ? 0xFF1B2230 : 0xFFE7F2FD);
			hits.add(new Hit(x, cellY, x + 25, cellY + 25, () -> draft.backgroundAlignment = alignment));
		}
		return y + 107;
	}

	private int selector(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int y,
			String label, String description, String value, Runnable action) {
		row(graphics, y, label, description);
		int controlRight = contentRight - 20, controlLeft = Math.max(contentLeft + 135, controlRight - 156);
		graphics.fill(controlLeft, y + 14, controlRight, y + 43,
				hovered(mouseX, mouseY, controlLeft, y + 14, controlRight, y + 43) ? 0xFF405A72 : 0xFF303D4B);
		graphics.text(font, font.plainSubstrByWidth(value, controlRight - controlLeft - 26),
				controlLeft + 8, y + 24, 0xFFEDF5FD);
		graphics.text(font, "⌄", controlRight - 15, y + 24, 0xFFB4CADB);
		hits.add(new Hit(controlLeft, y + 14, controlRight, y + 43, action));
		return y + 65;
	}

	private int stepper(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int y,
			String label, String description, String value, Runnable minus, Runnable plus) {
		row(graphics, y, label, description);
		int right = contentRight - 20;
		int left = Math.max(contentLeft + 136, right - 156);
		graphics.fill(left, y + 14, right, y + 43, 0xFF303D4B);
		graphics.fill(left, y + 14, left + 29, y + 43,
				hovered(mouseX, mouseY, left, y + 14, left + 29, y + 43) ? 0xFF517391 : 0xFF394D60);
		graphics.fill(right - 29, y + 14, right, y + 43,
				hovered(mouseX, mouseY, right - 29, y + 14, right, y + 43) ? 0xFF517391 : 0xFF394D60);
		graphics.centeredText(font, "−", left + 14, y + 24, 0xFFFFFFFF);
		graphics.centeredText(font, "+", right - 14, y + 24, 0xFFFFFFFF);
		graphics.centeredText(font, value, (left + right) / 2, y + 24, 0xFFEDF5FD);
		hits.add(new Hit(left, y + 14, left + 29, y + 43, minus));
		hits.add(new Hit(right - 29, y + 14, right, y + 43, plus));
		return y + 65;
	}

	private void row(GuiGraphicsExtractor graphics, int y, String label, String description) {
		int left = contentLeft + 10, right = contentRight - 12;
		graphics.fill(left, y, right, y + 57, 0xFF25303B);
		graphics.text(font, label, left + 12, y + 12, 0xFFF0F5FB);
		graphics.text(font, font.plainSubstrByWidth(description, Math.max(55, right - left - 205)),
				left + 12, y + 32, 0xFF9FB2C3);
	}

	private void selectColorRole(LuaSyntaxHighlighter.ColorRole role) {
		if (hexInput == null || !applyPendingHex()) return;
		selectedColorRole = role;
		loadingHex = true;
		hexInput.setValue(String.format(java.util.Locale.ROOT, "#%06X", draft.syntaxColor(role)));
		loadingHex = false;
		hexDirty = false;
		syntaxMessage = role.label + " · 输入 #RRGGBB，按回车应用";
	}

	private boolean applyPendingHex() {
		return !hexDirty || applyHexColor();
	}

	private boolean applyHexColor() {
		Integer rgb = LuaPersonalizationSettings.rgbValue(hexInput.getValue());
		if (rgb == null) {
			syntaxMessage = LuaUiText.text("无效颜色：请填写 6 位十六进制值");
			return false;
		}
		if (rgb == draft.theme.defaultColor(selectedColorRole)) draft.syntaxColors.remove(selectedColorRole);
		else draft.syntaxColors.put(selectedColorRole, rgb);
		hexDirty = false;
		syntaxMessage = selectedColorRole.label + " 已暂存，点击“保存并应用”生效";
		return true;
	}

	private void resetHexColor() {
		draft.syntaxColors.remove(selectedColorRole);
		hexDirty = false;
		selectColorRole(selectedColorRole);
		syntaxMessage = selectedColorRole.label + " 已恢复主题默认颜色";
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (getFocused() == wheelSensitivityInput && (event.key() == InputConstants.KEY_RETURN
				|| event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)) {
			if (applyWheelSensitivity()) setFocused(null);
			return true;
		}
		if (section == Section.SYNTAX && getFocused() == hexInput
				&& event.key() == InputConstants.KEY_RETURN) {
			applyHexColor();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		if (embedded && !contains(event.x(), event.y())) return false;
		if (event.button() == 0 && IdeWindowCloseButton.contains(event.x(), event.y(), panelRight, panelTop)) { onClose(); return true; }
		if (embedded && event.button() == 0)
			boundsAnimation.snapTo(panelLeft, panelTop, panelRight - panelLeft, panelBottom - panelTop);
		if (embedded && event.button() == 0 && !dockSide.docked()) {
			resizeEdges = resizeEdgesAt(event.x(), event.y());
			if (resizeEdges != 0) {
				resizeStartX = event.x(); resizeStartY = event.y();
				resizeLeft = panelLeft; resizeTop = panelTop;
				resizeRight = panelRight; resizeBottom = panelBottom;
				return true;
			}
		}
		if (embedded && event.button() == 0 && event.y() >= panelTop && event.y() < panelTop + (compact ? 29 : 38)) {
			dragging = !dockSide.docked() || !layoutLocked;
			dragOffsetX = event.x() - panelLeft;
			dragOffsetY = event.y() - panelTop;
			return true;
		}
		if (wheelSensitivityInput.visible && wheelSensitivityInput.isMouseOver(event.x(), event.y())) {
			if (wheelSensitivityInput.mouseClicked(event, doubleClick)) {
				setFocused(wheelSensitivityInput);
				return true;
			}
		}
		if (super.mouseClicked(event, doubleClick)) return true;
		if (event.button() != 0) return false;
		for (Hit hit : hits) {
			if (hit.contains(event.x(), event.y())
					&& (hit.left < contentLeft || event.y() >= viewTop && event.y() < viewBottom)) {
				hit.action.run();
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
		if (resizeEdges != 0) {
			int dx = (int) Math.round(event.x() - resizeStartX);
			int dy = (int) Math.round(event.y() - resizeStartY);
			int minimumWidth = Math.min(360, Math.max(1, width - 24));
			int minimumHeight = Math.min(240, Math.max(1, height - 24));
			int newLeft = (resizeEdges & 1) != 0 ? Math.clamp(resizeLeft + dx, 0, resizeRight - minimumWidth) : resizeLeft;
			int newRight = (resizeEdges & 2) != 0 ? Math.clamp(resizeRight + dx, newLeft + minimumWidth, width) : resizeRight;
			int newTop = (resizeEdges & 4) != 0 ? Math.clamp(resizeTop + dy, 0, resizeBottom - minimumHeight) : resizeTop;
			int newBottom = (resizeEdges & 8) != 0 ? Math.clamp(resizeBottom + dy, newTop + minimumHeight, height) : resizeBottom;
			applyBounds(newLeft, newTop, newRight - newLeft, newBottom - newTop);
			return true;
		}
		if (!dragging) return super.mouseDragged(event, dragX, dragY);
		if (dockSide.docked()) {
			setDockSide(DockSide.FLOATING);
			dragOffsetX = Math.min(dragOffsetX, Math.max(18, panelRight - panelLeft - 18));
			dragOffsetY = 12;
		}
		boundsAnimation.snapTo(panelLeft, panelTop, panelRight - panelLeft, panelBottom - panelTop);
		int nextX = Math.clamp((int) Math.round(event.x() - dragOffsetX), 0, width - (panelRight - panelLeft));
		int nextY = Math.clamp((int) Math.round(event.y() - dragOffsetY), 0, height - (panelBottom - panelTop));
		applyBounds(nextX, nextY, panelRight - panelLeft, panelBottom - panelTop);
		return true;
	}

	@Override
	public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
		if (resizeEdges != 0) { resizeEdges = 0; return true; }
		if (dragging) { dragging = false; return true; }
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (mouseX >= contentLeft && mouseX < contentRight && mouseY >= viewTop && mouseY < viewBottom) {
			int max = Math.max(0, contentHeight - (viewBottom - viewTop));
			scroll = Math.clamp(scroll - (int) Math.round(vertical * 32), 0, max);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	private void save() {
		try {
			if (!applyWheelSensitivity() || !applyPendingHex()) return;
			draft.save();
			previous.setPersonalization(draft.copy());
			onClose();
		} catch (Exception error) {
			status = LuaUiText.text("保存失败：") + error.getMessage();
		}
	}

	private boolean applyWheelSensitivity() {
		Float parsed = LuaPersonalizationSettings.parseCodeWheelSensitivity(wheelSensitivityInput.getValue());
		if (parsed == null) {
			status = "无法保存：代码滚轮灵敏度须为 0.1～20 的数字";
			return false;
		}
		draft.codeWheelSensitivity = parsed;
		status = "滚轮灵敏度已暂存，点击“保存并应用”生效";
		return true;
	}

	@Override public void onClose() {
		if (!embedded) { minecraft.setScreen(previous); return; }
		if (closing) return;
		closing = true;
		int targetX = dockSide == DockSide.LEFT ? -(panelRight - panelLeft) - 12
				: dockSide == DockSide.BOTTOM ? panelLeft : width + 12;
		int targetY = dockSide == DockSide.BOTTOM ? height + 12 : panelTop;
		boundsAnimation.animateTo(panelLeft, panelTop, panelRight - panelLeft, panelBottom - panelTop,
				targetX, targetY, panelRight - panelLeft, panelBottom - panelTop);
	}
	@Override public boolean isPauseScreen() { return false; }

	private static boolean hovered(double x, double y, int left, int top, int right, int bottom) {
		return x >= left && x < right && y >= top && y < bottom;
	}
	private static <T extends Enum<T>> T next(T current) {
		T[] values = current.getDeclaringClass().getEnumConstants();
		return values[(current.ordinal() + 1) % values.length];
	}
	private static float tenths(float value, int step, int minimum, int maximum) {
		return Math.clamp(Math.round(value * 10) + step, minimum, maximum) / 10.0F;
	}
	private static String cursorColorName(int color) {
		return switch (color) {
			case 0xFF66DDFF -> LuaUiText.text("青色");
			case 0xFFFFD76A -> LuaUiText.text("黄色");
			case 0xFFCCAAFF -> LuaUiText.text("紫色");
			default -> LuaUiText.text("白色");
		};
	}
	private static int nextCursorColor(int color) {
		return switch (color) {
			case 0xFFE6F3FF -> 0xFF66DDFF;
			case 0xFF66DDFF -> 0xFFFFD76A;
			case 0xFFFFD76A -> 0xFFCCAAFF;
			default -> 0xFFE6F3FF;
		};
	}
}
