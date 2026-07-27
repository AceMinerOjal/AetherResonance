package entity;

import lib.Stat;

public class Health extends Stat {
  public Health(double hp, double maxHp, double regen) {
    super(hp, maxHp, regen);
  }

  public void damage(double amount) {
    consume(amount);
  }

  public void heal(double amount) {
    add(amount);
  }
}
