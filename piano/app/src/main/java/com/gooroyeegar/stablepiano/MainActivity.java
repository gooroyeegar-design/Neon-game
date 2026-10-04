package com.gooroyeegar.stablepiano;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.*;
import android.media.*;
import android.view.*;
import android.content.Context;
import java.util.*;

public class MainActivity extends Activity {
    Piano piano;
    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideBars();
        piano=new Piano(this); setContentView(piano);
    }
    void hideBars(){ getWindow().getDecorView().setSystemUiVisibility(
        View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE); }
    @Override public void onWindowFocusChanged(boolean f){super.onWindowFocusChanged(f);if(f)hideBars();}
    @Override protected void onDestroy(){if(piano!=null)piano.close();super.onDestroy();}

    final class Piano extends View {
        Paint p=new Paint(3), s=new Paint(3);
        HashMap<Integer,Integer> fingers=new HashMap<>();
        HashMap<Integer,Note> notes=new HashMap<>();
        ArrayList<String> take=new ArrayList<>();
        AudioTrack track; Thread mixer; volatile boolean alive=true;
        int instrument=0; float volume=.8f,keyW=72; float scroll=0;
        boolean sustain=false,rec=false,playing=false; long recAt;
        final String[] names={"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"};
        final int[] whites={0,2,4,5,7,9,11};
        final boolean[] black={false,true,false,true,false,false,true,false,true,false,true,false};

        class Note { int m; float v; long on,off; boolean held=true;
            Note(int m,float v){this.m=m;this.v=v;on=System.nanoTime();}}
        Piano(Context c){super(c);p.setTypeface(Typeface.create("sans",0));s.setStyle(Paint.Style.STROKE);setFocusable(true);audio();}

        void audio(){
            int rate=44100,min=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT);
            track=new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                new AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
                Math.max(min*2,8192),AudioTrack.MODE_STREAM,AudioManager.AUDIO_SESSION_ID_GENERATE);
            track.play(); mixer=new Thread(()->mix(rate),"AudioMixer"); mixer.start();
        }
        double wave(double ph,int i){
            double x;
            if(i==1)x=Math.sin(ph)+.4*Math.sin(2*ph)+.18*Math.sin(3*ph)+.08*Math.sin(5*ph);
            else if(i==2)x=Math.sin(ph)+.55*Math.sin(2*ph)+.3*Math.sin(3*ph)+.15*Math.sin(4*ph);
            else if(i==3)x=Math.sin(ph)+.72*Math.sin(2*ph)+.42*Math.sin(3*ph)+.2*Math.sin(4*ph)+.1*Math.sin(8*ph);
            else if(i==4)x=2*(ph/(2*Math.PI)-Math.floor(ph/(2*Math.PI)+.5));
            else if(i==5)x=Math.sin(ph)+.45*Math.sin(2*ph)+.2*Math.sin(3*ph)+.08*Math.sin(5*ph);
            else x=Math.sin(ph)+.58*Math.sin(2*ph)+.3*Math.sin(3*ph)+.15*Math.sin(4*ph)+.07*Math.sin(5*ph)+.03*Math.sin(7*ph);
            return x/2.2;
        }
        void mix(int rate){
            short[] out=new short[1024]; double[] ph=new double[109];
            while(alive){
                Arrays.fill(out,(short)0);
                synchronized(notes){
                    Iterator<Map.Entry<Integer,Note>> it=notes.entrySet().iterator();
                    while(it.hasNext()){
                        Note n=it.next().getValue(); double f=440*Math.pow(2,(n.m-69)/12.0),phase=ph[n.m];
                        for(int k=0;k<512;k++){
                            double age=(System.nanoTime()-n.on)/1e9, env;
                            if(n.held) env=age<.01?age/.01:.9*Math.exp(-age*.7)+.1;
                            else {double r=(System.nanoTime()-n.off)/1e9;env=.72*Math.exp(-r*5.5);}
                            double z=wave(phase,instrument)*env*n.v*volume;
                            int q=k*2; int v=out[q]+(int)(z*3000);
                            out[q]=(short)Math.max(-32767,Math.min(32767,v));
                            out[q+1]=(short)Math.max(-32767,Math.min(32767,v));
                            phase+=2*Math.PI*f/rate;if(phase>6.283)phase-=6.283;
                        }
                        ph[n.m]=phase;
                        if(!n.held && System.nanoTime()-n.off>900000000L)it.remove();
                    }
                }
                try{track.write(out,0,out.length);}catch(Exception e){}
            }
        }

        int wm(int i){
            if(i==0)return 21;if(i==1)return 23;
            int o=(i-2)/7+1;return o*12+whites[(i-2)%7];
        }
        String label(int m){return names[m%12]+(m/12-1);}
        boolean pressed(int m){synchronized(notes){Note n=notes.get(m);return n!=null&&n.held;}}
        int blackAfter(int pc){if(pc==0)return 1;if(pc==2)return 3;if(pc==5)return 6;if(pc==7)return 8;if(pc==9)return 10;return -1;}

        @Override protected void onDraw(Canvas c){
            int W=getWidth(),H=getHeight();c.drawColor(Color.rgb(9,12,18));
            p.setColor(Color.rgb(19,23,31));c.drawRect(0,0,W,84,p);
            p.setTypeface(Typeface.create("sans",Typeface.BOLD));p.setTextAlign(Paint.Align.LEFT);p.setTextSize(24);p.setColor(Color.WHITE);c.drawText("REAL PIANO PRO",20,32,p);
            p.setTypeface(Typeface.DEFAULT);p.setTextSize(11);p.setColor(Color.rgb(145,155,171));c.drawText("88 KEYS  •  MULTI-TOUCH  •  POLYPHONIC AUDIO",20,54,p);
            btn(c,190,10,300,72,new String[]{"GRAND","BRIGHT","E-PIANO","ORGAN","SYNTH","GUITAR"}[instrument],true);
            btn(c,308,10,355,72,"−",false);btn(c,361,10,408,72,"+",false);
            btn(c,416,10,470,72,"◀",false);btn(c,476,10,530,72,"▶",false);
            btn(c,538,10,625,72,sustain?"SUSTAIN ✓":"SUSTAIN",sustain);
            btn(c,633,10,712,72,rec?"● REC":"REC",rec);
            btn(c,720,10,800,72,playing?"PLAYING":"PLAY",playing);
            p.setTextAlign(Paint.Align.RIGHT);p.setTextSize(11);p.setColor(Color.rgb(145,155,171));
            c.drawText("7 OCTAVES  •  ZOOM − +",W-18,29,p);c.drawText("SHIFT WITH ◀ ▶",W-18,47,p);c.drawText("VOL "+Math.round(volume*100)+"%",W-18,65,p);

            float top=84,bottom=H-8,partial=scroll-(int)scroll,x0=-partial*keyW;
            for(int i=0;i<52;i++){float x=x0+i*keyW;if(x>W||x+keyW<0)continue;int m=wm(i);
                p.setColor(pressed(m)?Color.rgb(211,225,255):Color.rgb(249,249,246));c.drawRoundRect(x+1,top,x+keyW-1,bottom,3,3,p);
                s.setStrokeWidth(1);s.setColor(Color.rgb(110,114,120));c.drawRect(x+1,top,x+keyW-1,bottom,s);
                if(keyW>=54){p.setTextAlign(Paint.Align.CENTER);p.setTextSize(12);p.setColor(Color.rgb(70,74,80));c.drawText(label(m),x+keyW/2,bottom-14,p);}
            }
            float bh=(bottom-top)*.59f;
            for(int i=0;i<52;i++){int m=wm(i),b=blackAfter(m%12);if(b<0||m+1>108)continue;float x=x0+(i+1)*keyW-keyW*.31f,bw=keyW*.62f;
                if(x>W||x+bw<0)continue;p.setColor(pressed(m+1)?Color.rgb(91,104,145):Color.rgb(18,20,25));c.drawRoundRect(x,top,x+bw,top+bh,4,4,p);s.setColor(Color.BLACK);s.setStrokeWidth(2);c.drawRoundRect(x,top,x+bw,top+bh,4,4,s);
            }
            postInvalidateDelayed(50);
        }
        void btn(Canvas c,float l,float t,float r,float b,String text,boolean on){
            p.setColor(on?Color.rgb(91,72,170):Color.rgb(31,37,47));c.drawRoundRect(l,t,r,b,11,11,p);
            p.setTextAlign(Paint.Align.CENTER);p.setTextSize(text.length()>8?11:14);p.setTypeface(Typeface.create("sans",Typeface.BOLD));p.setColor(Color.WHITE);c.drawText(text,(l+r)/2,t+38,p);p.setTypeface(Typeface.DEFAULT);
        }

        int hit(float x,float y){
            float top=84,bottom=getHeight()-8;if(y<top||y>bottom)return -1;int first=(int)scroll;float x0=-(scroll-first)*keyW;
            float bh=(bottom-top)*.59f;
            for(int i=0;i<52;i++){int m=wm(i),b=blackAfter(m%12);if(b<0||m+1>108)continue;float bx=x0+(i+1)*keyW-keyW*.31f,bw=keyW*.62f;if(x>=bx&&x<=bx+bw&&y<=top+bh)return m+1;}
            int i=(int)Math.floor((x-x0)/keyW);return i>=0&&i<52?wm(i):-1;
        }
        void down(int m,float v){if(m<21||m>108)return;synchronized(notes){Note n=notes.get(m);if(n!=null){n.held=false;n.off=System.nanoTime();}notes.put(m,new Note(m,v));}if(rec)take.add("D,"+(System.currentTimeMillis()-recAt)+","+m+","+v);invalidate();}
        void up(int m){synchronized(notes){Note n=notes.get(m);if(n!=null){n.held=false;n.off=System.nanoTime();}}if(rec)take.add("U,"+(System.currentTimeMillis()-recAt)+","+m);invalidate();}
        void release(){synchronized(notes){for(Note n:notes.values()){n.held=false;n.off=System.nanoTime();}}fingers.clear();}
        @Override public boolean onTouchEvent(MotionEvent e){
            int a=e.getActionMasked(),idx=e.getActionIndex(),id=e.getPointerId(idx);
            if(a==MotionEvent.ACTION_DOWN||a==MotionEvent.ACTION_POINTER_DOWN){
                float x=e.getX(idx),y=e.getY(idx);if(y<84){top(x);return true;}int m=hit(x,y);if(m>=0){fingers.put(id,m);down(m,.9f);}return true;
            }
            if(a==MotionEvent.ACTION_MOVE){for(int i=0;i<e.getPointerCount();i++){int pid=e.getPointerId(i);Integer old=fingers.get(pid);if(old==null)continue;int m=hit(e.getX(i),e.getY(i));if(m>=0&&!mEquals(old,m)){up(old);fingers.put(pid,m);down(m,.82f);}}return true;}
            if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_POINTER_UP||a==MotionEvent.ACTION_CANCEL){Integer m=fingers.remove(id);if(m!=null&&!sustain)up(m);return true;}return true;
        }
        boolean mEquals(int a,int b){return a==b;}
        void top(float x){
            if(x>=190&&x<300)instrument=(instrument+1)%6;
            else if(x>=308&&x<355)keyW=Math.max(48,keyW-8);
            else if(x>=361&&x<408)keyW=Math.min(112,keyW+8);
            else if(x>=416&&x<470)scroll=Math.max(0,scroll-7);
            else if(x>=476&&x<530)scroll=Math.min(36,scroll+7);
            else if(x>=538&&x<625){sustain=!sustain;if(!sustain)release();}
            else if(x>=633&&x<712){rec=!rec;if(rec){take.clear();recAt=System.currentTimeMillis();}}
            else if(x>=720&&x<800&&!rec)play();
            invalidate();
        }
        void play(){
            if(take.isEmpty()||playing)return;playing=true;invalidate();
            new Thread(()->{try{long last=0;for(String z:take){String[]q=z.split(",");long t=Long.parseLong(q[1]);Thread.sleep(Math.max(0,t-last));last=t;int m=Integer.parseInt(q[2]);if(q[0].equals("D"))down(m,Float.parseFloat(q[3]));else up(m);}Thread.sleep(500);}catch(Exception e){}playing=false;invalidate();},"Playback").start();
        }
        void close(){alive=false;release();if(mixer!=null)try{mixer.join(300);}catch(Exception e){}if(track!=null)try{track.stop();track.release();}catch(Exception e){}}
    }
}