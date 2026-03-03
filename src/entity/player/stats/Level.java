package entity.player.stats;

public class Level {
  public static final int START_LEVEL = 0;
  public static final int MAX_LEVEL = 128;

  private int level;
  private int exp;

  private static final int BASE_XP = 20;

  public Level() {
    this.level = START_LEVEL;
    this.exp = 0;
  }

  public Level(int level, int exp) {
    this.level = Math.max(0, Math.min(level, MAX_LEVEL));
    this.exp = this.level >= MAX_LEVEL ? 0 : Math.max(exp, 0);
  }

  public void addExp(int amount) {
    if (amount <= 0)
      return;
    if (level >= MAX_LEVEL) {
      exp = 0;
      return;
    }
    exp += amount;

    int required = requiredExp();
    while (level < MAX_LEVEL && exp >= required) {
      exp -= required;
      level++;
      required = requiredExp();
    }

    if (level >= MAX_LEVEL) {
      level = MAX_LEVEL;
      exp = 0;
    }
  }

  public int requiredExp() {
    return (int) Math
        .floor(BASE_XP * (Math.pow((double) level, Math.log(level + 1) / Math.log(4))
            + (1.5 * Math.min(level, 6))));
  }

  public int getLevel() {
    return level;
  }

  public int getExp() {
    return exp;
  }
}
