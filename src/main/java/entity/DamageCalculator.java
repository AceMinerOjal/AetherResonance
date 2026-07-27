package entity;

import entity.statusEffects.EffectTarget;
import entity.player.SignatureElement;
import entity.player.ElementMatrix;
import java.util.concurrent.ThreadLocalRandom;

public class DamageCalculator {
  private static final double SAME_ELEMENT_BONUS = main.GameConfig.DEFAULT.combat().sameElementBonus();

  /**
   * Calculates the final damage using a modern, predictable MMO pipeline.
   * Eliminates pure RNG variance in favor of player-driven multipliers,
   * critical hits, signature elements, and elemental type advantages.
   *
   * @param attacker The entity dealing the damage
   * @param target The entity receiving the damage
   * @param skillPower The power coefficient of the skill used
   * @param skillElement The elemental type of the skill being cast
   * @return The final calculated damage amount
   */
  public static double calculate(EffectTarget attacker, EffectTarget target, double skillPower, SignatureElement skillElement) {
    int level = attacker.getLevel();
    double attack = attacker.getAttackPower();
    double defence = Math.max(1.0, target.getDefence());
    
    // 1. Core Baseline Math (The mechanical foundation)
    double baseDamage = ((((2.0 * level) / 5.0 + 2.0) * skillPower * attack / defence) / 50.0) + 2.0;

    // Start with a clean slate modifier pipeline
    double pipelineMultiplier = 1.0;

    // 2. Step A: Critical Hit Check (Player-driven High Roll)
    if (ThreadLocalRandom.current().nextDouble() < attacker.getCritChance()) {
      pipelineMultiplier *= attacker.getCritDamageMultiplier();
    }

    // 3. Step B: Same-Type Signature Element Synergy
    if (skillElement != null && skillElement == attacker.getSignatureElement()) {
      pipelineMultiplier *= SAME_ELEMENT_BONUS;
    }

    // 4. Step C: Elemental Matchup Type Chart
    double matchupBonus = ElementMatrix.getMultiplier(skillElement, target.getSignatureElement());
    pipelineMultiplier *= matchupBonus; // Can be 0.5x (resist), 1.0x (neutral), or 2.0x (weakness)

    // 5. Step D: Global Buffs & Debuffs
    pipelineMultiplier *= attacker.getDamageDealtMultiplier();
    pipelineMultiplier *= target.getDamageTakenMultiplier();

    // 6. Final Calculation
    return baseDamage * pipelineMultiplier;
  }
}
