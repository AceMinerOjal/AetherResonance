package entity.player.classes;

import main.KeyHandler;
import main.PlayerControls;

import entity.player.Health;
import entity.player.Player;
import entity.player.SignatureElement;
import entity.player.stats.Dialectics;
import entity.player.stats.Mana;

public class Warrior extends Player {
  public Warrior(double x, double y, KeyHandler kh, PlayerControls controls) {
    super(x, y, kh, controls, SignatureElement.LIGHTNING);

    this.hp = new Health(50, 50, 0.5);
    this.mana = new Mana(20, 20, 0.1);
    this.ap = new Dialectics(20);
    this.defence = new Dialectics(10);
  }

  @Override
  protected void performSkill(int num) {
    switch (num) {
      case 0 -> stab();
      case 1 -> dash();
      case 2 -> stomp();
      case 3 -> cleave();
    }
  }

  private void stab() {
    if (!spendMana(5)) {
      return;
    }
    dashForward(18);
    restoreMana(1);
    inflictConfiguredStatusEffectNearby(56, ap.get());
    setAnimation(AnimationState.ATTACK);
  }

  private void dash() {
    if (!spendMana(8)) {
      return;
    }
    dashForward(48);
    applyTimedDefenceBonus(2, 2.5);
    setAnimation(AnimationState.ATTACK);
  }

  private void stomp() {
    if (!spendMana(10)) {
      return;
    }
    applyTimedDefenceBonus(6, 4);
    inflictConfiguredStatusEffectNearby(84, ap.get() * 1.1);
    setAnimation(AnimationState.ATTACK);
  }

  private void cleave() {
    if (!spendMana(12)) {
      return;
    }
    healSelf(4 + ap.get() * 0.25);
    applyTimedApBonus(4, 3);
    inflictConfiguredStatusEffectNearby(80, ap.get() * 1.35);
    setAnimation(AnimationState.ATTACK);
  }
}
