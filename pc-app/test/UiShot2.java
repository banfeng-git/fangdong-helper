import com.rent.pc.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/** 打开“编辑账单”窗口并截图（模态窗口用独立线程定时截屏后自动关闭） */
public class UiShot2 {
    public static void main(String[] args) throws Exception {
        final BillDialog[] dlg = new BillDialog[1];
        SwingUtilities.invokeLater(() -> {
            MainFrame f = new MainFrame();
            f.setVisible(true);
            Model.Data d = new Store().load();
            Model.Bill b = d.allBills().get(0);
            dlg[0] = new BillDialog(f, b, d.settings, b.room);
            dlg[0].setVisible(true);
        });
        Thread.sleep(3000);
        Robot robot = new Robot();
        BufferedImage img = robot.createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
        ImageIO.write(img, "png", new File(args.length > 0 ? args[0] : "/tmp/ui_bill.png"));
        System.out.println("screenshot ok");
        SwingUtilities.invokeLater(() -> { if (dlg[0] != null) dlg[0].dispose(); });
        Thread.sleep(500);
        System.exit(0);
    }
}
