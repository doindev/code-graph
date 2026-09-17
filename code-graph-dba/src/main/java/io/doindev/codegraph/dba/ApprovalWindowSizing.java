package io.doindev.codegraph.dba;

import java.awt.*;
import javax.swing.*;

/** JDialog has native resizing but no platform-independent maximize operation. */
final class ApprovalWindowSizing {
    private ApprovalWindowSizing() {}

    static JButton control(JDialog dialog) {
        JButton button = ApprovalTitleBar.iconButton(ApprovalTitleBar.WindowIcon.MAXIMIZE, false);
        button.setName("approval-window-size");
        button.setToolTipText("Maximize approval window");
        button.getAccessibleContext().setAccessibleName("Maximize approval window");
        Rectangle[] restore = new Rectangle[1];
        button.addActionListener(e -> {
            GraphicsConfiguration screen = dialog.getGraphicsConfiguration();
            Rectangle available = usableBounds(screen.getBounds(), Toolkit.getDefaultToolkit().getScreenInsets(screen));
            if (restore[0] == null) {
                restore[0] = dialog.getBounds();
                dialog.setBounds(available);
                button.setIcon(ApprovalTitleBar.WindowIcon.RESTORE);
                button.setToolTipText("Restore approval window");
                button.getAccessibleContext().setAccessibleName("Restore approval window");
                dialog.getRootPane().putClientProperty("approval.maximized", Boolean.TRUE);
            } else {
                // If the dialog moved or a display was detached, restore within the current monitor.
                dialog.setBounds(fit(restore[0], available));
                restore[0] = null;
                button.setIcon(ApprovalTitleBar.WindowIcon.MAXIMIZE);
                button.setToolTipText("Maximize approval window");
                button.getAccessibleContext().setAccessibleName("Maximize approval window");
                dialog.getRootPane().putClientProperty("approval.maximized", Boolean.FALSE);
            }
        });
        return button;
    }

    static boolean maximized(JDialog dialog) {
        return Boolean.TRUE.equals(dialog.getRootPane().getClientProperty("approval.maximized"));
    }

    static Rectangle usableBounds(Rectangle screen, Insets insets) {
        return new Rectangle(screen.x + insets.left, screen.y + insets.top,
                Math.max(1, screen.width - insets.left - insets.right),
                Math.max(1, screen.height - insets.top - insets.bottom));
    }

    static Rectangle fit(Rectangle desired, Rectangle available) {
        int width = Math.min(desired.width, available.width), height = Math.min(desired.height, available.height);
        return new Rectangle(Math.max(available.x, Math.min(desired.x, available.x + available.width - width)),
                Math.max(available.y, Math.min(desired.y, available.y + available.height - height)), width, height);
    }
}
