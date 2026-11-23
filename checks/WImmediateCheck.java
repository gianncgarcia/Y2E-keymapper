package com.codex.keymapper.engine;
public class WImmediateCheck {
 public static void main(String[] args){
 ReviewCheck.T t=new ReviewCheck.T();PointerEngine e=new PointerEngine(t);PointerEngine.Profile p=ReviewCheck.profile();
 p.dpad.transitionDelayMs=50;p.dpad.movementTuningEnabled=true;p.dpad.movementFrequency=60;p.dpad.movementStepMin=1;p.dpad.movementStepMax=1;
 e.configure(p,1000,1000);e.pause(false);e.key("k","KEY_W",true,100);ReviewCheck.at(t,500,400);
 e.key("k","KEY_D",true,150);for(int now=150;now<500;now++)e.tick(now);
 ReviewCheck.check(t.p.get("dpad")[0]>500,"sideways transition absent");
 e.key("k","KEY_D",false,510);ReviewCheck.at(t,500,400);
 e.key("k","KEY_S",true,520);ReviewCheck.at(t,500,500);e.tick(550);ReviewCheck.check(t.p.get("dpad")[1]<600,"S bypassed transition");
 e.key("k","KEY_S",false,560);ReviewCheck.at(t,500,400);
 ReviewCheck.check(t.downs==1&&t.ups==0,"touch restarted");e.key("k","KEY_W",false,570);ReviewCheck.check(t.p.isEmpty(),"stuck W");
 System.out.println("PASS: W immediate with 50ms duration/1px steps, W-only lateral cleanup, S still transitions, restore held W, one continuous touch");
 }
}
