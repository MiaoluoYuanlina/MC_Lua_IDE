package wiki.ica.xiaomiao.mcluaide.client;

import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeDocsApi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exports the administrator's currently synchronized API reference to a chosen Markdown file. */
final class LuaApiMarkdownExporter {
	private LuaApiMarkdownExporter() { }

	static String render(List<LuaApiDocumentation.DocumentEntry> entries,
			List<LuaApiDocumentation.DocumentPage> pages) {
		StringBuilder output = new StringBuilder(LuaUiText.text("# MC Lua IDE · 当前 API 文档\n\n", "# MC Lua IDE · API Reference\n\n"))
				.append(LuaUiText.text("包含当前服务器向管理员 IDE 提供的内置及第三方 API。\n\n", "Built-in and third-party APIs exposed by the current server to administrator clients.\n\n"))
				.append(LuaUiText.text("函数：", "Functions: ")).append(entries.size()).append(LuaUiText.text("；扩展文档页面：", "; extension pages: "))
				.append(pages.size()).append("。\n\n");
		Map<String, List<LuaApiDocumentation.DocumentEntry>> categories = new LinkedHashMap<>();
		for (LuaApiDocumentation.DocumentEntry entry : entries) {
			String key = entry.documentation().ownerModId().equals("mc_lua_ide")
					? "MC Lua IDE / " + entry.namespaceLabel()
					: entry.documentation().ownerModId() + " / " + entry.namespaceLabel();
			categories.computeIfAbsent(key, ignored -> new java.util.ArrayList<>()).add(entry);
		}
		for (Map.Entry<String, List<LuaApiDocumentation.DocumentEntry>> category : categories.entrySet()) {
			output.append("## ").append(plain(category.getKey())).append("\n\n");
			for (LuaApiDocumentation.DocumentEntry document : category.getValue()) {
				LuaApiDocumentation.Entry entry = document.documentation();
				output.append("### ").append(plain(document.symbol())).append("\n\n")
						.append("```lua\n").append(entry.signature()).append("\n```\n\n")
						.append(LuaUiText.text("- 作用：", "- Description: ")).append(plain(entry.summary())).append("\n")
						.append(LuaUiText.text("- 返回：", "- Returns: ")).append(plain(entry.returns())).append("\n")
						.append(LuaUiText.text("- 来源模组：`", "- Source mod: `")).append(plain(entry.ownerModId())).append("`\n");
				if (entry.clientRequired()) output.append(LuaUiText.text("- 限制：需要目标客户端安装本 Mod，且服务器启用强制客户端模式\n", "- Restriction: target client mod and forced-client mode required\n"));
				List<String> parameters = entry.parameters();
				for (int index = 0; index < parameters.size(); index++) {
					output.append(LuaUiText.text("- 参数", "- Parameter ")).append(index + 1).append(LuaUiText.text("：", ": "))
							.append(plain(entry.describe(parameters.get(index)))).append("\n");
				}
				if (!entry.details().isBlank()) output.append(LuaUiText.text("\n说明：", "\nNotes: "))
						.append(plain(entry.details())).append("\n");
				String example = LuaApiDocumentation.usageTemplate(document.symbol());
				if (!example.isBlank()) {
					output.append(LuaUiText.text("\n用法示例：\n\n", "\nUsage Example:\n\n"));
					appendCode(output, "lua", example);
				}
				output.append('\n');
			}
		}
		if (!pages.isEmpty()) output.append(LuaUiText.text("# 第三方模组文档页面\n\n", "# Third-party Mod Documentation\n\n"));
		for (LuaApiDocumentation.DocumentPage page : pages) {
			McLuaIdeDocsApi.Translation translation = page.translation();
			output.append("## ").append(plain(translation.categoryTitle())).append(" / ")
					.append(plain(translation.pageTitle())).append("\n\n")
					.append(LuaUiText.text("来源模组：`", "Source mod: `")).append(plain(page.ownerModId())).append("`\n\n");
			for (McLuaIdeDocsApi.Element element : translation.elements()) {
				switch (element.kind()) {
					case HEADING -> output.append("### ").append(plain(element.content())).append("\n\n");
					case TEXT -> output.append(plain(element.content())).append("\n\n");
					case CALLOUT -> output.append("> 提示：")
							.append(plain(element.content()).replace("\n", "\n> ")).append("\n\n");
					case CODE -> appendCode(output, "lua", element.content());
				}
			}
		}
		return output.toString();
	}

	static void writeAtomically(Path target, String markdown) throws IOException {
		Path parent = target.getParent();
		if (parent == null || !Files.isDirectory(parent)) throw new IOException("保存目录不存在");
		Path temporary = Files.createTempFile(parent, ".mc-lua-ide-api-", ".tmp");
		try {
			Files.writeString(temporary, markdown, StandardCharsets.UTF_8);
			try {
				Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
						StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static void appendCode(StringBuilder output, String language, String code) {
		int fenceLength = 3;
		for (String run : code.split("[^`]", -1)) fenceLength = Math.max(fenceLength, run.length() + 1);
		String fence = "`".repeat(fenceLength);
		output.append(fence).append(language).append('\n').append(code.stripTrailing())
				.append('\n').append(fence).append("\n\n");
	}

	private static String plain(String text) {
		return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
	}
}
