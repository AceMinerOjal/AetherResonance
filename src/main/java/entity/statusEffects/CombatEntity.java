package entity.statusEffects;

import entity.player.SignatureElement;

public interface CombatEntity {
  int getLevel();
  double getAttackPower();
  double getDefence();
  double getDamageTakenMultiplier();
  double getDamageDealtMultiplier();
  double getCritChance();
  double getCritDamageMultiplier();
  SignatureElement getSignatureElement();
  void applyDamage(double amount);
  void heal(double amount);
}
