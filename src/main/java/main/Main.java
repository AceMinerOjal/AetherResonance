package main;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.MemoryUtil;

public class Main {

  private static final int BASE_WIDTH = 640;
  private static final int BASE_HEIGHT = 360;

  public static void main(String[] args) {
    if (!glfwInit()) {
      throw new IllegalStateException("Unable to initialize GLFW");
    }

    GLFWVidMode vidMode = glfwGetVideoMode(glfwGetPrimaryMonitor());
    int scale = Math.max(1, Math.min(vidMode.width() / BASE_WIDTH, vidMode.height() / BASE_HEIGHT));
    int screenWidth = BASE_WIDTH * scale;
    int screenHeight = BASE_HEIGHT * scale;

    LaunchConfig config = LaunchConfig.fromArgs(args, screenWidth, screenHeight);

    glfwDefaultWindowHints();
    glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
    glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
    glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);

    long window = glfwCreateWindow(BASE_WIDTH, BASE_HEIGHT, "AetherResonance", NULL, NULL);
    if (window == NULL) {
      glfwTerminate();
      throw new RuntimeException("Failed to create GLFW window");
    }

    glfwSetWindowSize(window, screenWidth, screenHeight);
    GLFWVidMode mode = glfwGetVideoMode(glfwGetPrimaryMonitor());
    glfwSetWindowPos(window, (mode.width() - screenWidth) / 2, (mode.height() - screenHeight) / 2);

    GameContext ctx = GameContext.create(config, window);
    ctx.registerMaps(GamePaths.DEFAULT_MAP_IDS);

    GLFWKeyCallback keyCallback = GLFWKeyCallback.create((w, key, scancode, action, mods) -> {
      if (action == GLFW_PRESS) ctx.keyHandler.setKeyDown(key, true);
      else if (action == GLFW_RELEASE) ctx.keyHandler.setKeyDown(key, false);
    });
    glfwSetKeyCallback(window, keyCallback);
    glfwShowWindow(window);

    new GameLoop(ctx).run(window);

    ctx.close();
    keyCallback.free();
    glfwDestroyWindow(window);
    glfwTerminate();
  }
}
