package com.reasonix.recipebook;

public class Ingredient {
    public final String name;
    public final double amount;
    public final String unit;

    public Ingredient(String name, double amount, String unit) {
        this.name = name;
        this.amount = amount;
        this.unit = unit;
    }

    public String scaledText(double scale) {
        double value = amount * scale;
        String amountText = value == Math.floor(value)
                ? String.valueOf((int) value)
                : String.format("%.1f", value);
        return amountText + unit + " " + name;
    }
}
