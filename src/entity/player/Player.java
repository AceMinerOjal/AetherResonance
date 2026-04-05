package entity.player;

import lib.Entity;
import lib.Hitbox;
import lib.Timer;
import main.KeyHandler;
import main.PlayerControls;
import save.PlayerSaveState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import entity.player.stats.Dialectics;
import entity.player.stats.Level;
import entity.player.stats.Mana;
import entity.statusEffects.EarthFracture;
import entity.statusEffects.EffectTarget;
import entity.statusEffects.FireBurn;
import entity.statusEffects.IceFreeze;
import entity.statusEffects.LightningConductive;
import entity.statusEffects.ShadowObscure;
import entity.statusEffects.StatusEffect;
import entity.statusEffects.WindTempo;

public abstract class Player extends Entity implements EffectTarget {
  private static final double BASE_SKILL_RECOVERY_SECONDS = 0.35;

  protected Health hp;
  protected Mana mana;
  protected Dialectics ap;
  protected Dialectics defence;
  protected Level level;

  private final KeyHandler kh;
  private final PlayerControls controls;
  private final PlayerRace race;
  private final List<Profession> professions;
  private final List<Timer> activeEffects = new ArrayList<>();
  private final List<StatusEffect> activeStatusEffects = new ArrayList<>();
  private final List<InventoryItem> inventory = List.of(InventoryItem.ELEMENT_TUNER);
  private int selectedInventoryIndex;
  private List<Player> party = Collections.emptyList();
  private SignatureElement signatureElement;
  private double damageTakenMultiplier = 1.0;
  private double attackSpeedMultiplier = 1.0;
  private double accuracyMultiplier = 1.0;
  private double detectionRangeMultiplier = 1.0;
  private double skillRecoveryRemaining;
  private boolean frozen;
  private boolean friendlyFireEnabled;

  public Player(double x, double y, KeyHandler kh, PlayerControls controls, SignatureElement defaultElement,
      PlayerRace race, List<Profession> professions) {
    setPosition(x, y);
    // Keep feet/body collision tighter than full 32x32 sprite frame.
    setHitbox(20, 24, 6, 8);
    this.kh = kh;
    this.controls = controls;
    this.signatureElement = defaultElement;
    this.race = race;
    this.professions = List.copyOf(professions);
    this.level = new Level();
  }

  public void update(double dt) {
    for (int i = activeEffects.size() - 1; i >= 0; i--) {
      activeEffects.get(i).update(dt);
      if (!activeEffects.get(i).isActive()) {
        activeEffects.remove(i);
      }
    }

    for (int i = activeStatusEffects.size() - 1; i >= 0; i--) {
      activeStatusEffects.get(i).update(dt);
      if (!activeStatusEffects.get(i).isActive()) {
        activeStatusEffects.remove(i);
      }
    }

    handleMovement(dt);
    handleInputs();
    hp.update(dt);
    mana.update(dt);
    ap.update(dt);
    defence.update(dt);
    skillRecoveryRemaining = Math.max(0.0, skillRecoveryRemaining - dt);
    updateAnimation(dt);
  }

  public void clampToBounds(int worldWidth, int worldHeight) {
    Hitbox hb = getHitbox();
    double nextX = x;
    double nextY = y;

    if (hb.getLeft() < 0) {
      nextX -= hb.getLeft();
    }
    if (hb.getTop() < 0) {
      nextY -= hb.getTop();
    }
    if (hb.getRight() > worldWidth) {
      nextX -= hb.getRight() - worldWidth;
    }
    if (hb.getBottom() > worldHeight) {
      nextY -= hb.getBottom() - worldHeight;
    }

    setPosition(nextX, nextY);
  }

  public void setWorldPosition(double x, double y) {
    setPosition(x, y);
  }

  public PlayerSaveState createPlayerSaveState() {
    return new PlayerSaveState(
        getClass().getName(),
        signatureElement.name(),
        statusForElement(signatureElement).name(),
        x,
        y,
        hp.get(),
        mana.get(),
        ap.get(),
        defence.get(),
        level.getLevel(),
        level.getExp());
  }

  public boolean loadPlayerSaveState(PlayerSaveState state) {
    if (!getClass().getName().equals(state.playerClassName())) {
      return false;
    }

    try {
      signatureElement = SignatureElement.valueOf(state.signatureElement());
    } catch (Exception ignored) {
      signatureElement = SignatureElement.FIRE;
    }
    setPosition(state.x(), state.y());
    level = new Level(state.level(), state.exp());

    hp.scale(level.getLevel());
    mana.scale(level.getLevel());
    ap.scale(level.getLevel());
    defence.scale(level.getLevel());

    hp.set(state.hp());
    mana.set(state.mana());
    ap.set(state.ap());
    defence.set(state.defence());
    return true;
  }

  private void handleInputs() {
    int[] skillKeys = controls.skillKeys();
    boolean isShifting = kh.isDown(controls.itemModifierKey());

    for (int i = 0; i < skillKeys.length; i++) {
      if (kh.isTriggered(skillKeys[i])) {
        if (isShifting) {
          handleInventoryInput(i);
        } else {
          handleHotbarInput(i);
        }
        return;
      }
    }
  }

  private void handleMovement(double dt) {
    if (frozen) {
      setAnimation(AnimationState.IDLE);
      return;
    }

    int dy = ((kh.isDown(controls.downKey())) ? 1 : 0)
        - ((kh.isDown(controls.upKey())) ? 1 : 0);
    int dx = ((kh.isDown(controls.rightKey())) ? 1 : 0)
        - ((kh.isDown(controls.leftKey())) ? 1 : 0);

    if (dy != 0) {
      direction = (dy > 0) ? Direction.DOWN : Direction.UP;
    } else if (dx != 0) {
      direction = (dx > 0) ? Direction.RIGHT : Direction.LEFT;
    }

    double mag = Math.hypot(dx, dy);
    double vx, vy;

    if (mag > 0) {
      vx = dx / mag;
      vy = dy / mag;
      if (getCurrentAnimation() != AnimationState.ATTACK || skillRecoveryRemaining <= 0.0) {
        setAnimation(AnimationState.WALK);
      }
    } else {
      vx = vy = 0.0;
      if (getCurrentAnimation() != AnimationState.ATTACK || skillRecoveryRemaining <= 0.0) {
        setAnimation(AnimationState.IDLE);
      }
    }

    setPosition(x + vx * SPEED * dt, y + vy * SPEED * dt);
  }

  protected abstract void performSkill(int skillNum);

  private void handleHotbarInput(int slot) {
    if (skillRecoveryRemaining > 0.0) {
      return;
    }
    performSkill(slot);
    setAnimation(AnimationState.ATTACK);
    skillRecoveryRemaining = BASE_SKILL_RECOVERY_SECONDS / Math.max(0.25, attackSpeedMultiplier);
  }

  private void handleInventoryInput(int slot) {
    switch (slot) {
      case 0 -> selectPreviousInventoryItem();
      case 1 -> useSelectedInventoryItem();
      case 2 -> selectNextInventoryItem();
      default -> {
      }
    }
  }

  protected boolean spendMana(double cost) {
    if (!mana.canSpend(cost)) {
      return false;
    }
    mana.spend(cost);
    return true;
  }

  protected void dashForward(double distance) {
    double targetX = x;
    double targetY = y;
    switch (direction) {
      case UP -> targetY -= distance;
      case DOWN -> targetY += distance;
      case LEFT -> targetX -= distance;
      case RIGHT -> targetX += distance;
    }
    setPosition(targetX, targetY);
  }

  protected void applyTimedApBonus(double amount, double seconds) {
    if (seconds <= 0 || amount == 0) {
      return;
    }
    ap.add(amount);
    addTimedEffect(seconds, () -> ap.consume(amount));
  }

  protected void applyTimedDefenceBonus(double amount, double seconds) {
    if (seconds <= 0 || amount == 0) {
      return;
    }
    defence.add(amount);
    addTimedEffect(seconds, () -> defence.consume(amount));
  }

  protected void applyTimedRegenBonus(double hpRegenBonus, double manaRegenBonus, double seconds) {
    if (seconds <= 0) {
      return;
    }
    if (hpRegenBonus != 0) {
      hp.setRegen(hp.getRegen() + hpRegenBonus);
    }
    if (manaRegenBonus != 0) {
      mana.setRegen(mana.getRegen() + manaRegenBonus);
    }
    addTimedEffect(seconds, () -> {
      if (hpRegenBonus != 0) {
        hp.setRegen(hp.getRegen() - hpRegenBonus);
      }
      if (manaRegenBonus != 0) {
        mana.setRegen(mana.getRegen() - manaRegenBonus);
      }
    });
  }

  protected void healSelf(double amount) {
    hp.heal(amount);
  }

  protected void restoreMana(double amount) {
    mana.add(amount);
  }

  protected void inflictConfiguredStatusEffectOn(Player target, double power) {
    if (target == null || target == this) {
      return;
    }
    if (!friendlyFireEnabled || !target.friendlyFireEnabled) {
      return;
    }
    if (ThreadLocalRandom.current().nextDouble() > Math.min(1.0, Math.max(0.05, accuracyMultiplier))) {
      return;
    }

    // Offensive skills always inherit the active elemental skill type.
    StatusEffect effect = switch (statusForElement(signatureElement)) {
      case BURN -> new FireBurn(3.0, Math.max(1.0, power * 0.25), 1.0);
      case FREEZE -> new IceFreeze(Math.max(0.75, 1.25 + power * 0.01));
      case CONDUCTIVE -> new LightningConductive(3.0, Math.max(1.0, power * 0.2), 1.0, 0.5, 64.0);
      case FRACTURE -> new EarthFracture(4.0, 0.15);
      case HASTE_SLOW -> new WindTempo(3.5, -0.35);
      case OBSCURE -> new ShadowObscure(4.0, -0.35, -0.4);
    };

    effect.apply(target);
    activeStatusEffects.add(effect);
  }

  protected void inflictConfiguredStatusEffectNearby(double radius, double power) {
    if (!friendlyFireEnabled) {
      return;
    }
    for (Player other : party) {
      if (other == null || other == this) {
        continue;
      }
      double dx = other.x - x;
      double dy = other.y - y;
      if (Math.hypot(dx, dy) <= radius) {
        inflictConfiguredStatusEffectOn(other, power);
      }
    }
  }

  private void addTimedEffect(double seconds, Runnable onFinish) {
    Timer timer = new Timer(seconds) {
      @Override
      public void onFinish() {
        onFinish.run();
      }

      @Override
      public void onActive() {
      }
    };
    timer.start();
    activeEffects.add(timer);
  }

  public void setParty(List<Player> party) {
    this.party = (party == null) ? Collections.emptyList() : party;
  }

  public SignatureElement getSignatureElement() {
    return signatureElement;
  }

  public PlayerRace getRace() {
    return race;
  }

  public List<Profession> getProfessions() {
    return professions;
  }

  public void setFriendlyFireEnabled(boolean friendlyFireEnabled) {
    this.friendlyFireEnabled = friendlyFireEnabled;
  }

  protected void cycleSignatureElement() {
    signatureElement = signatureElement.next();
    System.out.println(getClass().getSimpleName() + " element -> " + signatureElement);
  }

  private InventoryItem selectedInventoryItem() {
    return inventory.get(selectedInventoryIndex);
  }

  private void selectPreviousInventoryItem() {
    selectedInventoryIndex = (selectedInventoryIndex - 1 + inventory.size()) % inventory.size();
    System.out.println(getClass().getSimpleName() + " inventory -> " + selectedInventoryItem());
  }

  private void selectNextInventoryItem() {
    selectedInventoryIndex = (selectedInventoryIndex + 1) % inventory.size();
    System.out.println(getClass().getSimpleName() + " inventory -> " + selectedInventoryItem());
  }

  private void useSelectedInventoryItem() {
    switch (selectedInventoryItem()) {
      case ELEMENT_TUNER -> cycleSignatureElement();
    }
  }

  private StatusEffectType statusForElement(SignatureElement element) {
    return switch (element) {
      case FIRE -> StatusEffectType.BURN;
      case ICE -> StatusEffectType.FREEZE;
      case LIGHTNING -> StatusEffectType.CONDUCTIVE;
      case EARTH -> StatusEffectType.FRACTURE;
      case WIND -> StatusEffectType.HASTE_SLOW;
      case SHADOW -> StatusEffectType.OBSCURE;
    };
  }

  @Override
  public void applyDamage(double amount) {
    double mitigated = Math.max(0.0, amount - (defence.get() * 0.1));
    hp.damage(mitigated * damageTakenMultiplier);
  }

  @Override
  public void heal(double amount) {
    hp.heal(amount);
  }

  @Override
  public void setFrozen(boolean frozen) {
    this.frozen = frozen;
  }

  @Override
  public void modifyDamageTakenMultiplier(double delta) {
    damageTakenMultiplier = Math.max(0.1, damageTakenMultiplier + delta);
  }

  @Override
  public double getDamageTakenMultiplier() {
    return damageTakenMultiplier;
  }

  @Override
  public List<Entity> getNearbyEntities(double radius) {
    if (party.isEmpty()) {
      return Collections.emptyList();
    }
    double effectiveRadius = Math.max(0.0, radius * detectionRangeMultiplier);
    List<Entity> nearby = new ArrayList<>();
    for (Player other : party) {
      if (other == null || other == this) {
        continue;
      }
      double dx = other.x - x;
      double dy = other.y - y;
      if (Math.hypot(dx, dy) <= effectiveRadius) {
        nearby.add(other);
      }
    }
    return nearby;
  }

  @Override
  public void modifyAttackSpeedMultiplier(double delta) {
    attackSpeedMultiplier = Math.max(0.25, attackSpeedMultiplier + delta);
  }

  @Override
  public void modifyAccuracyMultiplier(double delta) {
    accuracyMultiplier = Math.max(0.05, accuracyMultiplier + delta);
  }

  @Override
  public void modifyDetectionRangeMultiplier(double delta) {
    detectionRangeMultiplier = Math.max(0.1, detectionRangeMultiplier + delta);
  }

  public void gainExp(int amount) {
    int oldLevel = level.getLevel();
    level.addExp(amount);
    int newLevel = level.getLevel();

    if (newLevel > oldLevel) {
      onLevelUp(newLevel);
    }
  }

  private void onLevelUp(int level) {
    hp.scale(level);
    mana.scale(level);
    ap.scale(level);
    defence.scale(level);
  }
}
