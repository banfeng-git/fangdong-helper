package com.rent.pc;

import javax.swing.*;
import javax.swing.UIManager.LookAndFeelInfo;

/** 入口：独立桌面窗口，不是浏览器网页 */
public final class Main {

    public static void main(String[] args) {
        // 高 DPI 下界面不发虚
        System.setProperty("sun.java2d.uiScale.enabled", "true");

        try {
            for (LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (Exception ignored) {
        }

        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
        });
    }
}
