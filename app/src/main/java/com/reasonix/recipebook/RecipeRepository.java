package com.reasonix.recipebook;

import java.util.Arrays;
import java.util.List;

public class RecipeRepository {
    public static List<String> categories() {
        return Arrays.asList("全部", "快手菜", "家常菜", "汤羹", "主食", "轻食", "自定义");
    }

    public static List<Recipe> recipes() {
        return Arrays.asList(
                new Recipe(
                        1,
                        "番茄炒蛋",
                        "快手菜",
                        "酸甜下饭，十分钟就能端上桌。",
                        "简单",
                        12,
                        2,
                        320,
                        Arrays.asList("下饭", "儿童友好", "省时"),
                        Arrays.asList(
                                new Ingredient("鸡蛋", 3, "个"),
                                new Ingredient("番茄", 2, "个"),
                                new Ingredient("葱花", 1, "小把"),
                                new Ingredient("盐", 2, "克"),
                                new Ingredient("糖", 4, "克")
                        ),
                        Arrays.asList(
                                "鸡蛋打散，番茄切块。",
                                "热锅下油，倒入蛋液炒到刚凝固后盛出。",
                                "番茄下锅炒出汁，加盐和少量糖。",
                                "倒回鸡蛋翻匀，撒葱花出锅。"
                        )
                ),
                new Recipe(
                        2,
                        "土豆炖牛腩",
                        "家常菜",
                        "软烂入味，适合周末多做一锅。",
                        "中等",
                        70,
                        3,
                        680,
                        Arrays.asList("高蛋白", "便当", "炖菜"),
                        Arrays.asList(
                                new Ingredient("牛腩", 500, "克"),
                                new Ingredient("土豆", 2, "个"),
                                new Ingredient("胡萝卜", 1, "根"),
                                new Ingredient("姜片", 4, "片"),
                                new Ingredient("生抽", 2, "勺"),
                                new Ingredient("料酒", 1, "勺")
                        ),
                        Arrays.asList(
                                "牛腩冷水下锅焯水，捞出冲净。",
                                "锅里放姜片和牛腩煸香，加生抽、料酒和热水。",
                                "小火炖约五十分钟。",
                                "加入土豆和胡萝卜，再炖二十分钟，收汁调味。"
                        )
                ),
                new Recipe(
                        3,
                        "菌菇鸡汤",
                        "汤羹",
                        "清爽鲜甜，不用复杂调料。",
                        "简单",
                        55,
                        3,
                        420,
                        Arrays.asList("暖胃", "低油", "晚餐"),
                        Arrays.asList(
                                new Ingredient("鸡腿", 2, "只"),
                                new Ingredient("香菇", 6, "朵"),
                                new Ingredient("白玉菇", 150, "克"),
                                new Ingredient("姜片", 3, "片"),
                                new Ingredient("盐", 3, "克")
                        ),
                        Arrays.asList(
                                "鸡腿焯水，菌菇洗净。",
                                "锅中放鸡腿、姜片和足量清水。",
                                "小火煮四十分钟后加入菌菇。",
                                "再煮十分钟，加盐调味。"
                        )
                ),
                new Recipe(
                        4,
                        "虾仁蛋炒饭",
                        "主食",
                        "剩饭也能做出香气，适合一人食。",
                        "简单",
                        15,
                        1,
                        560,
                        Arrays.asList("一人食", "剩饭", "快手"),
                        Arrays.asList(
                                new Ingredient("米饭", 1, "碗"),
                                new Ingredient("虾仁", 8, "只"),
                                new Ingredient("鸡蛋", 1, "个"),
                                new Ingredient("豌豆", 30, "克"),
                                new Ingredient("生抽", 1, "勺")
                        ),
                        Arrays.asList(
                                "鸡蛋炒散，虾仁煎到变色。",
                                "倒入米饭压散，加入豌豆翻炒。",
                                "放生抽调味，最后倒回鸡蛋炒匀。"
                        )
                ),
                new Recipe(
                        5,
                        "凉拌鸡胸肉",
                        "轻食",
                        "低脂高蛋白，适合健身和晚餐。",
                        "简单",
                        25,
                        2,
                        360,
                        Arrays.asList("低脂", "高蛋白", "清爽"),
                        Arrays.asList(
                                new Ingredient("鸡胸肉", 250, "克"),
                                new Ingredient("黄瓜", 1, "根"),
                                new Ingredient("蒜末", 1, "勺"),
                                new Ingredient("生抽", 1.5, "勺"),
                                new Ingredient("香醋", 1, "勺"),
                                new Ingredient("辣椒油", 1, "勺")
                        ),
                        Arrays.asList(
                                "鸡胸肉煮熟后撕成细条。",
                                "黄瓜切丝，和鸡胸肉放入碗中。",
                                "加入蒜末、生抽、香醋和辣椒油拌匀。"
                        )
                ),
                new Recipe(
                        6,
                        "蒜蓉西兰花",
                        "家常菜",
                        "颜色清亮，适合做日常蔬菜配菜。",
                        "简单",
                        10,
                        2,
                        180,
                        Arrays.asList("素菜", "低卡", "快手"),
                        Arrays.asList(
                                new Ingredient("西兰花", 1, "颗"),
                                new Ingredient("蒜末", 1, "勺"),
                                new Ingredient("盐", 2, "克"),
                                new Ingredient("蚝油", 1, "勺")
                        ),
                        Arrays.asList(
                                "西兰花掰小朵，焯水一分钟。",
                                "热锅放油，下蒜末炒香。",
                                "倒入西兰花，加盐和蚝油快速翻匀。"
                        )
                )
        );
    }
}
