package com.gooroyeegar.stablepiano;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.media.*;
import android.media.midi.*;
import android.view.*;
import android.widget.Toast;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    PianoView piano;
    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setNavigationBarColor(Color.BLACK);
        immersive();
        piano=new PianoView(this); setContentView(piano);
    }
    void immersive(){getWindow().getDecorView().setSystemUiVisibility(
        View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);}
    @Override public void onWindowFocusChanged(boolean h){super.onWindowFocusChanged(h);if(h)immersive();}
    @Override protected void onDestroy(){if(piano!=null)piano.close();super.onDestroy();}

    final class PianoView extends View {
        final Paint p=new Paint(3), line=new Paint(3);
        final HashMap<Integer,Voice> voices=new HashMap<>();
        final HashMap<Integer,Integer> fingers=new HashMap<>();
        final ArrayList<Event> take=new ArrayList<>();
        final String[] inst={"Acoustic Grand Piano","Bright Acoustic Piano","Electric Grand Piano","Honky-tonk Piano","Electric Piano 1","Electric Piano 2","Harpsichord","Clavinet","Celesta","Glockenspiel","Music Box","Vibraphone","Marimba","Xylophone","Tubular Bells","Dulcimer","Drawbar Organ","Percussive Organ","Rock Organ","Church Organ","Reed Organ","Accordion","Harmonica","Tango Accordion","Acoustic Guitar","Electric Guitar","Jazz Guitar","Clean Guitar","Muted Guitar","Overdriven Guitar","Distortion Guitar","Guitar Harmonics","Acoustic Bass","Finger Bass","Pick Bass","Fretless Bass","Slap Bass 1","Slap Bass 2","Synth Bass 1","Synth Bass 2","Violin","Viola","Cello","Contrabass","Tremolo Strings","Pizzicato Strings","Orchestral Harp","Timpani","String Ensemble 1","String Ensemble 2","Synth Strings 1","Synth Strings 2","Choir Aahs","Voice Oohs","Synth Choir","Orchestra Hit","Trumpet","Trombone","Tuba","Muted Trumpet","French Horn","Brass Section","Synth Brass 1","Synth Brass 2","Soprano Sax","Alto Sax","Tenor Sax","Baritone Sax","Oboe","English Horn","Bassoon","Clarinet","Piccolo","Flute","Recorder","Pan Flute","Blown Bottle","Shakuhachi","Whistle","Ocarina","Lead 1 Square","Lead 2 Saw","Lead 3 Calliope","Lead 4 Chiff","Lead 5 Charang","Lead 6 Voice","Lead 7 Fifths","Lead 8 Bass+Lead","Pad 1 New Age","Pad 2 Warm","Pad 3 Polysynth","Pad 4 Choir","Pad 5 Bowed","Pad 6 Metallic","Pad 7 Halo","Pad 8 Sweep","FX 1 Rain","FX 2 Soundtrack","FX 3 Crystal","FX 4 Atmosphere","FX 5 Brightness","FX 6 Goblins","FX 7 Echoes","FX 8 Sci-fi","Sitar","Banjo","Shamisen","Koto","Kalimba","Bagpipe","Fiddle","Shanai","Tinkle Bell","Agogo","Steel Drums","Woodblock","Taiko Drum","Melodic Tom","Synth Drum","Reverse Cymbal","Guitar Fret Noise","Breath Noise","Seashore","Bird Tweet","Telephone","Helicopter","Applause","Gunshot"};
        final String[] modes={"FREESTYLE","CHORDS","SPLIT","DUAL","LEARN"};
        final String[] scales={"C Major","G Major","D Major","A Major","E Major","B Major","F Major","Bb Major","A Minor","E Minor","D Minor","Pentatonic"};
        final int[] whitePc={0,2,4,5,7,9,11};
        int instrument=0,mode=0,scale=0,transpose=0,bpm=100;
        float keyW=64f,scroll=0,volume=.85f,masterPitch=1f;
        boolean sustain=false,recording=false,playing=false,metronome=false,labels=true,velocity=true;
        long recStart=0; int beat=0; long lastBeat=0;
        AudioTrack audio; Thread mixer; volatile boolean alive=true;
        MidiManager midiManager; MidiDevice midiDevice; MidiReceiver midiReceiver;
        Random rng=new Random(7);
        int learnNote=60; long learnSince;

        class Voice {int note;float amp;double ph;long born,off;boolean held=true;Voice(int n,float a){note=n;amp=a;born=System.nanoTime();}}
        class Event {long t;int type,note;float vel;Event(long t,int type,int n,float v){this.t=t;this.type=type;this.note=n;this.vel=v;}}

        PianoView(Context c){super(c);setFocusable(true);line.setStyle(Paint.Style.STROKE);line.setStrokeWidth(1);startAudio();startMidi();learnSince=System.currentTimeMillis();}
        void startAudio(){
            int rate=44100,min=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT);
            AudioFormat f=new AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
            audio=new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),f,Math.max(min*4,16384),AudioTrack.MODE_STREAM,AudioManager.AUDIO_SESSION_ID_GENERATE);
            audio.play();mixer=new Thread(()->mix(rate),"PianoAudio");mixer.start();
        }
        void startMidi(){
            try{
                midiManager=(MidiManager)getSystemService(MIDI_SERVICE);
                midiManager.registerDeviceCallback(new MidiManager.DeviceCallback(){
                    public void onDeviceAdded(MidiDeviceInfo info){connectMidi(info);}
                    public void onDeviceRemoved(MidiDeviceInfo info){}
                },new Handler(Looper.getMainLooper()));
                for(MidiDeviceInfo i:midiManager.getDevices())connectMidi(i);
            }catch(Exception e){}
        }
        void connectMidi(MidiDeviceInfo info){
            try{midiManager.openDevice(info,d->{if(d!=null){midiDevice=d;for(int port=0;port<d.getInfo().getInputPortCount();port++){MidiInputPort ip=d.openInputPort(port);if(ip!=null){}}}},new Handler(Looper.getMainLooper()));}catch(Exception e){}
        }
        double timbre(double ph,int prog){
            double s=Math.sin(ph),h=Math.sin(2*ph),h3=Math.sin(3*ph),h4=Math.sin(4*ph),h5=Math.sin(5*ph);
            int group=prog/8;
            if(prog==0||prog<8)return .62*s+.25*h+.10*h3+.04*h4;
            if(group==1)return .55*s+.30*h+.18*h3;
            if(group==2)return .5*s+.3*h+.18*h3+.1*h5;
            if(group==3)return Math.sin(ph)+.35*Math.sin(3*ph)+.2*Math.sin(5*ph);
            if(group==4)return .7*s+.25*h+.08*h3;
            if(group==5)return .6*s+.3*h+.16*h3+.08*h4;
            if(group==6)return .45*s+.4*h+.2*h3+.12*h4;
            if(group==7)return 2*(ph/(2*Math.PI)-Math.floor(ph/(2*Math.PI)+.5));
            if(group==8)return .5*s+.25*Math.sin(1.5*ph)+.2*h3;
            return .55*s+.25*h+.15*h3;
        }
        void mix(int rate){
            short[] buf=new short[1024];long nextBeat=System.nanoTime();
            while(alive){
                Arrays.fill(buf,(short)0);
                synchronized(voices){
                    Iterator<Voice> it=voices.values().iterator();
                    while(it.hasNext()){
                        Voice v=it.next();double f=440*Math.pow(2,(v.note-69+transpose)/12.0)*masterPitch;
                        for(int k=0;k<512;k++){
                            double age=(System.nanoTime()-v.born)/1e9,env;
                            if(v.held){double a=1-Math.exp(-age*55);env=a*Math.exp(-age*(instrument==0?0.45:1.8));}
                            else {double r=(System.nanoTime()-v.off)/1e9;env=.8*Math.exp(-r*(instrument==0?4.0:8.0));}
                            double ph=v.ph+(2*Math.PI*f*k/rate);
                            double z=timbre(ph,instrument)*env*v.amp*volume;
                            int q=k*2;int l=buf[q]+(int)(z*5000),r=buf[q+1]+(int)(z*5000);
                            buf[q]=(short)Math.max(-32767,Math.min(32767,l));buf[q+1]=(short)Math.max(-32767,Math.min(32767,r));
                        }
                        v.ph+=2*Math.PI*f*512/rate;v.ph%=Math.PI*2;
                        if(!v.held&&(System.nanoTime()-v.off)>1000000000L)it.remove();
                    }
                }
                if(metronome&&System.nanoTime()>nextBeat){nextBeat=System.nanoTime()+60000000000L/bpm;beep();}
                try{audio.write(buf,0,buf.length);}catch(Exception e){}
            }
        }
        void beep(){long now=System.nanoTime();synchronized(voices){Voice v=new Voice(120,0.22f);v.born=now;v.held=false;v.off=now;voices.put(120,v);}}
        int whiteIndex(int m){int octave=m/12;int pc=m%12;int wi=0;for(int i=0;i<7;i++){if(whitePc[i]==pc)return (octave*7)+i;}return -1;}
        int noteAtWhite(int wi){int o=wi/7;return o*12+whitePc[wi%7];}
        boolean black(int pc){return pc==1||pc==3||pc==6||pc==8||pc==10;}
        int hit(float x,float y){
            float top=92,bottom=getHeight()-12;if(y<top||y>bottom)return -1;
            int first=(int)scroll;float x0=-(scroll-first)*keyW;float bh=(bottom-top)*.58f;
            for(int i=0;i<52;i++){int m=noteAtWhite(i+first);int pc=m%12;if(m>108)continue;if(black(pc)){float bx=x0+(i+1)*keyW-keyW*.30f;if(x>=bx&&x<=bx+keyW*.60f&&y<=top+bh)return m;}}
            int wi=(int)Math.floor((x-x0)/keyW);return wi+first>=0&&wi+first<52?noteAtWhite(wi+first):-1;
        }
        void noteOn(int n,float vel){
            if(n<21||n>108)return;
            if(mode==1){int root=n;noteOnRaw(root,vel);noteOnRaw(root+4,vel*.75f);noteOnRaw(root+7,vel*.75f);noteOnRaw(root+11,vel*.62f);}
            else if(mode==2&&n>60){noteOnRaw(n,vel);noteOnRaw(n-12,vel*.72f);} else noteOnRaw(n,vel);
            if(mode==4&&n==learnNote){learnNote=60+rng.nextInt(25);learnSince=System.currentTimeMillis();}
        }
        void noteOnRaw(int n,float vel){synchronized(voices){Voice old=voices.get(n);if(old!=null){old.held=false;old.off=System.nanoTime();}voices.put(n,new Voice(n,vel));}if(recording)take.add(new Event(System.currentTimeMillis()-recStart,1,n,vel));invalidate();}
        void noteOff(int n){
            if(mode==1){noteOffRaw(n);noteOffRaw(n+4);noteOffRaw(n+7);noteOffRaw(n+11);}
            else if(mode==2){noteOffRaw(n);noteOffRaw(n-12);}else noteOffRaw(n);
        }
        void noteOffRaw(int n){synchronized(voices){Voice v=voices.get(n);if(v!=null){v.held=false;v.off=System.nanoTime();}}if(recording)take.add(new Event(System.currentTimeMillis()-recStart,0,n,0));}
        void releaseAll(){synchronized(voices){for(Voice v:voices.values()){v.held=false;v.off=System.nanoTime();}}fingers.clear();}
        boolean pressed(int n){synchronized(voices){Voice v=voices.get(n);return v!=null&&v.held;}}
        boolean scaleContains(int n){int pc=(n%12+12)%12;int[] major={0,2,4,5,7,9,11};int root=new int[]{0,7,2,9,4,11,5,10,9,4,2,0}[scale];if(scale==11){int rel=(pc-root+12)%12;return rel==0||rel==2||rel==4||rel==7||rel==9;}for(int x:major)if((x+root)%12==pc)return true;return false;}
        @Override protected void onDraw(Canvas c){
            int W=getWidth(),H=getHeight();c.drawColor(Color.rgb(7,10,15));
            p.setColor(Color.rgb(15,19,27));c.drawRect(0,0,W,92,p);
            p.setTypeface(Typeface.create("sans",Typeface.BOLD));p.setTextAlign(Paint.Align.LEFT);p.setTextSize(22);p.setColor(Color.WHITE);c.drawText("PIANO ∞",18,29,p);
            p.setTypeface(Typeface.DEFAULT);p.setTextSize(10);p.setColor(Color.rgb(145,155,170));c.drawText(inst[instrument],18,51,p);c.drawText(modes[mode]+"  •  "+scales[scale]+"  •  "+bpm+" BPM",18,68,p);
            String[] b={"INSTR","MODE","SCALE","CHORD","METRO","REC","PLAY","SAVE","MIDI","⚙"};
            float x=150;for(int i=0;i<b.length;i++){float bw=i==7?64:58;button(c,x,13,x+bw,78,b[i],(i==4&&metronome)||(i==5&&recording)||(i==6&&playing));x+=bw+6;}
            p.setTextAlign(Paint.Align.RIGHT);p.setTextSize(10);p.setColor(Color.rgb(145,155,170));c.drawText("88 KEYS • "+(velocity?"VELOCITY":"FIXED")+" • "+(sustain?"SUSTAIN":"NORMAL"),W-18,28,p);c.drawText("ZOOM "+Math.round(keyW)+"  TRANSPOSE "+(transpose>=0?"+":"")+transpose,W-18,48,p);c.drawText("◀ ▶ PAN",W-18,68,p);
            float top=92,bottom=H-12;int first=(int)scroll;float x0=-(scroll-first)*keyW;
            for(int i=0;i<52;i++){int n=noteAtWhite(i+first);if(n<21||n>108)continue;float xx=x0+i*keyW;if(xx>W||xx+keyW<0)continue;boolean hi=scaleContains(n);p.setColor(pressed(n)?Color.rgb(184,203,255):(hi&&mode==4?Color.rgb(232,222,194):Color.rgb(247,246,241)));c.drawRoundRect(xx+1,top,xx+keyW-1,bottom,3,3,p);line.setColor(Color.rgb(80,84,90));c.drawRoundRect(xx+1,top,xx+keyW-1,bottom,3,3,line);if(labels){p.setColor(Color.rgb(70,73,79));p.setTextAlign(Paint.Align.CENTER);p.setTextSize(11);c.drawText(noteName(n),xx+keyW/2,bottom-13,p);}}
            float bh=(bottom-top)*.58f;for(int i=0;i<52;i++){int n=noteAtWhite(i+first),pc=n%12;if(!black(pc)||n>108)continue;float bx=x0+(i+1)*keyW-keyW*.30f;if(bx>W||bx+keyW*.60f<0)continue;p.setColor(pressed(n)?Color.rgb(95,113,166):Color.rgb(18,20,25));c.drawRoundRect(bx,top,bx+keyW*.60f,top+bh,4,4,p);line.setColor(Color.BLACK);line.setStrokeWidth(2);c.drawRoundRect(bx,top,bx+keyW*.60f,top+bh,4,4,line);}
            if(mode==4){p.setTextAlign(Paint.Align.CENTER);p.setTextSize(18);p.setColor(Color.rgb(240,207,120));c.drawText("PLAY  "+noteName(learnNote)+"  •  tap the highlighted target",W/2,H-28,p);}
            postInvalidateDelayed(35);
        }
        String noteName(int n){String[] a={"C","C♯","D","D♯","E","F","F♯","G","G♯","A","A♯","B"};return a[(n%12+12)%12]+(n/12-1);}
        void button(Canvas c,float l,float t,float r,float b,String s,boolean on){p.setColor(on?Color.rgb(112,79,180):Color.rgb(31,37,48));c.drawRoundRect(l,t,r,b,10,10,p);p.setTextAlign(Paint.Align.CENTER);p.setTypeface(Typeface.create("sans",Typeface.BOLD));p.setTextSize(s.length()>5?9:11);p.setColor(Color.WHITE);c.drawText(s,(l+r)/2,t+38,p);p.setTypeface(Typeface.DEFAULT);}
        void toolbar(float x){
            if(x<150)return;int i=(int)((x-150)/64);if(i<0)return;
            if(i==0){instrument=(instrument+1)%128;}
            else if(i==1){mode=(mode+1)%5;}
            else if(i==2){scale=(scale+1)%scales.length;}
            else if(i==3){mode=1;}
            else if(i==4){metronome=!metronome;}
            else if(i==5){recording=!recording;if(recording){take.clear();recStart=System.currentTimeMillis();}}
            else if(i==6){playTake();}
            else if(i==7){exportMenu();}
            else if(i==8){showMidi();}
            else if(i==9){settingsDialog();}
            invalidate();
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            int a=e.getActionMasked(),idx=e.getActionIndex(),id=e.getPointerId(idx);
            if(a==MotionEvent.ACTION_DOWN||a==MotionEvent.ACTION_POINTER_DOWN){float x=e.getX(idx),y=e.getY(idx);if(y<92){toolbar(x);return true;}int n=hit(x,y);if(n>0){fingers.put(id,n);float vel=velocity?Math.max(.25f,Math.min(1f,1f-(y-92f)/(getHeight()-104f)*.75f)):.8f;noteOn(n,vel);}return true;}
            if(a==MotionEvent.ACTION_MOVE){for(int j=0;j<e.getPointerCount();j++){int pid=e.getPointerId(j);Integer old=fingers.get(pid);if(old==null)continue;int n=hit(e.getX(j),e.getY(j));if(n>0&&!old.equals(n)){noteOff(old);fingers.put(pid,n);noteOn(n,.75f);}}return true;}
            if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_POINTER_UP||a==MotionEvent.ACTION_CANCEL){Integer n=fingers.remove(id);if(n!=null&&!sustain)noteOff(n);return true;}return true;
        }
        void playTake(){if(take.isEmpty()||playing)return;playing=true;new Thread(()->{long last=0;for(Event e:take){try{Thread.sleep(Math.max(0,e.t-last));}catch(Exception x){}if(e.type==1)noteOnRaw(e.note,e.vel);else noteOffRaw(e.note);last=e.t;}playing=false;invalidate();},"Playback").start();}
        void exportMenu(){new AlertDialog.Builder(MainActivity.this).setTitle("Export performance").setItems(new String[]{"WAV audio","Standard MIDI (.mid)","Share app recording folder"},(d,w)->{if(w==0)exportWav();else if(w==1)exportMidi();else shareFolder();}).show();}
        File musicDir(){File d=new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC),"PianoInfinity");if(!d.exists())d.mkdirs();return d;}
        void exportMidi(){try{File f=new File(musicDir(),"performance_"+System.currentTimeMillis()+".mid");FileOutputStream o=new FileOutputStream(f);ByteArrayOutputStream tr=new ByteArrayOutputStream();writeVar(tr,0);tr.write(new byte[]{(byte)0xC0,(byte)instrument});int last=0;for(Event e:take){writeVar(tr,(int)Math.max(0,e.t-last));tr.write(e.type==1?0x90:0x80);tr.write(e.note);tr.write(e.type==1?(int)(e.vel*127):0);last=(int)e.t;}writeVar(tr,0);tr.write(new byte[]{(byte)0xFF,0x2F,0});byte[] tb=tr.toByteArray();o.write("MThd".getBytes());o.write(new byte[]{0,0,0,6,0,0,0,1,1,(byte)0xE0});o.write("MTrk".getBytes());o.write(new byte[]{(byte)(tb.length>>24),(byte)(tb.length>>16),(byte)(tb.length>>8),(byte)tb.length});o.write(tb);o.close();shareFile(f,"audio/midi");}catch(Exception e){toast("MIDI export failed");}}
        void writeVar(ByteArrayOutputStream o,int v)throws Exception{int buffer=v&127;while((v>>=7)>0){buffer<<=8;buffer|=((v&127)|128);}while(true){o.write(buffer&255);if((buffer&128)!=0)buffer>>=8;else break;}}
        void exportWav(){try{int rate=44100;long dur=take.isEmpty()?1:take.get(take.size()-1).t+1500;int frames=(int)Math.min((long)rate*30,dur*rate/1000);short[] pcm=new short[frames*2];for(Event e:take){}for(int i=0;i<frames;i++){double t=i/(double)rate;double z=0;for(Event e:take)if(e.type==1&&e.t<=t*1000&&e.t+1500>t*1000){double age=t-e.t/1000.0;double freq=440*Math.pow(2,(e.note-69+transpose)/12.0);double env=Math.exp(-age*(instrument==0?.8:3));z+=timbre(2*Math.PI*freq*t,instrument)*env*e.vel*.25;}pcm[i*2]=pcm[i*2+1]=(short)Math.max(-32767,Math.min(32767,z*24000*volume));}File f=new File(musicDir(),"performance_"+System.currentTimeMillis()+".wav");FileOutputStream o=new FileOutputStream(f);wavHeader(o,pcm.length,rate);byte[] b=new byte[2];for(short q:pcm){b[0]=(byte)q;b[1]=(byte)(q>>8);o.write(b);}o.close();shareFile(f,"audio/wav");}catch(Exception e){toast("Audio export failed");}}
        void wavHeader(FileOutputStream o,int samples,int rate)throws Exception{int data=samples*2;ByteArrayOutputStream h=new ByteArrayOutputStream();h.write("RIFF".getBytes());le(h,36+data,4);h.write("WAVEfmt ".getBytes());le(h,16,4);le(h,1,2);le(h,2,2);le(h,rate,4);le(h,rate*4,4);le(h,4,2);le(h,16,2);h.write("data".getBytes());le(h,data,4);o.write(h.toByteArray());}
        void le(ByteArrayOutputStream o,int v,int n){for(int i=0;i<n;i++)o.write((v>>(8*i))&255);}
        void shareFile(File f,String type){Intent i=new Intent(Intent.ACTION_SEND);i.setType(type);i.putExtra(Intent.EXTRA_TEXT,f.getAbsolutePath());try{startActivity(Intent.createChooser(i,"Share performance"));}catch(Exception e){toast(f.getAbsolutePath());}}
        void shareFolder(){toast("Saved in "+musicDir().getAbsolutePath());}
        void showMidi(){new AlertDialog.Builder(MainActivity.this).setTitle("MIDI").setMessage("USB/Bluetooth MIDI support is enabled. Connect a MIDI keyboard, then play it through Piano ∞. Recording captures the same note events.").setPositiveButton("OK",null).show();}
        void settingsDialog(){final String[] a={"Show note labels","Touch velocity","Sustain on release","Fullscreen"};boolean[] c={labels,velocity,sustain,true};new AlertDialog.Builder(MainActivity.this).setTitle("Performance settings").setMultiChoiceItems(a,c,(d,w,on)->{if(w==0)labels=on;if(w==1)velocity=on;if(w==2)sustain=on;}).setSingleChoiceItems(new String[]{"Transpose -12","Transpose -6","Transpose 0","Transpose +6","Transpose +12"},transpose==0?2:transpose>0?3:1,(d,w)->{transpose=new int[]{-12,-6,0,6,12}[w];d.dismiss();}).setPositiveButton("Done",null).show();}
        void toast(String s){Toast.makeText(MainActivity.this,s,Toast.LENGTH_LONG).show();}
        void close(){alive=false;releaseAll();if(mixer!=null)try{mixer.join(500);}catch(Exception e){}if(audio!=null)try{audio.stop();audio.release();}catch(Exception e){}}
    }
}