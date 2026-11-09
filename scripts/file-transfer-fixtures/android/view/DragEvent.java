package android.view;
import android.content.ClipData;
public record DragEvent(int action, ClipData clip, Object localState) {
    public static final int ACTION_DRAG_STARTED=1, ACTION_DROP=3, ACTION_DRAG_ENDED=4;
    public int getAction(){return action;}
    public ClipData getClipData(){return clip;}
    public Object getLocalState(){return localState;}
}
