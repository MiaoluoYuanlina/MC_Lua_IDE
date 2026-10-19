package wiki.ica.xiaomiao.mcluaide.client;

import java.nio.file.Files;
import java.util.Properties;
import java.util.Random;

final class LuaHudLayoutSmoke {
	private static int checks;
	public static void main(String[] args) throws Exception {
		var root = Files.createTempDirectory("lua-hud-layout-test-");
		var file = root.resolve("layout.properties");
		try {
			LuaHudLayout layout = new LuaHudLayout(file);
			for (int[] viewport : new int[][]{{320,240},{640,360},{854,480},{1920,1080}}) {
				for (var a : LuaHudLayout.Panel.values()) for (var b : LuaHudLayout.Panel.values()) if (a!=b) {
					var ar=layout.bounds(a,viewport[0],viewport[1]); var br=layout.bounds(b,viewport[0],viewport[1]);
					require(ar.right()<=br.x() || br.right()<=ar.x() || ar.bottom()<=br.y() || br.bottom()<=ar.y(),"Overlapping defaults: "+a+" / "+b);
				}
			}
			Random random = new Random(62437);
			for (var panel : LuaHudLayout.Panel.values()) {
				for (int[] viewport : new int[][]{{1920,1080},{640,360},{320,240},{100,60},{1,1}}) {
					int vw = viewport[0], vh = viewport[1];
					var defaults = layout.bounds(panel, vw, vh); inside(defaults,vw,vh);
					for (int edges : new int[]{0,1,2,4,8,5,6,9,10}) for (int i=0;i<20;i++) {
						var moved = LuaHudLayout.drag(panel, defaults, edges, random.nextInt(10000)-5000, random.nextInt(10000)-5000, vw,vh);
						inside(moved,vw,vh);
						require(moved.width()>=Math.min(vw,panel.minWidth) && moved.height()>=Math.min(vh,panel.minHeight),"Minimum size");
						if (edges!=0) {
							if ((edges&1)!=0) require(moved.right()==defaults.right(),"Left resize shifted right edge");
							if ((edges&2)!=0) require(moved.x()==defaults.x(),"Right resize shifted left edge");
							if ((edges&4)!=0) require(moved.bottom()==defaults.bottom(),"Top resize shifted bottom edge");
							if ((edges&8)!=0) require(moved.y()==defaults.y(),"Bottom resize shifted top edge");
						}
					}
				}
				var rect = new LuaHudLayout.Rect(120,80,300,140);
				layout.set(panel,rect,1000,600);
				require(layout.bounds(panel,1000,600).equals(rect),"Set bounds");
				require(layout.bounds(panel,2000,1200).equals(new LuaHudLayout.Rect(240,160,600,280)),"Resolution scaling");
				require(LuaHudLayout.edges(rect,120,100)==1,"Left hint");
				require(LuaHudLayout.edges(rect,420,100)==2,"Right hint");
				require(LuaHudLayout.edges(rect,200,80)==4,"Top hint");
				require(LuaHudLayout.edges(rect,200,220)==8,"Bottom hint");
				require(LuaHudLayout.edges(rect,120,80)==5,"Corner hint");
				require(LuaHudLayout.edges(rect,200,100)==0 && LuaHudLayout.edges(rect,1,1)==0,"No unrelated edge hints");
			}
			for (var panel : LuaHudLayout.Panel.values()) {
				int original = layout.backgroundColor(panel);
				int defaultTransparency = layout.transparency(panel);
				for (int percent : new int[]{0,1,25,50,89,99,100}) {
					layout.setTransparency(panel, percent);
					require(layout.transparency(panel)==percent,"Transparency percent");
					int color=layout.backgroundColor(panel);
					require((color&0xFFFFFF)==(original&0xFFFFFF),"Background RGB changed");
					require((color>>>24)==Math.round(255*(100-percent)/100F),"Transparency alpha");
				}
				layout.setTransparency(panel,-10); require((layout.backgroundColor(panel)>>>24)==255,"Opaque lower clamp");
				layout.setTransparency(panel,500); require((layout.backgroundColor(panel)>>>24)==0,"Transparent upper clamp");
				layout.resetTransparency(panel);
				require(layout.backgroundColor(panel)==original&&layout.transparency(panel)==defaultTransparency,"Exact legacy default restored");
				layout.setTransparency(panel, panel.ordinal()*25);
			}
			require(layout.save(),"Save");
			var reloaded = new LuaHudLayout(file);
			for (var panel : LuaHudLayout.Panel.values()) require(reloaded.bounds(panel,1000,600).equals(layout.bounds(panel,1000,600)),"Reload");
			for (var panel : LuaHudLayout.Panel.values()) require(reloaded.transparency(panel)==panel.ordinal()*25
					&& reloaded.backgroundColor(panel)==layout.backgroundColor(panel),"Independent transparency reload");
			for (String bad : new String[]{"NaN","Infinity","-1","101","10.5","invalid"}) {
				Properties values=layout.properties(); values.setProperty("VARIABLES.backgroundTransparency",bad); reloaded.read(values);
				require(reloaded.backgroundColor(LuaHudLayout.Panel.VARIABLES)==0xBC10131A,"Invalid transparency fallback");
				require(reloaded.transparency(LuaHudLayout.Panel.LOGS)==50,"Other panel transparency affected");
			}
			Properties appearanceOnly=new Properties(); appearanceOnly.setProperty("CAMERA.backgroundTransparency","60"); reloaded.read(appearanceOnly);
			require(reloaded.transparency(LuaHudLayout.Panel.CAMERA)==60,"Transparency lost without custom bounds");
			for (String bad : new String[]{"NaN","Infinity","-1","2","garbage"}) {
				Properties values=layout.properties(); values.setProperty("VARIABLES.x",bad); reloaded.read(values);
				LuaHudLayout defaults=new LuaHudLayout(root.resolve("missing.properties"));
				require(reloaded.bounds(LuaHudLayout.Panel.VARIABLES,1000,600).equals(defaults.bounds(LuaHudLayout.Panel.VARIABLES,1000,600)),"Invalid config fallback: "+bad);
			}
			reloaded.reset(); require(reloaded.properties().isEmpty() && reloaded.save(),"Reset and save");
			require(new LuaHudLayout(file).properties().isEmpty(),"Reset persisted");
			System.out.println("HUD layout smoke passed: "+checks+" checks; four panels, bounds, resize directions, scaling, transparency/alpha/RGB isolation, persistence, legacy defaults, reset and malformed config");
		} finally { Files.deleteIfExists(file); Files.deleteIfExists(root); }
	}
	private static void inside(LuaHudLayout.Rect r,int w,int h) { require(r.x()>=0&&r.y()>=0&&r.width()>0&&r.height()>0&&r.right()<=w&&r.bottom()<=h,"Outside viewport: "+r); }
	private static void require(boolean value,String message) { checks++; if(!value)throw new AssertionError(message); }
}
