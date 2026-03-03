package main;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import net.NetworkConfig;
import net.NetworkMode;

public class Main {

  public static void main(String[] args) {
    NetworkConfig config = parseArgs(args);

    SwingUtilities.invokeLater(() -> {
      JFrame frame = new JFrame("Shardbound");

      GamePanel gamePanel = new GamePanel(config);
      frame.add(gamePanel);
      frame.pack();

      frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
      frame.addWindowListener(new WindowAdapter() {
        @Override
        public void windowClosing(WindowEvent e) {
          gamePanel.shutdown();
        }
      });
      frame.setResizable(false);
      frame.setLocationRelativeTo(null);
      frame.setVisible(true);
      gamePanel.startGameThread();
    });
  }

  private static NetworkConfig parseArgs(String[] args) {
    NetworkMode mode = NetworkMode.LOCAL;
    String host = "127.0.0.1";
    int port = 7777;

    for (String arg : args) {
      if (arg.startsWith("--mode=")) {
        String value = arg.substring("--mode=".length()).trim().toLowerCase();
        mode = switch (value) {
          case "local" -> NetworkMode.LOCAL;
          case "lan-host" -> NetworkMode.LAN_HOST;
          case "lan-client" -> NetworkMode.LAN_CLIENT;
          case "tcp-host" -> NetworkMode.TCP_HOST;
          case "tcp-client" -> NetworkMode.TCP_CLIENT;
          default -> mode;
        };
      } else if (arg.startsWith("--host=")) {
        host = arg.substring("--host=".length()).trim();
      } else if (arg.startsWith("--port=")) {
        try {
          port = Integer.parseInt(arg.substring("--port=".length()).trim());
        } catch (NumberFormatException ignored) {
        }
      }
    }

    return new NetworkConfig(mode, host, port);
  }
}
