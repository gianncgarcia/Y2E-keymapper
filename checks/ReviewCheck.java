package com.codex.keymapper.engine;
import java.util.*;
public class ReviewCheck {
 static class T implements PointerEngine.Touch {
 Map<String,float[]> p=new HashMap<>();int downs,ups,moves;float totalX,totalY;
 public void down(String n,float x,float y){if(p.put(n,new float[]{x,y})!=null)throw new AssertionError("duplicate down");downs++;}
 public void move(String n,float x,float y){float[] old=p.get(n);if(old==null)throw new AssertionError("move without down");
 if(n.equals("aim")){totalX+=x-old[0];totalY+=y-old[1];}p.put(n,new float[]{x,y});moves++;}
 public void up(String n){if(p.remove(n)==null)throw new AssertionError("up without down");ups++;}
 public void upAt(String n,float x,float y){up(n);}public void cancel(String n){p.remove(n);}public void cancel(){p.clear();}
 }
 static PointerEngine.Profile profile(){PointerEngine.Profile p=new PointerEngine.Profile();p.dpad=new PointerEngine.Dpad(new PointerEngine.Point(.5f,.5f),.1f,0,"KEY_W","KEY_S","KEY_A","KEY_D","KEY_LEFTSHIFT",-.06f,true,45,80,1);return p;}
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static void at(T t,float x,float y){float[] a=t.p.get("dpad");check(a!=null&&Math.abs(a[0]-x)<.1&&Math.abs(a[1]-y)<.1,"direction "+Arrays.toString(a));}
 public static void main(String[] args){
 T t=new T();PointerEngine e=new PointerEngine(t);e.configure(profile(),1000,1000);e.pause(false);
 e.key("k","KEY_W",true,100);check(t.downs==1,"DOWN postponed");
 for(int i=1;i<30;i++){e.key("k","KEY_D",i%2==1,100+i);e.tick(100+i);}check(t.moves>0,"rapid keys starved move");
 e.key("k","KEY_D",false,140);e.tick(160);at(t,500,400);
 e.key("k","KEY_S",true,180);e.tick(200);at(t,500,600);e.key("k","KEY_S",false,220);e.tick(240);at(t,500,400);
 e.key("k","KEY_LEFTSHIFT",true,260);e.tick(280);at(t,500,460);e.key("k","KEY_LEFTSHIFT",false,300);e.tick(320);at(t,500,400);
 e.key("k","KEY_W",false,330);check(!t.p.containsKey("dpad"),"release delayed");
 int d=t.downs,u=t.ups;e.key("k","KEY_W",true,400);e.key("k","KEY_W",false,401);e.tick(500);check(t.downs==d+1&&t.ups==u+1,"short tap lost or resurrected");
 PointerEngine.Profile edge=profile();edge.dpad=new PointerEngine.Dpad(new PointerEngine.Point(.99f,.01f),.4f,0,"KEY_W","KEY_S","KEY_A","KEY_D","",0,true,45,80,1);
 e.configure(edge,1000,1000);e.key("k","KEY_W",true,600);e.key("k","KEY_D",true,600);e.tick(620);float[] a=t.p.get("dpad");check(a[0]<=998&&a[1]>=1,"offscreen touch");
 T aim=new T();e=new PointerEngine(aim);PointerEngine.Profile ap=new PointerEngine.Profile();ap.aim=new PointerEngine.Point(.5f,.5f);ap.gainX=1;ap.gainY=1;ap.aimBoundary=true;ap.aimBoundaryRadius=.1f;ap.aimAutoRelease=false;e.configure(ap,1000,1000);e.pause(false);e.key("m","BTN_MIDDLE",true,1000);
 e.motion("m",350,40,1010);for(int i=0;i<10;i++)e.tick(1020+i);check(Math.abs(aim.totalX-350)<.02&&Math.abs(aim.totalY-40)<.02,"aim remainder lost "+aim.totalX+","+aim.totalY);
 e.motion("m",-5000,1000,1100);for(int i=0;i<30;i++)e.tick(1110+i);check(Math.abs(aim.totalX+4650)<1&&Math.abs(aim.totalY-1040)<1,"large aim remainder lost");
 System.out.println("PASS: rapid-update starvation, immediate short-tap/release, last-pressed restore, walking hold, screen bounds, aim boundary remainder including large moves");
 }
}
