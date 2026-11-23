package com.codex.keymapper.engine;
public class NoOvershootCheck {
 static final String[][] DIR={{"KEY_W"},{"KEY_W","KEY_D"},{"KEY_D"},{"KEY_S","KEY_D"},{"KEY_S"},{"KEY_S","KEY_A"},{"KEY_A"},{"KEY_W","KEY_A"}};
 static boolean contains(String[] keys,String key){for(String k:keys)if(k.equals(key))return true;return false;}
 public static void main(String[] args){int cases=0;
 for(String[] from:DIR)for(String[] to:DIR){
 ReviewCheck.T t=new ReviewCheck.T();PointerEngine e=new PointerEngine(t);PointerEngine.Profile p=ReviewCheck.profile();
 p.dpad.transitionDelayMs=40;p.dpad.movementTuningEnabled=true;p.dpad.movementFrequency=60;p.dpad.movementStepMin=1;p.dpad.movementStepMax=50;
 e.configure(p,1000,1000);e.pause(false);for(String k:from)e.key("k",k,true,100);for(int n=100;n<=1100;n++)e.tick(n);
 for(String k:to)if(!contains(from,k))e.key("k",k,true,1200);
 for(String k:from)if(!contains(to,k))e.key("k",k,false,1200);
 float[] old=t.p.get("dpad").clone();
 for(int n=1200;n<=2500;n++){
  e.tick(n);float[] q=t.p.get("dpad");ReviewCheck.check(q!=null,"touch gap");
  double radius=Math.hypot(q[0]-500,q[1]-500);ReviewCheck.check(radius>=99.98,"cut through slow center: "+radius);
  ReviewCheck.check(radius<=100.02,"radius overshoot");
  ReviewCheck.check(Math.hypot(q[0]-old[0],q[1]-old[1])<=25.52,"step exceeded");old=q.clone();
 }
 int x=contains(to,"KEY_D")?1:contains(to,"KEY_A")?-1:0,y=contains(to,"KEY_S")?1:contains(to,"KEY_W")?-1:0;
 float norm=(float)Math.hypot(x,y);ReviewCheck.at(t,500+x*100/norm,500+y*100/norm);ReviewCheck.check(t.downs==1&&t.ups==0,"touch restart");
 e.key("k","KEY_LEFTSHIFT",true,2600);
 for(int n=2600;n<=3100;n++){e.tick(n);float[] q=t.p.get("dpad");double r=Math.hypot(q[0]-500,q[1]-500);ReviewCheck.check(r>=39.98&&r<=100.02,"walking radius overshoot");}
 ReviewCheck.at(t,500+x*40/norm,500+y*40/norm);
 for(String k:to)e.key("k",k,false,3200);ReviewCheck.check(t.p.isEmpty(),"last release stuck");cases++;
 }
 System.out.println("PASS: "+cases+" outer-arc direction transitions never enter the inner radius, respect step caps and keep one finger down");
 }
}
