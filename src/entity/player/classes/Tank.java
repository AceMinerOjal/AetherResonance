package entity.player.classes;

import java.util.List;

import main.KeyHandler;
import main.PlayerControls;

import entity.player.Health;
import entity.player.Player;
import entity.player.PlayerRace;
import entity.player.Profession;
import entity.player.SignatureElement;
import entity.player.stats.Dialectics;
import entity.player.stats.Mana;

public class Tank extends Player {
  public Tank(double x, double y, KeyHandler kh, PlayerControls controls) {
    super(x, y, kh, controls, SignatureElement.EARTH, PlayerRace.DWARF,
        List.of(Profession.FORGING, Profession.MINING_ORES, Profession.MINING_ORBS));

    this.hp = new Health(100, 100, 0.5);
    this.mana = new Mana(10, 10, 0.25);
    this.ap = new Dialectics(10);
    this.defence = new Dialectics(20);
    loadPlayerSprites("tank");
  }

  @Override
  protected void performSkill(int num) {
    switch (num) {
      case 0 -> shield();
      case 1 -> bash();
      case 2 -> wall();
      case 3 -> taunt();
    }
  }

  private void shield() {
    if (!spendMana(5)) {
      return;
    }
    applyTimedDefenceBonus(10, 4);
    healSelf(5);
    setAnimation(AnimationState.ATTACK);
  }

  private void bash() {
    if (!spendMana(6)) {
      return;
    }
    dashForward(28);
    applyTimedApBonus(3, 2);
    inflictConfiguredStatusEffectNearby(60, ap.get());
    setAnimation(AnimationState.ATTACK);
  }

  private void wall() {
    if (!spendMana(10)) {
      return;
    }
    applyTimedRegenBonus(1.5, 0.5, 6);
    setAnimation(AnimationState.ATTACK);
  }

  private void taunt() {
    if (!spendMana(4)) {
      return;
    }
    restoreMana(3);
    applyTimedDefenceBonus(4, 3);
    inflictConfiguredStatusEffectNearby(96, ap.get() * 0.9);
    setAnimation(AnimationState.ATTACK);
  }
}
