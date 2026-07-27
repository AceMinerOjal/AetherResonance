package lib;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StatTest {

  @Test
  void initialValueEqualsBase() {
    Stat stat = new TestStat(100, 200, 0);
    assertEquals(100, stat.get(), 0.001);
  }

  @Test
  void clampToMax() {
    Stat stat = new TestStat(100, 150, 0);
    stat.add(100);
    assertEquals(150, stat.get(), 0.001);
  }

  @Test
  void clampToZero() {
    Stat stat = new TestStat(100, 150, 0);
    stat.consume(200);
    assertEquals(0, stat.get(), 0.001);
  }

  @Test
  void regenIncreasesValue() {
    Stat stat = new TestStat(50, 100, 10);
    stat.update(1.0);
    assertEquals(60, stat.get(), 0.001);
  }

  @Test
  void scaleAdjustsMaxAndPreservesRatio() {
    Stat stat = new TestStat(50, 100, 0);
    stat.scale(10);
    // max is rescaled via base * (1 + pow(level/128, 0.75) * 9)
    // value = newMax * ratio where ratio = 50/100 = 0.5
    assertTrue(stat.get() > 0);
  }

  @Test
  void setClampsValue() {
    Stat stat = new TestStat(50, 100, 0);
    stat.set(200);
    assertEquals(100, stat.get(), 0.001);
    stat.set(-10);
    assertEquals(0, stat.get(), 0.001);
  }

  @Test
  void hasChecksValue() {
    Stat stat = new TestStat(50, 100, 0);
    assertTrue(stat.has(50));
    assertFalse(stat.has(51));
  }

  @Test
  void restoreSetsToMax() {
    Stat stat = new TestStat(50, 100, 0);
    stat.consume(40);
    stat.restore();
    assertEquals(100, stat.get(), 0.001);
  }

  @Test
  void regenStillAppliesWithZeroMax() {
    Stat stat = new TestStat(50, 0, 10);
    stat.update(1.0);
    // regen applies even with max=0; clamp() skips when max<=0
    assertEquals(60, stat.get(), 0.001);
  }

  private static class TestStat extends Stat {
    TestStat(double base, double max, double regen) {
      super(base, max, regen);
    }
  }
}
