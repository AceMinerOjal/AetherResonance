package net;

import static org.lwjgl.glfw.GLFW.*;

import main.KeyHandler;

public record NetInput(
    boolean up,
    boolean down,
    boolean left,
    boolean right,
    boolean item,
    boolean skill1,
    boolean skill2,
    boolean skill3,
    boolean skill4) {

  public static NetInput fromClientKeys(KeyHandler kh) {
    return new NetInput(
        kh.isDown(GLFW_KEY_W),
        kh.isDown(GLFW_KEY_S),
        kh.isDown(GLFW_KEY_A),
        kh.isDown(GLFW_KEY_D),
        kh.isDown(GLFW_KEY_LEFT_SHIFT),
        kh.isDown(GLFW_KEY_1),
        kh.isDown(GLFW_KEY_2),
        kh.isDown(GLFW_KEY_3),
        kh.isDown(GLFW_KEY_4));
  }
}
