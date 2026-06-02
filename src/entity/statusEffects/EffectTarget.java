package entity.statusEffects;

import java.util.Collections;
import java.util.List;
import lib.Entity;

public interface EffectTarget {
  default void applyDamage(double amount) {
  }

  default void heal(double amount) {
  }

  default void setFrozen(boolean frozen) {
  }

  default void modifyDamageTakenMultiplier(double delta) {
  }

  default double getDamageTakenMultiplier() {
    return 1.0;
  }

  default double getDamageDealtMultiplier() {
    return 1.0;
  }

  default double getCritChance() {
    return 0.05; // 5% base
  }

  default double getCritDamageMultiplier() {
    return 1.5; // 50% extra damage
  }

  default entity.player.SignatureElement getSignatureElement() {
    return entity.player.SignatureElement.EARTH; // Neutral fallback
  }

  default int getLevel() {
    return 1;
  }

  default double getAttackPower() {
    return 0;
  }

  default double getDefence() {
    return 0;
  }

  default void addStatusEffect(StatusEffect effect) {
  }

  default void removeStatusEffect(StatusEffect effect) {
  }

  default List<Entity> getNearbyEntities(double radius) {
    return Collections.emptyList();
  }

  default void modifyAttackSpeedMultiplier(double delta) {
  }

  default void modifyAccuracyMultiplier(double delta) {
  }

  default void modifyDetectionRangeMultiplier(double delta) {
  }
}
