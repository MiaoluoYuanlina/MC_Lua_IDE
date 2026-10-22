package wiki.ica.xiaomiao.mcluaide.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class MarkdownManualDocument {
	private MarkdownManualDocument() {
	}

	public static Document load() {
		String resource = "/assets/mc_lua_ide/manual/" + LuaUiText.manualLocale() + ".md";
		try (InputStream stream = MarkdownManualDocument.class.getResourceAsStream(resource)) {
			if (stream == null) {
				return errorDocument(LuaUiText.text("无法加载 Markdown 使用手册", "Could not load the Markdown user guide"));
			}
			return parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
		} catch (IOException error) {
			return errorDocument("手册读取失败：" + error.getMessage());
		}
	}

	private static Document errorDocument(String message) {
		return new Document(List.of(new Line(message, Kind.HEADING, -1)), List.of());
	}

	static Document parse(String markdown) {
		List<Line> lines = new ArrayList<>();
		List<CodeBlock> codeBlocks = new ArrayList<>();
		List<String> currentCode = new ArrayList<>();
		boolean codeBlock = false;
		String codeLanguage = "代码";
		int codeBlockIndex = -1;

		for (String raw : markdown.replace("\r", "").split("\n", -1)) {
			String trimmed = raw.strip();
			if (trimmed.startsWith("```")) {
				if (!codeBlock) {
					codeBlock = true;
					codeLanguage = trimmed.length() > 3 ? trimmed.substring(3).strip().toUpperCase() : "代码";
					codeBlockIndex = codeBlocks.size();
					currentCode.clear();
				} else {
					codeBlocks.add(new CodeBlock(codeLanguage, String.join("\n", currentCode)));
					codeBlock = false;
					codeBlockIndex = -1;
				}
				continue;
			}
			if (codeBlock) {
				currentCode.add(raw.replace("\t", "    "));
				lines.add(new Line(raw.replace("\t", "    "), Kind.CODE, codeBlockIndex));
			} else if (trimmed.isEmpty() || trimmed.equals("---")) {
				if (!lines.isEmpty() && lines.getLast().kind() != Kind.SPACE) {
					lines.add(new Line("", Kind.SPACE, -1));
				}
			} else if (trimmed.startsWith("|")) {
				if (!trimmed.matches("^[|\\s:-]+$")) {
					lines.add(new Line("• " + inline(trimmed.replaceAll("^\\||\\|$", "").strip().replaceAll("\\s*\\|\\s*", "  │  ")), Kind.TEXT, -1));
				}
			} else if (trimmed.startsWith("# ")) {
				lines.add(new Line(inline(trimmed.substring(2)), Kind.HEADING, -1));
			} else if (trimmed.startsWith("## ") || trimmed.startsWith("### ")) {
				lines.add(new Line(inline(trimmed.substring(trimmed.indexOf(' ') + 1)), Kind.SUBHEADING, -1));
			} else if (trimmed.matches("^[-*+]\\s+.*")) {
				lines.add(new Line("• " + inline(trimmed.substring(2)), Kind.TEXT, -1));
			} else if (trimmed.matches("^\\d+\\.\\s+.*")) {
				lines.add(new Line(inline(trimmed), Kind.TEXT, -1));
			} else if (trimmed.startsWith("> ")) {
				lines.add(new Line("▌ " + inline(trimmed.substring(2)), Kind.QUOTE, -1));
			} else {
				lines.add(new Line(inline(trimmed), Kind.TEXT, -1));
			}
		}
		if (codeBlock) {
			codeBlocks.add(new CodeBlock(codeLanguage, String.join("\n", currentCode)));
		}
		while (!lines.isEmpty() && lines.getLast().kind() == Kind.SPACE) {
			lines.removeLast();
		}
		return new Document(List.copyOf(lines), List.copyOf(codeBlocks));
	}

	private static String inline(String text) {
		return text.replace("**", "").replace("__", "").replace("`", "");
	}

	public record Document(List<Line> lines, List<CodeBlock> codeBlocks) {
	}

	public record Line(String text, Kind kind, int codeBlockIndex) {
	}

	public record CodeBlock(String language, String source) {
	}

	public enum Kind {
		HEADING,
		SUBHEADING,
		TEXT,
		QUOTE,
		CODE,
		SPACE
	}
}
