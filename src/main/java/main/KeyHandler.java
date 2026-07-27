package main;

import java.util.BitSet;

public class KeyHandler {

  private static final int KEY_COUNT = 65536;
  private final BitSet pressed = new BitSet(KEY_COUNT);
  private final BitSet triggered = new BitSet(KEY_COUNT);
  private final BitSet virtualPressed = new BitSet(KEY_COUNT);

  public boolean isTriggered(int keyCode) {
    if (keyCode >= 0 && keyCode < KEY_COUNT && triggered.get(keyCode)) {
      triggered.clear(keyCode);
      return true;
    }
    return false;
  }

  public boolean isDown(int keyCode) {
    if (keyCode >= 0 && keyCode < KEY_COUNT) {
      return pressed.get(keyCode) || virtualPressed.get(keyCode);
    }
    return false;
  }

  public void setVirtualDown(int keyCode, boolean down) {
    if (keyCode < 0 || keyCode >= KEY_COUNT) {
      return;
    }
    if (down && !virtualPressed.get(keyCode) && !pressed.get(keyCode)) {
      triggered.set(keyCode);
    }
    virtualPressed.set(keyCode, down);
  }

  public void setKeyDown(int keyCode, boolean down) {
    if (keyCode >= 0 && keyCode < KEY_COUNT) {
      if (down && !pressed.get(keyCode)) {
        triggered.set(keyCode);
      }
      pressed.set(keyCode, down);
    }
  }
}
