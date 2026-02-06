package gg.mineral.bot.base.client.profile;

public class KnockbackProfile {
    private String name;
    private double friction;
    private double horizontal;
    private double vertical;
    private double verticalLimit;
    private double extraHorizontal;
    private double extraVertical;
    private double recoilMultiplier;

    public KnockbackProfile(String name, double friction, double horizontal, double vertical, double verticalLimit,
            double extraHorizontal, double extraVertical, double recoilMultiplier) {
        this.name = name;
        this.friction = friction;
        this.horizontal = horizontal;
        this.vertical = vertical;
        this.verticalLimit = verticalLimit;
        this.extraHorizontal = extraHorizontal;
        this.extraVertical = extraVertical;
        this.recoilMultiplier = recoilMultiplier;
    }

    public String getName() {
        return name;
    }

    public double getFriction() {
        return friction;
    }

    public double getHorizontal() {
        return horizontal;
    }

    public double getVertical() {
        return vertical;
    }

    public double getVerticalLimit() {
        return verticalLimit;
    }

    public double getExtraHorizontal() {
        return extraHorizontal;
    }

    public double getExtraVertical() {
        return extraVertical;
    }

    public double getRecoilMultiplier() {
        return recoilMultiplier;
    }
}
