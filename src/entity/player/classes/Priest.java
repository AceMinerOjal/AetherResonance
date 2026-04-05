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

public class Priest extends Player {
  public Priest(double x, double y, KeyHandler kh, PlayerControls controls) {
    super(x, y, kh, controls, SignatureElement.ICE, PlayerRace.HALF_ELF,
        List.of(Profession.ALCHEMY, Profession.BLESSING_ENCHANTING));

    this.hp = new Health(50, 50, 0.5);
    this.mana = new Mana(20, 20, 0.2);
    this.ap = new Dialectics(10);
    this.defence = new Dialectics(10);
    loadPlayerSprites("priest");
  }

  @Override
  protected void performSkill(int num) {
    switch (num) {
      case 0 -> cleanse();
      case 1 -> levitate();
      case 2 -> holyNova();
      case 3 -> divineIntervention();
    }
  }

  private void cleanse() {
    if (!spendMana(6)) {
      return;
    }
    healSelf(8 + ap.get() * 0.2);
    restoreMana(4);
    inflictConfiguredStatusEffectNearby(52, ap.get() * 0.8);
    setAnimation(AnimationState.ATTACK);
  }

  private void levitate() {
    if (!spendMana(8)) {
      return;
    }
    dashForward(40);
    applyTimedRegenBonus(0.75, 0.75, 4);
    setAnimation(AnimationState.ATTACK);
  }

  private void holyNova() {
    if (!spendMana(20)) {
      return;
    }
    healSelf(16 + ap.get() * 0.3);
    applyTimedRegenBonus(1.5, 2.0, 6);
    inflictConfiguredStatusEffectNearby(110, ap.get());
    setAnimation(AnimationState.ATTACK);
  }

  private void divineIntervention() {
    if (!spendMana(40)) {
      return;
    }
    applyTimedApBonus(8, 8);
    applyTimedDefenceBonus(8, 8);
    applyTimedRegenBonus(1, 1, 8);
    inflictConfiguredStatusEffectNearby(140, ap.get() * 1.25);
    setAnimation(AnimationState.ATTACK);
  }
}
