package main;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import net.NetworkConfig;
import net.NetworkMode;
import render.vulkan.VulkanShaderInitializer;

public class Main {
  private static final String WINDOW_ICON_RESOURCE = "AetherResonance.ico";

  public static void main(String[] args) {
    LaunchOptions launchOptions = parseArgs(args);
    AutoCloseable vulkanContext = initializeRenderer(launchOptions.renderBackend());

    SwingUtilities.invokeLater(() -> {
      JFrame frame = new JFrame("AetherResonance");
      applyWindowIcon(frame);

      GamePanel gamePanel = new GamePanel(launchOptions.networkConfig());
      frame.add(gamePanel);
      frame.pack();

      frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
      frame.addWindowListener(new WindowAdapter() {
        @Override
        public void windowClosing(WindowEvent e) {
          gamePanel.shutdown();
          if (vulkanContext != null) {
            try {
              vulkanContext.close();
            } catch (Exception ex) {
              System.err.println("Failed to close Vulkan resources: " + ex.getMessage());
            }
          }
        }
      });
      frame.setResizable(false);
      frame.setLocationRelativeTo(null);
      frame.setVisible(true);
      gamePanel.startGameThread();
    });
  }

  private static AutoCloseable initializeRenderer(RenderBackend backend) {
    if (backend != RenderBackend.VULKAN) {
      return null;
    }

    try {
      VulkanShaderInitializer initializer = VulkanShaderInitializer.initialize();
      System.out.println("Vulkan renderer initialized (shader modules created).");
      return initializer;
    } catch (RuntimeException ex) {
      System.err.println("Vulkan initialization failed, continuing with Java2D fallback: " + ex.getMessage());
      return null;
    }
  }

  private static void applyWindowIcon(JFrame frame) {
    URL iconUrl = Thread.currentThread().getContextClassLoader().getResource(WINDOW_ICON_RESOURCE);
    if (iconUrl == null) {
      System.err.println("Window icon not found: " + WINDOW_ICON_RESOURCE);
      return;
    }

    Image icon = Toolkit.getDefaultToolkit().getImage(iconUrl);
    frame.setIconImage(icon);
  }

  private static LaunchOptions parseArgs(String[] args) {
    NetworkMode mode = NetworkMode.LOCAL;
    String host = "127.0.0.1";
    int port = 7777;
    List<String> peerAddresses = new ArrayList<>();
    RenderBackend renderBackend = RenderBackend.JAVA2D;

    for (String arg : args) {
      if (arg.startsWith("--mode=")) {
        String value = arg.substring("--mode=".length()).trim().toLowerCase();
        mode = switch (value) {
          case "local" -> NetworkMode.LOCAL;
          case "p2p-host" -> NetworkMode.P2P_HOST;
          case "p2p-peer" -> NetworkMode.P2P_PEER;
          default -> mode;
        };
      } else if (arg.startsWith("--host=")) {
        host = arg.substring("--host=".length()).trim();
      } else if (arg.startsWith("--port=")) {
        try {
          port = Integer.parseInt(arg.substring("--port=".length()).trim());
        } catch (NumberFormatException ignored) {
        }
      } else if (arg.startsWith("--peer=")) {
        peerAddresses.add(arg.substring("--peer=".length()).trim());
      } else if (arg.startsWith("--renderer=")) {
        String value = arg.substring("--renderer=".length()).trim().toLowerCase();
        if ("vulkan".equals(value)) {
          renderBackend = RenderBackend.VULKAN;
        } else {
          renderBackend = RenderBackend.JAVA2D;
        }
      }
    }

    return new LaunchOptions(new NetworkConfig(mode, host, port, peerAddresses), renderBackend);
  }

  private record LaunchOptions(NetworkConfig networkConfig, RenderBackend renderBackend) {
  }
}
