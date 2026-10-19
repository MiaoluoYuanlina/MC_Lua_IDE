package wiki.ica.xiaomiao.mcluaide.client;

import java.nio.file.*;

public final class LuaConfiguredShopEditorSmoke {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        String source=Files.readString(Path.of(args[0]));
        var analysis=LuaCodeAnalyzer.analyze("Bed_Wars/Shop/Main",source);
        if(analysis.errorCount()!=0)throw new AssertionError("Configured shop editor errors: "+analysis.diagnostics());
        System.out.println("Configured shop editor smoke passed: no syntax/API/type/undefined-variable errors");
    }
}
