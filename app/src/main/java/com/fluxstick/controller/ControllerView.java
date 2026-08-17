package com.fluxstick.controller;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.view.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class ControllerView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private DatagramSocket socket;
    private InetAddress pc;
    private final int port = 26760;
    private String pcIp = "192.168.0.103";

    private float sx, sy;
    private final HashMap<String, Boolean> buttons = new HashMap<>();
    private float lx=0, ly=0, rx=0, ry=0;
    private boolean gyro = false, mouse = false;

    private final RectF leftStick = new RectF(), rightStick = new RectF();
    private final ArrayList<Hit> hits = new ArrayList<>();

    private static class Hit {
        RectF r; String id;
        Hit(float l,float t,float rr,float b,String i){r=new RectF(l,t,rr,b);id=i;}
        boolean contains(float x,float y){return r.contains(x,y);}
    }

    public ControllerView(Context c) {
        super(c);
        setFocusable(true);
        text.setTypeface(Typeface.create("sans", Typeface.BOLD));
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(1);
        startNetwork();
    }

    private void startNetwork() {
        net.execute(() -> {
            try { socket = new DatagramSocket(); pc = InetAddress.getByName(pcIp); }
            catch(Exception ignored) {}
        });
    }

    private void send() {
        net.execute(() -> {
            try {
                if(socket==null || pc==null) return;
                String json = "{\"lx\":"+fmt(lx)+",\"ly\":"+fmt(ly)+
                        ",\"rx\":"+fmt(rx)+",\"ry\":"+fmt(ry)+
                        ",\"a\":"+b("A")+",\"b\":"+b("B")+",\"x\":"+b("X")+",\"y\":"+b("Y")+
                        ",\"lb\":"+b("LB")+",\"rb\":"+b("RB")+",\"lt\":"+b("LT")+",\"rt\":"+b("RT")+
                        ",\"up\":"+b("UP")+",\"down\":"+b("DOWN")+",\"left\":"+b("LEFT")+",\"right\":"+b("RIGHT")+
                        ",\"lsb\":"+b("LSB")+",\"rsb\":"+b("RSB")+
                        ",\"select\":"+b("SELECT")+",\"start\":"+b("START")+
                        ",\"back\":"+b("BACK")+",\"guide\":"+b("GUIDE")+
                        ",\"gyro\":"+gyro+",\"mouse\":"+mouse+"}";
                byte[] data=json.getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(data,data.length,pc,port));
            } catch(Exception ignored) {}
        });
    }

    private String fmt(float v){return String.format(Locale.US,"%.3f",v);}
    private boolean b(String k){Boolean v=buttons.get(k);return v!=null&&v;}

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        sx=getWidth()/1920f; sy=getHeight()/1080f;
        float s=Math.min(sx,sy);
        c.drawColor(Color.rgb(2,7,12));

        // subtle honeycomb-style grid
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1,1.0f*s));
        p.setColor(Color.argb(35,0,145,230));
        float step=34*s;
        for(float y=42*s;y<getHeight();y+=step) {
            for(float x=18*s;x<getWidth();x+=step) {
                float xx=x+(((int)(y/step)&1)*step/2);
                c.drawCircle(xx,y,6*s,grid);
            }
        }

        // outer frame
        p.setColor(Color.rgb(0,155,235));
        p.setStrokeWidth(3*s);
        c.drawRoundRect(8*s,8*s,getWidth()-8*s,getHeight()-8*s,18*s,18*s,p);

        drawHeader(c,s);
        hits.clear();

        // Shoulder controls
        button(c,195,105,380,190,"LB",s,1.0f);
        button(c,400,75,515,220,"LT",s,1.0f);
        button(c,1405,75,1520,220,"RT",s,1.0f);
        button(c,1540,105,1725,190,"RB",s,1.0f);

        // sticks - deliberately large
        leftStick.set(90*s,290*s,455*s,655*s);
        rightStick.set(1465*s,290*s,1830*s,655*s);
        drawStick(c,leftStick,lx,ly,"LSB",s);
        drawStick(c,rightStick,rx,ry,"RSB",s);

        // D pad and ABXY
        drawDpad(c,650,385,s);
        drawFace(c,1245,420,s);

        // center controls
        button(c,750,680,910,755,"SELECT",s,1.0f);
        circleButton(c,925,718,"BACK", "≡", s);
        circleButton(c,995,718,"GUIDE", "□", s);
        button(c,1015,680,1175,755,"START",s,1.0f);

        // mode
        circleButton(c,1735,705,"MODE","MODE",s);

        // bottom indicators
        text.setTextSize(14*s);
        text.setColor(Color.rgb(0,210,150));
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText("● ONLINE / UDP",20*s,(getHeight()-22*s),text);
        text.setColor(Color.rgb(110,130,145));
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(gyro ? "● GYRO LOOK" : "○ GYRO LOOK",850*s,getHeight()-22*s,text);
        c.drawText(mouse ? "● MOUSE MODE" : "○ MOUSE MODE",1060*s,getHeight()-22*s,text);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText("PING --",getWidth()-20*s,getHeight()-22*s,text);
    }

    private void drawHeader(Canvas c,float s) {
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans",Typeface.BOLD));
        text.setTextSize(28*s);
        text.setColor(Color.rgb(0,165,245));
        c.drawText("FLUXSTICK",getWidth()/2f,31*s,text);
        text.setTextSize(8*s);
        text.setColor(Color.rgb(100,125,140));
        c.drawText("VIRTUAL GAME CONTROLLER",getWidth()/2f,42*s,text);
        text.setTextSize(30*s);
        text.setColor(Color.LTGRAY);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText("⚙",getWidth()-22*s,43*s,text);
    }

    private void drawStick(Canvas c,RectF r,float vx,float vy,String label,float s) {
        float cx=r.centerX(), cy=r.centerY(), rad=r.width()/2f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3*s);
        p.setColor(Color.rgb(0,155,245));
        c.drawCircle(cx,cy,rad,p);
        p.setStrokeWidth(1*s);
        p.setColor(Color.argb(100,40,110,150));
        c.drawCircle(cx,cy,rad*.70f,p);
        c.drawCircle(cx,cy,rad*.48f,p);
        float kx=cx+vx*rad*.52f, ky=cy+vy*rad*.52f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.rgb(10,25,35));
        c.drawCircle(kx,ky,rad*.19f,p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2*s);
        p.setColor(Color.rgb(60,90,110));
        c.drawCircle(kx,ky,rad*.19f,p);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(12*s);
        text.setColor(Color.rgb(0,170,245));
        text.setTypeface(Typeface.DEFAULT_BOLD);
        c.drawText(label,cx,cy+rad+24*s,text);
        hits.add(new Hit(r.left,r.top,r.right,r.bottom,label));
    }

    private void drawDpad(Canvas c,float cx,float cy,float s) {
        float w=82*s, h=82*s, gap=3*s;
        button(c,cx-w/2,cy-h*1.5f,cx+w/2,cy-h/2,"UP",s,.82f);
        button(c,cx-w*1.5f,cy-h/2,cx-w/2-gap,cy+h/2,"LEFT",s,.82f);
        button(c,cx+w/2+gap,cy-h/2,cx+w*1.5f,cy+h/2,"RIGHT",s,.82f);
        button(c,cx-w/2,cy+h/2,cx+w/2,cy+h*1.5f,"DOWN",s,.82f);
    }

    private void drawFace(Canvas c,float cx,float cy,float s) {
        face(c,cx,cy-62*s,"Y",Color.rgb(255,205,25),s);
        face(c,cx-62*s,cy,"X",Color.rgb(30,170,255),s);
        face(c,cx+62*s,cy,"B",Color.rgb(245,65,70),s);
        face(c,cx,cy+62*s,"A",Color.rgb(35,220,125),s);
    }

    private void face(Canvas c,float cx,float cy,String id,int color,float s) {
        float r=34*s;
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.rgb(4,18,28));
        c.drawCircle(cx,cy,r,p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2*s);
        p.setColor(Color.rgb(15,95,140));
        c.drawCircle(cx,cy,r,p);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(18*s);
        text.setColor(color);
        c.drawText(id,cx,cy+6*s,text);
        hits.add(new Hit(cx-r,cy-r,cx+r,cy+r,id));
    }

    private void button(Canvas c,float l,float t,float r,float b,String id,float s,float scale) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.rgb(7,25,36));
        c.drawRoundRect(l*s,t*s,r*s,b*s,12*s,12*s,p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2*s);
        p.setColor(Color.rgb(20,105,150));
        c.drawRoundRect(l*s,t*s,r*s,b*s,12*s,12*s,p);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(Math.max(14,22*scale)*s);
        text.setColor(Color.rgb(215,235,245));
        c.drawText(labelFor(id),(l+r)*s/2,(t+b)*s/2+7*s,text);
        hits.add(new Hit(l*s,t*s,r*s,b*s,id));
    }

    private String labelFor(String id) {
        if(id.equals("UP"))return "▲";
        if(id.equals("DOWN"))return "▼";
        if(id.equals("LEFT"))return "◀";
        if(id.equals("RIGHT"))return "▶";
        return id;
    }

    private void circleButton(Canvas c,float cx,float cy,String id,String label,float s) {
        float r=id.equals("MODE")?30*s:25*s;
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.rgb(4,18,28));
        c.drawCircle(cx*s,cy*s,r,p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2*s);
        p.setColor(Color.rgb(20,105,150));
        c.drawCircle(cx*s,cy*s,r,p);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize((id.equals("MODE")?10:16)*s);
        text.setColor(Color.LTGRAY);
        c.drawText(label,cx*s,cy*s+5*s,text);
        hits.add(new Hit(cx*s-r,cy*s-r,cx*s+r,cy*s+r,id));
    }

    @Override public boolean onTouchEvent(android.view.MotionEvent e) {
        float x=e.getX(), y=e.getY();
        int action=e.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN ||
           action==MotionEvent.ACTION_MOVE) {
            for(int i=0;i<e.getPointerCount();i++) {
                float px=e.getX(i), py=e.getY(i);
                handlePointer(px,py,true);
            }
            invalidate(); send(); return true;
        }
        if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP ||
           action==MotionEvent.ACTION_CANCEL) {
            if(action==MotionEvent.ACTION_UP) {
                for(String k:new ArrayList<>(buttons.keySet())) buttons.put(k,false);
                lx=ly=rx=ry=0;
            } else {
                handlePointer(x,y,false);
            }
            invalidate(); send(); return true;
        }
        return true;
    }

    private void handlePointer(float x,float y,boolean down) {
        // sticks
        if(leftStick.contains(x,y)) {
            lx=Math.max(-1,Math.min(1,(x-leftStick.centerX())/(leftStick.width()/2f)));
            ly=Math.max(-1,Math.min(1,(y-leftStick.centerY())/(leftStick.height()/2f)));
            if(!down){lx=ly=0;}
            return;
        }
        if(rightStick.contains(x,y)) {
            rx=Math.max(-1,Math.min(1,(x-rightStick.centerX())/(rightStick.width()/2f)));
            ry=Math.max(-1,Math.min(1,(y-rightStick.centerY())/(rightStick.height()/2f)));
            if(!down){rx=ry=0;}
            return;
        }
        for(Hit h:hits) if(h.contains(x,y)) {
            if(h.id.equals("MODE")) { if(down){mouse=!mouse;} }
            else if(h.id.equals("GYRO")) { if(down)gyro=!gyro; }
            else buttons.put(h.id,down);
        }
    }
}
