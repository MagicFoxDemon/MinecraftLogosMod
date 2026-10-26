package dev.logos.lang;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Example spells. None of these exist as runes: each is composed from orthogonal primitives,
 * which is exactly what the prototype is meant to validate.
 */
public final class Examples {
    public static final Map<String, String> ALL = new LinkedHashMap<>();
    private static final Map<String, String> ID_BY_SOURCE = new HashMap<>();

    private Examples() {}

    private static void add(String id, String src) {
        String s = src.stripIndent().strip() + "\n";
        ALL.put(id, s);
        ID_BY_SOURCE.put(s, id);
    }

    /** The example a source is, if it is one unchanged; its display name is then {@code logos.example.<id>}. */
    public static String idOf(String source) {
        return source == null ? null : ID_BY_SOURCE.get(source);
    }

    static {
        add("hello", """
                ; 最简单的术式：弹丸 → 命中 → 伤害
                (spell :name "启蒙魔弹" :mana 10
                  (projectile
                    (on-hit (damage target minor))))
                """);

        add("fireball", """
                ; 爆炎火球：没有"火球"符文，只有 弹丸 + 命中 + 伤害 + 消散时的范围
                (spell :name "爆炎火球" :mana 30
                  (projectile :speed 1.4
                    (on-hit
                      (damage target standard fire))
                    (on-expire
                      (each (enemies :radius 3)
                        (damage target minor fire)
                        (ignite target 3)))))
                """);

        add("frost-nova", """
                ; 冰霜新星：没有形体，直接选择周身敌人
                (spell :name "冰霜新星" :mana 50
                  (each (enemies :at (pos-of caster) :radius 6)
                    (damage target minor frost)
                    (freeze target 3)
                    (push target 1 :from (pos-of caster))))
                """);

        add("chain-lightning", """
                ; 连锁闪电：射线 + 递归 + :fresh（跳过已命中者）
                ; 注入多少魔力，就跳多少次
                (spell :name "连锁闪电" :mana 60
                  (ray :to (nearest :radius 12 :fresh)
                    (on-hit
                      (damage target 5 lightning)
                      (recurse))))
                """);

        add("meteor", """
                ; 陨星天降：记住落点 → 预警领域 → 延迟 → 从天而降的抛物弹丸
                (spell :name "陨星天降" :mana 90
                  (let $p (look-point 40)
                    (field :at $p :radius 5 :life 1.5 :every 2)
                    (delay 1.5
                      (projectile :from (above $p 16) :dir down :speed 1.2
                                  :motion arc :size 0.9
                        (on-expire
                          (each (enemies :radius 5)
                            (damage target major physical)
                            (damage target standard fire)
                            (ignite target 4)
                            (launch target 0.8)))))))
                """);

        add("star-pull", """
                ; 群星牵引：追踪魔弹只"记录"敌人，
                ; 消散时把所有被记录者拖向终点
                (spell :name "群星牵引" :mana 70
                  (split 5 :spread 70
                    (projectile :motion homing :speed 0.8 :life 2.5 :pierce 2
                      (on-hit (mark target #star))
                      (on-expire
                        (each (marked #star :radius 24)
                          (pull target 1.5)
                          (unmark target #star))))))
                """);

        add("execute-proof", """
                ; 法则证明：if 中的命题成为 execute 的证明
                ; 证明 hp≤30% 且燃烧 → 代价 = 4×最大生命×0.3×0.75
                (spell :name "论证处决" :mana 60
                  (ray :range 24
                    (on-hit
                      (if (and (< (hp-ratio target) 0.3)
                               (has target burning))
                        (execute target)
                        (do
                          (ignite target 4)
                          (damage target standard fire))))))
                """);

        add("execute-brute", """
                ; 对照组：不给任何证明的处决
                ; 代价 = 4×最大生命（僵尸 80，铁傀儡 400）
                (spell :name "蛮力处决" :mana 120
                  (ray :range 24
                    (on-hit (execute target))))
                """);

        add("ash-mark", """
                ; 证明链 1/3：点燃并刻下 #ash（若已冻结，多刻两层）
                (spell :name "灰烬之印" :mana 30
                  (ray :range 24
                    (on-hit
                      (ignite target 6)
                      (mark target #ash)
                      (if (has target frozen)
                        (mark target #ash 2)))))
                """);

        add("frost-bind", """
                ; 证明链 2/3：冻结
                (spell :name "寒霜之缚" :mana 30
                  (ray :range 24
                    (on-hit
                      (freeze target 5)
                      (damage target minor frost))))
                """);

        add("sever", """
                ; 证明链 3/3：一步步证明 Boss 应该死
                ; 4 个前提 → 代价 ≈ 最大生命 × 0.41（无证明时为 ×4）
                (spell :name "终证·断魂" :mana 200
                  (ray :range 32
                    (on-hit
                      (if (and (< (hp-ratio target) 0.25)
                               (has target burning)
                               (has target frozen)
                               (>= (stacks target #ash) 3))
                        (execute target)
                        (echo (hp-ratio target))))))
                """);

        add("paradox", """
                ; 悖论：证明成立后又亲手推翻它
                ; 执行时前提不再成立 → 术式反噬施法者
                (spell :name "悖论反噬" :mana 80
                  (ray :range 24
                    (on-hit
                      (if (< (hp-ratio target) 0.5)
                        (do
                          (heal target 20)
                          (execute target))))))
                """);

        add("scarlet", """
                ; 血印裂变：三层印记后分裂，击杀处展开汲取领域
                ; 整个术式共享 160 魔力，耗尽即熄灭
                (spell :name "血印裂变" :mana 160
                  (projectile :motion homing :speed 1
                    (on-hit
                      (damage target 4 void)
                      (mark target #blood)
                      (if (>= (stacks target #blood) 3)
                        (do
                          (unmark target #blood)
                          (split 3 :spread 120
                            (projectile :from (above here 1.5) :speed 0.8 :motion homing
                              (on-hit
                                (damage target 4 void
                                  (on-kill
                                    (field :radius 3 :life 4 :every 0.5
                                      (on-tick
                                        (each (enemies :radius 3)
                                          (drain target 1))))))))))))))
                """);

        add("blood-aura", """
                ; 吸血光环：跟随施法者的领域
                (spell :name "汲血光环" :mana 80
                  (field :radius 4 :life 8 :every 1 :follow
                    (on-tick
                      (each (enemies :radius 4)
                        (drain target 1.5)))))
                """);

        add("mana-radius", """
                ; 以魔定域：注入越多，范围越大（试着修改 :mana）
                (spell :name "以魔定域" :mana 90
                  (let $r (/ (mana-left) 15)
                    (let $p (look-point 30)
                      (field :at $p :radius $r :life 0.6 :every 0.5)
                      (each (enemies :at $p :radius $r)
                        (damage target 3 frost)
                        (freeze target 2)))))
                """);

        add("orbit", """
                ; 环星护卫：同一个 split 作用于"环绕"运动
                (spell :name "环星护卫" :mana 60
                  (split 3 :spread 360
                    (projectile :motion orbit :speed 1 :life 8 :pierce 99
                      (on-hit
                        (damage target minor physical)
                        (push target 0.6 :from (pos-of caster))))))
                """);

        add("blink", """
                ; 闪现
                (spell :name "闪现" :mana 20
                  (blink (look-point 14)))
                """);

        add("thorns", """
                ; 被动（受伤时）：target 是攻击者
                ; 写进术式载页挂在法杖上即生效，/logos passives 查看
                (spell :name "雷鸣反击" :trigger hurt :mana 15
                  (if (alive target)
                    (ray :to target
                      (on-hit
                        (damage target minor lightning)
                        (push target 1 :from (pos-of caster))))))
                """);

        add("corpse-burst", """
                ; 被动（击杀时）：here 是死亡位置
                (spell :name "亡骸爆燃" :trigger kill :mana 25
                  (each (enemies :radius 4)
                    (damage target standard fire)
                    (launch target 0.5)))
                """);

        // ---- 作用于方块、掉落物、玩家与维度 ----

        add("harvest", """
                ; 丰饶之触：看着的方块周围，作物长一阶，能用骨粉的方块像用了骨粉
                (spell :name "丰饶之触" :mana 60
                  (grow (look-block 24) 2))
                """);

        add("wither-touch", """
                ; 衰朽之触：作物倒退一阶，树叶凋落，草和花枯死
                (spell :name "衰朽之触" :mana 50
                  (decay (look-block 24) 2))
                """);

        add("debug-quill", """
                ; 调向之笔：像调试棒一样切换看着的方块的朝向（不触发更新）
                (spell :name "调向之笔" :mana 10
                  (toggle-state (look-block 8) :property "facing"))
                """);

        add("lumberjack", """
                ; 伐林：看着的那棵树周围的原木全部砍倒（按 #minecraft:logs 标签筛选）
                (spell :name "伐林" :mana 120
                  (break-block
                    (blocks :at (look-block 16) :radius 5 :filter "#minecraft:logs")))
                """);

        add("magnet", """
                ; 磁引拾遗：把附近的掉落物吸到身边
                (spell :name "磁引拾遗" :mana 40
                  (pull (items :radius 12) 2 :to (pos-of caster)))
                """);

        add("ember-bolt", """
                ; 燎原火种：弹丸撞上方块时，在那里点火（on-impact 的 here 就是被撞的方块）
                (spell :name "燎原火种" :mana 20
                  (projectile :speed 1.5
                    (on-impact (set-fire here))
                    (on-hit (ignite target 4))))
                """);

        add("npc-heal", """
                ; 抚生之触：沿视线治疗第一个非玩家生物，恢复 10 点生命（5 颗心）；也能治疗使魔与宠物
                (spell :name "抚生之触" :mana 20
                  (ray :range 24
                    (on-hit (heal target 10))))
                """);

        add("npc-group-heal", """
                ; 生息之环：治疗施法者周围 16 格内的非玩家生物，每个恢复 6 点生命；不区分敌我
                ; 100 魔力包含选择器的 2 点和最多 10 个目标的治疗费用（每个 9 点）
                (spell :name "生息之环" :mana 100
                  (heal (entities :at (pos-of caster) :radius 16 :filter (kind living)) 6))
                """);

        add("group-heal", """
                ; 同袍之愈：:players true 才能选择、影响其他玩家
                (spell :name "同袍之愈" :players true :mana 80
                  (heal (players :radius 8) 6)
                  (heal caster 6))
                """);

        add("nether-step", """
                ; 下界跃迁：瞬移到下界的对应位置（水平坐标 ÷ 8），会自动找能站立的空位
                (spell :name "下界跃迁" :mana 60
                  (blink (in-dimension @the_nether (pos-of caster))))
                """);

        add("storm-hammer", """
                ; 回旋雷槌：:look 只决定弹丸长什么样（这里借用重锤的模型，每秒翻转 2 圈）
                ; 它依然是同一个 projectile：折返、穿透、命中事件都照常工作
                (spell :name "回旋雷槌" :mana 50
                  (projectile :look &mace :spin 2 :motion boomerang :speed 1.1 :pierce 3
                    (on-hit
                      (damage target standard lightning)
                      (push target 1))))
                """);

        add("ghast-charge", """
                ; 恶魂火弹：外观借用火焰弹；火焰来自 damage 与 set-fire，而不是外观
                (spell :name "恶魂火弹" :mana 30
                  (projectile :look &fire_charge :speed 1.0
                    (on-hit (damage target standard fire))
                    (on-impact (set-fire here))))
                """);

        add("specium", """
                ; 光柱：从眼前放出一道粗光束，转身时立刻跟着扫过去
                ; 每 0.2 秒对光柱里的每个敌人造成伤害；照到方块就点火
                (spell :name "光柱横扫" :mana 80
                  (beam :radius 0.6 :range 28 :life 3 :every 0.2
                    (on-hit (damage target minor lightning))
                    (on-impact (set-fire here))))
                """);

        add("arrow-ward", """
                ; 偏箭之风：⊙ 筛选 (kind projectiles) 选出身边飞行中的箭矢、火球，把它们推开
                ; 亡灵之外的敌人还会被点燃：(except (kind enemies) "#minecraft:undead")
                (spell :name "偏箭之风" :mana 120
                  (field :follow :radius 4 :life 6 :every 0.25
                    (on-tick
                      (push (entities :radius 4 :filter (kind projectiles)) 1.5)
                      (ignite (enemies :radius 4 :filter (except (kind enemies) "#minecraft:undead")) 1))))
                """);

        add("aegis-wall", """
                ; 守御之壁：在眼前凝出一面单向的墙。从正面来的箭矢、火球、法术和爆炸都会被挡下，
                ; 施法者自己的法术可以从背面穿出去；挡下东西时让它发光
                (spell :name "守御之壁" :mana 40
                  (construct :shape wall :width 5 :height 3 :life 15 :one-way
                    (on-block (glow target 2))))
                """);

        add("sky-bridge", """
                ; 凌空栈道：脚下凝出一条对齐方块网格的玻璃平台，可以走上去，也能贴着它放方块搭建
                (spell :name "凌空栈道" :mana 60
                  (construct :shape plane :snap :width 3 :depth 9 :at (above (pos-of caster) -1.4) :life 30 :look &glass))
                """);

        add("lift-circle", """
                ; 腾跃法阵：前方地面上的单向平台（从下面能穿过），站上去就被弹向高处
                (spell :name "腾跃法阵" :mana 60
                  (construct :shape plane :width 3 :depth 3 :at (ahead 3 :from (above (pos-of caster) -0.9))
                             :one-way :life 20
                    (on-stand (launch target 1.6))))
                """);

        add("fire-wall", """
                ; 焰幕：谁都不挡（:solid (kind none)），但穿过它的生物和箭矢都会着火
                (spell :name "焰幕" :mana 60
                  (construct :shape wall :width 6 :height 3 :life 12 :solid (kind none)
                    (on-pass (ignite target 5))))
                """);

        add("forge-gate", """
                ; 焰锻之门：单向墙挡住正面来的箭；自己从背面射出的箭穿过时被点燃、一分为三
                ; (is target (kind projectiles)) 让穿过的人不受影响；复制品不能再被复制
                (spell :name "焰锻之门" :mana 120
                  (construct :shape wall :width 4 :height 3 :life 20 :one-way
                    (on-pass
                      (if (is target (kind projectiles))
                        (do (ignite target 5)
                            (copy target 2 :spread 8))))))
                """);

        add("lift", """
                ; 升降浮台：脚下的玻璃平台。有人站上来就升起 8 格，到顶停 2 秒，再回到起点
                ; 没有时间轴：self 是这个炼成体，事件决定什么时候动，move 决定去哪里
                (spell :name "升降浮台" :mana 200
                  (let $base (above (pos-of caster) -1.4)
                    (construct :shape plane :snap :width 3 :depth 3 :at $base :life 60 :look &glass
                      (on-stand (move self (above $base 8) :speed 0.15))
                      (on-arrive
                        (if (> (distance here $base) 1)
                          (delay 2 (move self $base :speed 0.15)))))))
                """);

        add("held-shield", """
                ; 随身圆盾：面前的单向圆盾跟着视线转动，挡住迎面来的箭和法术，自己的法术可以从背后射出去
                (spell :name "随身圆盾" :mana 60
                  (construct :shape disk :radius 1.4 :at (ahead 2) :follow :one-way :life 20))
                """);

        add("recall", """
                ; 归家：先在脚下立一个「归途」锚点（消耗一枚锚钉），再回到锚点「家」
                ; ^家 要先拿着锚钉右键方块立下；^名字 本身就是位置，跨维度也行
                (spell :name "归家" :mana 80
                  (anchor ^归途 (pos-of caster))
                  (blink ^家))
                """);

        add("return-path", """
                ; 循途而返：回到上一次「归家」离开的地方
                (spell :name "循途而返" :mana 60
                  (blink ^归途))
                """);

        add("night-watch", """
                ; 守夜：让最后召唤的使魔主动猎杀身边 24 格内的敌对生物，但不碰苦力怕（免得它在你身边炸开）
                ; 收手：(stance :hunt (kind none))；只打亡灵：:hunt "#minecraft:undead"
                (spell :name "守夜" :mana 10
                  (stance :hunt (except (kind hostile) "minecraft:creeper") :radius 24))
                """);

        add("mana-lifeform", """
                ; 凝魔成灵：把这道术式剩下的魔力凝成一只 1 级魔力生命体「灵」（至少 50，最多 1000）
                ; 它一直存在，靠自己的魔力行动；潜行右键它打开心核，放入下面两种载页
                ; 魔力耗尽会沉寂落地，拿紫水晶碎片、青金石或回响碎片右键喂它
                (spell :name "凝魔成灵" :mana 300
                  (summon mana_lifeform :level 1 :name "灵"))
                """);

        add("lifeform-bolt", """
                ; 灵弹：放进魔力生命体心核的「普攻」格。它战斗时会转向敌人施放，(foe) 就是它对准的那个
                (spell :name "灵弹" :mana 20
                  (damage (foe) 6))
                """);

        add("lifeform-guard", """
                ; 护主：放进心核的被动格（2 级起才有）。生命体受击时，治疗它的主人
                (spell :name "护主" :trigger hurt :mana 15
                  (heal (master) 4))
                """);

        add("ender-gale", """
                ; 末影疾风：:trail 把尾迹换成末影粒子；phantasm（幻像显现）在命中处召出旋风粒子
                ; 拖尾与幻象只是表象：击退来自 push，而不是 gust 粒子
                (spell :name "末影疾风" :mana 30
                  (projectile :trail %portal :speed 1.3
                    (on-hit
                      (damage target minor void)
                      (push target 2)
                      (phantasm %gust_emitter_large))))
                """);
    }
}
