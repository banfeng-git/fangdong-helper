import com.rent.pc.*;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/** 在虚拟显示器里启动主窗口并截图，用来确认界面真的能打开 */
public class UiShot {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeLater(() -> {
            MainFrame f = new MainFrame();
            f.setVisible(true);
        });
        Thread.sleep(2500);
        Robot robot = new Robot();
        Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        BufferedImage img = robot.createScreenCapture(screen);
        ImageIO.write(img, "png", new File(args.length > 0 ? args[0] : "/tmp/ui.png"));
        System.out.println("screenshot -> " + args[0]);
        System.exit(0);
    }
}
