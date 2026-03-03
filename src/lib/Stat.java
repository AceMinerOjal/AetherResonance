package lib;

public abstract class Stat {
  protected double base;
  protected double value;
  protected double max;
  protected double regen;

  protected Stat(double base, double max, double regen) {
    this.base = base;
    this.value = base;
    this.max = max;
    this.regen = regen;
    clamp();
  }

  public void update(double dt) {
    if (regen != 0) {
      value += regen * dt;
      clamp();
    }
  }

  public void scale(int level) {
    double ratio = this.value / this.max;
    this.max = this.base * (1 + Math.pow((double) level / 128.0, 0.75) * 9);
    this.value = this.max * ratio;
    clamp();
  }

  protected void clamp() {
    if (max > 0) {
      if (value > max)
        value = max;
      if (value < 0)
        value = 0;
    }
  }

  public void set(double amount) {
    value = amount;
    clamp();
  }

  public void add(double amount) {
    value += amount;
    clamp();
  }

  public void consume(double amount) {
    value -= amount;
    clamp();
  }

  public boolean has(double amount) {
    return value >= amount;
  }

  public void restore() {
    if (max > 0)
      value = max;
  }

  public double get() {
    return value;
  }

  public double getBase() {
    return base;
  }

  public double getMax() {
    return max;
  }

  public double getRegen() {
    return regen;
  }

  public void setBase(double base) {
    this.base = base;
    clamp();
  }

  public void setMax(double max) {
    this.max = max;
    clamp();
  }

  public void setRegen(double regen) {
    this.regen = regen;
  }
}
