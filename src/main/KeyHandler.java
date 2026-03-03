package main;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

public class KeyHandler implements KeyListener {

  // 65536 is the upper limit for Java key codes, though usually lower is fine.
  private static final int KEY_COUNT = 65536;
  private final boolean[] keyPressed = new boolean[KEY_COUNT];
  private final boolean[] keyTriggered = new boolean[KEY_COUNT];
  private final boolean[] virtualPressed = new boolean[KEY_COUNT];

  @Override
  public void keyPressed(KeyEvent e) {
    int keyCode = e.getKeyCode();
    if (keyCode >= 0 && keyCode < KEY_COUNT) {
      if (!keyPressed[keyCode]) {
        keyTriggered[keyCode] = true;
      }
      keyPressed[keyCode] = true;
    }
  }

  @Override
  public void keyReleased(KeyEvent e) {
    int keyCode = e.getKeyCode();
    if (keyCode >= 0 && keyCode < KEY_COUNT) {
      keyPressed[keyCode] = false;
    }
  }

  @Override
  public void keyTyped(KeyEvent e) {
  }

  public boolean isTriggered(int keyCode) {
    if (keyCode >= 0 && keyCode < KEY_COUNT && keyTriggered[keyCode]) {
      keyTriggered[keyCode] = false;
      return true;
    }
    return false;
  }

  public boolean isDown(int keyCode) {
    if (keyCode >= 0 && keyCode < KEY_COUNT) {
      return keyPressed[keyCode] || virtualPressed[keyCode];
    }
    return false;
  }

  public void setVirtualDown(int keyCode, boolean down) {
    if (keyCode < 0 || keyCode >= KEY_COUNT) {
      return;
    }
    if (down && !virtualPressed[keyCode] && !keyPressed[keyCode]) {
      keyTriggered[keyCode] = true;
    }
    virtualPressed[keyCode] = down;
  }
}
