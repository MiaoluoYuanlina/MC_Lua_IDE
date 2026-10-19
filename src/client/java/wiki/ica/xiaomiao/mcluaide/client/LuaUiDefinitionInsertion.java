package wiki.ica.xiaomiao.mcluaide.client;

/** Places generated UI fields inside an existing Lua table, or inserts a table value. */
final class LuaUiDefinitionInsertion {
	private LuaUiDefinitionInsertion() { }

	record Edit(int start, int end, String text, boolean filledExistingTable) { }

	static Edit plan(String source, int selectionStart, int selectionEnd, String fields) {
		int left = selectionStart;
		while (left > 0 && Character.isWhitespace(source.charAt(left - 1))) left--;
		int right = selectionEnd;
		while (right < source.length() && Character.isWhitespace(source.charAt(right))) right++;
		if (left > 0 && right < source.length() && source.charAt(left - 1) == '{'
				&& source.charAt(right) == '}') {
			int open = left - 1;
			String innerIndent = LuaIndentation.indentForNewLine(source, open + 1);
			String outerIndent = innerIndent.endsWith(LuaIndentation.INDENT)
					? innerIndent.substring(0, innerIndent.length() - LuaIndentation.INDENT.length()) : "";
			return new Edit(open + 1, right, "\n" + indentFields(fields, innerIndent) + "\n" + outerIndent, true);
		}
		String table = "{\n" + fields + "\n}";
		return new Edit(selectionStart, selectionEnd,
				LuaIndentation.formatPaste(table, source, selectionStart), false);
	}

	private static String indentFields(String fields, String indent) {
		String[] lines = fields.strip().split("\n");
		StringBuilder result = new StringBuilder(fields.length() + indent.length() * lines.length);
		for (String line : lines) {
			if (!result.isEmpty()) result.append('\n');
			result.append(indent).append(line.strip());
		}
		return result.toString();
	}
}
