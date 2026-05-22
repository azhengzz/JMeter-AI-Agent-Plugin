package org.qainsights.jmeter.ai.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.jmeter.gui.plugin.MenuCreator;

import javax.swing.*;

public class AiMenuCreator implements MenuCreator {
    private static final Logger log = LoggerFactory.getLogger(AiMenuCreator.class);
    private volatile AiMenuItem aiMenuItem;

    @Override
    public JMenuItem[] getMenuItemsAtLocation(MENU_LOCATION location) {
        if (location == MENU_LOCATION.RUN) {
            try {
                JMenu parentMenu = new JMenu("AI");
                aiMenuItem = new AiMenuItem(parentMenu);
                return new JMenuItem[] {
                        aiMenuItem
                };
            } catch (Throwable e) {
                log.error("Failed to load validate thread group plugin", e);
                return new JMenuItem[0];
            }

        } else {
            return new JMenuItem[0];
        }
    }

    @Override
    public JMenu[] getTopLevelMenus() {
        return new JMenu[0];
    }

    @Override
    public boolean localeChanged(MenuElement menu) {
        return false;
    }

    @Override
    public void localeChanged() {
        AiMenuItem item = aiMenuItem;
        if (item != null) {
            // JMeterToolBar.localeChanged() does removeAll() + rebuild after this callback,
            // so defer toolbar re-add to run after the rebuild completes
            SwingUtilities.invokeLater(item::readdToolbarIcon);
        }
    }
}
