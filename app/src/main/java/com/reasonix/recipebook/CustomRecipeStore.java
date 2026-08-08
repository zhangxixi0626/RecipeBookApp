package com.reasonix.recipebook;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CustomRecipeStore {
    private static final String PREFS = "custom_recipes";
    private static final String KEY_NAMES = "names";
    private static final int CUSTOM_ID_START = 10000;

    private final SharedPreferences prefs;

    public CustomRecipeStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<Recipe> loadRecipes() {
        List<Recipe> recipes = new ArrayList<>();
        List<String> names = loadNames();
        for (int i = 0; i < names.size(); i++) {
            recipes.add(createRecipe(CUSTOM_ID_START + i, names.get(i)));
        }
        return recipes;
    }

    public void addRecipeName(String name) {
        List<String> names = loadNames();
        names.add(name);
        saveNames(names);
    }

    public List<String> exportNames() {
        return loadNames();
    }

    public void importNames(List<String> names) {
        List<String> cleaned = new ArrayList<>();
        for (String name : names) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                cleaned.add(trimmed);
            }
        }
        saveNames(cleaned);
    }

    private Recipe createRecipe(int id, String name) {
        return new Recipe(
                id,
                name,
                "自定义",
                "这是你自己保存的菜谱，可以先记录菜名，后续再补充用料和步骤。",
                "待完善",
                0,
                2,
                0,
                Arrays.asList("自定义", "待完善"),
                Arrays.asList(new Ingredient("用料待补充", 1, "项")),
                Arrays.asList("后续可以继续增加用料、步骤和图片。")
        );
    }

    private List<String> loadNames() {
        List<String> names = new ArrayList<>();
        String raw = prefs.getString(KEY_NAMES, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String name = array.optString(i).trim();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        } catch (JSONException ignored) {
        }
        return names;
    }

    private void saveNames(List<String> names) {
        JSONArray array = new JSONArray();
        for (String name : names) {
            array.put(name);
        }
        prefs.edit().putString(KEY_NAMES, array.toString()).apply();
    }
}
