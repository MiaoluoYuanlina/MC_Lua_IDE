package wiki.ica.xiaomiao.mcluaide.client;

import java.util.List;

public final class LuaSelectionOccurrencesSmoke {
    public static void main(String[] args) {
        LuaSelectionOccurrences model = new LuaSelectionOccurrences();
        String source = "Player Player player Player";
        model.update(source, 6, 0);
        require(model.visible(0, source.length()).equals(List.of(range(7, 13), range(21, 27))), "Literal/case-sensitive matches");
        model.update(source, 0, 6);
        require(model.visible(9, 12).equals(List.of(range(7, 13))), "Reversed selection / partially visible match");
        require(model.visible(14, 20).isEmpty(), "No unrelated match");
        model.update("aaaaa", 0, 3);
        require(model.visible(0, 5).equals(List.of(range(1, 4), range(2, 5))), "Overlapping matches");
        model.update("[.]+ [.]+", 0, 4);
        require(model.visible(0, 9).equals(List.of(range(5, 9))), "Punctuation is literal, not a regex");
        model.update("中文😀 中文😀", 0, 4);
        require(model.visible(0, 9).equals(List.of(range(5, 9))), "Chinese / surrogate pair");
        model.update("a\nb a\nb", 0, 3);
        require(model.visible(4, 5).equals(List.of(range(4, 7))), "Multiline matches");
        model.update(" \n x", 0, 2); require(!model.active(), "Ignore whitespace-only selection");
        model.update(source, 6, 6); require(!model.active(), "Collapsed selection clears matches");
        model.update(source, 0, source.length()); require(!model.active(), "Whole source has no other occurrence");
        model.update("foo foo", 0, 3); require(model.visible(0, 7).size() == 1, "Initial cache");
        model.update("foo bar", 0, 3); require(model.visible(0, 7).isEmpty(), "Edits invalidate cache");
        String large = "x".repeat(4_999_997) + "abc";
        model.update(large, large.length() - 3, large.length());
        for (int i = 0; i < 1000; i++) require(model.visible(i, i + 100).isEmpty(), "Bounded large-file search " + i);
        System.out.println("Selection occurrence smoke passed: literal, reverse, overlap, Unicode, multiline, invalidation and bounded 5 MB searches");
    }
    private static LuaSelectionOccurrences.Range range(int start, int end) { return new LuaSelectionOccurrences.Range(start, end); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
