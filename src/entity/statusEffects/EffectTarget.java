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

  default List<Entity> getNearbyEntities(double radius) {
    return Collections.emptyList();
  }

  default void modifyDefence(double amount) {
  }

  default void modifyAp(double amount) {
  }

  default void modifyAttackSpeedMultiplier(double delta) {
  }

  default void modifyAccuracyMultiplier(double delta) {
  }

  default void modifyDetectionRangeMultiplier(double delta) {
  }
}
