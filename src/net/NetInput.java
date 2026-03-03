package net;

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
        kh.isDown(java.awt.event.KeyEvent.VK_W),
        kh.isDown(java.awt.event.KeyEvent.VK_S),
        kh.isDown(java.awt.event.KeyEvent.VK_A),
        kh.isDown(java.awt.event.KeyEvent.VK_D),
        kh.isDown(java.awt.event.KeyEvent.VK_SHIFT),
        kh.isDown(java.awt.event.KeyEvent.VK_1),
        kh.isDown(java.awt.event.KeyEvent.VK_2),
        kh.isDown(java.awt.event.KeyEvent.VK_3),
        kh.isDown(java.awt.event.KeyEvent.VK_4));
  }
}
