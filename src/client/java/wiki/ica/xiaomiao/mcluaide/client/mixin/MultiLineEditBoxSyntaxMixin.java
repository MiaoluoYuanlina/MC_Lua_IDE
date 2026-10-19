package wiki.ica.xiaomiao.mcluaide.client.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.gui.components.TextCursorUtils;
import wiki.ica.xiaomiao.mcluaide.client.LuaSyntaxHighlighter;
import wiki.ica.xiaomiao.mcluaide.client.LuaEditorViewport;

@Mixin(MultiLineEditBox.class)
public abstract class MultiLineEditBoxSyntaxMixin {
	@Shadow private long focusedTime;
	@WrapMethod(method = "setFocused")
	private void mcLuaIde$stableInputFocus(boolean focused, Operation<Void> original) {
		MultiLineEditBox self = (MultiLineEditBox) (Object) this;
		if (net.minecraft.client.Minecraft.getInstance().screen instanceof wiki.ica.xiaomiao.mcluaide.client.LuaIdeScreen
				&& self.isFocused() == focused) return;
		original.call(focused);
	}
	@WrapMethod(method = "extractContents")
	private void mcLuaIde$horizontalContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick, Operation<Void> original) {
		int offset = LuaEditorViewport.offset((MultiLineEditBox) (Object) this);
		graphics.pose().pushMatrix();
		try {
			graphics.pose().translate(-offset, 0);
			original.call(graphics, mouseX, mouseY, partialTick);
			LuaEditorViewport.extractSelection(graphics, (MultiLineEditBox) (Object) this);
		} finally { graphics.pose().popMatrix(); }
	}

	@Redirect(method = "extractContents", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;textHighlight(IIIIZ)V"))
	private void mcLuaIde$selection(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, boolean invert) {
		if (!LuaSyntaxHighlighter.isLuaEditor((MultiLineEditBox) (Object) this))
			graphics.textHighlight(left, top, right, bottom, invert);
	}

	@Inject(method = "scrollToCursor", at = @At("TAIL"))
	private void mcLuaIde$revealHorizontalCursor(CallbackInfo ci) {
		LuaEditorViewport.revealCursor((MultiLineEditBox) (Object) this);
	}
	@Redirect(
			method = "extractContents",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V"
			)
	)
	private void mcLuaIde$renderHighlighted(
			GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int color, boolean shadow) {
		MultiLineEditBox self = (MultiLineEditBox) (Object) this;
		if (LuaSyntaxHighlighter.isLuaEditor(self)) {
			MultilineTextField field = ((MultiLineEditBoxAccessor) self).mcLuaIde$getTextField();
			String source = field.value();
			// extractContents receives document-space coordinates; scrolling is applied later by the pose stack.
			int lineHeight = scaledLineHeight(self, font.lineHeight);
			int visualLine = Math.max(0, (y - self.getY() - 4) / lineHeight);
			LineRange view = viewAt(field, visualLine);
			int absoluteStart = locateSegmentStart(self, font, source, text, x, view);
			int logicalLine = LuaSyntaxHighlighter.logicalLineAt(self, source, absoluteStart);
			float scale = LuaSyntaxHighlighter.scale(self);
			if (scale == 1.0F && LuaSyntaxHighlighter.cellWidth(self) == 1.0F) {
				graphics.text(font, LuaSyntaxHighlighter.highlight(self, source, text, absoluteStart, logicalLine).getVisualOrderText(),
						x, y, color, shadow);
			} else {
				graphics.pose().pushMatrix();
				graphics.pose().translate(x, y);
				graphics.pose().scale(scale * LuaSyntaxHighlighter.cellWidth(self), scale);
				graphics.text(font, LuaSyntaxHighlighter.highlight(self, source, text, absoluteStart, logicalLine).getVisualOrderText(),
						0, 0, color, shadow);
				graphics.pose().popMatrix();
			}
		} else {
			graphics.text(font, text, x, y, color, shadow);
		}
	}

	@Redirect(
			method = "extractContents",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/gui/Font;width(Ljava/lang/String;)I"
			)
	)
	private int mcLuaIde$scaledTextWidth(Font font, String text) {
		MultiLineEditBox self = (MultiLineEditBox) (Object) this;
		int width = font.width(text);
		if (!LuaSyntaxHighlighter.isLuaEditor(self)) return width;
		return Math.round(width * LuaSyntaxHighlighter.scale(self) * LuaSyntaxHighlighter.cellWidth(self));
	}

	@ModifyConstant(
			method = {"extractContents", "getInnerHeight", "scrollToCursor"},
			constant = @Constant(intValue = 9)
	)
	private int mcLuaIde$scaledIntegerLineHeight(int original) {
		MultiLineEditBox self = (MultiLineEditBox) (Object) this;
		return scaledLineHeight(self, original);
	}

	@ModifyConstant(
			method = "scrollToCursor",
			constant = @Constant(doubleValue = 9.0D)
	)
	private double mcLuaIde$scaledDoubleLineHeight(double original) {
		MultiLineEditBox self = (MultiLineEditBox) (Object) this;
		if (!LuaSyntaxHighlighter.isLuaEditor(self)) return original;
		return scaledLineHeight(self, (int) original);
	}

	@Redirect(
			method = "seekCursorScreen",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/gui/components/MultilineTextField;seekCursorToPoint(DD)V"
			)
	)
	private void mcLuaIde$seekScaledCursor(MultilineTextField field, double x, double y) {
		MultiLineEditBox self = (MultiLineEditBox) (Object) this;
		if (!LuaSyntaxHighlighter.isLuaEditor(self)) {
			field.seekCursorToPoint(x, y);
			return;
		}
		LuaEditorViewport.seekCursorToPoint(self, field, x, y);
	}

	@Redirect(method = "extractContents", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/TextCursorUtils;isCursorVisible(J)Z"))
	private boolean mcLuaIde$cursorVisibility(long elapsed) {
		MultiLineEditBox self = (MultiLineEditBox) (Object) this;
		// Keep prefix/suffix geometry stable; blink only the cursor paint.
		return LuaSyntaxHighlighter.isLuaEditor(self)
				|| TextCursorUtils.isCursorVisible(elapsed);
	}

	@Redirect(method = "extractContents", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/TextCursorUtils;extractInsertCursor(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIII)V"))
	private void mcLuaIde$insertCursor(GuiGraphicsExtractor graphics, int x, int y, int color, int height) {
		MultiLineEditBox self = (MultiLineEditBox) (Object) this;
		if (!LuaSyntaxHighlighter.isLuaEditor(self)) {
			TextCursorUtils.extractInsertCursor(graphics, x, y, color, height);
			return;
		}
		mcLuaIde$drawCursor(graphics, self, x, y, height);
	}

	@Redirect(method = "extractContents", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/TextCursorUtils;extractAppendCursor(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIIZ)V"))
	private void mcLuaIde$appendCursor(GuiGraphicsExtractor graphics, Font font, int x, int y, int color, boolean shadow) {
		MultiLineEditBox self = (MultiLineEditBox) (Object) this;
		if (!LuaSyntaxHighlighter.isLuaEditor(self)) {
			TextCursorUtils.extractAppendCursor(graphics, font, x, y, color, shadow);
			return;
		}
		mcLuaIde$drawCursor(graphics, self, x, y, scaledLineHeight(self, font.lineHeight));
	}

	private void mcLuaIde$drawCursor(GuiGraphicsExtractor graphics, MultiLineEditBox editor,
			int x, int y, int height) {
		if (LuaSyntaxHighlighter.cursorBlink(editor)
				&& !TextCursorUtils.isCursorVisible(net.minecraft.util.Util.getMillis() - focusedTime)) return;
		int color = LuaSyntaxHighlighter.cursorColor(editor);
		int width = Math.max(1, LuaSyntaxHighlighter.cursorWidth(editor));
		if (LuaSyntaxHighlighter.cursorShape(editor) == wiki.ica.xiaomiao.mcluaide.client.LuaPersonalizationSettings.CursorShape.BAR) {
			graphics.fill(x, y, x + width, y + height, color);
			return;
		}
		int cell = Math.max(5, Math.round(8 * LuaSyntaxHighlighter.scale(editor) * LuaSyntaxHighlighter.cellWidth(editor)));
		if (LuaSyntaxHighlighter.cursorShape(editor) == wiki.ica.xiaomiao.mcluaide.client.LuaPersonalizationSettings.CursorShape.UNDERLINE)
			graphics.fill(x, y + height - Math.min(height, width + 1), x + cell, y + height, color);
		else graphics.fill(x, y, x + cell, y + height, (color & 0xFFFFFF) | 0x88000000);
	}

	private static int scaledLineHeight(MultiLineEditBox editor, int original) {
		if (!LuaSyntaxHighlighter.isLuaEditor(editor)) return original;
		return Math.max(1, Math.round(original * LuaSyntaxHighlighter.scale(editor) * LuaSyntaxHighlighter.lineHeight(editor)));
	}

	private static LineRange viewAt(MultilineTextField field, int visualLine) {
		int index = 0;
		LineRange last = null;
		for (Object rawView : field.iterateLines()) {
			StringViewAccessor view = (StringViewAccessor) rawView;
			last = new LineRange(view.mcLuaIde$getBeginIndex(), view.mcLuaIde$getEndIndex());
			if (index++ == visualLine) {
				return last;
			}
		}
		return last;
	}

	private static int locateSegmentStart(MultiLineEditBox editor, Font font, String source, String text, int x,
			LineRange view) {
		if (view == null) {
			return -1;
		}
		int begin = Math.max(0, Math.min(source.length(), view.beginIndex()));
		int end = Math.max(begin, Math.min(source.length(), view.endIndex()));
		if (text.isEmpty()) return begin;
		int best = -1;
		int bestDistance = Integer.MAX_VALUE;
		int candidate = source.indexOf(text, begin);
		while (candidate >= begin && candidate + text.length() <= end) {
			int expectedX = editor.getX() + 4 + Math.round(font.width(source.substring(begin, candidate))
					* LuaSyntaxHighlighter.scale(editor) * LuaSyntaxHighlighter.cellWidth(editor));
			int distance = Math.abs(expectedX - x);
			if (distance < bestDistance) {
				best = candidate;
				bestDistance = distance;
			}
			candidate = source.indexOf(text, candidate + 1);
		}
		return best;
	}

	private record LineRange(int beginIndex, int endIndex) {
	}

}
