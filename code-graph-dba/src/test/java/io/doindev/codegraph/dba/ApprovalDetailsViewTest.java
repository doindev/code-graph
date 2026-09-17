package io.doindev.codegraph.dba;

import org.junit.jupiter.api.Test;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import javax.swing.*;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalDetailsViewTest {
    static <T extends Component> T child(Container parent, Class<T> type) {
        return DesktopApprovalsTest.components(parent).stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
    }
    static void press(JRootPane root, int key, int modifiers) {
        Object command = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(key, modifiers));
        assertNotNull(command, "Missing zoom shortcut");
        root.getActionMap().get(command).actionPerformed(new ActionEvent(root, 0, command.toString()));
    }
    static ApprovalDetailsView viewer() {
        return new ApprovalDetailsView(ApprovalPresentation.html(Profiles.JSON.createObjectNode()
                .put("sql", "SELECT '<literal & text>' AS value;\n".repeat(50))));
    }

    @Test void zoomScalesHtmlAndPreformattedTextWithoutChangingTheRequestOrSelection() throws Exception {
        DesktopApprovalsTest.onEdt(() -> {
            ApprovalDetailsView view = viewer();
            JRootPane root = new JRootPane(); root.setContentPane(view); view.installShortcuts(root);
            JEditorPane editor = child(view, JEditorPane.class);
            editor.setSize(600, 4000);
            var document = editor.getDocument(); String text = document.getText(0, document.getLength());
            int sqlOffset = text.indexOf("SELECT");
            editor.select(sqlOffset, sqlOffset + 6);
            double initialLine = editor.modelToView2D(sqlOffset).getHeight();
            int initialHeight = editor.getPreferredSize().height;
            press(root, KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
            assertEquals(16, editor.getFont().getSize());
            assertTrue(editor.getPreferredSize().height > initialHeight, "HTML must actually render larger, not just report a font change");
            assertTrue(editor.modelToView2D(sqlOffset).getHeight() > initialLine, "SQL preformatted text must scale too");
            assertSame(document, editor.getDocument()); assertEquals(text, document.getText(0, document.getLength()));
            assertEquals("SELECT", editor.getSelectedText()); assertFalse(editor.isEditable());
            press(root, KeyEvent.VK_0, InputEvent.CTRL_DOWN_MASK);
            assertEquals(14, editor.getFont().getSize());
            assertEquals(initialLine, editor.modelToView2D(sqlOffset).getHeight());
            assertEquals(initialHeight, editor.getPreferredSize().height);
            return null;
        });
    }

    @Test void allKeyboardVariantsHaveBoundsAndEachPromptResetsIndependently() throws Exception {
        DesktopApprovalsTest.onEdt(() -> {
            ApprovalDetailsView view = viewer(); JRootPane root = new JRootPane(); view.installShortcuts(root);
            JEditorPane editor = child(view, JEditorPane.class);
            for (int key : new int[]{KeyEvent.VK_PLUS, KeyEvent.VK_EQUALS, KeyEvent.VK_ADD}) {
                press(root, KeyEvent.VK_0, InputEvent.CTRL_DOWN_MASK);
                press(root, key, InputEvent.CTRL_DOWN_MASK); assertEquals(16, editor.getFont().getSize());
            }
            for (int n = 0; n < 30; n++) press(root, KeyEvent.VK_ADD, InputEvent.CTRL_DOWN_MASK);
            assertEquals(28, editor.getFont().getSize());
            for (int n = 0; n < 30; n++) press(root, KeyEvent.VK_SUBTRACT, InputEvent.CTRL_DOWN_MASK);
            assertEquals(10, editor.getFont().getSize());
            press(root, KeyEvent.VK_NUMPAD0, InputEvent.CTRL_DOWN_MASK); assertEquals(14, editor.getFont().getSize());
            press(root, KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK); assertEquals(12, editor.getFont().getSize());
            assertEquals(14, child(viewer(), JEditorPane.class).getFont().getSize());
            return null;
        });
    }

    @Test void darkScrollbarsPaintHoverAndDragForBothOrientationsAndStillScroll() throws Exception {
        DesktopApprovalsTest.onEdt(() -> {
            JScrollPane scroll = child(viewer(), JScrollPane.class);
            for (JScrollBar bar : new JScrollBar[]{scroll.getVerticalScrollBar(), scroll.getHorizontalScrollBar()}) {
                assertInstanceOf(ApprovalDetailsView.DarkScrollBarUI.class, bar.getUI());
                boolean vertical = bar.getOrientation() == JScrollBar.VERTICAL;
                bar.setSize(vertical ? 16 : 240, vertical ? 240 : 16);
                bar.setValues(30, 15, 0, 100); bar.doLayout();
                BufferedImage initial = paint(bar);
                Point thumb = find(initial, ApprovalDetailsView.THUMB); assertNotNull(thumb);
                assertNotNull(find(initial, ApprovalDetailsView.TRACK));
                mouse(bar, MouseEvent.MOUSE_MOVED, thumb, MouseEvent.NOBUTTON, 0);
                assertNotNull(find(paint(bar), ApprovalDetailsView.HOVER));
                mouse(bar, MouseEvent.MOUSE_PRESSED, thumb, MouseEvent.BUTTON1, InputEvent.BUTTON1_DOWN_MASK);
                assertNotNull(find(paint(bar), ApprovalDetailsView.DRAG));
                Point moved = new Point(thumb.x + (vertical ? 0 : 30), thumb.y + (vertical ? 30 : 0));
                mouse(bar, MouseEvent.MOUSE_DRAGGED, moved, MouseEvent.NOBUTTON, InputEvent.BUTTON1_DOWN_MASK);
                mouse(bar, MouseEvent.MOUSE_RELEASED, moved, MouseEvent.BUTTON1, 0);
                assertTrue(bar.getValue() > 30, "Dark thumb must retain drag scrolling");
            }
            assertEquals(ApprovalDetailsView.TRACK, scroll.getCorner(JScrollPane.LOWER_RIGHT_CORNER).getBackground());
            return null;
        });
    }

    private static BufferedImage paint(JComponent component) {
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); try { component.paint(g); } finally { g.dispose(); } return image;
    }
    private static Point find(BufferedImage image, Color color) {
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
            if (image.getRGB(x, y) == color.getRGB()) return new Point(x, y);
        return null;
    }
    private static void mouse(Component target, int event, Point point, int button, int modifiers) {
        target.dispatchEvent(new MouseEvent(target, event, System.currentTimeMillis(), modifiers, point.x, point.y, 1, false, button));
    }

    @Test void sizingRespectsTaskbarsNegativeMonitorCoordinatesAndRemovedDisplays() {
        Rectangle monitor = new Rectangle(-1920, -300, 1920, 1080);
        Rectangle usable = ApprovalWindowSizing.usableBounds(monitor, new Insets(28, 0, 48, 0));
        assertEquals(new Rectangle(-1920, -272, 1920, 1004), usable);
        Rectangle original = new Rectangle(-1800, -100, 820, 680);
        assertEquals(original, ApprovalWindowSizing.fit(original, usable));
        Rectangle other = new Rectangle(0, 0, 1280, 720);
        assertEquals(new Rectangle(0, 0, 820, 680), ApprovalWindowSizing.fit(original, other));
        assertEquals(other, ApprovalWindowSizing.fit(new Rectangle(-1920, -300, 1920, 1080), other));
        assertEquals(monitor, new Rectangle(-1920, -300, 1920, 1080), "Helpers must not mutate source bounds");
    }
}
