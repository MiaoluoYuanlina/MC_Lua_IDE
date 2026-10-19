package wiki.ica.xiaomiao.mcluaide.server;

import net.minecraft.network.chat.Component;
import net.minecraft.locale.Language;
import java.util.Map;

public final class LuaNameTranslationSmoke {
    private static int checks;
    private static void check(boolean condition) { checks++; if(!condition)throw new AssertionError("Name translation check "+checks); }
    public static void main(String[] args) {
        Language original=Language.getInstance();
        Map<String,String> target=Map.of("item.test.gem","测试宝石","wrapper","%2$s / %1$s / %% / %s","nested","子名称");
        Map<String,String> english=Map.of("item.test.gem","Test Gem","missing.target","English Fallback");
        var translated=LuaNameTranslation.translate(Component.translatable("item.test.gem"),"zh_cn",target,english);
        check(translated.name().equals("测试宝石")&&translated.language().equals("zh_cn"));
        check(!LuaNameTranslation.isLiteral(Component.translatable("item.test.gem")));
        var fallback=LuaNameTranslation.translate(Component.translatable("missing.target"),"zh_cn",target,english);
        check(fallback.name().equals("English Fallback")&&fallback.language().equals("en_us"));
        var literal=LuaNameTranslation.translate(Component.literal("固定名称"),"zh_cn",target,english);
        check(literal.name().equals("固定名称")&&literal.language().equals("literal")&&LuaNameTranslation.isLiteral(Component.literal("固定名称")));
        var unresolved=LuaNameTranslation.translate(Component.translatable("missing.all"),"zh_cn",target,english);
        check(unresolved.name().equals("missing.all")&&unresolved.language().equals("untranslated"));
        var customFallback=LuaNameTranslation.translate(Component.translatableWithFallback("missing.all","Fixed fallback"),"zh_cn",target,english);
        check(customFallback.name().equals("Fixed fallback")&&customFallback.language().equals("untranslated"));
        var formatted=LuaNameTranslation.translate(Component.translatable("wrapper",Component.translatable("nested"),7),"zh_cn",target,english);
        check(formatted.name().equals("7 / 子名称 / % / 子名称"));
        var siblings=LuaNameTranslation.translate(Component.literal("前缀 ").append(Component.translatable("item.test.gem")).append(" 后缀"),"zh_cn",target,english);
        check(siblings.name().equals("前缀 测试宝石 后缀")&&siblings.language().equals("zh_cn"));
        check(Language.getInstance()==original);
        var deep=Component.literal("deep");
        for(int i=0;i<40;i++)deep=Component.literal("").append(deep);
        try { LuaNameTranslation.translate(deep,"zh_cn",target,english);throw new AssertionError("Unbounded recursion"); }
        catch(IllegalArgumentException expected){checks++;}
        System.out.println("Name translation smoke passed: "+checks+" checks");
    }
}
