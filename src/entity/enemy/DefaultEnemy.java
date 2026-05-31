package entity.enemy;

import entity.Health;
import entity.Dialectics;

public class DefaultEnemy extends Enemy {
  public DefaultEnemy(double x, double y, int movementVariant, int spawnTileX, int spawnTileY) {
    super(x, y, movementVariant, spawnTileX, spawnTileY);
    this.appearanceId = "enemy-" + movementVariant;

    // Standard enemy scaling
    this.hp = new Health(40, 40, 0);
    this.ap = new Dialectics(8);
    this.defence = new Dialectics(4);
  }
}
