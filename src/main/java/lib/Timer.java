package lib;

public abstract class Timer {
  private double duration;
  private double timeLeft;
  private boolean active;

  public Timer(double duration) {
    this.duration = duration;
    this.timeLeft = 0;
    this.active = false;
  }

  public static Timer of(double duration, Runnable onFinish) {
    return new Timer(duration) {
      @Override public void onFinish() { onFinish.run(); }
    };
  }

  public void start() {
    this.timeLeft = duration;
    this.active = true;
  }

  public void update(double dt) {
    if (!active)
      return;

    timeLeft -= dt;
    if (timeLeft <= 0) {
      timeLeft = 0;
      active = false;
      onFinish();
    }
  }

  public abstract void onFinish();

  public boolean isActive() {
    return active;
  }

  public void finish() {
    if (active) {
      active = false;
      timeLeft = 0;
      onFinish();
    }
  }
}
