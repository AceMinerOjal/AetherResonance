package entity.statusEffects;

import java.util.Collections;
import java.util.List;
import lib.Entity;

public interface StatusEffectTarget {
  default void setFrozen(boolean frozen) {}
  default void modifyDamageTakenMultiplier(double delta) {}
  default void modifyAttackSpeedMultiplier(double delta) {}
  default void modifyAccuracyMultiplier(double delta) {}
  default void modifyDetectionRangeMultiplier(double delta) {}
  default void addStatusEffect(StatusEffect effect) {}
  default void removeStatusEffect(StatusEffect effect) {}
  default List<Entity> getNearbyEntities(double radius) {
    return Collections.emptyList();
  }
}
