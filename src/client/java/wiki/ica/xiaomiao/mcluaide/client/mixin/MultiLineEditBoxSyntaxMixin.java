package wiki.ica.xiaomiao.mcluaide.client.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import wiki.ica.xiaomiao.mcluaide.client.LuaSyntaxHighlighter;

@Mixin(MultiLineEditBox.class)
public abstract class MultiLineEditBoxSyntaxMixin {
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
			if (scale == 1.0F) {
				graphics.text(font, LuaSyntaxHighlighter.highlight(self, source, text, absoluteStart, logicalLine).getVisualOrderText(),
						x, y, color, shadow);
			} else {
				graphics.pose().pushMatrix();
				graphics.pose().translate(x, y);
				graphics.pose().scale(scale, scale);
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
		return Math.round(width * LuaSyntaxHighlighter.scale(self));
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
		return Math.max(1.0D, original * LuaSyntaxHighlighter.scale(self));
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
		float scale = Math.max(0.10F, LuaSyntaxHighlighter.scale(self));
		field.seekCursorToPoint(x / scale, y / scale);
	}

	private static int scaledLineHeight(MultiLineEditBox editor, int original) {
		if (!LuaSyntaxHighlighter.isLuaEditor(editor)) return original;
		return Math.max(1, Math.round(original * LuaSyntaxHighlighter.scale(editor)));
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
			int expectedX = editor.getX() + 4 + font.width(source.substring(begin, candidate));
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
