package com.fluxstick.controller;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.view.*;
import android.widget.EditText;
import android.widget.Toast;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class ControllerView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint editPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isSending = new AtomicBoolean(false);
    private DatagramSocket socket;
    private InetAddress pc;
    private final int port = 26760;
    private String pcIp = "192.168.0.103";
    private long packetsSent = 0;
    private String lastError = null;

    private float sx, sy;
    private final HashMap<String, Boolean> buttons = new HashMap<>();
    private float lx = 0, ly = 0, rx = 0, ry = 0;
    private boolean gyro = false, mouse = false;

    private final RectF leftStick = new RectF(), rightStick = new RectF();
    private final ArrayList<Hit> hits = new ArrayList<>();

    // Layout Editor fields
    private boolean isEditMode = false;
    private LayoutElement selectedElement = null;
    private boolean isDragging = false;
    private float dragOffsetX = 0, dragOffsetY = 0;
    private final LinkedHashMap<String, LayoutElement> elements = new LinkedHashMap<>();

    public static class LayoutElement {
        public final String id;
        public final String displayName;
        public final float defaultX, defaultY;
        public final float width, height;

        public float x, y;
        public float scale = 1.0f;

        public LayoutElement(String id, String displayName, float defaultX, float defaultY, float width, float height) {
            this.id = id;
            this.displayName = displayName;
            this.defaultX = defaultX;
            this.defaultY = defaultY;
            this.x = defaultX;
            this.y = defaultY;
            this.width = width;
            this.height = height;
        }

        public void reset() {
            this.x = defaultX;
            this.y = defaultY;
            this.scale = 1.0f;
        }

        public RectF getDesignBounds() {
            float w = width * scale;
            float h = height * scale;
            return new RectF(x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f);
        }

        public RectF getBounds(float s) {
            float w = width * scale * s;
            float h = height * scale * s;
            float cx = x * s;
            float cy = y * s;
            return new RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        }
    }

    private static class Hit {
        RectF r;
        String id;

        Hit(float l, float t, float rr, float b, String i, float s) {
            float pad = 16f * s;
            r = new RectF(l - pad, t - pad, rr + pad, b + pad);
            id = i;
        }

        boolean contains(float x, float y) {
            return r.contains(x, y);
        }
    }

    public ControllerView(Context c) {
        super(c);
        setFocusable(true);
        text.setTypeface(Typeface.create("sans", Typeface.BOLD));
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(1);

        loadPrefs();
        initElements();
        loadLayout();
        startNetwork();
    }

    private void loadPrefs() {
        SharedPreferences prefs = getContext().getSharedPreferences("fluxstick_prefs", Context.MODE_PRIVATE);
        pcIp = prefs.getString("pc_ip", "192.168.0.103");
    }

    private void initElements() {
        elements.put("LB", new LayoutElement("LB", "LB Bumper", 287.5f, 147.5f, 185f, 85f));
        elements.put("LT", new LayoutElement("LT", "LT Trigger", 457.5f, 147.5f, 115f, 145f));
        elements.put("RT", new LayoutElement("RT", "RT Trigger", 1462.5f, 147.5f, 115f, 145f));
        elements.put("RB", new LayoutElement("RB", "RB Bumper", 1632.5f, 147.5f, 185f, 85f));

        elements.put("LS", new LayoutElement("LS", "Left Stick", 272.5f, 472.5f, 365f, 365f));
        elements.put("RS", new LayoutElement("RS", "Right Stick", 1647.5f, 472.5f, 365f, 365f));

        elements.put("DPAD", new LayoutElement("DPAD", "D-Pad", 650f, 385f, 246f, 246f));
        elements.put("FACE", new LayoutElement("FACE", "ABXY Buttons", 1245f, 420f, 250f, 250f));

        elements.put("SELECT", new LayoutElement("SELECT", "Select", 830f, 717.5f, 160f, 75f));
        elements.put("BACK", new LayoutElement("BACK", "Back", 925f, 718f, 50f, 50f));
        elements.put("GUIDE", new LayoutElement("GUIDE", "Guide", 995f, 718f, 50f, 50f));
        elements.put("START", new LayoutElement("START", "Start", 1095f, 717.5f, 160f, 75f));

        elements.put("MODE", new LayoutElement("MODE", "Mode", 1735f, 705f, 60f, 60f));
    }

    private void saveLayout() {
        SharedPreferences.Editor ed = getContext().getSharedPreferences("fluxstick_layout", Context.MODE_PRIVATE).edit();
        for (LayoutElement elem : elements.values()) {
            ed.putFloat(elem.id + "_x", elem.x);
            ed.putFloat(elem.id + "_y", elem.y);
            ed.putFloat(elem.id + "_scale", elem.scale);
        }
        ed.apply();
    }

    private void loadLayout() {
        SharedPreferences prefs = getContext().getSharedPreferences("fluxstick_layout", Context.MODE_PRIVATE);
        for (LayoutElement elem : elements.values()) {
            elem.x = prefs.getFloat(elem.id + "_x", elem.defaultX);
            elem.y = prefs.getFloat(elem.id + "_y", elem.defaultY);
            elem.scale = prefs.getFloat(elem.id + "_scale", 1.0f);
        }
    }

    private void resetLayout() {
        getContext().getSharedPreferences("fluxstick_layout", Context.MODE_PRIVATE).edit().clear().apply();
        for (LayoutElement elem : elements.values()) {
            elem.reset();
        }
    }

    private void startNetwork() {
        net.execute(() -> {
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
                socket = new DatagramSocket();
                pc = InetAddress.getByName(pcIp);
                lastError = null;
            } catch (Exception e) {
                lastError = e.getMessage() != null ? e.getMessage() : "Network Init Error";
            }
        });
    }

    private void send() {
        if (isEditMode) return;
        if (!isSending.compareAndSet(false, true)) return;

        net.execute(() -> {
            try {
                if (socket == null || pc == null) {
                    if (pc == null) pc = InetAddress.getByName(pcIp);
                    if (socket == null || socket.isClosed()) socket = new DatagramSocket();
                }
                String json = "{\"lx\":" + fmt(lx) + ",\"ly\":" + fmt(ly) +
                        ",\"rx\":" + fmt(rx) + ",\"ry\":" + fmt(ry) +
                        ",\"a\":" + b("A") + ",\"b\":" + b("B") + ",\"x\":" + b("X") + ",\"y\":" + b("Y") +
                        ",\"lb\":" + b("LB") + ",\"rb\":" + b("RB") + ",\"lt\":" + b("LT") + ",\"rt\":" + b("RT") +
                        ",\"up\":" + b("UP") + ",\"down\":" + b("DOWN") + ",\"left\":" + b("LEFT") + ",\"right\":" + b("RIGHT") +
                        ",\"lsb\":" + b("LSB") + ",\"rsb\":" + b("RSB") +
                        ",\"select\":" + b("SELECT") + ",\"start\":" + b("START") +
                        ",\"back\":" + b("BACK") + ",\"guide\":" + b("GUIDE") +
                        ",\"gyro\":" + gyro + ",\"mouse\":" + mouse + "}";
                byte[] data = json.getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(data, data.length, pc, port));
                packetsSent++;
                lastError = null;
            } catch (Exception e) {
                lastError = e.getMessage() != null ? e.getMessage() : "Send Failed";
            } finally {
                isSending.set(false);
            }
        });
    }

    private String fmt(float v) {
        return String.format(Locale.US, "%.3f", v);
    }

    private boolean b(String k) {
        Boolean v = buttons.get(k);
        return v != null && v;
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        sx = getWidth() / 1920f;
        sy = getHeight() / 1080f;
        float s = Math.min(sx, sy);
        c.drawColor(Color.rgb(2, 7, 12));

        // subtle honeycomb-style grid
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, s));
        p.setColor(Color.argb(35, 0, 145, 230));
        float step = 34 * s;
        for (float y = 42 * s; y < getHeight(); y += step) {
            for (float x = 18 * s; x < getWidth(); x += step) {
                float xx = x + (((int) (y / step) & 1) * step / 2);
                c.drawCircle(xx, y, 6 * s, grid);
            }
        }

        // outer frame
        p.setColor(Color.rgb(0, 155, 235));
        p.setStrokeWidth(3 * s);
        c.drawRoundRect(8 * s, 8 * s, getWidth() - 8 * s, getHeight() - 8 * s, 18 * s, 18 * s, p);

        hits.clear();
        drawHeader(c, s);

        // Shoulder controls
        drawElementButton(c, "LB", s);
        drawElementButton(c, "LT", s);
        drawElementButton(c, "RT", s);
        drawElementButton(c, "RB", s);

        // Sticks
        drawElementStick(c, "LS", "LSB", lx, ly, s);
        drawElementStick(c, "RS", "RSB", rx, ry, s);

        // D-pad and ABXY
        drawDpad(c, s);
        drawFace(c, s);

        // Center controls
        drawElementButton(c, "SELECT", s);
        drawCircleButtonElement(c, "BACK", "≡", s);
        drawCircleButtonElement(c, "GUIDE", "□", s);
        drawElementButton(c, "START", s);

        // Mode
        drawCircleButtonElement(c, "MODE", "MODE", s);

        // Bottom indicators
        text.setTextSize(14 * s);
        if (lastError == null) {
            text.setColor(Color.rgb(0, 210, 150));
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText("● UDP (" + pcIp + ":" + port + ") | " + packetsSent + " pkts", 20 * s, (getHeight() - 22 * s), text);
        } else {
            text.setColor(Color.rgb(255, 90, 90));
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText("● ERR (" + pcIp + "): " + lastError, 20 * s, (getHeight() - 22 * s), text);
        }

        text.setColor(Color.rgb(110, 130, 145));
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(gyro ? "● GYRO LOOK" : "○ GYRO LOOK", 850 * s, getHeight() - 22 * s, text);
        c.drawText(mouse ? "● MOUSE MODE" : "○ MOUSE MODE", 1060 * s, getHeight() - 22 * s, text);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText("PORT 26760", getWidth() - 20 * s, getHeight() - 22 * s, text);

        // Draw Layout Editor overlay when active
        if (isEditMode) {
            drawEditorOverlay(c, s);
        }
    }

    private void drawHeader(Canvas c, float s) {
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans", Typeface.BOLD));
        text.setTextSize(28 * s);
        text.setColor(Color.rgb(0, 165, 245));
        c.drawText("FLUXSTICK", getWidth() / 2f, 31 * s, text);

        text.setTextSize(8 * s);
        text.setColor(Color.rgb(100, 125, 140));
        c.drawText(isEditMode ? "LAYOUT EDITOR MODE" : "VIRTUAL GAME CONTROLLER", getWidth() / 2f, 42 * s, text);

        text.setTextSize(30 * s);
        text.setColor(isEditMode ? Color.YELLOW : Color.LTGRAY);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText("⚙", getWidth() - 22 * s, 43 * s, text);

        hits.add(new Hit(getWidth() - 80 * s, 0, getWidth(), 60 * s, "SETTINGS", s));
    }

    private void drawElementButton(Canvas c, String id, float s) {
        LayoutElement elem = elements.get(id);
        if (elem == null) return;
        float cx = elem.x * s;
        float cy = elem.y * s;
        float w = elem.width * elem.scale * s;
        float h = elem.height * elem.scale * s;
        float l = cx - w / 2f;
        float t = cy - h / 2f;
        float r = cx + w / 2f;
        float b = cy + h / 2f;

        boolean pressed = b(id);

        p.setStyle(Paint.Style.FILL);
        p.setColor(pressed ? Color.rgb(0, 160, 240) : Color.rgb(7, 25, 36));
        c.drawRoundRect(l, t, r, b, 12 * s * elem.scale, 12 * s * elem.scale, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * s);
        p.setColor(pressed ? Color.rgb(0, 220, 255) : Color.rgb(20, 105, 150));
        c.drawRoundRect(l, t, r, b, 12 * s * elem.scale, 12 * s * elem.scale, p);

        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(Math.max(12, 22 * elem.scale) * s);
        text.setColor(pressed ? Color.WHITE : Color.rgb(215, 235, 245));
        c.drawText(labelFor(id), cx, cy + 7 * s * elem.scale, text);

        hits.add(new Hit(l, t, r, b, id, s));
    }

    private void drawElementStick(Canvas c, String elemId, String stickLabel, float vx, float vy, float s) {
        LayoutElement elem = elements.get(elemId);
        if (elem == null) return;
        RectF bounds = elem.getBounds(s);
        if (elemId.equals("LS")) leftStick.set(bounds);
        else if (elemId.equals("RS")) rightStick.set(bounds);

        float cx = bounds.centerX(), cy = bounds.centerY(), rad = bounds.width() / 2f;
        boolean pressed = b(stickLabel);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3 * s);
        p.setColor(pressed ? Color.rgb(0, 220, 255) : Color.rgb(0, 155, 245));
        c.drawCircle(cx, cy, rad, p);

        p.setStrokeWidth(1 * s);
        p.setColor(Color.argb(100, 40, 110, 150));
        c.drawCircle(cx, cy, rad * 0.70f, p);
        c.drawCircle(cx, cy, rad * 0.48f, p);

        float kx = cx + vx * rad * 0.52f, ky = cy + vy * rad * 0.52f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(pressed ? Color.rgb(0, 170, 245) : Color.rgb(10, 25, 35));
        c.drawCircle(kx, ky, rad * 0.19f, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * s);
        p.setColor(pressed ? Color.WHITE : Color.rgb(60, 90, 110));
        c.drawCircle(kx, ky, rad * 0.19f, p);

        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(12 * s * elem.scale);
        text.setColor(pressed ? Color.WHITE : Color.rgb(0, 170, 245));
        text.setTypeface(Typeface.DEFAULT_BOLD);
        c.drawText(stickLabel, cx, cy + rad + 24 * s * elem.scale, text);

        hits.add(new Hit(bounds.left, bounds.top, bounds.right, bounds.bottom, stickLabel, s));
    }

    private void drawDpad(Canvas c, float s) {
        LayoutElement elem = elements.get("DPAD");
        if (elem == null) return;
        float cx = elem.x * s, cy = elem.y * s;
        float scale = elem.scale;
        float w = 82 * s * scale, h = 82 * s * scale, gap = 3 * s * scale;

        drawSubButton(c, cx - w / 2, cy - h * 1.5f, cx + w / 2, cy - h / 2, "UP", s, scale);
        drawSubButton(c, cx - w * 1.5f, cy - h / 2, cx - w / 2 - gap, cy + h / 2, "LEFT", s, scale);
        drawSubButton(c, cx + w / 2 + gap, cy - h / 2, cx + w * 1.5f, cy + h / 2, "RIGHT", s, scale);
        drawSubButton(c, cx - w / 2, cy + h / 2, cx + w / 2, cy + h * 1.5f, "DOWN", s, scale);
    }

    private void drawSubButton(Canvas c, float l, float t, float r, float b, String id, float s, float scale) {
        boolean pressed = b(id);

        p.setStyle(Paint.Style.FILL);
        p.setColor(pressed ? Color.rgb(0, 160, 240) : Color.rgb(7, 25, 36));
        c.drawRoundRect(l, t, r, b, 12 * s * scale, 12 * s * scale, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * s);
        p.setColor(pressed ? Color.rgb(0, 220, 255) : Color.rgb(20, 105, 150));
        c.drawRoundRect(l, t, r, b, 12 * s * scale, 12 * s * scale, p);

        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(Math.max(12, 18 * scale) * s);
        text.setColor(pressed ? Color.WHITE : Color.rgb(215, 235, 245));
        c.drawText(labelFor(id), (l + r) / 2f, (t + b) / 2f + 6 * s * scale, text);

        hits.add(new Hit(l, t, r, b, id, s));
    }

    private void drawFace(Canvas c, float s) {
        LayoutElement elem = elements.get("FACE");
        if (elem == null) return;
        float cx = elem.x * s, cy = elem.y * s;
        float scale = elem.scale;
        float offset = 62 * s * scale;

        face(c, cx, cy - offset, "Y", Color.rgb(255, 205, 25), s, scale);
        face(c, cx - offset, cy, "X", Color.rgb(30, 170, 255), s, scale);
        face(c, cx + offset, cy, "B", Color.rgb(245, 65, 70), s, scale);
        face(c, cx, cy + offset, "A", Color.rgb(35, 220, 125), s, scale);
    }

    private void face(Canvas c, float cx, float cy, String id, int defaultColor, float s, float scale) {
        float r = 34 * s * scale;
        boolean pressed = b(id);

        p.setStyle(Paint.Style.FILL);
        p.setColor(pressed ? defaultColor : Color.rgb(4, 18, 28));
        c.drawCircle(cx, cy, r, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * s);
        p.setColor(pressed ? Color.WHITE : Color.rgb(15, 95, 140));
        c.drawCircle(cx, cy, r, p);

        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(18 * s * scale);
        text.setColor(pressed ? Color.rgb(10, 15, 20) : defaultColor);
        c.drawText(id, cx, cy + 6 * s * scale, text);

        hits.add(new Hit(cx - r, cy - r, cx + r, cy + r, id, s));
    }

    private void drawCircleButtonElement(Canvas c, String id, String label, float s) {
        LayoutElement elem = elements.get(id);
        if (elem == null) return;
        float cx = elem.x * s, cy = elem.y * s;
        float scale = elem.scale;
        float r = (id.equals("MODE") ? 30f : 25f) * scale * s;
        boolean pressed = b(id);

        p.setStyle(Paint.Style.FILL);
        p.setColor(pressed ? Color.rgb(0, 160, 240) : Color.rgb(4, 18, 28));
        c.drawCircle(cx, cy, r, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * s);
        p.setColor(pressed ? Color.rgb(0, 220, 255) : Color.rgb(20, 105, 150));
        c.drawCircle(cx, cy, r, p);

        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize((id.equals("MODE") ? 10 : 16) * scale * s);
        text.setColor(pressed ? Color.WHITE : Color.LTGRAY);
        c.drawText(label, cx, cy + 5 * s * scale, text);

        hits.add(new Hit(cx - r, cy - r, cx + r, cy + r, id, s));
    }

    private String labelFor(String id) {
        if (id.equals("UP")) return "▲";
        if (id.equals("DOWN")) return "▼";
        if (id.equals("LEFT")) return "◀";
        if (id.equals("RIGHT")) return "▶";
        return id;
    }

    private void drawEditorOverlay(Canvas c, float s) {
        // 1. Draw bounding frames around elements
        editPaint.setStyle(Paint.Style.STROKE);
        editPaint.setPathEffect(new DashPathEffect(new float[]{10 * s, 6 * s}, 0));

        for (LayoutElement elem : elements.values()) {
            RectF bounds = elem.getBounds(s);
            if (elem == selectedElement) {
                // Highlighted selection
                editPaint.setStrokeWidth(3 * s);
                editPaint.setColor(Color.rgb(255, 215, 0)); // Gold
                c.drawRoundRect(bounds, 10 * s, 10 * s, editPaint);

                // Label above selected element
                text.setTextAlign(Paint.Align.CENTER);
                text.setTextSize(14 * s);
                text.setColor(Color.YELLOW);
                c.drawText(elem.displayName + " (" + Math.round(elem.scale * 100) + "%)",
                        bounds.centerX(), bounds.top - 10 * s, text);
            } else {
                // Unselected element frame
                editPaint.setStrokeWidth(1.5f * s);
                editPaint.setColor(Color.argb(120, 0, 190, 255));
                c.drawRoundRect(bounds, 8 * s, 8 * s, editPaint);
            }
        }
        editPaint.setPathEffect(null); // Clear dash effect

        // 2. Editor Top Toolbar
        float tbHeight = 65 * s;
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(235, 8, 18, 28));
        c.drawRect(0, 0, getWidth(), tbHeight, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * s);
        p.setColor(Color.rgb(0, 165, 245));
        c.drawLine(0, tbHeight, getWidth(), tbHeight, p);

        // Toolbar Title & Instructions
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(18 * s);
        text.setColor(Color.CYAN);
        c.drawText("LAYOUT EDITOR", 25 * s, 28 * s, text);

        text.setTextSize(11 * s);
        text.setColor(Color.LTGRAY);
        c.drawText("Drag controls to move • Select element to resize", 25 * s, 48 * s, text);

        // Toolbar Action Buttons
        drawToolbarButton(c, 1080, 1220, "RESET", Color.rgb(180, 100, 30), s);
        drawToolbarButton(c, 1240, 1310, " - ", Color.rgb(40, 90, 130), s);
        drawToolbarButton(c, 1320, 1390, " + ", Color.rgb(40, 90, 130), s);
        drawToolbarButton(c, 1410, 1560, "CANCEL", Color.rgb(150, 40, 50), s);
        drawToolbarButton(c, 1580, 1890, "SAVE & EXIT", Color.rgb(0, 150, 90), s);
    }

    private void drawToolbarButton(Canvas c, float l, float r, String label, int color, float s) {
        float t = 12, b = 52;
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawRoundRect(l * s, t * s, r * s, b * s, 8 * s, 8 * s, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f * s);
        p.setColor(Color.WHITE);
        c.drawRoundRect(l * s, t * s, r * s, b * s, 8 * s, 8 * s, p);

        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(13 * s);
        text.setColor(Color.WHITE);
        c.drawText(label, (l + r) * s / 2f, (t + b) * s / 2f + 5 * s, text);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (isEditMode) {
            handleEditTouch(e);
            return true;
        }

        float x = e.getX(), y = e.getY();
        int action = e.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            for (Hit h : hits) {
                if (h.id.equals("SETTINGS") && h.contains(x, y)) {
                    showSettingsDialog();
                    return true;
                }
            }
        }

        // Toggles for MODE and GYRO
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int idx = e.getActionIndex();
            float px = e.getX(idx);
            float py = e.getY(idx);
            for (Hit h : hits) {
                if (h.contains(px, py)) {
                    if (h.id.equals("MODE")) mouse = !mouse;
                    else if (h.id.equals("GYRO")) gyro = !gyro;
                }
            }
        }

        // Handle button and joystick touch tracking across all pointers
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (e.getPointerCount() <= 1) {
                buttons.replaceAll((k, v) -> false);
                lx = 0; ly = 0; rx = 0; ry = 0;
                invalidate();
                send();
                return true;
            }
        }

        buttons.replaceAll((k, v) -> false);
        boolean newLx = false, newRx = false;

        for (int i = 0; i < e.getPointerCount(); i++) {
            if (action == MotionEvent.ACTION_POINTER_UP && i == e.getActionIndex()) {
                continue;
            }

            float px = e.getX(i);
            float py = e.getY(i);

            if (leftStick.contains(px, py)) {
                lx = Math.max(-1, Math.min(1, (px - leftStick.centerX()) / (leftStick.width() / 2f)));
                ly = Math.max(-1, Math.min(1, (py - leftStick.centerY()) / (leftStick.height() / 2f)));
                newLx = true;
            }

            if (rightStick.contains(px, py)) {
                rx = Math.max(-1, Math.min(1, (px - rightStick.centerX()) / (rightStick.width() / 2f)));
                ry = Math.max(-1, Math.min(1, (py - rightStick.centerY()) / (rightStick.height() / 2f)));
                newRx = true;
            }

            for (Hit h : hits) {
                if (h.contains(px, py)) {
                    if (!h.id.equals("SETTINGS") && !h.id.equals("MODE") && !h.id.equals("GYRO")) {
                        buttons.put(h.id, true);
                    }
                }
            }
        }

        if (!newLx) { lx = 0; ly = 0; }
        if (!newRx) { rx = 0; ry = 0; }

        invalidate();
        send();
        return true;
    }

    private void handleEditTouch(MotionEvent e) {
        float x = e.getX();
        float y = e.getY();
        float s = Math.min(sx, sy);
        int action = e.getActionMasked();

        float dx = x / s;
        float dy = y / s;

        if (action == MotionEvent.ACTION_DOWN) {
            // Check Toolbar Buttons first
            if (dy >= 10 && dy <= 55) {
                if (dx >= 1580 && dx <= 1890) { // SAVE & EXIT
                    saveLayout();
                    isEditMode = false;
                    selectedElement = null;
                    Toast.makeText(getContext(), "Layout Saved", Toast.LENGTH_SHORT).show();
                    invalidate();
                    return;
                }
                if (dx >= 1410 && dx <= 1560) { // CANCEL
                    loadLayout();
                    isEditMode = false;
                    selectedElement = null;
                    Toast.makeText(getContext(), "Edits Cancelled", Toast.LENGTH_SHORT).show();
                    invalidate();
                    return;
                }
                if (dx >= 1320 && dx <= 1390) { // [+] Scale
                    if (selectedElement != null) {
                        selectedElement.scale = Math.min(2.2f, selectedElement.scale + 0.1f);
                        invalidate();
                    } else {
                        Toast.makeText(getContext(), "Select an element first", Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                if (dx >= 1240 && dx <= 1310) { // [-] Scale
                    if (selectedElement != null) {
                        selectedElement.scale = Math.max(0.4f, selectedElement.scale - 0.1f);
                        invalidate();
                    } else {
                        Toast.makeText(getContext(), "Select an element first", Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                if (dx >= 1080 && dx <= 1220) { // RESET
                    if (selectedElement != null) {
                        selectedElement.reset();
                        Toast.makeText(getContext(), selectedElement.displayName + " reset", Toast.LENGTH_SHORT).show();
                    } else {
                        resetLayout();
                        Toast.makeText(getContext(), "All elements reset", Toast.LENGTH_SHORT).show();
                    }
                    invalidate();
                    return;
                }
            }

            // Check elements for dragging/selecting
            LayoutElement found = null;
            ArrayList<LayoutElement> list = new ArrayList<>(elements.values());
            Collections.reverse(list);
            for (LayoutElement elem : list) {
                RectF bounds = elem.getDesignBounds();
                RectF touchBounds = new RectF(bounds.left - 20, bounds.top - 20, bounds.right + 20, bounds.bottom + 20);
                if (touchBounds.contains(dx, dy)) {
                    found = elem;
                    break;
                }
            }

            if (found != null) {
                selectedElement = found;
                isDragging = true;
                dragOffsetX = dx - selectedElement.x;
                dragOffsetY = dy - selectedElement.y;
            } else {
                selectedElement = null;
                isDragging = false;
            }
            invalidate();
            return;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            if (isDragging && selectedElement != null) {
                selectedElement.x = dx - dragOffsetX;
                selectedElement.y = dy - dragOffsetY;

                // Clamp positions to design screen boundaries
                selectedElement.x = Math.max(60f, Math.min(1860f, selectedElement.x));
                selectedElement.y = Math.max(90f, Math.min(1020f, selectedElement.y));
                invalidate();
            }
            return;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            isDragging = false;
            invalidate();
        }
    }

    private void showSettingsDialog() {
        post(() -> {
            String[] options = {
                    "✏️ Edit Controller Layout",
                    "🌐 Change PC IP Address (" + pcIp + ")",
                    "⚡ Reconnect / Reset Network",
                    "🔄 Reset Layout to Default"
            };
            new AlertDialog.Builder(getContext())
                    .setTitle("FluxStick Options")
                    .setItems(options, (dialog, which) -> {
                        if (which == 0) {
                            isEditMode = true;
                            selectedElement = null;
                            invalidate();
                        } else if (which == 1) {
                            showIpDialog();
                        } else if (which == 2) {
                            startNetwork();
                            Toast.makeText(getContext(), "Reconnecting to " + pcIp + "...", Toast.LENGTH_SHORT).show();
                            invalidate();
                        } else if (which == 3) {
                            resetLayout();
                            Toast.makeText(getContext(), "Layout reset to default", Toast.LENGTH_SHORT).show();
                            invalidate();
                        }
                    })
                    .setNegativeButton("Close", null)
                    .show();
        });
    }

    private void showIpDialog() {
        final EditText input = new EditText(getContext());
        input.setText(pcIp);
        input.setSelection(pcIp.length());
        new AlertDialog.Builder(getContext())
                .setTitle("PC IP Address")
                .setMessage("Enter the IP address of your PC running FluxStick Server:")
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    String newIp = input.getText().toString().trim();
                    if (!newIp.isEmpty()) {
                        pcIp = newIp;
                        getContext().getSharedPreferences("fluxstick_prefs", Context.MODE_PRIVATE)
                                .edit().putString("pc_ip", pcIp).apply();
                        startNetwork();
                        Toast.makeText(getContext(), "IP updated to " + pcIp, Toast.LENGTH_SHORT).show();
                        invalidate();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
