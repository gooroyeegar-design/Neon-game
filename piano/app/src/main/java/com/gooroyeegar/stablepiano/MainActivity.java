package com.gooroyeegar.stablepiano;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

public class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(new PianoView());
    }

    private static final class PianoView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int active = -1;

        PianoView() { super(MainActivity.this); setFocusable(true); }

        @Override protected void onDraw(Canvas c) {
            c.drawColor(Color.rgb(245,242,250));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(Color.rgb(55,35,85));
            paint.setTextSize(42);
            c.drawText("Piano", getWidth()/2f, 62, paint);
            paint.setTextSize(17);
            paint.setColor(Color.DKGRAY);
            c.drawText("Tap a key", getWidth()/2f, 91, paint);

            float top=125, bottom=getHeight()-28, w=getWidth()/7f;
            int[] white={0,2,4,5,7,9,11,12};
            for(int i=0;i<8;i++) {
                float l=i*w, r=(i+1)*w;
                paint.setColor(active==white[i] ? Color.rgb(218,200,242) : Color.WHITE);
                c.drawRect(l,top,r,bottom,paint);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2); paint.setColor(Color.LTGRAY);
                c.drawRect(l,top,r,bottom,paint); paint.setStyle(Paint.Style.FILL);
            }
            int[] black={1,3,6,8,10}; int[] left={0,1,3,4,5};
            float bw=w*.58f, bh=(bottom-top)*.57f;
            for(int i=0;i<5;i++) {
                float center=(left[i]+1)*w;
                paint.setColor(active==black[i] ? Color.rgb(95,70,120) : Color.rgb(25,22,28));
                c.drawRect(center-bw/2,top,center+bw/2,top+bh,paint);
            }
        }

        private int keyAt(float x,float y) {
            float top=125,bottom=getHeight()-28,w=getWidth()/7f;
            if(y<top || y>bottom) return -1;
            int[] black={1,3,6,8,10}; int[] left={0,1,3,4,5};
            float bw=w*.58f,bh=(bottom-top)*.57f;
            for(int i=0;i<5;i++) { float center=(left[i]+1)*w; if(x>=center-bw/2 && x<=center+bw/2 && y<=top+bh) return black[i]; }
            int wi=(int)(x/w); int[] white={0,2,4,5,7,9,11,12};
            return wi>=0 && wi<8 ? white[wi] : -1;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if(e.getAction()==MotionEvent.ACTION_DOWN || e.getAction()==MotionEvent.ACTION_MOVE) { active=keyAt(e.getX(),e.getY()); invalidate(); return true; }
            if(e.getAction()==MotionEvent.ACTION_UP || e.getAction()==MotionEvent.ACTION_CANCEL) { active=-1; invalidate(); return true; }
            return true;
        }
    }
}
