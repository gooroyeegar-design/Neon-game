package com.gooroyeegar.stablepiano;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.media.*;
import android.os.Handler;
import android.media.midi.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import android.content.res.AssetManager;
import android.content.res.AssetFileDescriptor;

// Final performance/UI build verification
public class MainActivity extends Activity {
    PianoView piano;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setNavigationBarColor(Color.BLACK);
        immersive();
        piano = new PianoView(this);
        setContentView(piano);
    }

    void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    @Override public void onWindowFocusChanged(boolean h) {
        super.onWindowFocusChanged(h);
        if (h) immersive();
    }

    @Override protected void onDestroy() {
        if (piano != null) piano.close();
        super.onDestroy();
    }

    final class PianoView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        final HashMap<Integer,Voice> voices = new HashMap<>();
        final HashMap<Integer,Integer> fingers = new HashMap<>();
        final ArrayList<Event> take = new ArrayList<>();

        final int[] SAMPLE_NOTES = {21,24,27,30,33,36,39,42,45,48,51,54,57,60,63,66,69,72,75,78,81,84,87,90,93,96,99,102,105,108};
        final int MIN_NOTE=21, MAX_NOTE=108;
        final int MIN_WHITE=whiteIndex(MIN_NOTE), MAX_WHITE=whiteIndex(MAX_NOTE);
        final int[] visibleWhites={15,18,21,24,28,35};



        final String[] instruments = {
            "Grand Piano","Bright Piano","Electric Piano","Honky-Tonk","Rhodes","Harpsichord",
            "Clavinet","Celesta","Glockenspiel","Music Box","Vibraphone","Marimba","Church Organ",
            "Drawbar Organ","Accordion","Acoustic Guitar","Electric Guitar","Violin","Cello","Strings",
            "Choir","Trumpet","Trombone","Saxophone","Flute","Clarinet","Synth Lead","Warm Pad"
        };
        final String[] modes = {"PIANO","CHORDS","SPLIT","DUAL","LEARN"};
        final String[] scales = {"OFF","C MAJOR","G MAJOR","D MAJOR","A MINOR","PENTATONIC"};

        int instrument = 0, mode = 0, scale = 0, transpose = 0, bpm = 100;
        float scroll = 24f;
        int zoomStep = 2;
        float volume = .88f;
        boolean sustain = false, recording = false, playing = false, metronome = false;
        boolean labels = true, velocity = true;
        long recStart;
        int learnNote = 60;
        long nextClickNs = 0;
        long clickUntilNs = 0;
        double clickPhase = 0;
        float panStartX, panStartScroll;
        boolean panning = false;

        final int[] soundIds = new int[109];
        final int[] sampleForNote = new int[109];
        final HashMap<Integer,Integer> streams = new HashMap<>();
        final HashMap<Integer,Runnable> autoStops = new HashMap<>();
        final Handler audioHandler = new Handler(Looper.getMainLooper());
        SoundPool soundPool;
        int loadedSamples = 0;
        boolean soundReady = false;

        PianoView(Context c) {
            super(c);
            setFocusable(true);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(1f);
            shadow.setShadowLayer(7f,0,3f,0x66000000);
            setLayerType(View.LAYER_TYPE_SOFTWARE,null);
            loadSamples();
            startAudio();
            startMidi();
        }

        void loadSamples() {
            try {
                AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
                soundPool = new SoundPool.Builder()
                    .setAudioAttributes(attrs)
                    .setMaxStreams(32)
                    .build();
                soundPool.setOnLoadCompleteListener((pool,id,status) -> {
                    if(status==0) {
                        loadedSamples++;
                        if(loadedSamples>=SAMPLE_NOTES.length) { soundReady=true; postInvalidate(); }
                    }
                });
                AssetManager am=getAssets();
                for(int midi:SAMPLE_NOTES) {
                    AssetFileDescriptor afd=am.openFd("piano_"+midi+".wav");
                    soundIds[midi]=soundPool.load(afd,1);
                    afd.close();
                }
                for(int n=MIN_NOTE;n<=MAX_NOTE;n++) {
                    int best=SAMPLE_NOTES[0], dist=999;
                    for(int m:SAMPLE_NOTES) {
                        int d=Math.abs(n-m);
                        if(d<dist){dist=d;best=m;}
                    }
                    sampleForNote[n]=best;
                }
            } catch(Exception e) { soundReady=false; }
        }

        void startAudio() {}

        void playSample(int note,float vel) {
            if(!soundReady || soundPool==null || note<MIN_NOTE || note>MAX_NOTE) return;
            int anchor=sampleForNote[note], sid=soundIds[anchor];
            if(sid==0) return;
            float rate=(float)Math.pow(2.0,(note-anchor)/12.0);
            rate=Math.max(.5f,Math.min(2f,rate));
            Integer old=streams.get(note);
            if(old!=null) soundPool.stop(old);
            int stream=soundPool.play(sid,Math.min(1f,vel)*volume,Math.min(1f,vel)*volume,1,0,rate);
            if(stream!=0) streams.put(note,stream);
            Runnable stopper=() -> {
                Integer st=streams.remove(note);
                if(st!=null && soundPool!=null) soundPool.stop(st);
            };
            Runnable previous=autoStops.put(note,stopper);
            if(previous!=null) audioHandler.removeCallbacks(previous);
            audioHandler.postDelayed(stopper,1850);
        }

        void stopSample(int note) {
            Runnable r=autoStops.remove(note);
            if(r!=null) audioHandler.removeCallbacks(r);
            Integer st=streams.remove(note);
            if(st!=null && soundPool!=null) soundPool.stop(st);
        }

        class Voice {
            int note;
            float amp;
            final long bornNs=System.nanoTime();
            boolean held=true;
            long releaseNs=0;
            Voice(int n,float a){note=n;amp=a;}
        }

        class Event {
            long t;
            int type,note;
            float vel;
            Event(long tt,int ty,int n,float v){t=tt;type=ty;note=n;vel=v;}
        }

        int whiteIndex(int midi) {
            int[] pcs={0,2,4,5,7,9,11};
            int octave=midi/12, pc=midi%12;
            for(int i=0;i<7;i++) if(pcs[i]==pc) return octave*7+i;
            return -1;
        }
        int noteAtWhite(int wi) {
            int[] pcs={0,2,4,5,7,9,11};
            return (wi/7)*12+pcs[wi%7];
        }
        int visibleCount(){return visibleWhites[Math.max(0,Math.min(visibleWhites.length-1,zoomStep))];}
        float keyWidth(){return getWidth()/(float)visibleCount();}
        float keyboardTop(){return 82f;}
        float keyboardBottom(){return getHeight()-8f;}
        void clampScroll(){
            float max=MAX_WHITE-MIN_WHITE+1-visibleCount();
            if(max<0) max=0;
            scroll=Math.max(MIN_WHITE,Math.min(MIN_WHITE+max,scroll));
        }

        void startMidi() {
            try {
                midiManager = (MidiManager)getSystemService(MIDI_SERVICE);
                midiReceiver = new MidiReceiver() {
                    @Override public void onSend(byte[] data, int offset, int count, long timestamp) {
                        int end = Math.min(data.length, offset+count);
                        for (int i=offset;i<end;i++) {
                            int status=data[i]&255;
                            if ((status&0xF0)==0x90 && i+2<end) {
                                int n=data[++i]&127, v=data[++i]&127;
                                if (v==0) noteOff(n); else noteOn(n,v/127f);
                            } else if ((status&0xF0)==0x80 && i+2<end) {
                                int n=data[++i]&127; i++;
                                noteOff(n);
                            } else if ((status&0xF0)==0xB0 && i+2<end) {
                                int cc=data[++i]&127, val=data[++i]&127;
                                if (cc==64) { sustain=val>=64; if(!sustain) releaseAll(); }
                            } else if ((status&0xF0)==0xC0 && i+1<end) {
                                instrument=(data[++i]&127)%instruments.length;
                            }
                        }
                    }
                };
                midiManager.registerDeviceCallback(new MidiManager.DeviceCallback() {
                    public void onDeviceAdded(MidiDeviceInfo info) { connectMidi(info); }
                    public void onDeviceRemoved(MidiDeviceInfo info) {}
                }, new Handler(Looper.getMainLooper()));
                for (MidiDeviceInfo info : midiManager.getDevices()) connectMidi(info);
            } catch (Exception ignored) {}
        }

        void connectMidi(MidiDeviceInfo info) {
            try {
                midiManager.openDevice(info, device -> {
                    if (device == null) return;
                    midiDevice=device;
                    for (int port=0;port<device.getInfo().getOutputPortCount();port++) {
                        MidiOutputPort op=device.openOutputPort(port);
                        if (op!=null) op.connect(midiReceiver);
                    }
                }, new Handler(Looper.getMainLooper()));
            } catch (Exception ignored) {}
        }

        void noteOn(int n, float vel) {
            n += transpose;
            if (n<MIN_NOTE || n>MAX_NOTE) return;
            if (mode==1) {
                noteOnRaw(n,vel); noteOnRaw(n+4,vel*.78f); noteOnRaw(n+7,vel*.72f);
            } else if (mode==2 && n>60) {
                noteOnRaw(n,vel); noteOnRaw(n-12,vel*.68f);
            } else {
                noteOnRaw(n,vel);
            }
            if (mode==4 && n==learnNote) learnNote = 60 + (int)(Math.random()*25);
        }

        void noteOnRaw(int n,float vel) {
            if (n<MIN_NOTE || n>MAX_NOTE) return;
            synchronized (voices) {
                Voice old=voices.get(n);
                if (old!=null) { old.held=false; old.releaseNs=System.nanoTime(); }
                voices.put(n,new Voice(n,Math.max(.08f,Math.min(1f,vel))));
            }
            playSample(n,vel);
            if (recording) take.add(new Event(System.currentTimeMillis()-recStart,1,n,vel));
            invalidate();
        }

        void noteOff(int n) {
            n += transpose;
            if (mode==1) {
                noteOffRaw(n); noteOffRaw(n+4); noteOffRaw(n+7);
            } else if (mode==2 && n>60) {
                noteOffRaw(n); noteOffRaw(n-12);
            } else noteOffRaw(n);
        }

        void noteOffRaw(int n) {
            synchronized (voices) {
                Voice v=voices.get(n);
                if (v!=null && v.held && !sustain) { v.held=false; v.releaseNs=System.nanoTime(); stopSample(n); }
            }
            if (recording) take.add(new Event(System.currentTimeMillis()-recStart,0,n,0));
        }

        void releaseAll() {
            synchronized (voices) {
                long now=System.nanoTime();
                for (Voice v:voices.values()) { v.held=false; v.releaseNs=now; stopSample(v.note); }
            }
            fingers.clear();
        }

        boolean pressed(int n) {
            synchronized (voices) { Voice v=voices.get(n); return v!=null && v.held; }
        }

        boolean isBlack(int note) {
            int pc=(note%12+12)%12;
            return pc==1||pc==3||pc==6||pc==8||pc==10;
        }

        int hit(float x,float y) {
            float top=keyboardTop(), bottom=keyboardBottom();
            if (y<top || y>bottom) return -1;
            float kw=keyWidth();
            int first=(int)Math.floor(scroll);
            float x0=-(scroll-first)*kw;
            float blackH=(bottom-top)*.58f;
            for (int i=0;i<=visibleCount()+1;i++) {
                int white=first+i;
                int base=noteAtWhite(white);
                int black=base+1;
                if (isBlack(black) && black>=MIN_NOTE && black<=MAX_NOTE) {
                    float bx=x0+(i+1)*kw;
                    float bw=kw*.62f;
                    if (x>=bx-bw/2 && x<=bx+bw/2 && y<=top+blackH) return black;
                }
            }
            int wi=first+(int)Math.floor((x-x0)/kw);
            if (wi<MIN_WHITE || wi>MAX_WHITE) return -1;
            int n=noteAtWhite(wi);
            return (n>=MIN_NOTE && n<=MAX_NOTE) ? n : -1;
        }

        void toolbarAction(float x) {
            float left=10, w=Math.max(62f,Math.min(82f,(getWidth()-24f)/9f)), gap=3;
            if (x<left) return;
            int i=(int)((x-left)/(w+gap));
            if (i<0 || i>8) return;
            switch(i) {
                case 0: instrument=(instrument+1)%instruments.length; break;
                case 1: mode=(mode+1)%modes.length; break;
                case 2: scale=(scale+1)%scales.length; break;
                case 3: metronome=!metronome; if(metronome) nextClickNs=System.nanoTime(); break;
                case 4: recording=!recording; if(recording){take.clear();recStart=System.currentTimeMillis();} break;
                case 5: playTake(); break;
                case 6: zoomStep=Math.max(0,zoomStep-1); clampScroll(); break;
                case 7: zoomStep=Math.min(visibleWhites.length-1,zoomStep+1); clampScroll(); break;
                case 8: settingsDialog(); break;
            }
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            int W=getWidth(), H=getHeight();
            c.drawColor(Color.rgb(238,240,243));

            // Real-piano-style control surface: compact, dark, functional, with the keyboard occupying the screen.
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(250,250,252));
            c.drawRect(0,0,W,keyboardTop(),p);
            p.setColor(Color.rgb(210,213,218));
            c.drawRect(0,keyboardTop()-3,W,keyboardTop()+2,p);

            p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
            p.setTextAlign(Paint.Align.LEFT);
            p.setTextSize(18);
            p.setColor(Color.rgb(25,28,33));
            c.drawText("PIANO ∞",14,27,p);
            p.setTypeface(Typeface.DEFAULT);
            p.setTextSize(10);
            p.setColor(Color.rgb(88,93,101));
            c.drawText(instruments[instrument],14,47,p);
            c.drawText(modes[mode]+"  •  "+scales[scale]+"  •  "+bpm+" BPM",14,64,p);

            String[] labels={"Sound","Mode","Scale","Metronome","Record","Play","Zoom −","Zoom +","Settings"};
            float bw=Math.max(62f,Math.min(82f,(W-24f)/9f)), gap=3, x=10;
            for(int i=0;i<labels.length;i++) {
                drawButton(c,x,10,bw,labels[i],
                    (i==3&&metronome)||(i==4&&recording)||(i==5&&playing));
                x+=bw+gap;
            }

            p.setTextAlign(Paint.Align.RIGHT);
            p.setTextSize(10);
            p.setColor(Color.rgb(160,162,168));
            c.drawText("88 KEYS  •  "+(velocity?"TOUCH":"FIXED")+"  •  "+(sustain?"SUSTAIN":"NORMAL"),W-12,25,p);
            c.drawText("ZOOM "+visibleCount()+" WHITE KEYS  •  "+(transpose>=0?"+":"")+transpose+"  •  "+volumePercent()+"%",W-12,43,p);
            if(mode==4) c.drawText("TARGET "+noteName(learnNote),W-12,62,p);

            drawKeyboard(c);
            postInvalidateDelayed(16);
        }

        int volumePercent(){return Math.round(volume*100f);}

        void drawButton(Canvas c,float x,float y,float w,String text,boolean on) {
            p.setColor(on?Color.rgb(55,103,184):Color.WHITE);
            c.drawRoundRect(x,y,x+w,y+64,8,8,p);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
            p.setTextSize(text.length()>5?9:11);
            p.setColor(on?Color.WHITE:Color.rgb(31,34,39));
            c.drawText(text,x+w/2,y+38,p);
            p.setTypeface(Typeface.DEFAULT);
        }

        void drawKeyboard(Canvas c) {
            float top=keyboardTop(), bottom=keyboardBottom(), kw=keyWidth();
            int first=(int)Math.floor(scroll);
            float x0=-(scroll-first)*kw;

            // White keys: continuous, full-width, no empty half-screen.
            for(int i=0;i<visibleCount()+2;i++) {
                int wi=first+i;
                if(wi<MIN_WHITE || wi>MAX_WHITE) continue;
                int n=noteAtWhite(wi);
                float x=x0+i*kw;
                if(x>getWidth() || x+kw<0) continue;
                boolean active=pressed(n);
                boolean target=mode==4 && n==learnNote;
                // Physical white-key depth: shadow, gradient face and lower lip.
                p.setShader(null);
                p.setColor(Color.argb(70,0,0,0));
                c.drawRoundRect(x+1,top+3,x+kw-1,bottom+2,3,3,p);
                int topColor = active ? Color.rgb(225,235,255) : target ? Color.rgb(255,235,166) : Color.rgb(255,255,252);
                int bottomColor = active ? Color.rgb(168,190,238) : target ? Color.rgb(225,194,111) : Color.rgb(218,218,214);
                p.setShader(new LinearGradient(0,top,0,bottom,topColor,bottomColor,Shader.TileMode.CLAMP));
                c.drawRoundRect(x+1,top,x+kw-1,bottom,3,3,p);
                p.setShader(null);
                p.setColor(active?Color.rgb(125,149,201):Color.rgb(177,178,180));
                c.drawRect(x+2,bottom-5,x+kw-2,bottom-2,p);
                stroke.setColor(Color.rgb(72,74,78));
                stroke.setStrokeWidth(1.5f);
                c.drawRoundRect(x+1,top,x+kw-1,bottom,3,3,stroke);
                if(labels && kw>=34) {
                    p.setTextAlign(Paint.Align.CENTER);
                    p.setTextSize(Math.max(8,Math.min(12,kw*.18f)));
                    p.setColor(active?Color.rgb(25,42,80):Color.rgb(68,68,72));
                    c.drawText(letterName(n),x+kw/2,bottom-30,p);
                    p.setTextSize(Math.max(8,Math.min(11,kw*.16f)));
                    c.drawText(solfegeName(n),x+kw/2,bottom-14,p);
                }
            }

            // Black keys are drawn after white keys, using the actual chromatic note between whites.
            float blackH=(bottom-top)*.60f;
            for(int i=0;i<visibleCount()+2;i++) {
                int wi=first+i;
                if(wi<MIN_WHITE || wi>MAX_WHITE) continue;
                int black=noteAtWhite(wi)+1;
                if(!isBlack(black) || black<MIN_NOTE || black>MAX_NOTE) continue;
                float center=x0+(i+1)*kw;
                float bw=kw*.62f;
                if(center+bw/2<0 || center-bw/2>getWidth()) continue;
                boolean active=pressed(black);
                // Raised black-key cap with deep shadow and a small bevel highlight.
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.argb(105,0,0,0));
                c.drawRoundRect(center-bw/2+2,top+4,center+bw/2+2,top+blackH+3,5,5,p);
                int blackTop=active?Color.rgb(91,111,169):Color.rgb(58,59,64);
                int blackBottom=active?Color.rgb(49,65,105):Color.rgb(12,13,15);
                p.setShader(new LinearGradient(0,top,0,top+blackH,blackTop,blackBottom,Shader.TileMode.CLAMP));
                c.drawRoundRect(center-bw/2,top,center+bw/2,top+blackH,5,5,p);
                p.setShader(null);
                p.setColor(active?Color.rgb(150,169,215):Color.rgb(104,105,110));
                c.drawRoundRect(center-bw/2+3,top+2,center+bw/2-3,top+5,2,2,p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.7f);
                p.setColor(Color.rgb(4,4,5));
                c.drawRoundRect(center-bw/2,top,center+bw/2,top+blackH,5,5,p);
                p.setStyle(Paint.Style.FILL);
            }

            // Tiny octave markers make the real-keyboard layout easier to navigate.
            p.setTextAlign(Paint.Align.LEFT);
            p.setTextSize(9);
            p.setColor(Color.rgb(120,120,125));
            for(int i=0;i<visibleCount()+1;i++) {
                int wi=first+i, n=noteAtWhite(wi);
                if(n%12==0) {
                    float xx=x0+i*kw+4;
                    if(xx>=0 && xx<getWidth()) c.drawText("C"+(n/12-1),xx,top+blackH+16,p);
                }
            }
        }

        String letterName(int n) {
            String[] a={"C","C♯","D","D♯","E","F","F♯","G","G♯","A","A♯","B"};
            return a[(n%12+12)%12];
        }
        String solfegeName(int n) {
            String[] a={"Do","Do♯","Re","Re♯","Mi","Fa","Fa♯","Sol","Sol♯","La","La♯","Si"};
            return a[(n%12+12)%12];
        }
        String noteName(int n) { return letterName(n)+(n/12-1); }
        String dualLabel(int n) { return letterName(n)+" / "+solfegeName(n); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            int action=e.getActionMasked();
            int idx=e.getActionIndex();
            int id=e.getPointerId(idx);

            if(action==MotionEvent.ACTION_DOWN) {
                float x=e.getX(), y=e.getY();
                if(y<82) { toolbarAction(x); return true; }
                panning=false;
                int n=hit(x,y);
                if(n>0) {
                    fingers.put(id,n);
                    float pressure = e.getPressure(idx);
                    float vel = velocity
                        ? Math.max(.18f, Math.min(1f,
                            pressure > 0.01f
                                ? (.38f + pressure*.62f)
                                : (1f-((y-keyboardTop())/(keyboardBottom()-keyboardTop()))*.72f)))
                        : .82f;
                    noteOn(n,vel);
                }
                return true;
            }

            if(action==MotionEvent.ACTION_POINTER_DOWN) {
                float x=e.getX(idx), y=e.getY(idx);
                if(y<92) { toolbarAction(x); return true; }
                int n=hit(x,y);
                if(n>0) {
                    fingers.put(id,n);
                    float pressure=e.getPressure(idx);
                    noteOn(n, velocity
                        ? Math.max(.25f,Math.min(1f,pressure>0.01f?.42f+pressure*.58f:.8f))
                        : .82f);
                }
                return true;
            }

            if(action==MotionEvent.ACTION_MOVE) {
                for(int j=0;j<e.getPointerCount();j++) {
                    int pid=e.getPointerId(j);
                    Integer old=fingers.get(pid);
                    if(old==null) continue;
                    int n=hit(e.getX(j),e.getY(j));
                    if(n>0 && !old.equals(n)) {
                        noteOff(old);
                        fingers.put(pid,n);
                        noteOn(n,velocity?.78f:.82f);
                    }
                }
                return true;
            }

            if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP || action==MotionEvent.ACTION_CANCEL) {
                Integer n=fingers.remove(id);
                if(n!=null) noteOff(n);
                return true;
            }
            return true;
        }

        void releaseAllIfNeeded() {
            if(!sustain) {
                synchronized(voices) {
                    long now=System.nanoTime();
                    for(Voice v:voices.values()) if(v.held) {v.held=false;v.releaseNs=now;}
                }
            }
        }

        void playTake() {
            if(take.isEmpty() || playing) return;
            playing=true;
            new Thread(() -> {
                long last=0;
                for(Event e:take) {
                    if(!alive) break;
                    try { Thread.sleep(Math.max(0,e.t-last)); } catch(Exception ignored) {}
                    if(e.type==1) noteOnRaw(e.note,e.vel); else noteOffRaw(e.note);
                    last=e.t;
                }
                playing=false;
                postInvalidate();
            },"Playback").start();
        }

        void settingsDialog() {
            String[] items={"Touch velocity","Sustain","Note labels"};
            boolean[] checked={velocity,sustain,labels};
            new AlertDialog.Builder(MainActivity.this)
                .setTitle("Piano settings")
                .setMessage("Grand Piano sound: Salamander Grand Piano V3 by Alexander Holm — real Yamaha C5 recordings, CC BY 3.0.")
                .setMultiChoiceItems(items,checked,(d,w,on)->{
                    if(w==0) velocity=on;
                    if(w==1) {sustain=on;if(!on)releaseAll();}
                    if(w==2) labels=on;
                })
                .setSingleChoiceItems(
                    new String[]{"Transpose −12","Transpose −6","Transpose 0","Transpose +6","Transpose +12"},
                    transpose==0?2:transpose>0?3:1,
                    (d,w)->{transpose=new int[]{-12,-6,0,6,12}[w];d.dismiss();}
                )
                .setPositiveButton("DONE",null)
                .show();
        }

        void toast(String s) {
            Toast.makeText(MainActivity.this,s,Toast.LENGTH_LONG).show();
        }

        void close() {
            alive=false;
            releaseAll();
            if(soundPool!=null) try{soundPool.release();}catch(Exception ignored){}
            if(midiDevice!=null) try{midiDevice.close();}catch(Exception ignored){}
        }
    }
}
