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
    private static final String[] PLAN_DAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final String[] PLAN_SLOTS = {"荤菜1", "荤菜2", "素菜1", "素菜2", "汤羹", "主食"};
    private static final int PLAN_SIZE = PLAN_DAYS.length * PLAN_SLOTS.length;

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

        TextView title = text("一周吃什么", 30, 0xFF26211E, true);
        root.addView(title);

        TextView subtitle = text("从你会做的菜里自动排好 7 天菜单，每天 6 个菜，也可以手动改。", 15, 0xFF776B62, false);
        subtitle.setPadding(0, dp(4), 0, dp(18));
        root.addView(subtitle);

        Button weeklyPlan = greenButton("随机生成一周菜单");
        weeklyPlan.setOnClickListener(v -> generateWeeklyPlan());
        root.addView(weeklyPlan);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);

        Button addRecipe = primaryButton("添加菜名");
        addRecipe.setOnClickListener(v -> showAddRecipeDialog());
        actionRow.addView(addRecipe, new LinearLayout.LayoutParams(0, dp(46), 1));

        Button settings = outlineButton("设置");
        settings.setOnClickListener(v -> showSettingsDialog());
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        settingsParams.setMargins(dp(10), 0, 0, 0);
        actionRow.addView(settings, settingsParams);
        root.addView(actionRow, topMargin(dp(10)));

        root.addView(planSummaryCard(), topMargin(dp(12)));
        root.addView(savedWeeklyPlanCard(), topMargin(dp(12)));

        LinearLayout libraryHeader = new LinearLayout(this);
        libraryHeader.setGravity(Gravity.CENTER_VERTICAL);
        libraryHeader.setPadding(0, dp(20), 0, dp(8));
        TextView libraryTitle = text("我会做的菜", 20, 0xFF26211E, true);
        libraryHeader.addView(libraryTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        countText = text("", 14, 0xFF776B62, false);
        libraryHeader.addView(countText);
        root.addView(libraryHeader);

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
        root.addView(categoryBar());

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        return scroll;
    }

    private View planSummaryCard() {
        LinearLayout card = card();
        card.setBackground(roundedBackground(0xFFFFF0DD, 8, 0xFFFFD0A3));

        TextView label = text("本周计划", 13, 0xFFA93624, true);
        card.addView(label);

        int customCount = customRecipeStore.exportNames().size();
        List<String> savedPlan = weeklyPlanStore.load();
        String summary = "菜库：" + customCount + " 道自定义菜 · 本周：" + filledPlanCount(savedPlan) + "/" + PLAN_SIZE + " 个位置";
        TextView value = text(summary, 18, 0xFF26211E, true);
        value.setPadding(0, dp(6), 0, dp(4));
        card.addView(value);

        TextView hint = text("先把你会做的菜加进菜库，再点随机生成。菜不够时会自动重复，生成后仍可逐格改。", 14, 0xFF5B514B, false);
        card.addView(hint);
        return card;
    }

    private View savedWeeklyPlanCard() {
        List<String> plan = normalizePlan(weeklyPlanStore.load());
        LinearLayout card = card();

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text("一周菜单表", 20, 0xFF26211E, true);
        top.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button edit = chip("手动改", false);
        edit.setOnClickListener(v -> showEditWeeklyPlanDialog(plan));
        top.addView(edit);
        card.addView(top);

        if (filledPlanCount(plan) == 0) {
            TextView empty = text("还没有生成菜单。点上面的“随机生成一周菜单”，这里会出现 7 天 × 6 道菜。", 15, 0xFF776B62, false);
            empty.setPadding(0, dp(12), 0, 0);
            card.addView(empty);
            return card;
        }

        for (int day = 0; day < PLAN_DAYS.length; day++) {
            card.addView(dayPlanView(plan, day), topMargin(day == 0 ? dp(12) : dp(10)));
        }
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

    private View dayPlanView(List<String> plan, int dayIndex) {
        LinearLayout day = new LinearLayout(this);
        day.setOrientation(LinearLayout.VERTICAL);
        day.setPadding(dp(12), dp(10), dp(12), dp(10));
        day.setBackground(roundedBackground(dayIndex % 2 == 0 ? 0xFFFFFCF7 : 0xFFF7FBF7, 8, 0xFFE8DDD1));

        TextView dayTitle = text(PLAN_DAYS[dayIndex], 17, 0xFF26211E, true);
        day.addView(dayTitle);

        for (int slot = 0; slot < PLAN_SLOTS.length; slot++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, 0);

            TextView slotLabel = text(PLAN_SLOTS[slot], 13, 0xFF776B62, true);
            row.addView(slotLabel, new LinearLayout.LayoutParams(dp(52), LinearLayout.LayoutParams.WRAP_CONTENT));

            String dish = planDish(plan, dayIndex, slot);
            TextView dishText = text(dish.isEmpty() ? "待安排" : dish, 15, dish.isEmpty() ? 0xFFAAA19A : 0xFF26211E, false);
            row.addView(dishText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            Button change = chip("换", false);
            change.setMinHeight(dp(34));
            change.setOnClickListener(v -> showChangePlanDishDialog(dayIndex, slot, normalizePlan(weeklyPlanStore.load())));
            row.addView(change, new LinearLayout.LayoutParams(dp(58), dp(36)));
            day.addView(row);
        }
        return day;
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

        EditText serverUrlInput = labeledInput(content, "WebDAV服务器地址", "例如：https://example.com/dav/");
        serverUrlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        serverUrlInput.setText(webDavSettingsStore.getServerUrl());

        EditText targetPathInput = labeledInput(content, "目标地址", "例如：recipebook/recipebook-backup.json");
        targetPathInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        targetPathInput.setText(webDavSettingsStore.getTargetPath());

        EditText usernameInput = labeledInput(content, "账号", "WebDAV账号，可为空");
        usernameInput.setText(webDavSettingsStore.getUsername());

        EditText passwordInput = labeledInput(content, "密码", "WebDAV密码，可为空");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordInput.setText(webDavSettingsStore.getPassword());

        TextView hint = text("为保护数据，WebDAV服务器地址需要使用 https://。目标地址是备份文件在网盘里的路径，留空会使用 recipebook-backup.json。备份内容包括：我会做的菜、本周菜单。", 13, 0xFF776B62, false);
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
            String serverUrl = serverUrlInput.getText().toString().trim();
            String targetPath = targetPathInput.getText().toString().trim();
            if (serverUrl.isEmpty()) {
                serverUrlInput.setError("请输入WebDAV服务器地址");
                return;
            }
            webDavSettingsStore.save(serverUrl, targetPath, usernameInput.getText().toString(), passwordInput.getText().toString());
            backupToWebDav(serverUrl, targetPath, usernameInput.getText().toString(), passwordInput.getText().toString());
        });

        restore.setOnClickListener(v -> {
            String serverUrl = serverUrlInput.getText().toString().trim();
            String targetPath = targetPathInput.getText().toString().trim();
            if (serverUrl.isEmpty()) {
                serverUrlInput.setError("请输入WebDAV服务器地址");
                return;
            }
            webDavSettingsStore.save(serverUrl, targetPath, usernameInput.getText().toString(), passwordInput.getText().toString());
            confirmRestoreFromWebDav(serverUrl, targetPath, usernameInput.getText().toString(), passwordInput.getText().toString(), dialog);
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

    private void backupToWebDav(String serverUrl, String targetPath, String username, String password) {
        Toast.makeText(this, "正在备份...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String json = webDavBackupManager.buildBackupJson(
                        customRecipeStore.exportNames(),
                        weeklyPlanStore.load()
                );
                webDavBackupManager.upload(serverUrl, targetPath, username, password, json);
                runOnUiThread(() -> Toast.makeText(this, "WebDAV备份成功", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> showError("备份失败", e.getMessage()));
            }
        }).start();
    }

    private void confirmRestoreFromWebDav(String serverUrl, String targetPath, String username, String password, AlertDialog settingsDialog) {
        new AlertDialog.Builder(this)
                .setTitle("确认恢复")
                .setMessage("恢复会覆盖本机已保存的“我会做的菜”和“本周菜单”。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复", (dialog, which) -> restoreFromWebDav(serverUrl, targetPath, username, password, settingsDialog))
                .show();
    }

    private void restoreFromWebDav(String serverUrl, String targetPath, String username, String password, AlertDialog settingsDialog) {
        Toast.makeText(this, "正在恢复...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String raw = webDavBackupManager.download(serverUrl, targetPath, username, password);
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

        List<String> plan = pickPlanDishes(source, PLAN_SIZE);
        weeklyPlanStore.save(plan);
        setContentView(buildHome());
        renderRecipes();
        showWeeklyPlanDialog(plan);
    }

    private List<String> pickPlanDishes(List<Recipe> source, int targetCount) {
        List<String> pool = new ArrayList<>();
        for (Recipe recipe : source) {
            pool.add(recipe.name);
        }

        List<String> plan = new ArrayList<>();
        while (plan.size() < targetCount) {
            Collections.shuffle(pool);
            for (String dish : pool) {
                plan.add(dish);
                if (plan.size() == targetCount) {
                    break;
                }
            }
        }
        return plan;
    }

    private void showWeeklyPlanDialog(List<String> plan) {
        TextView content = text(weeklyPlanText(normalizePlan(plan)), 15, 0xFF26211E, false);
        content.setPadding(dp(20), dp(8), dp(20), dp(6));

        new AlertDialog.Builder(this)
                .setTitle("已生成 7 天菜单")
                .setView(content)
                .setNegativeButton("关闭", null)
                .setNeutralButton("手动更改", (dialog, which) -> showEditWeeklyPlanDialog(plan))
                .setPositiveButton("重新生成", (dialog, which) -> generateWeeklyPlan())
                .show();
    }

    private void showEditWeeklyPlanDialog(List<String> currentPlan) {
        List<EditText> inputs = new ArrayList<>();
        List<String> plan = normalizePlan(currentPlan);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(8), dp(18), dp(4));
        scroll.addView(content);

        for (int day = 0; day < PLAN_DAYS.length; day++) {
            TextView label = text(PLAN_DAYS[day], 16, 0xFF26211E, true);
            label.setPadding(0, day == 0 ? 0 : dp(14), 0, dp(4));
            content.addView(label);

            for (int slot = 0; slot < PLAN_SLOTS.length; slot++) {
                EditText input = new EditText(this);
                input.setSingleLine(true);
                input.setText(planDish(plan, day, slot));
                input.setHint(PLAN_SLOTS[slot]);
                input.setTextSize(15);
                input.setPadding(dp(12), 0, dp(12), 0);
                input.setMinHeight(dp(44));
                input.setBackground(roundedBackground(0xFFFFFFFF, 8, 0xFFE8DDD1));
                content.addView(input, topMargin(slot == 0 ? dp(4) : dp(8)));
                inputs.add(input);
            }
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("手动更改 42 个菜")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            List<String> editedPlan = new ArrayList<>();
            for (EditText input : inputs) {
                String dish = input.getText().toString().trim();
                if (dish.isEmpty()) {
                    dish = "待安排";
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
        StringBuilder text = new StringBuilder();
        for (int day = 0; day < PLAN_DAYS.length; day++) {
            text.append(PLAN_DAYS[day]).append('\n');
            for (int slot = 0; slot < PLAN_SLOTS.length; slot++) {
                text.append(PLAN_SLOTS[slot]).append("：").append(planDish(plan, day, slot)).append('\n');
            }
            if (day < PLAN_DAYS.length - 1) {
                text.append('\n');
            }
        }
        return text.toString();
    }

    private void showChangePlanDishDialog(int dayIndex, int slotIndex, List<String> currentPlan) {
        List<String> choices = new ArrayList<>();
        List<Recipe> source = customRecipeStore.loadRecipes();
        if (source.isEmpty()) {
            source = recipes;
        }
        for (Recipe recipe : source) {
            choices.add(recipe.name);
        }

        String[] items = new String[choices.size() + 1];
        items[0] = "手动输入菜名";
        for (int i = 0; i < choices.size(); i++) {
            items[i + 1] = choices.get(i);
        }

        new AlertDialog.Builder(this)
                .setTitle(PLAN_DAYS[dayIndex] + " · " + PLAN_SLOTS[slotIndex])
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        showManualPlanDishInput(dayIndex, slotIndex, currentPlan);
                    } else {
                        updatePlanDish(dayIndex, slotIndex, choices.get(which - 1), currentPlan);
                    }
                })
                .show();
    }

    private void showManualPlanDishInput(int dayIndex, int slotIndex, List<String> currentPlan) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(planDish(currentPlan, dayIndex, slotIndex));
        input.setHint("输入菜名");
        input.setTextSize(16);
        input.setPadding(dp(14), 0, dp(14), 0);
        input.setMinHeight(dp(48));
        input.setBackground(roundedBackground(0xFFFFFFFF, 8, 0xFFE8DDD1));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("更换菜名")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String dish = input.getText().toString().trim();
            if (dish.isEmpty()) {
                input.setError("请输入菜名");
                return;
            }
            updatePlanDish(dayIndex, slotIndex, dish, currentPlan);
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void updatePlanDish(int dayIndex, int slotIndex, String dish, List<String> currentPlan) {
        List<String> plan = normalizePlan(currentPlan);
        plan.set(planIndex(dayIndex, slotIndex), dish);
        weeklyPlanStore.save(plan);
        setContentView(buildHome());
        renderRecipes();
        Toast.makeText(this, "已更换为：" + dish, Toast.LENGTH_SHORT).show();
    }

    private List<String> normalizePlan(List<String> rawPlan) {
        List<String> plan = new ArrayList<>();
        if (rawPlan.size() == PLAN_DAYS.length) {
            for (int day = 0; day < PLAN_DAYS.length; day++) {
                plan.add(rawPlan.get(day));
                for (int slot = 1; slot < PLAN_SLOTS.length; slot++) {
                    plan.add("");
                }
            }
        } else {
            for (int i = 0; i < rawPlan.size() && i < PLAN_SIZE; i++) {
                plan.add(rawPlan.get(i));
            }
        }
        while (plan.size() < PLAN_SIZE) {
            plan.add("");
        }
        return plan;
    }

    private int filledPlanCount(List<String> plan) {
        int count = 0;
        for (String dish : normalizePlan(plan)) {
            String trimmed = dish.trim();
            if (!trimmed.isEmpty() && !trimmed.equals("待安排")) {
                count++;
            }
        }
        return count;
    }

    private String planDish(List<String> plan, int dayIndex, int slotIndex) {
        int index = planIndex(dayIndex, slotIndex);
        if (index >= plan.size()) {
            return "";
        }
        return plan.get(index).trim();
    }

    private int planIndex(int dayIndex, int slotIndex) {
        return dayIndex * PLAN_SLOTS.length + slotIndex;
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
