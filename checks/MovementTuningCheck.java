package com.codex.keymapper.engine;
public class MovementTuningCheck {
 public static void main(String[] args){
  for(float angle:new float[]{0,30,45,60,90})for(int hz:new int[]{30,60,120}){
   ReviewCheck.T t=new ReviewCheck.T();PointerEngine e=new PointerEngine(t);PointerEngine.Profile p=ReviewCheck.profile();
   p.dpad.movementTuningEnabled=true;p.dpad.diagonalAngle=angle;p.dpad.movementFrequency=hz;p.dpad.movementStepMin=1;p.dpad.movementStepMax=50;
   e.configure(p,1000,1000);e.pause(false);e.key("k","KEY_W",true,100);e.key("k","KEY_D",true,100);
   long last=-1000;float oldX=t.p.get("dpad")[0],oldY=t.p.get("dpad")[1];int moves=t.moves;
   for(int now=100;now<=2100;now++){
    e.tick(now);float[] q=t.p.get("dpad");
    if(t.moves>moves){ReviewCheck.check(now-last>=1000.0/hz-.001,"frequency exceeded");
      ReviewCheck.check(Math.hypot(q[0]-oldX,q[1]-oldY)<=25.51,"step range midpoint exceeded");
      ReviewCheck.check(q[0]>=oldX&&q[1]<=oldY,"overshoot/reversal");last=now;oldX=q[0];oldY=q[1];moves=t.moves;
    }
   }
   float sx=(float)Math.sin(Math.toRadians(angle)),sy=(float)Math.cos(Math.toRadians(angle)),scale=Math.max(sx,sy);
   ReviewCheck.at(t,500+100*sx,500-100*sy);
   e.key("k","KEY_D",false,2200);for(int now=2200;now<4200;now++)e.tick(now);ReviewCheck.at(t,500,400);
   ReviewCheck.check(t.downs==1&&t.ups==0,"touch gap");e.key("k","KEY_W",false,4300);ReviewCheck.check(t.p.isEmpty(),"release stuck");
  }
  System.out.println("PASS: live-configured 0/30/45/60/90 degree targets, 30/60/120Hz frequency limits, 1–50px step range, no overshoot, held-direction restore and continuous touch");
 }
}
