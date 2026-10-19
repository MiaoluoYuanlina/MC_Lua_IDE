package wiki.ica.xiaomiao.mcluaide.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Server-side, declarative pages shown only in an administrator's API reference. */
public final class McLuaIdeDocsApi {
	public static final int EXTENSION_API_VERSION = 1;
	private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{1,63}");
	private static final Pattern LOCALE = Pattern.compile("[a-z]{2,3}(?:_[a-z]{2})?");
	private static final Map<String, Page> PAGES = new LinkedHashMap<>();

	private McLuaIdeDocsApi() { }

	/** Creates a page under an owner-controlled heading. Nothing here executes client-side code. */
	public static PageBuilder page(String ownerModId, String categoryId, String pageId) {
		return new PageBuilder(ownerModId, categoryId, pageId);
	}

	public static TranslationBuilder translation(String categoryTitle, String pageTitle) {
		return new TranslationBuilder(categoryTitle, pageTitle);
	}

	public static synchronized void register(Page page) {
		Objects.requireNonNull(page, "page");
		String key = page.ownerModId() + ':' + page.categoryId() + '/' + page.pageId();
		if (PAGES.containsKey(key)) throw new IllegalArgumentException("API 文档页面已注册：" + key);
		if (PAGES.size() >= 256) throw new IllegalStateException("第三方 API 文档页面数量已达上限");
		PAGES.put(key, page);
	}

	public static synchronized List<Page> pages() {
		return List.copyOf(PAGES.values());
	}

	public enum ElementKind { HEADING, TEXT, CALLOUT, CODE }

	public record Element(ElementKind kind, String content) {
		public Element {
			kind = Objects.requireNonNull(kind, "kind");
			content = bounded(content, kind == ElementKind.CODE ? 16_384 : 4_096);
			if (content.isBlank()) throw new IllegalArgumentException("文档 UI 元素内容不能为空");
		}
	}

	public record Translation(String categoryTitle, String pageTitle, List<Element> elements) {
		public Translation {
			categoryTitle = boundedRequired(categoryTitle, 96);
			pageTitle = boundedRequired(pageTitle, 128);
			elements = List.copyOf(elements == null ? List.of() : elements);
			if (elements.isEmpty() || elements.size() > 32) {
				throw new IllegalArgumentException("每个文档页面需要 1–32 个 UI 元素");
			}
		}
	}

	public record Page(String ownerModId, String categoryId, String pageId,
			Map<String, Translation> translations) {
		public Page {
			requireId(ownerModId, "模组 ID");
			requireId(categoryId, "分类 ID");
			requireId(pageId, "页面 ID");
			if (translations == null || translations.isEmpty()) {
				throw new IllegalArgumentException("文档页面至少需要一种语言");
			}
			Map<String, Translation> safe = new LinkedHashMap<>();
			translations.forEach((locale, translation) -> {
				String normalized = normalizeLocale(locale);
				if (!LOCALE.matcher(normalized).matches()) throw new IllegalArgumentException("无效语言代码：" + locale);
				if (safe.putIfAbsent(normalized, Objects.requireNonNull(translation, "translation")) != null) {
					throw new IllegalArgumentException("文档语言重复：" + normalized);
				}
			});
			translations = Collections.unmodifiableMap(safe);
		}

		public Translation translation(String requestedLocale) {
			String locale = normalizeLocale(requestedLocale);
			Translation exact = translations.get(locale);
			if (exact != null) return exact;
			String language = locale.contains("_") ? locale.substring(0, locale.indexOf('_')) : locale;
			for (Map.Entry<String, Translation> entry : translations.entrySet()) {
				if (entry.getKey().equals(language) || entry.getKey().startsWith(language + '_')) return entry.getValue();
			}
			Translation english = translations.get("en_us");
			return english != null ? english : translations.values().iterator().next();
		}
	}

	public static final class PageBuilder {
		private final String ownerModId;
		private final String categoryId;
		private final String pageId;
		private final Map<String, Translation> translations = new LinkedHashMap<>();

		private PageBuilder(String ownerModId, String categoryId, String pageId) {
			requireId(ownerModId, "模组 ID");
			requireId(categoryId, "分类 ID");
			requireId(pageId, "页面 ID");
			this.ownerModId = ownerModId;
			this.categoryId = categoryId;
			this.pageId = pageId;
		}

		public PageBuilder translation(String locale, Translation translation) {
			String normalized = normalizeLocale(locale);
			if (!LOCALE.matcher(normalized).matches()) throw new IllegalArgumentException("无效语言代码：" + locale);
			if (translations.putIfAbsent(normalized, Objects.requireNonNull(translation, "translation")) != null) {
				throw new IllegalArgumentException("文档语言重复：" + normalized);
			}
			return this;
		}

		public Page build() { return new Page(ownerModId, categoryId, pageId, translations); }

		public void register() { McLuaIdeDocsApi.register(build()); }
	}

	public static final class TranslationBuilder {
		private final String categoryTitle;
		private final String pageTitle;
		private final List<Element> elements = new ArrayList<>();

		private TranslationBuilder(String categoryTitle, String pageTitle) {
			this.categoryTitle = categoryTitle;
			this.pageTitle = pageTitle;
		}

		public TranslationBuilder heading(String text) { return add(ElementKind.HEADING, text); }
		public TranslationBuilder text(String text) { return add(ElementKind.TEXT, text); }
		public TranslationBuilder callout(String text) { return add(ElementKind.CALLOUT, text); }
		/** Lua code is displayed with Insert and Copy controls in the API reference. */
		public TranslationBuilder code(String lua) { return add(ElementKind.CODE, lua); }

		private TranslationBuilder add(ElementKind kind, String content) {
			elements.add(new Element(kind, content));
			return this;
		}

		public Translation build() { return new Translation(categoryTitle, pageTitle, elements); }
	}

	private static void requireId(String value, String label) {
		if (value == null || !ID.matcher(value).matches()) throw new IllegalArgumentException("无效" + label + "：" + value);
	}

	private static String normalizeLocale(String value) {
		return value == null ? "" : value.strip().replace('-', '_').toLowerCase(Locale.ROOT);
	}

	private static String boundedRequired(String value, int maximum) {
		String result = bounded(value, maximum);
		if (result.isBlank()) throw new IllegalArgumentException("文档标题不能为空");
		return result;
	}

	private static String bounded(String value, int maximum) {
		String result = value == null ? "" : value;
		if (result.length() > maximum) throw new IllegalArgumentException("文档字段不能超过 " + maximum + " 个字符");
		return result;
	}
}
