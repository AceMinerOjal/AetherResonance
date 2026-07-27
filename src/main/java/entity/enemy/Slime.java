package entity.enemy;

import entity.Dialectics;
import entity.Health;
import entity.player.Player;
import tile.TiledMap;

import java.util.List;

public class Slime extends Enemy {
  public Slime(double x, double y, int spawnTileX, int spawnTileY) {
    super(x, y, 0, spawnTileX, spawnTileY);
    this.appearanceId = "slime";
    this.level = 1;

    this.hp = new Health(30, 30, 0);
    this.ap = new Dialectics(5);
    this.defence = new Dialectics(3);

    this.attackDamage = 40;
    this.attackInterval = 0.5;
    this.attackCooldown = 0;
    this.expReward = 20;
    setHitbox(16, 14, 4, 10);
  }
}
