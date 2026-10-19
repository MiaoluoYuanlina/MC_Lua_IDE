package wiki.ica.xiaomiao.mcluaide.server;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import java.util.*;
import java.util.regex.Pattern;

/** Does not inject a global Language or reuse Component's cached default-language string. */
final class LuaNameTranslation {
    record Result(String name, String language) { }
    private static final Pattern FORMAT = Pattern.compile("%(?:(\\d+)\\$)?([%s])");
    static boolean isLiteral(Component component) {
        return isLiteral(component, 0, new int[1]);
    }
    private static boolean isLiteral(Component component, int depth, int[] nodes) {
        if (depth > 32 || ++nodes[0] > 2048) throw new IllegalArgumentException("名称文本组件嵌套过深");
        if (component.getContents() instanceof TranslatableContents) return false;
        for (Component sibling : component.getSiblings()) if (!isLiteral(sibling, depth + 1, nodes)) return false;
        return true;
    }
    static Result translate(Component component, String language, Map<String,String> target, Map<String,String> english) {
        State state = new State(language, target, english);
        return new Result(state.render(component, 0), state.untranslated ? "untranslated" : state.fallback ? "en_us" : state.translatable ? language : "literal");
    }
    private static final class State {
        final String language;
        final Map<String,String> target, english;
        boolean fallback, untranslated, translatable;
        int nodes;
        State(String language, Map<String,String> target, Map<String,String> english) { this.language=language;this.target=target;this.english=english; }
        String render(Component component, int depth) {
            if (depth > 32 || ++nodes > 2048) throw new IllegalArgumentException("名称文本组件嵌套过深");
            StringBuilder out = new StringBuilder();
            if (component.getContents() instanceof TranslatableContents contents) {
                translatable = true;
                String format = target.get(contents.getKey());
                if (format == null) { format = english.get(contents.getKey()); fallback = true; }
                if (format == null) { format = contents.getFallback(); untranslated = true; }
                if (format == null) format = contents.getKey();
                Object[] args = contents.getArgs();
                var matcher = FORMAT.matcher(format); int cursor=0, next=0;
                while (matcher.find()) {
                    out.append(format, cursor, matcher.start());
                    if (matcher.group(2).equals("%")) out.append('%');
                    else {
                        int index = matcher.group(1)==null ? next++ : Integer.parseInt(matcher.group(1))-1;
                        if(index<0||index>=args.length) out.append(matcher.group());
                        else out.append(args[index] instanceof Component nested ? render(nested,depth+1) : String.valueOf(args[index]));
                    }
                    cursor=matcher.end();
                }
                out.append(format.substring(cursor));
            } else component.getContents().visit(text -> { out.append(text);return Optional.empty(); });
            for (Component sibling : component.getSiblings()) out.append(render(sibling,depth+1));
            if (out.length() > 32768) throw new IllegalArgumentException("名称文本超过长度上限");
            return out.toString();
        }
    }
}
