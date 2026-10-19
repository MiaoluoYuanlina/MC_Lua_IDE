package wiki.ica.xiaomiao.mcluaide.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

/** Opt-in native input/render regression, restricted to a throwaway run directory and no world. */
final class LuaHudLayoutProbe {
	private static int stage, checks;
	private static long started, until;
	private static LuaDebugScreen menu;
	private static LuaHudLayoutScreen screen;
	private static boolean variables, logs, breaks, camera;
	static void tick(Minecraft client) {
		if (stage == 99 || client.getOverlay() != null) return;
		try {
			if (stage == 0) {
				started = System.nanoTime();
				require(client.gameDirectory.toPath().toAbsolutePath().normalize().toString().contains("hud-layout-probe"), "Unsafe probe directory");
				client.options.guiScale().set(1); client.resizeGui();
				variables=LuaDebugHud.variablesEnabled(); logs=LuaLogHud.enabled(); breaks=LuaBreakpointClient.showInfo(); camera=LuaDebuggerCamera.enabled();
				LuaHudLayout.current().reset(); menu = new LuaDebugScreen(client.screen); client.setScreen(menu);
				Button open = menu.children().stream().filter(v -> v instanceof Button b && b.getMessage().getString().equals("位置调整…"))
						.map(v -> (Button)v).findFirst().orElseThrow();
				menu.mouseClicked(event(open.getX()+10,open.getY()+10),false);
				require(client.screen instanceof LuaHudLayoutScreen, "F12 position button did not open layout screen");
				screen=(LuaHudLayoutScreen)client.screen;
				waitFor(1); return;
			}
			require(System.nanoTime()-started<60_000_000_000L,"HUD layout native timeout at "+stage);
			if (System.nanoTime()<until) return;
			if (stage == 1) {
				// Keep the fixtures apart so every border is tested through the real hit-test and mouse routing.
				int i=0;
				for(var panel:LuaHudLayout.Panel.values()) {
					int x=i%2==0?25:screen.width-225, y=i<2?65:screen.height-140;
					LuaHudLayout.current().set(panel,new LuaHudLayout.Rect(x,y,200,110),screen.width,screen.height); i++;
				}
				for(var panel:LuaHudLayout.Panel.values()) {
					var start=bounds(panel);
					drag(start.x()+30,start.y()+25,15,10);
					require(bounds(panel).equals(LuaHudLayout.drag(panel,start,0,15,10,screen.width,screen.height)),"Actual panel move: "+panel);
					for(int edges:new int[]{1,2,4,8,5,6,9,10}) {
						start=bounds(panel);
						double x=(edges&1)!=0?start.x():(edges&2)!=0?start.right():start.x()+start.width()/2;
						double y=(edges&4)!=0?start.y():(edges&8)!=0?start.bottom():start.y()+start.height()/2;
						int dx=(edges&1)!=0?-5:(edges&2)!=0?5:0, dy=(edges&4)!=0?-3:(edges&8)!=0?3:0;
						drag(x,y,dx,dy);
						require(bounds(panel).equals(LuaHudLayout.drag(panel,start,edges,dx,dy,screen.width,screen.height)),"Actual border resize: "+panel+" / "+edges);
					}
				}
				unchangedSwitches();
				// Tab changes selected panel; arrows move it and Ctrl+arrows resize instead of controlling scripts.
				key(InputConstants.KEY_TAB,0);
				var selected=LuaHudLayout.Panel.VARIABLES; var before=bounds(selected);
				key(InputConstants.KEY_RIGHT,0); require(bounds(selected).x()==before.x()+1,"Keyboard move");
				before=bounds(selected); key(InputConstants.KEY_RIGHT,2);
				require(bounds(selected).width()==before.width()+1,"Keyboard resize");
				// GUI resize cancels a pending drag and retains normalized geometry.
				before=bounds(selected); screen.mouseClicked(event(before.x()+25,before.y()+20),false);
				client.options.guiScale().set(2); client.resizeGui();
				require(!screen.mouseDragged(event(60,60),10,10),"Drag was not cancelled on GUI resize");
				for(var panel:LuaHudLayout.Panel.values()) { var b=bounds(panel); require(b.x()>=0&&b.y()>=0&&b.right()<=screen.width&&b.bottom()<=screen.height,"Panel off screen at new scale"); }
				client.options.guiScale().set(1); client.resizeGui();
				// Right-click menus remain above every window and do not turn slider drags into layout drags.
				for (var panel : LuaHudLayout.Panel.values()) {
					var geometry=bounds(panel);
					var others=new java.util.EnumMap<LuaHudLayout.Panel,Integer>(LuaHudLayout.Panel.class);
					for(var other:LuaHudLayout.Panel.values()) others.put(other,LuaHudLayout.current().backgroundColor(other));
					require(screen.mouseClicked(right(geometry.right()-1,geometry.bottom()-1),false),"Right-click menu did not open");
					var slider=slider();
					require(slider.getX()>=0&&slider.getRight()<=screen.width&&slider.getY()>=0&&slider.getY()+73<=screen.height,"Menu off screen");
					double sx=slider.getX()+4, sy=slider.getY()+10;
					screen.mouseClicked(event(sx,sy),false);
					require(LuaHudLayout.current().transparency(panel)==0,"Slider opaque endpoint");
					require(screen.mouseDragged(event(slider.getRight()-4,sy),slider.getWidth()-8,0),"Slider drag not routed");
					screen.mouseReleased(event(slider.getRight()-4,sy));
					require(LuaHudLayout.current().transparency(panel)==100,"Slider transparent endpoint");
					key(InputConstants.KEY_LEFT,0); require(LuaHudLayout.current().transparency(panel)==99,"Slider keyboard step");
					key(InputConstants.KEY_LEFT,1); require(LuaHudLayout.current().transparency(panel)==89,"Slider Shift step");
					for(var other:LuaHudLayout.Panel.values()) if(other!=panel) require(LuaHudLayout.current().backgroundColor(other)==others.get(other),"Other panel transparency changed");
					require(bounds(panel).equals(geometry),"Slider moved/resized window");
					key(InputConstants.KEY_ESCAPE,0);
					require(client.screen==screen&&!screen.children().stream().anyMatch(v->v instanceof AbstractSliderButton),"Esc did not only close menu");
				}
				// Reset only one background; closing with an outside click does not activate the window underneath.
				var geometry=bounds(LuaHudLayout.Panel.VARIABLES);
				screen.mouseClicked(right(geometry.x()+20,geometry.y()+20),false);
				Button opacityReset=screen.children().stream().filter(v->v instanceof Button b&&b.getMessage().getString().equals("恢复默认透明度"))
						.map(v->(Button)v).findFirst().orElseThrow();
				screen.mouseClicked(event(opacityReset.getX()+10,opacityReset.getY()+10),false);
				require(LuaHudLayout.current().backgroundColor(LuaHudLayout.Panel.VARIABLES)==0xBC10131A,"Individual transparency reset");
				require(LuaHudLayout.current().transparency(LuaHudLayout.Panel.LOGS)==89,"Reset changed another panel");
				screen.mouseClicked(event(0,screen.height/2),false);
				require(!screen.mouseDragged(event(10,screen.height/2),10,0)&&bounds(LuaHudLayout.Panel.VARIABLES).equals(geometry),"Outside click passed through menu");
				screen.mouseClicked(right(geometry.x()+20,geometry.y()+20),false);
				var alpha=slider(); screen.mouseClicked(event(alpha.getX()+4,alpha.getY()+10),false); screen.mouseReleased(event(alpha.getX()+4,alpha.getY()+10));
				for(int n=0;n<6;n++)key(InputConstants.KEY_RIGHT,1);
				require(LuaHudLayout.current().transparency(LuaHudLayout.Panel.VARIABLES)==60,"Preview transparency");
				waitFor(2); return;
			}
			if(stage==2) {
				net.minecraft.client.Screenshot.grab(client.gameDirectory,"hud-layout.png",client.getMainRenderTarget(),1,ignored->{});
				// Screenshot readback is asynchronous; do not replace the screen in the same frame.
				waitFor(3); return;
			}
			if(stage==3) {
				key(InputConstants.KEY_ESCAPE,0); require(client.screen==screen,"First Esc closed screen instead of menu");
				key(InputConstants.KEY_ESCAPE,0); require(client.screen==menu,"Esc did not return to F12 menu");
				var saved=new LuaHudLayout(FabricLoader.getInstance().getConfigDir().resolve("mc_lua_ide/debug-hud-layout.properties"));
				for(var panel:LuaHudLayout.Panel.values()) require(saved.bounds(panel,screen.width,screen.height).equals(bounds(panel)),"Saved layout mismatch");
				for(var panel:LuaHudLayout.Panel.values()) require(saved.transparency(panel)==LuaHudLayout.current().transparency(panel),"Transparency did not persist");
				unchangedSwitches();
				client.setScreen(new LuaHudLayoutScreen(menu)); screen=(LuaHudLayoutScreen)client.screen;
				key(InputConstants.KEY_F12,0); require(client.screen==menu,"F12 did not exit layout editor");
				waitFor(4); return;
			}
			if(stage==4) {
				client.setScreen(new LuaHudLayoutScreen(menu)); screen=(LuaHudLayoutScreen)client.screen;
				Button reset=screen.children().stream().filter(v->v instanceof Button b&&b.getMessage().getString().equals("恢复默认"))
						.map(v->(Button)v).findFirst().orElseThrow();
				screen.mouseClicked(event(reset.getX()+10,reset.getY()+10),false);
				require(LuaHudLayout.current().properties().isEmpty(),"Reset did not clear custom geometry");
				unchangedSwitches(); key(InputConstants.KEY_ESCAPE,0);
				System.out.println("HUD layout native probe passed: "+checks+" checks; F12/four previews, all moves/resizes, right-click opacity menus, slider drag/arrows/Shift, independent reset, outside clicks, persistence, Esc/F12, unchanged switches");
				stage=99; client.stop();
			}
		} catch(Exception|AssertionError error) { int failed=stage; stage=99; client.stop(); throw new IllegalStateException("HUD layout native probe failed at "+failed,error); }
	}
	private static void unchangedSwitches() { require(variables==LuaDebugHud.variablesEnabled()&&logs==LuaLogHud.enabled()&&breaks==LuaBreakpointClient.showInfo()&&camera==LuaDebuggerCamera.enabled(),"Layout changed debug/camera switches"); }
	private static LuaHudLayout.Rect bounds(LuaHudLayout.Panel panel) { return LuaHudLayout.current().bounds(panel,screen.width,screen.height); }
	private static MouseButtonEvent event(double x,double y) { return new MouseButtonEvent(x,y,new MouseButtonInfo(0,0)); }
	private static MouseButtonEvent right(double x,double y) { return new MouseButtonEvent(x,y,new MouseButtonInfo(1,0)); }
	private static AbstractSliderButton slider() { return screen.children().stream().filter(v->v instanceof AbstractSliderButton).map(v->(AbstractSliderButton)v).findFirst().orElseThrow(); }
	private static void drag(double x,double y,int dx,int dy) {
		require(screen.mouseClicked(event(x,y),false),"Mouse click not consumed");
		require(screen.mouseDragged(event(x+dx,y+dy),dx,dy),"Mouse drag not consumed");
		require(screen.mouseReleased(event(x+dx,y+dy)),"Mouse release not consumed");
	}
	private static void key(int key,int modifiers) { screen.keyPressed(new KeyEvent(key,0,modifiers)); }
	private static void waitFor(int next) { stage=next; until=System.nanoTime()+1_000_000_000L; }
	private static void require(boolean value,String message) { checks++; if(!value)throw new AssertionError(message); }
}
