package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import java.util.ArrayList;
import java.util.List;

/** Called only by the isolated container probe; exercises native clipboard and complete IDE input routing. */
final class LuaVariableCopyProbe {
	private static int checks;
	private static boolean completed;
	static void tick(Minecraft client) {
		if (completed || client.getOverlay() != null) return;
		completed = true;
		try {
			check(client.gameDirectory.toPath().toAbsolutePath().toString().contains("variable-copy-probe"), "Unsafe probe directory");
			verify(client); client.stop();
		} catch (Exception | AssertionError error) { client.stop(); throw new IllegalStateException("Variable window probe failed", error); }
	}
	static void verify(Minecraft client) throws Exception {
		String clipboard = client.keyboardHandler.getClipboard();
		try {
			client.options.guiScale().set(1); client.resizeGui();
			LuaIdeScreen ide = new LuaIdeScreen(null, ""); client.setScreen(ide);
			set(ide, "manualOpen", false); set(ide, "manualProgress", 0F); set(ide, "firstManualPending", false);
			invoke(ide, "openScript", new Class<?>[]{String.class}, "variable_copy_probe");
			Object doc = get(ide, "currentDocument"); String code = "local sample = 7\nprint(sample)";
			set(doc, "source", code); set(doc, "savedSource", code); set(doc, "lastSnapshot", code);
			set(doc, "loaded", true); set(doc, "loading", false); set(doc, "readRevision", (long) get(doc, "readRevision") + 1);
			invoke(ide, "restoreCurrentDocument", new Class<?>[0]);
			LuaVariableOverlay window = (LuaVariableOverlay) get(ide, "variableOverlay");
			if (!window.isOpen()) invoke(ide, "toggleVariableOverlay", new Class<?>[0]);
			set(window, "progress", 1F);
			List<LuaDebugHud.DisplayRow> rows = new ArrayList<>();
			rows.add(new LuaDebugHud.DisplayRow("局部与闭包变量", "", "", 0, true));
			rows.add(new LuaDebugHud.DisplayRow("count = ", "12345", "number", 1, false));
			rows.add(new LuaDebugHud.DisplayRow("message = ", "\"中文😀" + "长值".repeat(200) + "\"", "string", 1, false));
			for (int i = 0; i < 30; i++) rows.add(new LuaDebugHud.DisplayRow("hidden" + i + " = ", "true", "boolean", 1, false));
			setStatic("rows", List.copyOf(rows)); setStatic("script", "variable_copy_probe");
			for (DockSide side : DockSide.values()) {
				window.clearSelection(); window.setDockSide(DockSide.FLOATING); window.restoreGeometry(new WindowGeometry(200,100,330,260));
				if (side.docked()) { window.setDockSide(side); window.setDockBounds(200,100,330,260); for (int i=0;i<30;i++) window.tick(); }
				int x=(int)invoke(window,"panelX",new Class<?>[0]), y=(int)get(window,"y"), width=(int)get(window,"width");
				int top=y+50, tx=x+22+client.font.width("count = ");
				check(ide.mouseClicked(mouse(tx,top+14+5),false),"Variable selection click not routed: "+side);
				check(ide.mouseDragged(mouse(tx+client.font.width("123"),top+14+5),10,0),"Variable selection drag not routed");
				ide.mouseReleased(mouse(tx+client.font.width("123"),top+14+5));
				check(((LuaVariableText)get(window,"text")).selected().equals("123"),"Selection gesture failed: "+side+", geometry="+window.captureGeometry()+", selected="+((LuaVariableText)get(window,"text")).selected()+", focused="+get(ide,"variableWindowFocused"));
				check(ide.keyPressed(key(67,2)),"Ctrl+C not handled");
				check(client.keyboardHandler.getClipboard().equals("123"),"Selected numeric text not copied: "+side+" "+client.keyboardHandler.getClipboard());
				check(ide.keyPressed(key(256,0)) && window.isOpen(),"Esc selection unexpectedly closed variable window");
				check(ide.mouseClicked(mouse(tx,top+14+5),true),"Double click not routed");
				ide.mouseReleased(mouse(tx,top+14+5)); ide.keyPressed(key(67,2));
				check(client.keyboardHandler.getClipboard().equals("    count = 12345"),"Double click did not copy complete line");
				ide.keyPressed(key(65,2)); ide.keyPressed(key(67,2));
				String all=client.keyboardHandler.getClipboard();
				check(all.contains("hidden29 = true") && all.contains("长值".repeat(200)),"Copy all truncated offscreen rows/values");
				ide.keyPressed(key(256,0));
				ide.mouseClicked(mouse(x+width-40,y+12),false); ide.mouseReleased(mouse(x+width-40,y+12));
				check(client.keyboardHandler.getClipboard().equals(all),"Header copy-all button failed");
				check(window.isOpen(),"Header copy closed variable window: "+side);
			}
			window.setDockSide(DockSide.FLOATING); window.restoreGeometry(new WindowGeometry(200,100,330,260));
			window.clearSelection(); setStatic("rows", List.copyOf(rows));
			set(ide,"variableWindowFocused",true); ide.keyPressed(key(65,2));
			check(((LuaVariableText)get(window,"text")).selected().contains("12345"),"Snapshot Ctrl+A did not select fixture variables: "+((LuaVariableText)get(window,"text")).selected());
			setStatic("rows",List.of(new LuaDebugHud.DisplayRow("fresh = ","999","number",0,false)));
			ide.keyPressed(key(67,2)); check(client.keyboardHandler.getClipboard().contains("12345"),"Live refresh changed selected snapshot: selected="+((LuaVariableText)get(window,"text")).selected()+", clipboard="+client.keyboardHandler.getClipboard()+", focused="+get(ide,"variableWindowFocused"));
			ide.keyPressed(key(256,0));
			check(!((LuaVariableText)get(window,"text")).hasSelection() && window.isOpen(),"Esc failed to clear frozen selection without closing");
			ide.keyPressed(key(65,2)); ide.keyPressed(key(67,2));
			check(client.keyboardHandler.getClipboard().equals("fresh = 999"),"Esc did not resume latest snapshot: clipboard="+client.keyboardHandler.getClipboard()+", selected="+((LuaVariableText)get(window,"text")).selected()+", focused="+get(ide,"variableWindowFocused"));
			// A side-dock animation can leave the editor beneath our floating fixture. Move it away
			// before testing editor focus; clicking an overlaid window must not click through it.
			window.restoreGeometry(new WindowGeometry(400,260,250,160));
			MultiLineEditBox editor=(MultiLineEditBox)get(ide,"editor");
			ide.mouseClicked(mouse(editor.getX()+10,editor.getY()+10),false); ide.mouseReleased(mouse(editor.getX()+10,editor.getY()+10));
			ide.keyPressed(key(65,2)); ide.keyPressed(key(67,2));
			check(client.keyboardHandler.getClipboard().equals(code),"Variable window stole code editor Ctrl+A/C focus: "+client.keyboardHandler.getClipboard());
			window.close(); check(!((LuaVariableText)get(window,"text")).hasSelection(),"Close retained sensitive selected snapshot");
			System.out.println("Variable window native probe passed: "+checks+" checks; selection/line/all/header copy, full values, floating/docked windows, frozen refresh, Esc and editor focus");
		} finally { client.keyboardHandler.setClipboard(clipboard); }
	}
	private static MouseButtonEvent mouse(double x,double y){return new MouseButtonEvent(x,y,new MouseButtonInfo(0,0));}
	private static KeyEvent key(int key,int modifiers){return new KeyEvent(key,0,modifiers);}
	private static Object get(Object target,String name)throws Exception{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
	private static void set(Object target,String name,Object value)throws Exception{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
	private static void setStatic(String name,Object value)throws Exception{var f=LuaDebugHud.class.getDeclaredField(name);f.setAccessible(true);f.set(null,value);}
	private static Object invoke(Object target,String name,Class<?>[] types,Object...args)throws Exception{var m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);}
	private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
