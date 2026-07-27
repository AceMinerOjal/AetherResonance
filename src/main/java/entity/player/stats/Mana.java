package entity.player.stats;

import lib.Stat;

public class Mana extends Stat {
  public Mana(double mana, double maxMana, double regen) {
    super(mana, maxMana, regen);
  }

  public void spend(double amount) {
    consume(amount);
  }

  public boolean canSpend(double amount) {
    return has(amount);
  }
}
