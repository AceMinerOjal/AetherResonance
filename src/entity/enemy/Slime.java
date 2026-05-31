package entity.enemy;

import java.util.List;

import entity.DamageCalculator;
import entity.Dialectics;
import entity.Health;
import entity.player.Player;
import tile.TiledMap;

/**
 * Level 0 slime enemy.
 * A slow, low-health beginner enemy that wanders near its spawn tile
 * and deals light contact damage to players it bumps into.
 */
public class Slime extends Enemy {
  private static final double CONTACT_DAMAGE = 40.0; // Skill power for contact
  private static final double CONTACT_COOLDOWN = 0.5;

  private double contactCooldown;

  public Slime(double x, double y, int spawnTileX, int spawnTileY) {
    super(x, y, 0, spawnTileX, spawnTileY);
    this.appearanceId = "slime";
    this.level = 1;

    // Level 0 stats: easy to kill, barely hurts the player
    this.hp = new Health(30, 30, 0);
    this.ap = new Dialectics(5);
    this.defence = new Dialectics(3);

    this.contactCooldown = 0;
    setHitbox(16, 14, 4, 10);
  }

  @Override
  public void update(double dt, TiledMap map, List<Player> players) {
    if (!alive) return;
    contactCooldown = Math.max(0, contactCooldown - dt);

    super.update(dt, map, players);

    if (!alive) return;
    applyContactDamage(players, dt);
  }

  /**
   * Deal light contact damage to any player touching the slime's hitbox.
   */
  private void applyContactDamage(List<Player> players, double dt) {
    if (contactCooldown > 0) return;

    for (Player player : players) {
      if (player.getHitbox().intersects(getHitbox())) {
        double damage = DamageCalculator.calculate(this, player, CONTACT_DAMAGE);
        player.applyDamage(damage);
        contactCooldown = CONTACT_COOLDOWN;
        break;
      }
    }
  }

  private void onDeath() {
    // TODO: drop XP, loot particles
  }
}
