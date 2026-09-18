package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.*;
import java.awt.event.*;
import java.net.URI;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/** JDK-only consent curtain. No OS elevation, input capture, screenshots, or exclusive fullscreen. */
final class DesktopApprovals implements ApprovalBroker.Desktop {
    private volatile Boolean usable;
    private volatile boolean closed;
    private JFrame backdrop;
    private JDialog card;
    private Timer timer;
    private Timer raiseTimer;
    private Consumer<ApprovalBroker.Decision> decision;
    private JsonNode request;
    private boolean resolving;
    private volatile int waitingCount;
    private final java.util.function.IntUnaryOperator translucencyPolicy;
    DesktopApprovals(){this(level->level);}
    DesktopApprovals(java.util.function.IntUnaryOperator policy){translucencyPolicy=policy;}
    public void updateWaiting(int waiting){waitingCount=waiting;}
    public boolean available(){
        if(closed)return false;if(usable!=null)return usable;
        synchronized(this){if(usable!=null)return usable;
            try{
                if(GraphicsEnvironment.isHeadless())return usable=false;
                FutureTask<Boolean> probe=new FutureTask<>(()->{
                    GraphicsDevice device=GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
                    device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
                    Window w=new Window((Frame)null,device.getDefaultConfiguration());try{w.addNotify();return w.isDisplayable();}finally{w.dispose();}
                });
                EventQueue.invokeLater(probe);usable=probe.get(3,TimeUnit.SECONDS);
            }catch(Throwable e){usable=false;}return usable;
        }
    }
    public boolean browse(URI uri){
        try{if(closed||GraphicsEnvironment.isHeadless()||!Desktop.isDesktopSupported())return false;Desktop d=Desktop.getDesktop();if(!d.isSupported(Desktop.Action.BROWSE))return false;d.browse(uri);return true;}
        catch(Exception|LinkageError e){return false;}
    }
    static Rectangle chooseBounds(Point pointer,List<Rectangle> displays,Rectangle primary){
        if(pointer!=null)for(Rectangle bounds:displays)if(bounds.contains(pointer))return new Rectangle(bounds);
        return new Rectangle(primary);
    }
    static GraphicsConfiguration activeConfiguration(){
        GraphicsEnvironment env=GraphicsEnvironment.getLocalGraphicsEnvironment();GraphicsConfiguration primary=env.getDefaultScreenDevice().getDefaultConfiguration();
        Point point=null;try{PointerInfo info=MouseInfo.getPointerInfo();if(info!=null)point=info.getLocation();}catch(SecurityException ignored){}
        if(point!=null)for(GraphicsDevice d:env.getScreenDevices()){GraphicsConfiguration c=d.getDefaultConfiguration();if(c.getBounds().contains(point))return c;}
        return primary;
    }
    static int translucency(GraphicsDevice device){
        if(device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT))return 2;
        if(device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.TRANSLUCENT))return 1;
        return 0;
    }
    public void show(JsonNode r,int waiting,Consumer<ApprovalBroker.Decision> callback,Runnable detailed){
        waitingCount=waiting;
        if(!available())return;EventQueue.invokeLater(()->{
            if(closed)return;
            try{disposeWindows();request=r;decision=callback;resolving=false;
                GraphicsConfiguration config=activeConfiguration();Rectangle bounds=config.getBounds();
                backdrop=new JFrame("Code Graph approval backdrop",config);backdrop.setUndecorated(true);backdrop.setType(Window.Type.UTILITY);backdrop.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                int alpha=translucencyPolicy.applyAsInt(translucency(config.getDevice()));
                if(alpha==2){backdrop.setBackground(new Color(12,18,28,150));JPanel shade=new JPanel();shade.setOpaque(false);backdrop.setContentPane(shade);}
                else{backdrop.getContentPane().setBackground(new Color(12,18,28));if(alpha==1)backdrop.setOpacity(.60f);}
                backdrop.setBounds(bounds);backdrop.setFocusableWindowState(false);backdrop.setAutoRequestFocus(false);
                // Hiding an owner also hides its owned dialogs. Keep the approval independent
                // so switching applications can dismiss the dimmer without losing the prompt.
                card=new JDialog((Window)null,"Code Graph approval",Dialog.ModalityType.APPLICATION_MODAL,config);
                card.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);card.setResizable(true);card.setFocusCycleRoot(true);
                JPanel body=new JPanel(new BorderLayout(0,14));body.setBackground(new Color(32,39,53));body.setBorder(new EmptyBorder(14,20,16,20));ApprovalTitleBar.install(card,body);
                JPanel heading=new JPanel(new BorderLayout(10,0));heading.setOpaque(false);
                JLabel icon=new JLabel("◇");icon.setForeground(new Color(99,179,237));icon.setFont(icon.getFont().deriveFont(34f));icon.getAccessibleContext().setAccessibleName("Code Graph application");heading.add(icon,BorderLayout.WEST);
                JLabel title=new JLabel("Code Graph · Approval required");title.setForeground(Color.WHITE);title.setFont(title.getFont().deriveFont(Font.BOLD,21f));heading.add(title);
                body.add(heading,BorderLayout.NORTH);
                ApprovalDetailsView details=new ApprovalDetailsView(ApprovalPresentation.html(r));details.installShortcuts(card.getRootPane());
                body.add(details,BorderLayout.CENTER);
                JPanel footer=new JPanel();footer.setOpaque(false);footer.setLayout(new BoxLayout(footer,BoxLayout.Y_AXIS));
                boolean mutation=r.path("mutation").asBoolean(!r.path("eligiblePersistentRead").asBoolean());
                JLabel risk=new JLabel(mutation?"This action can change database or application configuration.":"Read-only access requested.");
                risk.setForeground(mutation?new Color(255,183,104):new Color(154,213,187));footer.add(risk);
                JLabel confirmation=new JLabel("Choosing Allow confirms the exact request, target, and displayed risks.");confirmation.setForeground(Color.WHITE);footer.add(confirmation);
                JLabel countdown=new JLabel();countdown.setForeground(new Color(185,198,216));footer.add(countdown);
                JLabel limitation=new JLabel("Application consent · No operating-system administrator privileges");limitation.setForeground(new Color(185,198,216));footer.add(limitation);
                JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT));actions.setOpaque(false);
                JButton deny=new JButton("Deny");deny.addActionListener(e->resolve("reject",false));actions.add(deny);
                if(ApprovalPresentation.complex(r)){
                    JButton review=new JButton("Open detailed review in browser");review.addActionListener(e->{review.setEnabled(false);detailed.run();});actions.add(review);
                }else{
                    JButton once=new JButton("Allow once");once.addActionListener(e->resolve("approve_once",true));once.getAccessibleContext().setAccessibleDescription("Approve this exact request once without creating a permission");actions.add(once);
                    if(r.path("approvalChoices").isArray()){
                        JButton arrow=new JButton("▾");arrow.setToolTipText("Reusable approval choices");arrow.getAccessibleContext().setAccessibleName("Reusable approval choices");
                        JPopupMenu menu=new JPopupMenu();menu.setBackground(new Color(32,39,53));menu.setForeground(Color.WHITE);
                        for(JsonNode choice:r.path("approvalChoices")){
                            JMenuItem item=new JMenuItem(choice.path("label").asText());item.setBackground(new Color(32,39,53));item.setForeground(Color.WHITE);
                            item.setEnabled(choice.path("enabled").asBoolean());item.setToolTipText(choice.path("reason").asText()+" · "+choice.path("lifetime").asText());
                            item.getAccessibleContext().setAccessibleDescription(item.getToolTipText());item.addActionListener(e->resolve(choice.path("action").asText(),true));menu.add(item);
                        }
                        arrow.addActionListener(e->menu.show(arrow,Math.min(0,arrow.getWidth()-menu.getPreferredSize().width),arrow.getHeight()));actions.add(arrow);
                    }else if(r.path("eligiblePersistentRead").asBoolean()){
                        JComboBox<String> choices=new JComboBox<>(new String[]{"Always allow this read on this target"});
                        JButton persistent=new JButton("Allow selected read access");
                        persistent.addActionListener(e->resolve(r.has("projectId")?"always_binding_read":"always_connection_read",true));
                        actions.add(choices);actions.add(persistent);
                    }
                }
                footer.add(actions);body.add(footer,BorderLayout.SOUTH);
                card.getRootPane().registerKeyboardAction(e->resolve("reject",false),KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),JComponent.WHEN_IN_FOCUSED_WINDOW);
                WindowAdapter close=new WindowAdapter(){public void windowClosing(WindowEvent e){resolve("reject",false);}};card.addWindowListener(close);backdrop.addWindowListener(close);
                // Switching apps hides the input-blocking backdrop. Returning to the card restores it without stealing focus.
                card.addWindowFocusListener(new WindowAdapter(){
                    public void windowLostFocus(WindowEvent e){if(e.getWindow()==card&&backdrop!=null){releaseInitialRaise();backdrop.setVisible(false);}}
                    public void windowGainedFocus(WindowEvent e){JDialog current=card;JFrame shade=backdrop;if(e.getWindow()==current&&current!=null&&shade!=null&&!resolving){shade.setVisible(true);if(card==current&&current.isDisplayable())current.toFront();}}
                });
                int width=Math.min(820,Math.max(320,bounds.width-64)),height=Math.min(680,Math.max(240,bounds.height-80));
                card.setSize(width,height);card.setMinimumSize(new Dimension(Math.min(440,width),Math.min(300,height)));
                card.setLocation(bounds.x+(bounds.width-width)/2,bounds.y+(bounds.height-height)/2);
                long expires=r.path("expiresAt").asLong();
                timer=new Timer(1000,e->{try{long seconds=Math.max(0,(expires-System.currentTimeMillis()+999)/1000);countdown.setText(seconds+" seconds remaining · "+waitingCount+" request"+(waitingCount==1?"":"s")+" waiting");
                    if(seconds==0){disposeWindows();return;}
                    if(card!=null){boolean present=java.util.Arrays.stream(GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()).anyMatch(d->d.getDefaultConfiguration().getBounds().equals(bounds));
                        if(!present){Rectangle b=activeConfiguration().getBounds();backdrop.setBounds(b);card.setLocation(b.x+Math.max(0,(b.width-card.getWidth())/2),b.y+Math.max(0,(b.height-card.getHeight())/2));}}
                }catch(Throwable displayFailure){usable=false;disposeWindows();}
                });timer.setInitialDelay(0);timer.start();
                JDialog shown=card;JFrame shade=backdrop;
                // Raise once, briefly: background-launched Windows processes may not acquire
                // focus with toFront alone. Never retain topmost status after an app switch.
                if(shade.isAlwaysOnTopSupported())shade.setAlwaysOnTop(true);
                if(shown.isAlwaysOnTopSupported())shown.setAlwaysOnTop(true);
                raiseTimer=new Timer(600,e->{
                    releaseInitialRaise();
                    if(card==shown&&!shown.isFocused()&&backdrop==shade)shade.setVisible(false);
                });raiseTimer.setRepeats(false);raiseTimer.start();
                shade.setVisible(true);EventQueue.invokeLater(()->{if(card==shown&&shown.isShowing()){shown.toFront();shown.requestFocus();deny.requestFocusInWindow();}});shown.setVisible(true);
            }catch(Throwable failure){usable=false;disposeWindows();}
        });
    }
    private void releaseInitialRaise(){
        if(raiseTimer!=null){raiseTimer.stop();raiseTimer=null;}
        if(backdrop!=null&&backdrop.isAlwaysOnTop())backdrop.setAlwaysOnTop(false);
        if(card!=null&&card.isAlwaysOnTop())card.setAlwaysOnTop(false);
    }
    private void resolve(String action,boolean acknowledged){if(resolving)return;resolving=true;Consumer<ApprovalBroker.Decision> callback=decision;disposeWindows();if(callback!=null)callback.accept(new ApprovalBroker.Decision(action,acknowledged));}
    public void dismiss(){if(Boolean.TRUE.equals(usable)||card!=null||backdrop!=null)EventQueue.invokeLater(this::disposeWindows);}
    private void disposeWindows(){if(raiseTimer!=null){raiseTimer.stop();raiseTimer=null;}if(timer!=null){timer.stop();timer=null;}if(card!=null){JDialog old=card;card=null;old.dispose();}if(backdrop!=null){JFrame old=backdrop;backdrop=null;old.dispose();}request=null;decision=null;}
    public void handoff(URI uri,String code){EventQueue.invokeLater(()->{
        JTextArea text=new JTextArea("Open this local page in your browser:\n"+uri+"\n\nPairing code: "+code+"\n\nThe code expires with this approval.");text.setEditable(false);text.setLineWrap(true);text.setWrapStyleWord(true);text.setColumns(48);text.setRows(7);
        JOptionPane.showMessageDialog(null,text,"Code Graph detailed approval",JOptionPane.INFORMATION_MESSAGE);
    });}
    public void close(){closed=true;dismiss();}
}
