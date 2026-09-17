package io.doindev.codegraph.dba;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalTitleBarTest {
    @Test void eightResizeDirectionsKeepTheOppositeEdgeAndRespectMinimumSize() {
        Rectangle start=new Rectangle(-1000,30,820,680);
        Dimension minimum=new Dimension(440,300);
        for(int edges:new int[]{1,2,4,8,3,6,9,12}) {
            Rectangle resized=ApprovalTitleBar.resizeBounds(start,minimum,edges,10000,10000);
            assertTrue(resized.width>=440);assertTrue(resized.height>=300);
            if((edges&ApprovalTitleBar.WEST)!=0)assertEquals(start.x+start.width,resized.x+resized.width);
            else assertEquals(start.x,resized.x);
            if((edges&ApprovalTitleBar.NORTH)!=0)assertEquals(start.y+start.height,resized.y+resized.height);
            else assertEquals(start.y,resized.y);
            resized=ApprovalTitleBar.resizeBounds(start,minimum,edges,-10000,-10000);
            assertTrue(resized.width>=440);assertTrue(resized.height>=300);
        }
        assertEquals(new Rectangle(-1000,30,820,680),start);
    }

    @Test void iconOnlyButtonsStayCompactAndPaintDarkWithDistinctStateIcons()throws Exception {
        DesktopApprovalsTest.onEdt(()->{
            JButton button=ApprovalTitleBar.iconButton(ApprovalTitleBar.WindowIcon.MAXIMIZE,false);
            assertEquals("",button.getText());assertEquals(new Dimension(30,28),button.getPreferredSize());
            assertEquals(16,button.getIcon().getIconWidth());assertEquals(16,button.getIcon().getIconHeight());
            button.setSize(button.getPreferredSize());
            BufferedImage normal=paint(button);assertEquals(ApprovalTitleBar.BACKGROUND.getRGB(),normal.getRGB(2,2));
            button.getModel().setRollover(true);assertNotEquals(normal.getRGB(2,2),paint(button).getRGB(2,2));
            button.getModel().setRollover(false);button.setIcon(ApprovalTitleBar.WindowIcon.RESTORE);
            BufferedImage restore=paint(button);boolean different=false;
            for(int y=0;y<28;y++)for(int x=0;x<30;x++)if(normal.getRGB(x,y)!=restore.getRGB(x,y))different=true;
            assertTrue(different,"Restore must show overlapping squares instead of the maximize square");
            return null;
        });
    }
    private static BufferedImage paint(JComponent component) {
        BufferedImage image=new BufferedImage(component.getWidth(),component.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics=image.createGraphics();try{component.paint(graphics);}finally{graphics.dispose();}return image;
    }
}
