package com.codex.keymapper.engine;
public class TransitionDelayCheck {
 public static void main(String[] args){for(int delay:new int[]{0,8,16,50}){
 ReviewCheck.T t=new ReviewCheck.T();PointerEngine e=new PointerEngine(t);PointerEngine.Profile p=ReviewCheck.profile();p.dpad.transitionDelayMs=delay;e.configure(p,1000,1000);e.pause(false);
 e.key("k","KEY_A",true,100);e.tick(100+delay);ReviewCheck.at(t,400,500);
 e.key("k","KEY_S",true,200);if(delay>0){e.tick(200+delay/2); float[] mid=t.p.get("dpad");ReviewCheck.check(Math.abs(Math.hypot(mid[0]-500,mid[1]-500)-100)<.02,"transition overshoot");}
 e.tick(200+delay);ReviewCheck.at(t,429.28932f,570.71068f);ReviewCheck.check(t.downs==1&&t.ups==0,"touch gap on direction change");
 e.key("k","KEY_A",false,300);e.tick(300+delay);ReviewCheck.at(t,500,600);
 ReviewCheck.check(t.downs==1&&t.ups==0,"touch gap on partial release");
 e.key("k","KEY_S",false,400);ReviewCheck.check(t.p.isEmpty()&&t.ups==1,"final release not immediate");
 e.tick(1000);ReviewCheck.check(t.p.isEmpty(),"old target replayed");
 }System.out.println("PASS: 0/8/16/50ms direction transitions, one continuous touch, latest direction and immediate final release");}
}
