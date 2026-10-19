package wiki.ica.xiaomiao.mcluaide.server;

import wiki.ica.xiaomiao.mcluaide.api.McLuaIdeDocsApi;
import wiki.ica.xiaomiao.mcluaide.network.LuaApiDescriptorCodec;

import java.util.List;

public final class DocsApiSmoke {
	public static void main(String[] args) {
		McLuaIdeDocsApi.Page page = McLuaIdeDocsApi.page("test_mod", "guide", "getting_started")
				.translation("zh_cn", McLuaIdeDocsApi.translation("测试模组教程", "快速开始")
						.heading("准备工作")
						.text("在脚本中调用 TestMod.hello()")
						.callout("需要管理员权限")
						.code("print(TestMod.hello())")
						.build())
				.translation("en_us", McLuaIdeDocsApi.translation("TestMod guide", "Getting started")
						.text("Call TestMod.hello() from Lua")
						.build())
				.build();
		McLuaIdeDocsApi.register(page);
		if (!page.translation("zh_cn").pageTitle().equals("快速开始")) throw new AssertionError("Chinese title");
		if (!page.translation("fr_fr").pageTitle().equals("Getting started")) throw new AssertionError("fallback");
		String encoded = LuaApiDescriptorCodec.encode(new LuaApiDescriptorCodec.Documentation(List.of(),
				McLuaIdeDocsApi.pages()));
		LuaApiDescriptorCodec.Documentation decoded = LuaApiDescriptorCodec.decodeDocumentation(encoded);
		if (decoded.pages().size() != 1 || decoded.pages().getFirst().translation("zh_cn").elements().size() != 4) {
			throw new AssertionError("document page wire roundtrip");
		}
		if (!LuaApiDescriptorCodec.decodeDocumentation("[]").pages().isEmpty()) {
			throw new AssertionError("legacy payload compatibility");
		}
		try {
			McLuaIdeDocsApi.register(page);
			throw new AssertionError("duplicate registration accepted");
		} catch (IllegalArgumentException expected) { }
		System.out.println("Third-party API documentation smoke test passed");
	}
}
