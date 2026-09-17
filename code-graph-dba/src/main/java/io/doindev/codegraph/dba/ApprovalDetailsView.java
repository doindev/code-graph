package io.doindev.codegraph.dba;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicArrowButton;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;

/** Prompt-local styling and text zoom; never modifies the approval request or global look and feel. */
final class ApprovalDetailsView extends JPanel {
    static final int DEFAULT_SIZE = 14, MIN_SIZE = 10, MAX_SIZE = 28;
    static final Color BACKGROUND = new Color(32, 39, 53);
    static final Color TRACK = new Color(24, 30, 41);
    static final Color THUMB = new Color(73, 88, 111);
    static final Color HOVER = new Color(105, 127, 157);
    static final Color DRAG = new Color(132, 163, 199);
    private final JEditorPane details = new JEditorPane();
    private final JLabel zoom = new JLabel();
    private int fontSize = DEFAULT_SIZE;

    ApprovalDetailsView(String html) {
        super(new BorderLayout(0, 6));
        setOpaque(false);
        HTMLEditorKit kit = new HTMLEditorKit();
        // Use an editor-local stylesheet: the kit's default stylesheet is shared by Swing.
        StyleSheet styles = new StyleSheet();
        styles.addStyleSheet(kit.getStyleSheet());
        styles.addRule("pre { font-size: 100%; }");
        kit.setStyleSheet(styles);
        details.setEditorKit(kit);
        details.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        details.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, DEFAULT_SIZE));
        details.setText(html);
        details.setEditable(false);
        details.setCaretPosition(0);
        details.setBackground(BACKGROUND);
        details.setForeground(new Color(232, 237, 245));
        details.setSelectionColor(new Color(62, 87, 122));
        details.setSelectedTextColor(Color.WHITE);
        details.getAccessibleContext().setAccessibleName("Exact request details");
        details.getAccessibleContext().setAccessibleDescription("Read-only request. Control plus or minus changes text size; Control zero resets it.");
        JScrollPane scroll = new JScrollPane(details);
        scroll.setBackground(TRACK);
        scroll.getViewport().setBackground(BACKGROUND);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(64, 76, 96)));
        for (JScrollBar bar : new JScrollBar[]{scroll.getVerticalScrollBar(), scroll.getHorizontalScrollBar()}) {
            bar.setUI(new DarkScrollBarUI());
            bar.setBackground(TRACK);
            bar.setForeground(THUMB);
        }
        for (String corner : new String[]{JScrollPane.LOWER_RIGHT_CORNER, JScrollPane.LOWER_LEFT_CORNER,
                JScrollPane.UPPER_RIGHT_CORNER, JScrollPane.UPPER_LEFT_CORNER}) {
            JPanel fill = new JPanel();
            fill.setBackground(TRACK);
            scroll.setCorner(corner, fill);
        }
        zoom.setForeground(new Color(185, 198, 216));
        zoom.setHorizontalAlignment(SwingConstants.RIGHT);
        zoom.getAccessibleContext().setAccessibleName("Request text zoom");
        updateZoomLabel();
        add(zoom, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    void installShortcuts(JRootPane root) {
        bind(root, "approval-zoom-in", () -> setTextSize(fontSize + 2),
                KeyEvent.VK_PLUS, KeyEvent.VK_EQUALS, KeyEvent.VK_ADD);
        bind(root, "approval-zoom-out", () -> setTextSize(fontSize - 2),
                KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT);
        bind(root, "approval-zoom-reset", () -> setTextSize(DEFAULT_SIZE),
                KeyEvent.VK_0, KeyEvent.VK_NUMPAD0);
    }

    private void bind(JRootPane root, String name, Runnable operation, int... keys) {
        root.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { operation.run(); }
        });
        for (int key : keys) {
            root.getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key, InputEvent.CTRL_DOWN_MASK), name);
            // '+' commonly arrives as Shift+'='; also handle keyboard layouts needing Shift for digits.
            root.getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key,
                    InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), name);
        }
    }

    private void setTextSize(int requested) {
        int next = Math.max(MIN_SIZE, Math.min(MAX_SIZE, requested));
        if (fontSize == next) return;
        fontSize = next;
        // HONOR_DISPLAY_PROPERTIES updates HTML body and relative preformatted text together,
        // without replacing the document, losing its selection, or changing its exact contents.
        details.setFont(details.getFont().deriveFont((float) fontSize));
        updateZoomLabel();
        revalidate();
        repaint();
    }

    private void updateZoomLabel() {
        zoom.setText(Math.round(fontSize * 100f / DEFAULT_SIZE) + "% · Ctrl + / − · Ctrl 0 to reset");
    }

    static final class DarkScrollBarUI extends BasicScrollBarUI {
        @Override protected void configureScrollBarColors() {
            trackColor = TRACK;
            thumbColor = THUMB;
            thumbDarkShadowColor = THUMB;
            thumbHighlightColor = THUMB;
            thumbLightShadowColor = THUMB;
        }
        @Override protected Dimension getMinimumThumbSize() { return new Dimension(24, 24); }
        @Override protected JButton createDecreaseButton(int orientation) { return arrow(orientation, "Scroll backward"); }
        @Override protected JButton createIncreaseButton(int orientation) { return arrow(orientation, "Scroll forward"); }
        private JButton arrow(int orientation, String label) {
            JButton button = new BasicArrowButton(orientation, TRACK, TRACK, HOVER, TRACK);
            button.setBorder(new EmptyBorder(0, 0, 0, 0));
            button.getAccessibleContext().setAccessibleName(label);
            return button;
        }
        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle bounds) {
            g.setColor(TRACK);
            g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }
        @Override protected void paintThumb(Graphics g, JComponent c, Rectangle bounds) {
            if (bounds.isEmpty() || !scrollbar.isEnabled()) return;
            Graphics2D paint = (Graphics2D) g.create();
            try {
                paint.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                paint.setColor(isDragging ? DRAG : isThumbRollover() ? HOVER : THUMB);
                paint.fillRoundRect(bounds.x + 2, bounds.y + 2, bounds.width - 4, bounds.height - 4, 8, 8);
            } finally { paint.dispose(); }
        }
    }
}
