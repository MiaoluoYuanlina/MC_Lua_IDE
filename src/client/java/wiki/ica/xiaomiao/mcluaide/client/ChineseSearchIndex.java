package wiki.ica.xiaomiao.mcluaide.client;

import java.nio.charset.Charset;
import java.util.Locale;

/** Builds compact first-letter indexes for simplified Chinese display names. */
final class ChineseSearchIndex {
	private static final Charset GB2312 = Charset.forName("GB2312");
	private static final int[] PINYIN_BOUNDARIES = {
			45217, 45253, 45761, 46318, 46826, 47010, 47297, 47614,
			48119, 49062, 49324, 49896, 50371, 50614, 50622, 50906,
			51387, 51446, 52218, 52698, 52980, 53689, 54481, 55290
	};
	private static final char[] PINYIN_INITIALS = {
			'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h',
			'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q',
			'r', 's', 't', 'w', 'x', 'y', 'z'
	};

	private ChineseSearchIndex() {
	}

	static String initials(String text) {
		if (text == null || text.isBlank()) return "";
		StringBuilder result = new StringBuilder(text.length());
		for (int offset = 0; offset < text.length();) {
			int codePoint = text.codePointAt(offset);
			offset += Character.charCount(codePoint);
			if (codePoint < 128) {
				if (Character.isLetterOrDigit(codePoint)) {
					result.appendCodePoint(Character.toLowerCase(codePoint));
				}
				continue;
			}
			if (codePoint <= Character.MAX_VALUE) {
				char initial = initialOf((char) codePoint);
				if (initial != 0) result.append(initial);
			}
		}
		return result.toString().toLowerCase(Locale.ROOT);
	}

	private static char initialOf(char character) {
		byte[] encoded = String.valueOf(character).getBytes(GB2312);
		if (encoded.length < 2 || (encoded[0] & 0xFF) == '?') return 0;
		int code = (encoded[0] & 0xFF) * 256 + (encoded[1] & 0xFF);
		for (int index = 0; index < PINYIN_INITIALS.length; index++) {
			if (code >= PINYIN_BOUNDARIES[index] && code < PINYIN_BOUNDARIES[index + 1]) {
				return PINYIN_INITIALS[index];
			}
		}
		return 0;
	}
}
