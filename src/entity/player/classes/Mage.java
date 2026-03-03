package entity.player.classes;

import main.KeyHandler;
import main.PlayerControls;

import entity.Player;
import entity.SignatureElement;
import entity.StatusEffectType;
import entity.player.stats.*;
import entity.Health;

public class Mage extends Player {
  public Mage(double x, double y, KeyHandler kh, PlayerControls controls) {
    super(x, y, kh, controls, SignatureElement.FIRE, StatusEffectType.BURN);

    this.hp = new Health(50, 50, 0.25);
    this.mana = new Mana(20, 20, 0.5);
    this.ap = new Dialectics(20);
    this.defence = new Dialectics(10);
  }

  @Override
  protected void performSkill(int num) {
    switch (num) {
      case 0 -> ball();
      case 1 -> teleport();
      case 2 -> nova();
      case 3 -> explosion();
    }
  }

  @Override
  protected void lastItem() {
  }

  // Damaging skills have a chance for a character's element's status effect
  private void ball() {
    if (!spendMana(6)) {
      return;
    }
    applyTimedApBonus(2 + ap.get() * 0.05, 2.5);
    restoreMana(1.5);
    inflictConfiguredStatusEffectNearby(72, ap.get());
    setAnimation(AnimationState.ATTACK);
  }

  private void teleport() {
    if (!spendMana(12)) {
      return;
    }
    dashForward(96);
    setAnimation(AnimationState.ATTACK);
  }

  private void nova() {
    if (!spendMana(18)) {
      return;
    }
    healSelf(6 + ap.get() * 0.2);
    applyTimedDefenceBonus(3, 3);
    inflictConfiguredStatusEffectNearby(96, ap.get() * 1.2);
    setAnimation(AnimationState.ATTACK);
  }

  private void explosion() {
    if (!spendMana(20)) {
      return;
    }
    applyTimedApBonus(8 + ap.get() * 0.1, 5);
    inflictConfiguredStatusEffectNearby(120, ap.get() * 1.5);
    setAnimation(AnimationState.ATTACK);
  }
}
