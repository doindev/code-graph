package io.doindev.codegraph.dba;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit interactive test: shows synthetic consent only; never talks to a database or agent. */
@EnabledIfEnvironmentVariable(named="DBA_DESKTOP_TEST",matches="true")
class DesktopApprovalsTest {
    DesktopApprovals desktop;
    Thread.UncaughtExceptionHandler previous;
    final java.util.List<Throwable> uncaught=new java.util.concurrent.CopyOnWriteArrayList<>();
    @BeforeEach void start(){previous=Thread.getDefaultUncaughtExceptionHandler();Thread.setDefaultUncaughtExceptionHandler((thread,error)->uncaught.add(error));desktop=new DesktopApprovals();assertTrue(desktop.available(),"A real interactive desktop is required");}
    @AfterEach void close()throws Exception{desktop.close();EventQueue.invokeAndWait(()->{});Thread.setDefaultUncaughtExceptionHandler(previous);assertTrue(uncaught.isEmpty(),uncaught.toString());}
    static <T> T onEdt(java.util.concurrent.Callable<T> task)throws Exception{var result=new AtomicReference<T>();var failure=new AtomicReference<Throwable>();EventQueue.invokeAndWait(()->{try{result.set(task.call());}catch(Throwable e){failure.set(e);}});if(failure.get()!=null)throw new AssertionError(failure.get());return result.get();}
    static JDialog visible(){return Arrays.stream(Window.getWindows()).filter(w->w instanceof JDialog&&w.isShowing()&&"Code Graph approval".equals(((JDialog)w).getTitle())).map(w->(JDialog)w).findFirst().orElse(null);}
    static JFrame backdrop(){return Arrays.stream(Window.getWindows()).filter(w->w instanceof JFrame f&&f.getTitle().equals("Code Graph approval backdrop")&&w.isDisplayable()).map(w->(JFrame)w).findFirst().orElseThrow();}
    static List<Component> components(Container c){List<Component> out=new ArrayList<>();for(Component child:c.getComponents()){out.add(child);if(child instanceof Container nested)out.addAll(components(nested));}return out;}
    JDialog show(String type,boolean read,AtomicReference<ApprovalBroker.Decision> decision)throws Exception{
        var r=Profiles.JSON.createObjectNode().put("id",UUID.randomUUID().toString()).put("type",type).put("agentId","Synthetic desktop test")
            .put("purpose","Verify Code Graph consent controls").put("environment","dev").put("connectionName","Disposable QA target").put("sql","SELECT 1").put("eligiblePersistentRead",read).put("mutation",!read).put("expiresAt",System.currentTimeMillis()+300_000);
        long started=System.nanoTime();desktop.show(r,2,decision::set,()->{});
        JDialog card=null;for(int i=0;i<100;i++){card=onEdt(()->{JDialog visible=visible();return visible!=null&&!visible.isAlwaysOnTop()?visible:null;});if(card!=null)break;Thread.sleep(50);}
        assertNotNull(card,"Overlay did not appear");System.out.println("DESKTOP_FIRST_PROMPT_MS="+(System.nanoTime()-started)/1_000_000);return card;
    }
    @Test void overlayGeometryDirectConfirmationAndEscapeCleanup()throws Exception{
        var decision=new AtomicReference<ApprovalBroker.Decision>();JDialog card=show("live_sql",false,decision);
        onEdt(()->{
            assertEquals(Dialog.ModalityType.APPLICATION_MODAL,card.getModalityType());assertFalse(card.isAlwaysOnTop());
            assertNull(card.getOwner(),"Hiding the dimmer must not hide its approval dialog");
            Rectangle bounds=backdrop().getBounds(),content=card.getBounds();assertTrue(bounds.contains(content));
            assertEquals(bounds.getCenterX(),content.getCenterX(),2);assertEquals(bounds.getCenterY(),content.getCenterY(),2);
            var nodes=components(card);JButton allow=(JButton)nodes.stream().filter(c->c instanceof JButton b&&b.getText().equals("Allow once")).findFirst().orElseThrow();
            assertTrue(allow.isEnabled());assertTrue(nodes.stream().noneMatch(c->c instanceof JButton b&&b.getText().contains("selected read")));
            assertTrue(nodes.stream().noneMatch(c->c instanceof JCheckBox));
            // Clicking the backdrop does not approve or dismiss.
            backdrop().dispatchEvent(new MouseEvent(backdrop(),MouseEvent.MOUSE_CLICKED,System.currentTimeMillis(),0,10,10,1,false));
            assertNull(decision.get());return null;
        });
        Path screenshot=Path.of("target","approval-desktop-card.png");Files.createDirectories(screenshot.getParent());
        Rectangle area=onEdt(card::getBounds);ImageIO.write(new Robot().createScreenCapture(area),"png",screenshot.toFile());
        onEdt(()->{KeyStroke escape=KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0);Object binding=card.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(escape);card.getRootPane().getActionMap().get(binding).actionPerformed(new ActionEvent(card,0,"escape"));return null;});
        assertEquals("reject",decision.get().action());assertFalse(decision.get().acknowledged());assertNull(onEdt(DesktopApprovalsTest::visible));
    }
    @Test void explicitApprovalButtonsAcknowledgeWithoutCheckbox()throws Exception{
        for(boolean persistent:new boolean[]{false,true}){
            var decision=new AtomicReference<ApprovalBroker.Decision>();JDialog card=show("live_sql",persistent,decision);
            onEdt(()->{
                var nodes=components(card);assertTrue(nodes.stream().noneMatch(c->c instanceof JCheckBox));assertNull(decision.get());
                String label=persistent?"Allow selected read access":"Allow once";
                JButton allow=(JButton)nodes.stream().filter(c->c instanceof JButton b&&b.getText().equals(label)).findFirst().orElseThrow();
                assertTrue(allow.isEnabled());allow.doClick();return null;
            });
            assertEquals(persistent?"always_connection_read":"approve_once",decision.get().action());
            assertTrue(decision.get().acknowledged());assertNull(onEdt(DesktopApprovalsTest::visible));
        }
    }
    @Test void requestZoomDarkScrollingAndMaximizeRestoreLeaveDecisionControlsUnchanged()throws Exception{
        var decision=new AtomicReference<ApprovalBroker.Decision>();JDialog card=show("live_sql",false,decision);
        onEdt(()->{
            var nodes=components(card);
            JButton allow=(JButton)nodes.stream().filter(c->c instanceof JButton b&&"Allow once".equals(b.getText())).findFirst().orElseThrow();
            JButton size=(JButton)nodes.stream().filter(c->"approval-window-size".equals(c.getName())).findFirst().orElseThrow();
            JEditorPane details=(JEditorPane)nodes.stream().filter(JEditorPane.class::isInstance).findFirst().orElseThrow();
            Font buttonFont=allow.getFont();String exactText=details.getDocument().getText(0,details.getDocument().getLength());
            ApprovalDetailsViewTest.press(card.getRootPane(),KeyEvent.VK_ADD,InputEvent.CTRL_DOWN_MASK);
            assertEquals(16,details.getFont().getSize());assertEquals(buttonFont,allow.getFont());
            assertEquals(exactText,details.getDocument().getText(0,details.getDocument().getLength()));
            assertTrue(card.isResizable());Rectangle initial=card.getBounds();
            card.setSize(initial.width-40,initial.height-30);Rectangle resized=card.getBounds();
            assertEquals(initial.width-40,resized.width);assertEquals(initial.height-30,resized.height);
            Rectangle available=ApprovalWindowSizing.usableBounds(card.getGraphicsConfiguration().getBounds(),Toolkit.getDefaultToolkit().getScreenInsets(card.getGraphicsConfiguration()));
            assertEquals("",size.getText());assertEquals(new Dimension(30,28),size.getSize());
            assertEquals("Maximize approval window",size.getToolTipText());assertTrue(size.isFocusable());
            size.doClick();assertEquals(ApprovalTitleBar.WindowIcon.RESTORE,size.getIcon());assertEquals(available,card.getBounds());
            assertEquals("Restore approval window",size.getAccessibleContext().getAccessibleName());
            assertFalse(card.isAlwaysOnTop());assertNull(decision.get());
            size.doClick();assertEquals(ApprovalTitleBar.WindowIcon.MAXIMIZE,size.getIcon());assertEquals(resized,card.getBounds());
            assertEquals(16,details.getFont().getSize());
            ApprovalDetailsViewTest.press(card.getRootPane(),KeyEvent.VK_0,InputEvent.CTRL_DOWN_MASK);assertEquals(14,details.getFont().getSize());
            assertTrue(nodes.stream().filter(JScrollBar.class::isInstance).map(JScrollBar.class::cast).allMatch(b->b.getUI() instanceof ApprovalDetailsView.DarkScrollBarUI));
            card.dispatchEvent(new WindowEvent(card,WindowEvent.WINDOW_CLOSING));return null;
        });
        assertEquals("reject",decision.get().action());assertFalse(decision.get().acknowledged());
    }
    @Test void darkTitleBarMovesResizesAndClosesThroughTheSameDenyPath()throws Exception{
        var decision=new AtomicReference<ApprovalBroker.Decision>();JDialog card=show("live_sql",false,decision);
        onEdt(()->{
            assertTrue(card.isUndecorated(),"Do not leave an OS-drawn light title bar above the dark decorations");
            JComponent title=(JComponent)components(card).stream().filter(c->"approval-title-bar".equals(c.getName())).findFirst().orElseThrow();
            assertEquals(ApprovalTitleBar.BACKGROUND,title.getBackground());assertEquals(30,title.getHeight());
            Rectangle initial=card.getBounds();drag(title,20,15);
            assertEquals(initial.x+20,card.getX());assertEquals(initial.y+15,card.getY());
            for(int edge:new int[]{1,2,4,8,3,6,9,12}){
                card.setBounds(initial);card.validate();
                JComponent grip=(JComponent)components(card).stream().filter(c->("approval-resize-"+edge).equals(c.getName())).findFirst().orElseThrow();
                drag(grip,12,10);
                assertEquals(ApprovalTitleBar.resizeBounds(initial,card.getMinimumSize(),edge,12,10),card.getBounds());
                assertNull(decision.get());
            }
            card.setBounds(initial);card.validate();
            Point p=title.getLocationOnScreen();
            title.dispatchEvent(new MouseEvent(title,MouseEvent.MOUSE_CLICKED,System.currentTimeMillis(),0,20,10,p.x+20,p.y+10,2,false,MouseEvent.BUTTON1));
            assertTrue(ApprovalWindowSizing.maximized(card));Rectangle maximized=card.getBounds();
            JComponent grip=(JComponent)components(card).stream().filter(c->"approval-resize-6".equals(c.getName())).findFirst().orElseThrow();
            drag(grip,-100,-100);assertEquals(maximized,card.getBounds(),"Maximized windows must not resize accidentally");
            assertNull(decision.get());
            JButton close=(JButton)components(card).stream().filter(c->"approval-window-close".equals(c.getName())).findFirst().orElseThrow();
            assertEquals("Close and deny request",close.getToolTipText());close.doClick();return null;
        });
        assertEquals("reject",decision.get().action());assertFalse(decision.get().acknowledged());
        assertNull(onEdt(DesktopApprovalsTest::visible));
    }
    @Test void decoratedWindowAltF4StillRejectsWithoutApproval()throws Exception{
        var decision=new AtomicReference<ApprovalBroker.Decision>();JDialog card=show("live_sql",false,decision);
        onEdt(()->{
            ApprovalDetailsViewTest.press(card.getRootPane(),KeyEvent.VK_F4,InputEvent.ALT_DOWN_MASK);return null;
        });
        assertEquals("reject",decision.get().action());assertFalse(decision.get().acknowledged());
    }
    static void drag(JComponent target,int dx,int dy){
        Point start=target.getLocationOnScreen();int x=Math.min(2,target.getWidth()-1),y=Math.min(2,target.getHeight()-1);
        target.dispatchEvent(new MouseEvent(target,MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),InputEvent.BUTTON1_DOWN_MASK,x,y,start.x+x,start.y+y,1,false,MouseEvent.BUTTON1));
        target.dispatchEvent(new MouseEvent(target,MouseEvent.MOUSE_DRAGGED,System.currentTimeMillis(),InputEvent.BUTTON1_DOWN_MASK,x+dx,y+dy,start.x+x+dx,start.y+y+dy,0,false,MouseEvent.NOBUTTON));
        target.dispatchEvent(new MouseEvent(target,MouseEvent.MOUSE_RELEASED,System.currentTimeMillis(),0,x+dx,y+dy,start.x+x+dx,start.y+y+dy,1,false,MouseEvent.BUTTON1));
    }
    @Test void focusLossHidesOnlyBackdropAndReturningRestoresIt()throws Exception{
        var decision=new AtomicReference<ApprovalBroker.Decision>();JDialog card=show("live_sql",false,decision);
        onEdt(()->{
            Window shade=backdrop();assertNull(card.getOwner());
            for(int n=0;n<3;n++){
                WindowEvent lost=new WindowEvent(card,WindowEvent.WINDOW_LOST_FOCUS);
                for(WindowFocusListener listener:card.getWindowFocusListeners())listener.windowLostFocus(lost);
                assertFalse(shade.isVisible(),"The dimmer must not cover another application");
                assertTrue(card.isShowing(),"Losing focus must not hide the approval dialog with its backdrop");
                assertFalse(card.isAlwaysOnTop());assertFalse(shade.isAlwaysOnTop());assertNull(decision.get());
                WindowEvent gained=new WindowEvent(card,WindowEvent.WINDOW_GAINED_FOCUS);
                for(WindowFocusListener listener:card.getWindowFocusListeners())listener.windowGainedFocus(gained);
                assertTrue(shade.isShowing());assertTrue(card.isShowing());assertNull(decision.get());
            }
            card.dispatchEvent(new WindowEvent(card,WindowEvent.WINDOW_CLOSING));return null;
        });
        assertEquals("reject",decision.get().action());assertNull(onEdt(DesktopApprovalsTest::visible));
    }
    @Test void complexReviewHasNoDirectApprovalAndRepeatedWindowsAreReleased()throws Exception{
        int baseline=onEdt(()->(int)Arrays.stream(Window.getWindows()).filter(Window::isDisplayable).count());
        long start=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
        for(int i=0;i<6;i++){
            JDialog card=show("connection_create",false,new AtomicReference<>());
            onEdt(()->{var nodes=components(card);assertTrue(nodes.stream().anyMatch(c->c instanceof JButton b&&b.getText().equals("Open detailed review in browser")));assertFalse(nodes.stream().anyMatch(c->c instanceof JButton b&&b.getText().equals("Allow once")));return null;});
            desktop.dismiss();EventQueue.invokeAndWait(()->{});assertNull(onEdt(DesktopApprovalsTest::visible));
        }
        assertEquals(baseline,onEdt(()->(int)Arrays.stream(Window.getWindows()).filter(Window::isDisplayable).count()));
        System.out.println("DESKTOP_REPEATED_ALLOCATION_DELTA_BYTES="+((Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())-start));
    }
    @Test void fallbackBackdropsWindowCloseAndAccessibleFocusCycle()throws Exception{
        for(int fallback:new int[]{0,1,2}){
            desktop.close();EventQueue.invokeAndWait(()->{});desktop=new DesktopApprovals(level->Math.min(level,fallback));
            var decision=new AtomicReference<ApprovalBroker.Decision>();JDialog card=show("live_sql",true,decision);
            onEdt(()->{
                assertTrue(card.isFocusCycleRoot());assertFalse(backdrop().isAlwaysOnTop());
                var nodes=components(card);assertTrue(nodes.stream().anyMatch(c->c instanceof JEditorPane&&"Exact request details".equals(c.getAccessibleContext().getAccessibleName())));
                assertTrue(nodes.stream().anyMatch(c->c instanceof JButton b&&b.getText().equals("Allow selected read access")));
                card.dispatchEvent(new WindowEvent(card,WindowEvent.WINDOW_CLOSING));return null;
            });
            assertEquals("reject",decision.get().action());assertNull(onEdt(DesktopApprovalsTest::visible));
        }
    }
}
