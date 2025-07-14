package clientUI;

import common.CanvasAction;
import common.CanvasServerInterface;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.rmi.RemoteException;
import java.util.*;
import java.util.List;
import java.util.Arrays;

/**
 * CanvasComponent displays and manages drawing actions.
 * It sends user input to the server and renders all shapes.
 */
public class CanvasComponent extends JComponent {
    private List<CanvasAction> history = new ArrayList<>();   // all received actions
    private Color currentColor = Color.BLACK;                // drawing color
    private int strokeWidth = 2;                             // line thickness
    private CanvasAction.Type currentTool = CanvasAction.Type.LINE;
    private int startX, startY;                              // shape start coords
    private int previewX, previewY;                          // drag preview coords
    private boolean previewingShape = false;                 // in-shape-drag flag
    private transient Image offscreen;                       // double-buffer image
    private final String owner;        // this client’s name
    private CanvasServerInterface server;                    // RMI server stub
    private List<Point> currentStroke;                       // points for free draw
    private long currentStrokeId;                            // unique stroke id

    /**
     * Constructor: sets up mouse listeners and default size.
     */
    public CanvasComponent(CanvasServerInterface server, String owner) {
        this.server = server;
        this.owner  = owner;
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(800, 600));

        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                // begin new stroke or shape
                currentStrokeId++;
                startX = e.getX();
                startY = e.getY();
                previewX = startX;
                previewY = startY;
                previewingShape = (currentTool != CanvasAction.Type.FREE_DRAW
                        && currentTool != CanvasAction.Type.ERASER);

                if (currentTool == CanvasAction.Type.FREE_DRAW
                        || currentTool == CanvasAction.Type.ERASER) {
                    currentStroke = new ArrayList<>();
                    currentStroke.add(e.getPoint());
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (currentTool == CanvasAction.Type.FREE_DRAW
                        || currentTool == CanvasAction.Type.ERASER) {
                    // free-draw: send segments as user drags
                    Point prev = currentStroke.get(currentStroke.size() - 1);
                    Point curr = e.getPoint();
                    currentStroke.add(curr);

                    Color drawColor = (currentTool == CanvasAction.Type.ERASER
                            ? getBackground() : currentColor);
                    CanvasAction segment = new CanvasAction(
                            currentStrokeId,
                            owner,
                            Arrays.asList(prev, curr),
                            drawColor,
                            strokeWidth
                    );
                    try {
                        server.broadcastAction(segment);
                    } catch (RemoteException ex) {
                        handleServerDisconnect();
                        return;
                    }
                    addAction(segment);
                    repaint();

                } else if (previewingShape) {
                    // update shape preview
                    previewX = e.getX();
                    previewY = e.getY();
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                int x2 = e.getX(), y2 = e.getY();

                if (currentTool == CanvasAction.Type.TEXT) {
                    // prompt for text input
                    String text = JOptionPane.showInputDialog("Enter text:");
                    if (text == null || text.isEmpty()) return;

                    CanvasAction a = new CanvasAction(
                            currentStrokeId,
                            owner,
                            CanvasAction.Type.TEXT,
                            startX, startY, x2, y2,
                            currentColor, strokeWidth,
                            text
                    );
                    try {
                        server.broadcastAction(a);
                    } catch (RemoteException ex) {
                        handleServerDisconnect();
                        return;
                    }
                    addAction(a);
                    repaint();
                } else if (currentTool == CanvasAction.Type.TRIA) {
                    // create a triangle from (startX, startY), (x2, y2), and symmetric third point
                    int x3 = startX - (x2 - startX);
                    int y3 = y2;
                    // pack coords into arrays
                    int[] xs = { startX, x2, x3 };
                    int[] ys = { startY, y2, y3 };
                    CanvasAction a = new CanvasAction(
                            currentStrokeId, owner,
                            CanvasAction.Type.TRIA,
                            startX, startY, x2, y2,
                            currentColor, strokeWidth,
                            null
                    );
                    a.points = Arrays.asList(
                            new Point(xs[0], ys[0]),
                            new Point(xs[1], ys[1]),
                            new Point(xs[2], ys[2])
                    );
                    try {
                        server.broadcastAction(a);
                    } catch (RemoteException ex) {
                        handleServerDisconnect();
                        return;
                    }
                    addAction(a);
                    previewingShape = false;
                    repaint();

                } else if (currentTool != CanvasAction.Type.FREE_DRAW
                        && currentTool != CanvasAction.Type.ERASER) {
                    // finalize line/rect/oval
                    CanvasAction a = new CanvasAction(
                            currentStrokeId,
                            owner,
                            currentTool,
                            startX, startY, x2, y2,
                            currentColor, strokeWidth,
                            null
                    );
                    try {
                        server.broadcastAction(a);
                    } catch (RemoteException ex) {
                        handleServerDisconnect();
                        return;
                    }
                    addAction(a);
                    previewingShape = false;
                    repaint();

                } else {
                    // end eraser/free-draw without shape
                    currentStroke = null;
                    repaint();
                }
            }
        };

        addMouseListener(ma);
        addMouseMotionListener(ma);
    }

    /**
     * Show error then exit if server disconnects.
     */
    private void handleServerDisconnect() {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(
                    this,
                    "Lost connection to server.\nClosing whiteboard.",
                    "Connection Error",
                    JOptionPane.ERROR_MESSAGE
            );
            System.exit(1);
        });
    }

    @Override
    protected void paintComponent(Graphics g) {
        // initialize buffer
        if (offscreen == null) {
            offscreen = createImage(getWidth(), getHeight());
        }
        Graphics2D g2 = (Graphics2D) offscreen.getGraphics();
        // clear background
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, getWidth(), getHeight());

        // draw all history actions
        for (CanvasAction a : history) {
            g2.setStroke(new BasicStroke(a.strokeWidth));
            g2.setColor(a.color);
            switch (a.type) {
                case LINE:
                    g2.drawLine(a.x1, a.y1, a.x2, a.y2);
                    break;
                case TRIA:
                    if (a.points != null && a.points.size() == 3) {
                        int[] xs = new int[3], ys = new int[3];
                        for (int i = 0; i < 3; i++) {
                            xs[i] = a.points.get(i).x;
                            ys[i] = a.points.get(i).y;
                        }
                        g2.drawPolygon(xs, ys, 3);
                    }
                    break;
                case RECT:
                    g2.drawRect(
                            Math.min(a.x1, a.x2),
                            Math.min(a.y1, a.y2),
                            Math.abs(a.x2 - a.x1),
                            Math.abs(a.y2 - a.y1)
                    );
                    break;
                case OVAL:
                    g2.drawOval(
                            Math.min(a.x1, a.x2),
                            Math.min(a.y1, a.y2),
                            Math.abs(a.x2 - a.x1),
                            Math.abs(a.y2 - a.y1)
                    );
                    break;
                case TEXT:
                    if (a.text != null) {
                        g2.setFont(g2.getFont().deriveFont(a.strokeWidth * 10f));
                        g2.drawString(a.text, a.x1, a.y1);
                    }
                    break;
                case ERASER:
                case FREE_DRAW:
                    // draw point list
                    if (a.points != null && a.points.size() > 1) {
                        for (int i = 1; i < a.points.size(); i++) {
                            Point p0 = a.points.get(i - 1);
                            Point p1 = a.points.get(i);
                            g2.drawLine(p0.x, p0.y, p1.x, p1.y);
                        }
                    }
                    break;
            }
        }

        // draw preview stroke if any
        if (currentStroke != null && currentStroke.size() > 1) {
            g2.setColor(currentTool == CanvasAction.Type.ERASER
                    ? getBackground() : currentColor);
            g2.setStroke(new BasicStroke(strokeWidth));
            for (int i = 1; i < currentStroke.size(); i++) {
                Point p0 = currentStroke.get(i - 1);
                Point p1 = currentStroke.get(i);
                g2.drawLine(p0.x, p0.y, p1.x, p1.y);
            }
        }

        // draw offscreen buffer
        g.drawImage(offscreen, 0, 0, null);

        // draw shape preview if dragging
        if (previewingShape && currentTool != CanvasAction.Type.FREE_DRAW && currentTool != CanvasAction.Type.ERASER) {
            Graphics2D g3 = (Graphics2D) g;
            g3.setColor(currentColor);
            g3.setStroke(new BasicStroke(strokeWidth));
            switch (currentTool) {
                case LINE:
                    g3.drawLine(startX, startY, previewX, previewY);
                    break;
                case TRIA:
                    int x3 = startX - (previewX - startX);
                    int y3 = previewY;
                    int[] xs = { startX, previewX, x3 };
                    int[] ys = { startY, previewY,  y3 };
                    g3.drawPolygon(xs, ys, 3);
                    break;
                case RECT:
                    g3.drawRect(
                            Math.min(startX, previewX),
                            Math.min(startY, previewY),
                            Math.abs(previewX - startX),
                            Math.abs(previewY - startY)
                    );
                    break;
                case OVAL:
                    g3.drawOval(
                            Math.min(startX, previewX),
                            Math.min(startY, previewY),
                            Math.abs(previewX - startX),
                            Math.abs(previewY - startY)
                    );
                    break;
                default:
                    break;
            }
        }
    }

    /** Replace entire history and repaint. */
    public void setHistory(List<CanvasAction> h) {
        history = h;
        repaint();
    }

    /** Add a single action to history. */
    public void addAction(CanvasAction a) {
        history.add(a);
    }

    /** Get a copy of the current history list. */
    public List<CanvasAction> getHistory() {
        return new ArrayList<>(history);
    }

    /**
     * Render current canvas to a BufferedImage for export.
     */
    public BufferedImage getCanvasImage() {
        BufferedImage img = new BufferedImage(getWidth(), getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setColor(getBackground());
        g2.fillRect(0, 0, getWidth(), getHeight());
        paint(g2);
        g2.dispose();
        return img;
    }

    // setters for tool, color, and stroke width

    /** Set current drawing tool. */
    public void setTool(CanvasAction.Type t) {
        currentTool = t;
    }

    /** Set current drawing color. */
    public void setColor(Color c) {
        currentColor = c;
    }

    /** Set current stroke width. */
    public void setStrokeWidth(int w) {
        strokeWidth = w;
    }

    /** Get the current drawing color. */
    public Color getCurrentColor() {
        return currentColor;
    }
}
