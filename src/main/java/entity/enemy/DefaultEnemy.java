package entity.enemy;

import entity.DamageCalculator;
import entity.Health;
import entity.Dialectics;
import entity.player.Player;

import java.util.List;

public class DefaultEnemy extends Enemy {
  public DefaultEnemy(double x, double y, int movementVariant, int spawnTileX, int spawnTileY) {
    super(x, y, movementVariant, spawnTileX, spawnTileY);
    this.appearanceId = "enemy-" + movementVariant;

    double scale = 1.0 + movementVariant * 0.15;

    this.hp = new Health((int)(40 * scale), (int)(40 * scale), 0);
    this.ap = new Dialectics((int)(8 * scale));
    this.defence = new Dialectics((int)(4 * scale));

    this.attackDamage = (int)(30 * scale);
    this.attackInterval = Math.max(0.3, 0.8 - movementVariant * 0.05);
    this.attackCooldown = 0;
    this.expReward = (int)(15 * scale);
  }
}
