package main;

public record GameConfig(
    EnemyConfig enemy,
    CombatConfig combat,
    NetConfig net,
    XpConfig xp
) {
  public static final GameConfig DEFAULT = new GameConfig(
      EnemyConfig.DEFAULT,
      CombatConfig.DEFAULT,
      NetConfig.DEFAULT,
      XpConfig.DEFAULT
  );

  public record EnemyConfig(
      int senseRangeTiles,
      double speedPxPerSec,
      double repathIntervalSec,
      int patrolRadiusTiles
  ) {
    public static final EnemyConfig DEFAULT = new EnemyConfig(10, 52.0, 0.2, 10);
  }

  public record CombatConfig(
      double defaultCritChance,
      double defaultCritDamageMultiplier,
      double sameElementBonus
  ) {
    public static final CombatConfig DEFAULT = new CombatConfig(0.05, 1.5, 1.2);
  }

  public record NetConfig(
      int heartbeatIntervalMs,
      int disconnectTimeoutMs
  ) {
    public static final NetConfig DEFAULT = new NetConfig(2000, 5000);
  }

  public record XpConfig(
      double awardRangePx
  ) {
    public static final XpConfig DEFAULT = new XpConfig(200.0);
  }
}
