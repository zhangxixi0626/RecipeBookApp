package com.reasonix.recipebook;

import java.util.List;

public class Recipe {
    public final int id;
    public final String name;
    public final String category;
    public final String summary;
    public final String difficulty;
    public final int minutes;
    public final int servings;
    public final int calories;
    public final List<String> tags;
    public final List<Ingredient> ingredients;
    public final List<String> steps;

    public Recipe(
            int id,
            String name,
            String category,
            String summary,
            String difficulty,
            int minutes,
            int servings,
            int calories,
            List<String> tags,
            List<Ingredient> ingredients,
            List<String> steps
    ) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.summary = summary;
        this.difficulty = difficulty;
        this.minutes = minutes;
        this.servings = servings;
        this.calories = calories;
        this.tags = tags;
        this.ingredients = ingredients;
        this.steps = steps;
    }

    public String searchableText() {
        StringBuilder text = new StringBuilder();
        text.append(name).append(' ')
                .append(category).append(' ')
                .append(summary).append(' ')
                .append(difficulty).append(' ');
        for (String tag : tags) {
            text.append(tag).append(' ');
        }
        for (Ingredient ingredient : ingredients) {
            text.append(ingredient.name).append(' ');
        }
        return text.toString().toLowerCase();
    }
}
