package entity.player.stats;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LevelTest {

  @Test
  void startsAtLevelZero() {
    Level level = new Level();
    assertEquals(0, level.getLevel());
    assertEquals(0, level.getExp());
  }

  @Test
  void gainsExpAndLevelsUp() {
    Level level = new Level(0, 0);
    level.addExp(100);
    assertTrue(level.getLevel() > 0, "Should level up with 100 XP");
  }

  @Test
  void doesNotExceedMaxLevel() {
    Level level = new Level(Level.MAX_LEVEL, 0);
    level.addExp(999999);
    assertEquals(Level.MAX_LEVEL, level.getLevel());
    assertEquals(0, level.getExp());
  }

  @Test
  void clampstoMaxLevelWithConstructor() {
    Level level = new Level(999, 0);
    assertEquals(Level.MAX_LEVEL, level.getLevel());
  }

  @Test
  void negativeExpClampedToZero() {
    Level level = new Level(5, -10);
    assertEquals(0, level.getExp());
  }

  @Test
  void zeroOrNegativeExpGainIsNoOp() {
    Level level = new Level(0, 0);
    level.addExp(0);
    assertEquals(0, level.getLevel());
    level.addExp(-10);
    assertEquals(0, level.getLevel());
  }

  @Test
  void requiredExpIsPositive() {
    Level level = new Level(0, 0);
    assertTrue(level.requiredExp() > 0);
  }
}
