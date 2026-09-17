package io.doindev.codegraph.dba;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

/** Window-local, JDK-only dark decorations. No OS settings or global Swing theme changes. */
final class ApprovalTitleBar {
    static final Color BACKGROUND = new Color(24, 30, 41);
    static final Color FOREGROUND = new Color(232, 237, 245);
    static final int GRIP = 5;
    static final int NORTH = 1, EAST = 2, SOUTH = 4, WEST = 8;

    private ApprovalTitleBar() {}

    static void install(JDialog dialog, JComponent body) {
        dialog.setUndecorated(true);
        JPanel chrome = panel(new BorderLayout());
        chrome.setBorder(BorderFactory.createLineBorder(new Color(64, 76, 96)));
        JPanel center = panel(new BorderLayout());
        JPanel bar = panel(new BorderLayout(8, 0));
        bar.setName("approval-title-bar");
        bar.setPreferredSize(new Dimension(200, 30));
        bar.getAccessibleContext().setAccessibleName("Approval window title bar");
        JLabel title = new JLabel(dialog.getTitle());
        title.setForeground(FOREGROUND);
        title.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        title.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        bar.add(title, BorderLayout.CENTER);
        JPanel controls = panel(new FlowLayout(FlowLayout.RIGHT, 2, 1));
        JButton size = ApprovalWindowSizing.control(dialog);
        JButton close = iconButton(WindowIcon.CLOSE, true);
        close.setName("approval-window-close");
        close.setToolTipText("Close and deny request");
        close.getAccessibleContext().setAccessibleName("Close and deny request");
        close.addActionListener(e -> dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING)));
        controls.add(size);
        controls.add(close);
        bar.add(controls, BorderLayout.EAST);
        MouseAdapter drag = new MouseAdapter() {
            private Point pointer;
            private Point origin;
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && !ApprovalWindowSizing.maximized(dialog)) {
                    pointer = e.getLocationOnScreen(); origin = dialog.getLocation();
                }
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (pointer != null && !ApprovalWindowSizing.maximized(dialog))
                    dialog.setLocation(origin.x + e.getXOnScreen() - pointer.x, origin.y + e.getYOnScreen() - pointer.y);
            }
            @Override public void mouseReleased(MouseEvent e) { pointer = null; origin = null; }
            @Override public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) size.doClick();
            }
        };
        for (JComponent surface : new JComponent[]{bar, title}) {
            surface.addMouseListener(drag); surface.addMouseMotionListener(drag);
        }
        center.add(bar, BorderLayout.NORTH);
        center.add(body, BorderLayout.CENTER);
        chrome.add(center, BorderLayout.CENTER);
        chrome.add(edgeRow(dialog, NORTH), BorderLayout.NORTH);
        chrome.add(edgeRow(dialog, SOUTH), BorderLayout.SOUTH);
        chrome.add(grip(dialog, WEST), BorderLayout.WEST);
        chrome.add(grip(dialog, EAST), BorderLayout.EAST);
        dialog.setContentPane(chrome);
        dialog.getRootPane().registerKeyboardAction(e -> close.doClick(),
                KeyStroke.getKeyStroke(KeyEvent.VK_F4, InputEvent.ALT_DOWN_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private static JPanel panel(LayoutManager layout) {
        JPanel panel = new JPanel(layout); panel.setBackground(BACKGROUND); return panel;
    }

    private static JPanel edgeRow(JDialog dialog, int vertical) {
        JPanel row = panel(new BorderLayout());
        row.add(grip(dialog, vertical | WEST), BorderLayout.WEST);
        row.add(grip(dialog, vertical), BorderLayout.CENTER);
        row.add(grip(dialog, vertical | EAST), BorderLayout.EAST);
        return row;
    }

    private static JPanel grip(JDialog dialog, int edges) {
        JPanel grip = panel(new BorderLayout());
        grip.setName("approval-resize-" + edges);
        grip.setPreferredSize(new Dimension(GRIP, GRIP));
        int cursor = switch (edges) {
            case NORTH -> Cursor.N_RESIZE_CURSOR; case SOUTH -> Cursor.S_RESIZE_CURSOR;
            case EAST -> Cursor.E_RESIZE_CURSOR; case WEST -> Cursor.W_RESIZE_CURSOR;
            case NORTH | WEST -> Cursor.NW_RESIZE_CURSOR; case NORTH | EAST -> Cursor.NE_RESIZE_CURSOR;
            case SOUTH | WEST -> Cursor.SW_RESIZE_CURSOR; default -> Cursor.SE_RESIZE_CURSOR;
        };
        grip.setCursor(Cursor.getPredefinedCursor(cursor));
        dialog.getRootPane().addPropertyChangeListener("approval.maximized", e ->
                grip.setCursor(Cursor.getPredefinedCursor(ApprovalWindowSizing.maximized(dialog) ? Cursor.DEFAULT_CURSOR : cursor)));
        MouseAdapter resize = new MouseAdapter() {
            private Rectangle start;
            private Point pointer;
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && dialog.isResizable() && !ApprovalWindowSizing.maximized(dialog)) {
                    start = dialog.getBounds(); pointer = e.getLocationOnScreen();
                }
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (start != null && dialog.isResizable() && !ApprovalWindowSizing.maximized(dialog))
                    dialog.setBounds(resizeBounds(start, dialog.getMinimumSize(), edges,
                            e.getXOnScreen() - pointer.x, e.getYOnScreen() - pointer.y));
            }
            @Override public void mouseReleased(MouseEvent e) { start = null; pointer = null; }
        };
        grip.addMouseListener(resize); grip.addMouseMotionListener(resize);
        return grip;
    }

    static Rectangle resizeBounds(Rectangle start, Dimension minimum, int edges, int dx, int dy) {
        Rectangle result = new Rectangle(start);
        if ((edges & EAST) != 0) result.width = Math.max(minimum.width, start.width + dx);
        if ((edges & WEST) != 0) {
            result.width = Math.max(minimum.width, start.width - dx);
            result.x = start.x + start.width - result.width;
        }
        if ((edges & SOUTH) != 0) result.height = Math.max(minimum.height, start.height + dy);
        if ((edges & NORTH) != 0) {
            result.height = Math.max(minimum.height, start.height - dy);
            result.y = start.y + start.height - result.height;
        }
        return result;
    }

    static JButton iconButton(Icon icon, boolean close) {
        JButton button = new JButton("") {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D paint = (Graphics2D) g.create();
                try {
                    paint.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    Color background = close && (getModel().isRollover() || getModel().isPressed())
                            ? new Color(177, 47, 57) : getModel().isPressed() ? new Color(66, 87, 116)
                            : getModel().isRollover() ? new Color(54, 67, 86) : BACKGROUND;
                    paint.setColor(background); paint.fillRoundRect(0, 0, getWidth(), getHeight(), 4, 4);
                    Icon current = getIcon();
                    current.paintIcon(this, paint, (getWidth() - current.getIconWidth()) / 2, (getHeight() - current.getIconHeight()) / 2);
                    if (isFocusOwner()) {
                        paint.setColor(new Color(99, 179, 237)); paint.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 4, 4);
                    }
                } finally { paint.dispose(); }
            }
        };
        button.setIcon(icon);
        button.setForeground(FOREGROUND);
        button.setPreferredSize(new Dimension(30, 28));
        button.setMinimumSize(new Dimension(30, 28));
        button.setMaximumSize(new Dimension(30, 28));
        button.setBorder(BorderFactory.createEmptyBorder());
        button.setContentAreaFilled(false); button.setFocusPainted(false); button.setRolloverEnabled(true);
        return button;
    }

    enum WindowIcon implements Icon {
        MAXIMIZE, RESTORE, CLOSE;
        public int getIconWidth() { return 16; }
        public int getIconHeight() { return 16; }
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D paint = (Graphics2D) g.create();
            try {
                paint.translate(x, y); paint.setColor(c.getForeground()); paint.setStroke(new BasicStroke(1f));
                switch (this) {
                    case MAXIMIZE -> paint.drawRect(3, 3, 10, 10);
                    case RESTORE -> {
                        paint.drawLine(5, 2, 13, 2); paint.drawLine(13, 2, 13, 10); paint.drawLine(12, 10, 13, 10);
                        paint.drawLine(5, 2, 5, 3); paint.drawRect(2, 5, 8, 8);
                    }
                    case CLOSE -> { paint.drawLine(4, 4, 12, 12); paint.drawLine(12, 4, 4, 12); }
                }
            } finally { paint.dispose(); }
        }
    }
}
