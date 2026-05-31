package entity;

import entity.statusEffects.EffectTarget;

public class DamageCalculator {
  /**
   * Calculates damage using the formula:
   * ((((2 * Level) / 5 + 2) * SkillPower * Attack / Defence) / 50) + 2) * modifiers
   *
   * @param attacker The entity dealing the damage
   * @param target The entity receiving the damage
   * @param skillPower The power of the skill used
   * @return The final damage amount
   */
  public static double calculate(EffectTarget attacker, EffectTarget target, double skillPower) {
    int level = attacker.getLevel();
    double attack = attacker.getAttackPower();
    double defence = Math.max(1.0, target.getDefence());
    double modifiers = target.getDamageTakenMultiplier();

    double baseDamage = ((((2.0 * level) / 5.0 + 2.0) * skillPower * attack / defence) / 50.0) + 2.0;
    return baseDamage * modifiers;
  }
}
