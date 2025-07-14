package common;

import java.awt.*;
import java.io.Serializable;
import java.util.*;

public class CanvasAction implements Serializable {
    public enum Type { LINE, TRIA, RECT, OVAL, FREE_DRAW, TEXT, ERASER }
    public Type type;
    public int x1, y1, x2, y2;
    public String text;
    public Color color;
    public float strokeWidth;
    public long strokeId;
    public final String owner;        // name of the client who drew
    public java.util.List<Point> points;

    /**
     * Constructor for LINE, RECT, OVAL, or TEXT actions.
     */
    public CanvasAction(long strokeId, String owner, Type type, int x1, int y1, int x2, int y2,
                        Color color, float strokeWidth, String text) {
        this.type = type;
        this.owner = owner;
        this.x1=x1;
        this.y1=y1;
        this.x2=x2;
        this.y2=y2;
        this.color = color;
        this.strokeWidth = strokeWidth;
        this.text = text;
        this.points = null;
        this.strokeId = strokeId;
    }

    /**
     * Constructor for FREE_DRAW or ERASER actions.
     */
    public CanvasAction(long strokeId, String owner, java.util.List<Point> points, Color color, float strokeWidth) {
        this.type = Type.FREE_DRAW;
        this.owner = owner;
        this.points = new ArrayList<>(points);
        this.color = color;
        this.strokeWidth = strokeWidth;
        this.x1=x1;
        this.y1=y1;
        this.x2=x2;
        this.y2=y2;
        this.text = null;
        this.strokeId = strokeId;
    }
}