package com.reasonix.recipebook;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.drawable.GradientDrawable;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {
    private List<Recipe> recipes;
    private FavoriteStore favoriteStore;
    private CustomRecipeStore customRecipeStore;
    private WeeklyPlanStore weeklyPlanStore;
    private WebDavSettingsStore webDavSettingsStore;
    private WebDavBackupManager webDavBackupManager;
    private LinearLayout list;
    private TextView countText;
    private EditText searchInput;
    private String selectedCategory = "全部";
    private int selectedServings = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        favoriteStore = new FavoriteStore(this);
        customRecipeStore = new CustomRecipeStore(this);
        weeklyPlanStore = new WeeklyPlanStore(this);
        webDavSettingsStore = new WebDavSettingsStore(this);
        webDavBackupManager = new WebDavBackupManager();
        reloadRecipes();
        setContentView(buildHome());
        renderRecipes();
    }

    private View buildHome() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(color(0xFFFFF9F1));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(26));
        scroll.addView(root);

        TextView title = text("家常菜谱", 30, 0xFF26211E, true);
        root.addView(title);

        TextView subtitle = text("按食材、时间和口味快速找到今晚吃什么", 15, 0xFF776B62, false);
        subtitle.setPadding(0, dp(4), 0, dp(18));
        root.addView(subtitle);

        searchInput = new EditText(this);
        searchInput.setHint("搜索菜名、食材、标签");
        searchInput.setSingleLine(true);
        searchInput.setTextSize(16);
        searchInput.setPadding(dp(14), 0, dp(14), 0);
        searchInput.setMinHeight(dp(48));
        searchInput.setBackground(roundedBackground(0xFFFFFFFF, 8, 0xFFE8DDD1));
        root.addView(searchInput, matchWrap());
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderRecipes(); }
            @Override public void afterTextChanged(Editable s) { }
        });

        Button addRecipe = primaryButton("添加我会做的菜");
        addRecipe.setOnClickListener(v -> showAddRecipeDialog());
        root.addView(addRecipe, topMargin(dp(12)));

        Button weeklyPlan = greenButton("随机生成一周菜单");
        weeklyPlan.setOnClickListener(v -> generateWeeklyPlan());
        root.addView(weeklyPlan, topMargin(dp(10)));

        Button settings = outlineButton("设置与备份");
        settings.setOnClickListener(v -> showSettingsDialog());
        root.addView(settings, topMargin(dp(10)));

        View savedPlan = savedWeeklyPlanCard();
        if (savedPlan != null) {
            root.addView(savedPlan, topMargin(dp(12)));
        }

        root.addView(categoryBar());
        root.addView(recommendationCard());

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(18), 0, dp(8));
        TextView section = text("菜谱列表", 20, 0xFF26211E, true);
        header.addView(section, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        countText = text("", 14, 0xFF776B62, false);
        header.addView(countText);
        root.addView(header);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        return scroll;
    }

    private View savedWeeklyPlanCard() {
        List<String> plan = weeklyPlanStore.load();
        if (plan.size() != 7) {
            return null;
        }

        LinearLayout card = card();
        TextView label = text("本周菜单", 18, 0xFF26211E, true);
        card.addView(label);

        TextView dishes = text(weeklyPlanText(plan), 15, 0xFF5B514B, false);
        dishes.setPadding(0, dp(8), 0, 0);
        card.addView(dishes);

        Button edit = greenButton("手动更改菜单");
        edit.setOnClickListener(v -> showEditWeeklyPlanDialog(plan));
        card.addView(edit, topMargin(dp(12)));
        return card;
    }

    private void reloadRecipes() {
        recipes = new ArrayList<>(RecipeRepository.recipes());
        recipes.addAll(customRecipeStore.loadRecipes());
    }

    private View categoryBar() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setPadding(0, dp(14), 0, dp(10));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(row);

        for (String category : RecipeRepository.categories()) {
            Button button = chip(category, category.equals(selectedCategory));
            button.setOnClickListener(v -> {
                selectedCategory = category;
                setContentView(buildHome());
                searchInput.setText("");
                renderRecipes();
            });
            row.addView(button);
        }
        return scroll;
    }

    private View recommendationCard() {
        Recipe recipe = recipes.get(1);
        LinearLayout card = card();
        card.setBackground(roundedBackground(0xFFFFF0DD, 8, 0x00FFFFFF));

        TextView label = text("今日推荐", 13, 0xFFA93624, true);
        card.addView(label);

        TextView name = text(recipe.name, 24, 0xFF26211E, true);
        name.setPadding(0, dp(5), 0, dp(4));
        card.addView(name);

        TextView summary = text(recipe.summary, 15, 0xFF5B514B, false);
        card.addView(summary);

        Button open = primaryButton("查看做法");
        open.setOnClickListener(v -> showRecipe(recipe));
        card.addView(open, topMargin(dp(12)));
        return card;
    }

    private void renderRecipes() {
        if (list == null) return;
        list.removeAllViews();
        String query = searchInput == null ? "" : searchInput.getText().toString().trim().toLowerCase();
        List<Recipe> filtered = new ArrayList<>();
        for (Recipe recipe : recipes) {
            boolean categoryMatches = selectedCategory.equals("全部") || selectedCategory.equals(recipe.category);
            boolean queryMatches = query.isEmpty() || recipe.searchableText().contains(query);
            if (categoryMatches && queryMatches) {
                filtered.add(recipe);
            }
        }

        countText.setText(filtered.size() + " 道");
        if (filtered.isEmpty()) {
            TextView empty = text("没有找到合适的菜谱", 16, 0xFF776B62, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(32), 0, dp(32));
            list.addView(empty);
            return;
        }

        for (Recipe recipe : filtered) {
            list.addView(recipeCard(recipe), bottomMargin(dp(12)));
        }
    }

    private View recipeCard(Recipe recipe) {
        LinearLayout card = card();
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        top.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView name = text(recipe.name, 21, 0xFF26211E, true);
        info.addView(name);
        TextView meta = text(recipe.category + " · " + recipe.minutes + "分钟 · " + recipe.difficulty, 14, 0xFF776B62, false);
        meta.setPadding(0, dp(4), 0, 0);
        info.addView(meta);

        Button fav = chip(favoriteStore.isFavorite(recipe.id) ? "已收藏" : "收藏", favoriteStore.isFavorite(recipe.id));
        fav.setOnClickListener(v -> {
            favoriteStore.toggle(recipe.id);
            renderRecipes();
        });
        top.addView(fav);
        card.addView(top);

        TextView summary = text(recipe.summary, 15, 0xFF5B514B, false);
        summary.setPadding(0, dp(10), 0, dp(10));
        card.addView(summary);

        TextView tags = text(joinTags(recipe.tags), 13, 0xFF2D8C73, false);
        card.addView(tags);

        Button open = primaryButton("打开菜谱");
        open.setOnClickListener(v -> showRecipe(recipe));
        card.addView(open, topMargin(dp(12)));
        return card;
    }

    private void showRecipe(Recipe recipe) {
        selectedServings = recipe.servings;
        ScrollView detailScroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(10), dp(20), dp(6));
        detailScroll.addView(content);

        TextView meta = text(recipe.category + " · " + recipe.minutes + "分钟 · " + recipe.difficulty + " · 约" + recipe.calories + "千卡", 14, 0xFF776B62, false);
        content.addView(meta);

        TextView servingText = text("", 16, 0xFF26211E, true);
        servingText.setPadding(0, dp(16), 0, dp(8));
        content.addView(servingText);

        LinearLayout servingRow = new LinearLayout(this);
        Button minus = chip("-1人", false);
        Button plus = chip("+1人", false);
        servingRow.addView(minus);
        servingRow.addView(plus);
        content.addView(servingRow);

        TextView ingredients = text("", 15, 0xFF26211E, false);
        ingredients.setPadding(0, dp(14), 0, dp(14));
        content.addView(ingredients);

        TextView steps = text(stepsText(recipe), 15, 0xFF26211E, false);
        content.addView(steps);

        Runnable update = () -> {
            double scale = selectedServings / (double) recipe.servings;
            servingText.setText("用量换算：" + selectedServings + "人份");
            ingredients.setText(ingredientsText(recipe, scale));
        };
        minus.setOnClickListener(v -> {
            if (selectedServings > 1) {
                selectedServings--;
                update.run();
            }
        });
        plus.setOnClickListener(v -> {
            if (selectedServings < 8) {
                selectedServings++;
                update.run();
            }
        });
        update.run();

        new AlertDialog.Builder(this)
                .setTitle(recipe.name)
                .setView(detailScroll)
                .setPositiveButton("完成", null)
                .show();
    }

    private void showAddRecipeDialog() {
        EditText input = new EditText(this);
        input.setHint("例如：红烧排骨");
        input.setSingleLine(true);
        input.setTextSize(16);
        input.setPadding(dp(14), 0, dp(14), 0);
        input.setMinHeight(dp(48));
        input.setBackground(roundedBackground(0xFFFFFFFF, 8, 0xFFE8DDD1));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("添加菜谱")
                .setMessage("先输入菜名保存到菜谱里。")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                input.setError("请输入菜名");
                return;
            }
            customRecipeStore.addRecipeName(name);
            reloadRecipes();
            selectedCategory = "自定义";
            setContentView(buildHome());
            renderRecipes();
            Toast.makeText(this, "已保存到菜谱", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void showSettingsDialog() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(8), dp(18), dp(6));
        scroll.addView(content);

        TextView intro = text("建议设置项：WebDAV备份、每日菜数、是否允许重复、菜库分类、数据恢复。当前先实现 WebDAV 备份和恢复。", 14, 0xFF776B62, false);
        intro.setPadding(0, 0, 0, dp(12));
        content.addView(intro);

        EditText urlInput = labeledInput(content, "WebDAV地址", "例如：https://example.com/dav/recipebook-backup.json");
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setText(webDavSettingsStore.getUrl());

        EditText usernameInput = labeledInput(content, "账号", "WebDAV账号，可为空");
        usernameInput.setText(webDavSettingsStore.getUsername());

        EditText passwordInput = labeledInput(content, "密码", "WebDAV密码，可为空");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordInput.setText(webDavSettingsStore.getPassword());

        TextView hint = text("为保护数据，WebDAV地址需要使用 https://。如果地址以 / 结尾，会自动保存为 recipebook-backup.json。备份内容包括：我会做的菜、本周菜单。", 13, 0xFF776B62, false);
        hint.setPadding(0, dp(10), 0, dp(12));
        content.addView(hint);

        Button backup = greenButton("立即备份到WebDAV");
        Button restore = outlineButton("从WebDAV恢复");
        content.addView(backup);
        content.addView(restore, topMargin(dp(10)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(scroll)
                .setPositiveButton("完成", null)
                .create();

        backup.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (url.isEmpty()) {
                urlInput.setError("请输入WebDAV地址");
                return;
            }
            webDavSettingsStore.save(url, usernameInput.getText().toString(), passwordInput.getText().toString());
            backupToWebDav(url, usernameInput.getText().toString(), passwordInput.getText().toString());
        });

        restore.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (url.isEmpty()) {
                urlInput.setError("请输入WebDAV地址");
                return;
            }
            webDavSettingsStore.save(url, usernameInput.getText().toString(), passwordInput.getText().toString());
            confirmRestoreFromWebDav(url, usernameInput.getText().toString(), passwordInput.getText().toString(), dialog);
        });

        dialog.show();
    }

    private EditText labeledInput(LinearLayout parent, String label, String hint) {
        TextView labelView = text(label, 14, 0xFF26211E, true);
        labelView.setPadding(0, dp(8), 0, dp(4));
        parent.addView(labelView);

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setTextSize(15);
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setMinHeight(dp(46));
        input.setBackground(roundedBackground(0xFFFFFFFF, 8, 0xFFE8DDD1));
        parent.addView(input, matchWrap());
        return input;
    }

    private void backupToWebDav(String url, String username, String password) {
        Toast.makeText(this, "正在备份...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String json = webDavBackupManager.buildBackupJson(
                        customRecipeStore.exportNames(),
                        weeklyPlanStore.load()
                );
                webDavBackupManager.upload(url, username, password, json);
                runOnUiThread(() -> Toast.makeText(this, "WebDAV备份成功", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> showError("备份失败", e.getMessage()));
            }
        }).start();
    }

    private void confirmRestoreFromWebDav(String url, String username, String password, AlertDialog settingsDialog) {
        new AlertDialog.Builder(this)
                .setTitle("确认恢复")
                .setMessage("恢复会覆盖本机已保存的“我会做的菜”和“本周菜单”。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复", (dialog, which) -> restoreFromWebDav(url, username, password, settingsDialog))
                .show();
    }

    private void restoreFromWebDav(String url, String username, String password, AlertDialog settingsDialog) {
        Toast.makeText(this, "正在恢复...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String raw = webDavBackupManager.download(url, username, password);
                WebDavBackupManager.BackupData data = webDavBackupManager.parseBackupJson(raw);
                customRecipeStore.importNames(data.customDishNames);
                weeklyPlanStore.save(data.weeklyPlan);
                runOnUiThread(() -> {
                    reloadRecipes();
                    setContentView(buildHome());
                    renderRecipes();
                    settingsDialog.dismiss();
                    Toast.makeText(this, "WebDAV恢复成功", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> showError("恢复失败", e.getMessage()));
            }
        }).start();
    }

    private void showError(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message == null ? "请检查地址、账号、密码和网络。" : message)
                .setPositiveButton("知道了", null)
                .show();
    }

    private void generateWeeklyPlan() {
        List<Recipe> source = customRecipeStore.loadRecipes();
        if (source.isEmpty()) {
            source = recipes;
        }

        List<String> plan = pickSevenDishes(source);
        weeklyPlanStore.save(plan);
        setContentView(buildHome());
        renderRecipes();
        showWeeklyPlanDialog(plan);
    }

    private List<String> pickSevenDishes(List<Recipe> source) {
        List<String> pool = new ArrayList<>();
        for (Recipe recipe : source) {
            pool.add(recipe.name);
        }

        List<String> plan = new ArrayList<>();
        while (plan.size() < 7) {
            Collections.shuffle(pool);
            for (String dish : pool) {
                plan.add(dish);
                if (plan.size() == 7) {
                    break;
                }
            }
        }
        return plan;
    }

    private void showWeeklyPlanDialog(List<String> plan) {
        TextView content = text(weeklyPlanText(plan), 17, 0xFF26211E, false);
        content.setPadding(dp(20), dp(8), dp(20), dp(6));

        new AlertDialog.Builder(this)
                .setTitle("一周菜单已生成")
                .setView(content)
                .setNegativeButton("关闭", null)
                .setNeutralButton("手动更改", (dialog, which) -> showEditWeeklyPlanDialog(plan))
                .setPositiveButton("重新生成", (dialog, which) -> generateWeeklyPlan())
                .show();
    }

    private void showEditWeeklyPlanDialog(List<String> currentPlan) {
        String[] days = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        List<EditText> inputs = new ArrayList<>();

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(8), dp(18), dp(4));
        scroll.addView(content);

        for (int i = 0; i < days.length; i++) {
            TextView label = text(days[i], 14, 0xFF776B62, true);
            label.setPadding(0, i == 0 ? 0 : dp(10), 0, dp(4));
            content.addView(label);

            EditText input = new EditText(this);
            input.setSingleLine(true);
            input.setText(i < currentPlan.size() ? currentPlan.get(i) : "");
            input.setHint("输入菜名");
            input.setTextSize(16);
            input.setPadding(dp(12), 0, dp(12), 0);
            input.setMinHeight(dp(46));
            input.setBackground(roundedBackground(0xFFFFFFFF, 8, 0xFFE8DDD1));
            content.addView(input, matchWrap());
            inputs.add(input);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("手动更改本周菜单")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            List<String> editedPlan = new ArrayList<>();
            for (EditText input : inputs) {
                String dish = input.getText().toString().trim();
                if (dish.isEmpty()) {
                    input.setError("请输入菜名");
                    return;
                }
                editedPlan.add(dish);
            }

            weeklyPlanStore.save(editedPlan);
            setContentView(buildHome());
            renderRecipes();
            Toast.makeText(this, "本周菜单已保存", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private String weeklyPlanText(List<String> plan) {
        String[] days = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < days.length && i < plan.size(); i++) {
            text.append(days[i]).append("：").append(plan.get(i)).append('\n');
        }
        return text.toString();
    }

    private String ingredientsText(Recipe recipe, double scale) {
        StringBuilder text = new StringBuilder("用料\n");
        for (Ingredient ingredient : recipe.ingredients) {
            text.append("· ").append(ingredient.scaledText(scale)).append('\n');
        }
        return text.toString();
    }

    private String stepsText(Recipe recipe) {
        StringBuilder text = new StringBuilder("步骤\n");
        for (int i = 0; i < recipe.steps.size(); i++) {
            text.append(i + 1).append(". ").append(recipe.steps.get(i)).append('\n');
        }
        return text.toString();
    }

    private String joinTags(List<String> tags) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) text.append("  ");
            text.append(tags.get(i));
        }
        return text.toString();
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(roundedBackground(0xFFFFFFFF, 8, 0xFFE8DDD1));
        return card;
    }

    private Button chip(String label, boolean selected) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(color(selected ? 0xFFFFFFFF : 0xFF26211E));
        button.setBackground(roundedBackground(selected ? 0xFFD84F31 : 0xFFFFFFFF, 8, selected ? 0xFFD84F31 : 0xFFE8DDD1));
        button.setMinHeight(dp(42));
        button.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(42));
        params.setMargins(0, 0, dp(8), 0);
        button.setLayoutParams(params);
        return button;
    }

    private Button primaryButton(String label) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(15);
        button.setTextColor(color(0xFFFFFFFF));
        button.setBackground(roundedBackground(0xFFD84F31, 8, 0xFFD84F31));
        return button;
    }

    private Button greenButton(String label) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(15);
        button.setTextColor(color(0xFFFFFFFF));
        button.setBackground(roundedBackground(0xFF2D8C73, 8, 0xFF2D8C73));
        return button;
    }

    private Button outlineButton(String label) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(15);
        button.setTextColor(color(0xFF2D8C73));
        button.setBackground(roundedBackground(0xFFFFFFFF, 8, 0xFF2D8C73));
        return button;
    }

    private TextView text(String value, int sp, int hexColor, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color(hexColor));
        text.setLineSpacing(dp(2), 1.0f);
        if (bold) text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return text;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams topMargin(int top) {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, top, 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams bottomMargin(int bottom) {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, 0, 0, bottom);
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int color(int value) {
        return value;
    }

    private GradientDrawable roundedBackground(int fill, int radiusDp, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color(fill));
        drawable.setCornerRadius(dp(radiusDp));
        if ((stroke >>> 24) != 0) {
            drawable.setStroke(dp(1), color(stroke));
        }
        return drawable;
    }
}
