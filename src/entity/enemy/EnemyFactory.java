package entity.enemy;

@FunctionalInterface
public interface EnemyFactory {
  Enemy create(int tileX, int tileY, int variant, int spawnTileX, int spawnTileY, String layerName);
}
