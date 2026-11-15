package dev.logos.lang;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Example spells, laid out as a course: each group builds on the ones before it, and every example works as it is
 * in play. None of them exist as runes: each is composed from orthogonal primitives. Their ;; notes become regions
 * and sticky notes when an example is opened in the editor (see {@link SourceNotes}).
 */
public final class Examples {
    /** Course groups in order; each is named by {@code logos.example.group.<group>}. */
    public static final String BASICS = "basics", ADVANCED = "advanced", MELEE = "melee", UTILITY = "utility",
            LIFEFORMS = "lifeforms", GRAND = "grand";
    public static final List<String> GROUPS = List.of(BASICS, ADVANCED, MELEE, UTILITY, LIFEFORMS, GRAND);

    /** Example id → source, in course order. */
    public static final Map<String, String> ALL = new LinkedHashMap<>();
    private static final Map<String, String> GROUP = new HashMap<>();
    private static final Map<String, String> ID_BY_SOURCE = new HashMap<>();

    private Examples() {}

    private static void add(String group, String id, String src) {
        String s = src.stripIndent().strip() + "\n";
        ALL.put(id, s);
        GROUP.put(id, group);
        ID_BY_SOURCE.put(s, id);
    }

    /** The example a source is, if it is one unchanged; its display name is then {@code logos.example.<id>}. */
    public static String idOf(String source) {
        return source == null ? null : ID_BY_SOURCE.get(source);
    }

    /** The course group of an example, one of {@link #GROUPS}; null for an id that is not an example. */
    public static String group(String id) {
        return GROUP.get(id);
    }

    static {
        // ---- 入门 ----

        add(BASICS, "hello", """
                ;; 启蒙魔弹
                ;; 向视线方向射出一枚魔弹，命中敌人时造成轻微的物理伤害。本术式只用到开始时就已领悟的符文：魔弹、伤害、物理元素与直行。
                (spell :name "启蒙魔弹" :mana 10
                  (projectile
                    (on-hit
                      ;; 命中
                      ;; 「命中时」在魔弹击中生物时触发，其中的 target 就是被击中的生物。minor 表示轻微伤害（2 点），也可以直接写数值。
                      (damage target minor))))
                """);

        add(BASICS, "fireball", """
                ;; 爆炎火球
                ;; 射出一枚较快的魔弹，直击的敌人受到火焰伤害；魔弹消散时，再灼伤并点燃落点周围的敌人。模组中并没有「火球」符文，火球由魔弹、伤害与范围选择组合而成。
                (spell :name "爆炎火球" :mana 30
                  (projectile :speed 1.4
                    (on-hit
                      (damage target standard fire))
                    (on-expire
                      ;; 余烬
                      ;; 魔弹击中目标或飞完寿命后消散。此时逐个处理落点 3 格内的敌人：造成轻微火焰伤害，并点燃 3 秒。
                      (each (enemies :radius 3)
                        (damage target minor fire)
                        (ignite target 3)))))
                """);

        add(BASICS, "frost-nova", """
                ;; 冰霜新星
                ;; 不借助任何形体，直接作用于施法者周围 6 格内的敌人：造成轻微寒霜伤害，冻结 3 秒，并向外推开。适合在被近身围攻时脱身。
                (spell :name "冰霜新星" :mana 50
                  ;; 逐个结算
                  ;; 「周遭诸敌」选出范围内的全部敌人，「逐个实体」对其中每一个依次执行内部的效果，内部的 target 就是当前这一个。
                  (each (enemies :at (pos-of caster) :radius 6)
                    (damage target minor frost)
                    (freeze target 3)
                    (push target 1 :from (pos-of caster))))
                """);

        add(BASICS, "chain-lightning", """
                ;; 连锁闪电
                ;; 射线连向 12 格内最近的敌人，造成 5 点雷霆伤害，再从被击中者身上跳向下一个敌人。注入的魔力越多，能跳的次数越多。
                (spell :name "连锁闪电" :mana 60
                  (ray :to (nearest :radius 12 :fresh)
                    (on-hit
                      (damage target 5 lightning)
                      ;; 跳跃
                      ;; 「衔尾递归」在当前目标处重新执行整个术式。「最近之敌」的 :fresh 跳过本术式已经影响过的对象，因此每一跳都换一个目标；找不到新目标时，链条自然终止。
                      (recurse))))
                """);

        add(BASICS, "npc-heal", """
                ;; 抚生之触
                ;; 沿视线射出射线，为 24 格内第一个被击中的生物恢复 10 点生命，可用于治疗使魔、宠物与村民。治疗其他玩家请使用「同袍之愈」。
                (spell :name "抚生之触" :mana 20
                  (ray :range 24
                    (on-hit (heal target 10))))
                """);

        add(BASICS, "group-heal", """
                ;; 同袍之愈
                ;; 为 8 格内的其他玩家与施法者自己各恢复 6 点生命。
                (spell :name "同袍之愈" :players true :mana 80
                  ;; 选择玩家
                  ;; 只有在启式中写明 :players true 的术式，才能选择并影响其他玩家。「周遭玩家」不包括施法者本人，因此另行治疗施法者。
                  (heal (players :radius 8) 6)
                  (heal caster 6))
                """);

        add(BASICS, "blink", """
                ;; 闪现
                ;; 瞬移到视线 14 格内的落点，用于越过沟壑或脱离包围。
                (spell :name "闪现" :mana 20
                  (blink (look-point 14)))
                """);

        // ---- 进阶 ----

        add(ADVANCED, "meteor", """
                ;; 陨星天降
                ;; 在视线落点预警 1.5 秒，随后召下一颗燃烧的陨星，对落点 5 格内的敌人造成物理与火焰伤害，点燃并击飞。预警期间敌人可能离开，宜用于行动迟缓或已被控制的目标。
                (spell :name "陨星天降" :mana 90
                  ;; 锁定落点
                  ;; 「铭记」把视线 40 格内的落点记作 $p。之后的预警与陨星都以这一点为准，施法者转身也不会改变落点。
                  (let $p (look-point 40)
                    ;; 预警
                    ;; 在落点展开 1.5 秒的领域，标示即将受到打击的范围。
                    (field :at $p :radius 5 :life 1.5 :every 2)
                    ;; 陨星坠落
                    ;; 1.5 秒后，自落点上方 16 格射出一枚抛物线魔弹；魔弹消散时，对落点周围的敌人结算伤害。
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

        add(ADVANCED, "prismatic-volley", """
                ;; 虹彩飞矢
                ;; 同时射出三枚追踪魔弹，每枚随机取六种元素之一。各元素每点伤害的单价不同，这里统一按 10 点魔力换算出各自的伤害，因此无论抽到哪种元素，消耗都相同。
                ;; 每发消耗：分裂 2、择路 0.5、魔弹 4、追踪 3、伤害 10，三发合计至多 58.5，故注入 59。
                (spell :name "虹彩飞矢" :mana 59 :trigger cast
                  (split 3
                    ;; 随机元素
                    ;; 「随机数」取 0～5 的整数，「择路」只执行对应序号的一支，未选中的分支不花费魔力。
                    (switch (random 5 :whole)
                      (projectile :motion homing
                        (on-hit (damage target 10 arcane)))
                      (projectile :motion homing
                        (on-hit (damage target 14.28 fire)))
                      (projectile :motion homing
                        (on-hit (damage target 9.09 lightning)))
                      (projectile :motion homing
                        (on-hit (damage target 6.66 void)))
                      (projectile :motion homing
                        (on-hit (damage target 12.5 physical)))
                      (projectile :motion homing
                        (on-hit (damage target 10 frost))))))
                """);

        add(ADVANCED, "execute-proof", """
                ;; 论证处决
                ;; 处决的代价取决于给出的证明：不附任何证明时，代价为目标最大生命值的 4 倍；证明目标生命低于 30% 且正在燃烧时，代价降为 4 × 最大生命 × 0.3 × 0.75。
                ;; 条件不成立时，本术式改为点燃目标并造成火焰伤害，为下一次处决创造条件。
                (spell :name "论证处决" :mana 60
                  (ray :range 24
                    (on-hit
                      ;; 证明
                      ;; 「若则」的条件成立时，其中的命题就成为成立分支内的证明，处决据此降低代价。若执行时命题已不再成立，术式会遭到反噬。
                      (if (and (< (hp-ratio target) 0.3)
                               (has target burning))
                        (execute target)
                        ;; 创造条件
                        ;; 条件不成立时点燃目标 4 秒并造成火焰伤害；再次施放时，目标已在燃烧。
                        (do
                          (ignite target 4)
                          (damage target standard fire))))))
                """);

        add(ADVANCED, "star-pull", """
                ;; 群星牵引
                ;; 以扇形射出五枚追踪魔弹，命中的敌人受到奥秘伤害并被刻下星痕。魔弹飞完后，把周围带星痕的敌人一齐拖向施法者此刻的视线落点，再对聚拢的敌人造成一次群体奥秘伤害。适合把分散的敌人聚到一处，再接范围法术。
                (spell :name "群星牵引" :mana 150
                  (split 5 :spread 70
                    (projectile :motion homing :speed 0.8 :life 2.5 :pierce 2
                      (on-hit
                        (damage target 3 arcane)
                        (mark target #star))))
                  ;; 收束
                  ;; 2.6 秒后魔弹均已消散。此时把视线落点记作 $c，「带印记者」选出施法者 24 格内带星痕的敌人，拖向 $c 并清除星痕；0.5 秒后对 $c 周围 4 格内的敌人造成群体伤害。
                  (delay 2.6
                    (let $c (look-point 24)
                      (each (marked #star :at (pos-of caster) :radius 24)
                        (pull target 1.5 :to $c)
                        (unmark target #star))
                      (delay 0.5
                        (damage-all (enemies :at $c :radius 4) 8 arcane)))))
                """);

        add(ADVANCED, "scarlet", """
                ;; 血印裂变
                ;; 追踪魔弹命中时造成虚空伤害，并刻下一层血印。对同一目标施放三次后，血印清空，从目标身上分裂出三枚追踪魔弹；分裂魔弹击杀敌人时，在击杀处展开汲取生命的领域。
                (spell :name "血印裂变" :mana 200
                  (projectile :motion homing :speed 1
                    (on-hit
                      (damage target 4 void)
                      (mark target #blood)
                      ;; 三层裂变
                      ;; 印记留在目标身上，不随术式结束而消失，因此多次施放可以累积层数。层数达到 3 时清空血印，从目标上方分裂出三枚追踪魔弹。
                      (if (>= (stacks target #blood) 3)
                        (do
                          (unmark target #blood)
                          (split 3 :spread 120
                            (projectile :from (above here 1.5) :speed 0.8 :motion homing
                              (on-hit
                                ;; 汲取领域
                                ;; 分裂魔弹的伤害击杀敌人时，在击杀处展开 4 秒的领域，每 0.5 秒汲取范围内每个敌人的生命，其中一半治疗施法者。
                                (damage target 4 void
                                  (on-kill
                                    (field :radius 3 :life 4 :every 0.5
                                      (on-tick
                                        (each (enemies :radius 3)
                                          (drain target 1))))))))))))))
                """);

        add(ADVANCED, "blood-aura", """
                ;; 汲血光环
                ;; 展开一个跟随施法者、持续 8 秒的领域，每秒汲取周围 4 格内每个敌人 1.5 点生命，其中一半治疗施法者。适合在近身混战中维持生命。
                (spell :name "汲血光环" :mana 80
                  (field :radius 4 :life 8 :every 1 :follow
                    (on-tick
                      (each (enemies :radius 4)
                        (drain target 1.5)))))
                """);

        add(ADVANCED, "orbit", """
                ;; 环星护卫
                ;; 召出三枚环绕施法者的魔弹，持续 8 秒，碰到的敌人受到轻微物理伤害并被推开。环绕的魔弹会反复击中靠近的敌人。
                (spell :name "环星护卫" :mana 60
                  ;; 均匀分布
                  ;; 「扇形分裂」的 :spread 360 让三枚魔弹均匀分布在一整圈上；:pierce 99 让魔弹穿过敌人后继续环绕。
                  (split 3 :spread 360
                    (projectile :motion orbit :speed 1 :life 8 :pierce 99
                      (on-hit
                        (damage target minor physical)
                        (push target 0.6 :from (pos-of caster))))))
                """);

        add(ADVANCED, "specium", """
                ;; 光柱横扫
                ;; 从眼前放出一道持续 3 秒、射程 28 格的粗光束，转身时光束随视线扫动。每 0.2 秒对光束中的每个敌人造成轻微雷霆伤害，照到的方块会被点燃。施放期间施法者举杖维持光束。
                (spell :name "光柱横扫" :mana 80
                  (beam :radius 0.6 :range 28 :life 3 :every 0.2
                    (on-hit (damage target minor lightning))
                    (on-impact (set-fire here))))
                """);

        add(ADVANCED, "storm-hammer", """
                ;; 回旋雷槌
                ;; 掷出一柄翻转的重锤，飞出一段距离后折返，途中最多穿透 3 个敌人，造成雷霆伤害并击退。
                (spell :name "回旋雷槌" :mana 50
                  ;; 外观
                  ;; :look &mace 让魔弹显现为重锤的模型，:spin 2 每秒翻转两圈。外观不消耗魔力，也不改变折返、穿透与命中等行为。
                  (projectile :look &mace :spin 2 :motion boomerang :speed 1.1 :pierce 3
                    (on-hit
                      (damage target standard lightning)
                      (push target 1))))
                """);

        // ---- 近战与被动 ----

        add(MELEE, "flame-blade", """
                ;; 焰刃
                ;; 把一段术式缠绕在施法者的主手上，持续 30 秒：此后每一记蓄满的攻击，连同射出的箭，都额外造成 4 点火焰伤害。施放后需要用近战或弓箭攻击才会生效。
                ;; 缠绕在手上而不是物品上，换用其他武器时照样生效。
                (spell :name "焰刃" :mana 80
                  (imbue caster :life 30
                    (on-hit (damage target 4 fire))))
                """);

        add(MELEE, "sword-wave", """
                ;; 剑气
                ;; 缠绕主手 20 秒：每次蓄满挥动武器（挥空也算），都会斩出一道速度快、寿命短的魔弹，造成 5 点物理伤害。施放后需要挥动武器才会生效。
                (spell :name "剑气" :mana 110
                  (imbue caster :life 20
                    (on-swing
                      ;; 斩击
                      ;; 「挥动时」在缠绕的手蓄满挥动时触发，每挥动一次就射出一枚魔弹。
                      (projectile :speed 2 :life 0.6
                        (on-hit (damage target 5 physical))))))
                """);

        add(MELEE, "thorns", """
                ;; 雷鸣反击
                ;; 被动术式：写在载页上挂到法杖，手持法杖受到攻击时自动触发，以雷霆射线反击攻击者并将其击退。同一被动每秒最多触发一次，每次触发消耗 15 点魔力。
                (spell :name "雷鸣反击" :trigger hurt :mana 15
                  ;; 反击
                  ;; 「受伤时」触发的术式里，target 是攻击者。先确认攻击者仍然存活，再以射线连向它。
                  (if (alive target)
                    (ray :to target
                      (on-hit
                        (damage target minor lightning)
                        (push target 1 :from (pos-of caster))))))
                """);

        add(MELEE, "corpse-burst", """
                ;; 亡骸爆燃
                ;; 被动术式：手持挂有本页的法杖击杀生物时自动触发，对死亡位置 4 格内的敌人造成火焰伤害并击飞。
                (spell :name "亡骸爆燃" :trigger kill :mana 25
                  ;; 爆燃
                  ;; 「击杀时」触发的术式里，here 是被击杀者死亡的位置，因此范围选择以那里为中心。
                  (each (enemies :radius 4)
                    (damage target standard fire)
                    (launch target 0.5)))
                """);

        // ---- 实用 ----

        add(UTILITY, "harvest", """
                ;; 丰饶之触
                ;; 视线所指方块周围 2 格内：作物等有生长阶段的方块长一阶，能用骨粉催熟的方块如同使用了骨粉，幼年生物也会长大一些。每个对象消耗 2 点魔力。
                (spell :name "丰饶之触" :mana 60
                  (grow (look-block 24) 2))
                """);

        add(UTILITY, "lumberjack", """
                ;; 伐林
                ;; 砍倒视线所指方块周围 5 格内的全部原木，原木照常掉落。
                (spell :name "伐林" :mana 120
                  ;; 筛选原木
                  ;; 「周遭方块」的 :filter 按方块 id 或 #标签 筛选，#minecraft:logs 包括各种原木。
                  (break-block
                    (blocks :at (look-block 16) :radius 5 :filter "#minecraft:logs")))
                """);

        add(UTILITY, "magnet", """
                ;; 磁引拾遗
                ;; 把周围 12 格内的掉落物吸到施法者身边。
                (spell :name "磁引拾遗" :mana 40
                  (pull (items :radius 12) 2 :to (pos-of caster)))
                """);

        add(UTILITY, "arrow-ward", """
                ;; 偏箭之风
                ;; 展开一个跟随施法者、持续 6 秒的领域，每 0.25 秒推开周围 4 格内飞行中的箭矢、火球等弹射物。适合应对骷髅、烈焰人与掠夺者的远程攻击。
                (spell :name "偏箭之风" :mana 150
                  (field :follow :radius 4 :life 6 :every 0.25
                    (on-tick
                      ;; 偏转弹射物
                      ;; 「周遭实体」的筛选写成「种类筛选」projectiles，只选出飞行中的弹射物，再由「推离」把它们推出领域。
                      (push (entities :radius 4 :filter (kind projectiles)) 1.5))))
                """);

        add(UTILITY, "sky-bridge", """
                ;; 凌空栈道
                ;; 在脚下凝出一条宽 3 格、长 9 格的玻璃平台，持续 30 秒。平台对齐方块网格，可以站在上面，也可以贴着它放置方块，搭建时可用作临时脚手架。
                (spell :name "凌空栈道" :mana 100
                  (construct :shape plane :snap :width 3 :depth 9 :at (above (pos-of caster) -1.4) :life 30 :look &glass))
                """);

        add(UTILITY, "lift-circle", """
                ;; 腾跃法阵
                ;; 在前方 3 格的地面上展开一块单向平台，持续 20 秒。平台只阻挡从上方来的生物，站上去的生物会被弹向高处。
                (spell :name "腾跃法阵" :mana 60
                  (construct :shape plane :width 3 :depth 3 :at (ahead 3 :from (above (pos-of caster) -0.9))
                             :one-way :life 20
                    (on-stand
                      ;; 弹射
                      ;; 「踏上时」在有生物站上平台时触发，target 是站上来的生物。
                      (launch target 1.6))))
                """);

        add(UTILITY, "lift", """
                ;; 升降浮台
                ;; 在脚下凝出 3×3 的玻璃平台，持续 60 秒。有生物站上来时，平台升起 8 格，到顶停留 2 秒后返回起点，可用作临时升降梯。
                (spell :name "升降浮台" :mana 200
                  ;; 记住起点
                  ;; 把脚下的位置记作 $base，上升与返回都以它为准。
                  (let $base (above (pos-of caster) -1.4)
                    (construct :shape plane :snap :width 3 :depth 3 :at $base :life 60 :look &glass
                      (on-stand
                        ;; 上升
                        ;; 事件中的 self 就是这座平台。「移动形体」让它以每刻 0.15 格的速度移向起点上方 8 格。
                        (move self (above $base 8) :speed 0.15))
                      (on-arrive
                        ;; 返回
                        ;; 「抵达时」检查平台是否离开了起点；在高处时等待 2 秒，再移回起点。
                        (if (> (distance here $base) 1)
                          (delay 2 (move self $base :speed 0.15)))))))
                """);

        add(UTILITY, "held-shield", """
                ;; 随身圆盾
                ;; 在面前 2 格处凝出一面随视线转动的单向圆盾，持续 20 秒，挡下迎面而来的箭矢、火球与法术；施法者自己的法术可以从背面穿出。
                (spell :name "随身圆盾" :mana 60
                  (construct :shape disk :radius 1.4 :at (ahead 2) :follow :one-way :life 20))
                """);

        add(UTILITY, "fire-wall", """
                ;; 焰幕
                ;; 在面前凝出一道宽 6 格、高 3 格的火墙，持续 12 秒。火墙不阻挡任何东西，但穿过它的生物与箭矢都会被点燃；从背后射出的箭穿过后，会带着火焰命中目标。
                (spell :name "焰幕" :mana 60
                  (construct :shape wall :width 6 :height 3 :life 12 :solid (kind none)
                    (on-pass (ignite target 5))))
                """);

        add(UTILITY, "forge-gate", """
                ;; 焰锻之门
                ;; 在面前凝出一道单向墙，持续 20 秒：正面射来的箭会被挡下；施法者从背面射出的箭穿过时被点燃，并一分为三。
                (spell :name "焰锻之门" :mana 120
                  (construct :shape wall :width 4 :height 3 :life 20 :one-way
                    (on-pass
                      ;; 只处理弹射物
                      ;; 「实体符合」检查穿过的是否为弹射物，因此走过的生物不受影响。复制出的箭不能再被复制，也不能拾取。
                      (if (is target (kind projectiles))
                        (do (ignite target 5)
                            (copy target 2 :spread 8))))))
                """);

        add(UTILITY, "recall", """
                ;; 归家
                ;; 在脚下立起锚点「归途」，再瞬移回锚点「家」，跨维度同样有效。使用前需要先手持锚钉右键方块立下「家」；每次施放还会消耗一枚锚钉来记录「归途」。
                (spell :name "归家" :mana 80
                  ;; 记录归途
                  ;; 先把当前位置记为锚点「归途」，之后可以用「循途而返」回到这里。
                  (anchor ^归途 (pos-of caster))
                  (blink ^家))
                """);

        add(UTILITY, "return-path", """
                ;; 循途而返
                ;; 瞬移回锚点「归途」，即上一次施放「归家」时离开的位置。
                (spell :name "循途而返" :mana 60
                  (blink ^归途))
                """);

        add(UTILITY, "nether-step", """
                ;; 下界跃迁
                ;; 瞬移到下界中的对应位置：水平坐标按 8∶1 换算，并自动寻找可以站立的空位。只能前往施法者亲身到过的维度。
                (spell :name "下界跃迁" :mana 60
                  (blink (in-dimension @the_nether (pos-of caster))))
                """);

        // ---- 魔灵 ----

        add(LIFEFORMS, "mana-lifeform", """
                ;; 凝魔成灵
                ;; 把这道术式剩下的魔力凝成一只 1 级魔力生命体「灵」。它长期存在，依靠自身的魔力行动；潜行时右键它可打开心核，放入「灵弹」「护主」等载页。
                ;; 魔力耗尽时它会沉寂落地，用紫水晶碎片、青金石或回响碎片右键它可以补充魔力。
                (spell :name "凝魔成灵" :mana 300
                  (summon mana_lifeform :level 1 :name "灵"))
                """);

        add(LIFEFORMS, "lifeform-bolt", """
                ;; 灵弹
                ;; 魔力生命体的普攻页：放进心核的「普攻」格后，它在战斗中会转向敌人施放，对「当前之敌」造成 6 点伤害。玩家自己施放时没有当前之敌，因此不会生效。
                (spell :name "灵弹" :mana 20
                  (damage (foe) 6))
                """);

        add(LIFEFORMS, "lifeform-guard", """
                ;; 护主
                ;; 魔力生命体的被动页：放进心核的被动格（2 级起才有），生命体受到攻击时为它的主人恢复 4 点生命。
                (spell :name "护主" :trigger hurt :mana 15
                  (heal (master) 4))
                """);

        add(LIFEFORMS, "night-watch", """
                ;; 守夜
                ;; 让最后召唤的使魔主动猎杀身边 24 格内的敌对生物，但不碰苦力怕，以免它在施法者身边爆炸。施放前需要先召唤使魔。
                ;; 改写 :hunt 可以调整猎杀对象：写 (kind none) 让使魔收手，写 "#minecraft:undead" 只猎杀亡灵。
                (spell :name "守夜" :mana 10
                  (stance :hunt (except (kind hostile) "minecraft:creeper") :radius 24))
                """);

        // ---- 大型 ----

        add(GRAND, "frost-sweep", """
                ;; 寒冰横扫
                ;; 缠绕主手 30 秒：每次蓄满挥动武器，都会从身前贴地扫出一道 160° 的冰刺弧，最多穿透 3 个敌人，各造成 5 点寒霜伤害；冰刺在地面保留 1.5 秒，期间触碰到的敌人同样受到伤害。施放后需要挥动武器才会生效。
                (spell :name "寒冰横扫" :mana 600 :trigger cast
                  (imbue :life 30
                    (on-swing
                      ;; 冰刺弧
                      ;; 弧扫游脉以施法者为圆心，沿 :spread 160 度的弧扫过，:ccw 改为逆时针。:width 2 是碰到敌人的宽度，:linger 1.5 让扫过的路径保留 1.5 秒。
                      (vein :motion sweep :spread 160 :speed 1.3 :width 2 :pierce 3
                        :linger 1.5
                        :ccw (on-hit (damage target 5 frost))))))
                """);

        add(GRAND, "death-rain", """
                ;; 死亡之雨
                ;; 在施法者头顶 48 格处展开一面半径 32 格、朝下的法阵，持续 15 秒。法阵每 0.2 秒从表面随机几处降下细小的魔弹，魔弹消散时对 2 格内的生物造成 8 点奥秘伤害。
                ;; 本术式注入 12800 点魔力，需写在至高之理上。伤害的对象是「周遭实体」，不分敌友，只有施法者与其他玩家不受影响。
                (spell :name "死亡之雨" :mana 12800 :trigger cast
                  ;; 天穹法阵
                  ;; :solid (kind none) 让法阵不阻挡任何东西，:sigil 让圆盾显现为元素法阵。法阵每脉动一次，就执行一次「脉动时」里的内容。
                  (construct :shape disk :at (above (pos-of caster) 48) :facing down
                    :radius 32
                    :life 15
                    :every 0.2
                    :solid (kind none)
                    :sigil (on-tick
                            ;; 落雨
                            ;; 「择位」的 :in form :where surface 在法阵表面随机取点。每次脉动依次降下三枚魔弹，间隔 0.2 秒。
                            (repeat 3 :every 0.2
                              (projectile :from (spot :in form :where surface)
                                :dir down
                                :size 0.15
                                (on-expire
                                  (damage-all
                                    (entities :radius 2)
                                    8
                                    arcane)))))))
                """);

        add(GRAND, "starsplit-judgment", """
                ;; 裂星天罚
                ;; 在视线落点召来雷暴，并展开一面法阵，每 0.5 秒随机射出一道四元素射线。1 秒后六颗熔岩陨星陆续坠向落点周围，2.5 秒后八道落雷接连劈下。
                ;; 本术式注入 1800 点魔力，需天穹或更高品级的载页。
                (spell :name "裂星天罚" :mana 1800 :trigger cast :intent area
                  ;; 锁定落点
                  ;; 把视线 40 格内的落点记作 $m2，雷暴、法阵、陨星与落雷都以它为中心。
                  (let $m2
                    (look-point 40)
                    ;; 雷暴
                    ;; 在落点召来半径 16 格、持续 20 秒的雷暴。雷暴中的落雷每道只需 20 魔力。
                    (weather thunder :at $m2 :radius 16 :life 20)
                    ;; 四元素法阵
                    ;; 法阵存续 5 秒，每 0.5 秒脉动一次；它不阻挡任何东西，只作为射线的发射点。
                    (construct :shape disk :at $m2 :radius 5 :life 5 :every 0.5
                      :solid (kind none)
                      :sigil (on-tick
                              ;; 随机元素射线
                              ;; 「择路」随机选一种元素：奥秘、火焰（并点燃）、寒霜（并冻结）或雷霆。射线自法阵中心射向周围 24 格内随机一点。
                              (switch
                                (random 3 :whole)
                                (do
                                  (ray :from (pos-of form)
                                    :dir (toward
                                            (pos-of form)
                                            (spot :at (above here 3) :radius 24))
                                    :range 24
                                    (on-hit (damage target 14 arcane))))
                                (do
                                  (ray :from (pos-of form)
                                    :dir (toward
                                            (pos-of form)
                                            (spot :at (above here 3) :radius 24))
                                    :range 24
                                    (on-hit
                                      (damage target 20 fire)
                                      (ignite target 3))))
                                (do
                                  (ray :from (pos-of form)
                                    :dir (toward
                                            (pos-of form)
                                            (spot :at (above here 3) :radius 24))
                                    :range 24
                                    (on-hit
                                      (damage target 14 frost)
                                      (freeze target 2))))
                                (do
                                  (ray :from (pos-of form)
                                    :dir (toward
                                            (pos-of form)
                                            (spot :at (above here 3) :radius 24))
                                    :range 24
                                    (on-hit
                                      (damage target 12 lightning)))))))
                    ;; 熔岩陨星
                    ;; 1 秒后，每 0.7 秒从落点 9 格内随机一处的上空 20 格射下一颗熔岩陨星，共六颗。陨星消散时显现爆炸的幻象，对 4 格内的敌人造成 10 点物理伤害并点燃 5 秒。
                    (delay 1
                      (repeat 6 :every 0.7
                        (projectile
                          :from (above
                                  (spot :at $m2 :radius 9 :shape disk :ground)
                                  20)
                          :dir down
                          :speed 1.4
                          :motion arc
                          :size 1.2
                          :look &minecraft:magma_block
                          :trail %minecraft:flame
                          (on-expire
                            (phantasm %minecraft:explosion_emitter here :count 3
                              :spread 1)
                            (damage-all
                              (enemies :at here :radius 4)
                              10
                              physical)
                            (ignite-all (enemies :at here :radius 4) 5)))))
                    ;; 落雷
                    ;; 2.5 秒后，每 0.2 秒在落点 12 格内随机一处的地面降下落雷，共八道。
                    (delay 2.5
                      (repeat 8 :every 0.2
                        (lightning (spot :at $m2 :radius 12 :ground))))))
                """);

        add(GRAND, "heaven-thunder", """
                ;; 天雷殛
                ;; 以施法者为中心召来雷暴。此后 20 秒内，每秒从施法者头顶牵出电弧，连向 24 格内的每个敌人，随即在敌人所在处降下落雷。
                ;; 适合在敌群中央施放；敌人越多，每一轮的消耗越大。本术式注入 6400 点魔力，需贤者或更高品级的载页。
                (spell :name "天雷殛" :mana 6400
                  ;; 雷暴笼罩
                  ;; 召来半径 24 格、持续 22 秒的雷暴，并随施法者移动。落雷在雷暴中每道只需 20 魔力，晴天时则需 50。
                  (weather thunder :at (pos-of caster) :radius 24 :life 22 :follow)
                  ;; 天罚
                  ;; 每秒一轮，共 20 轮。每一轮先选出 24 格内的全部敌人，再对每个敌人依次结算电弧与落雷。
                  (repeat 20 :every 1
                    (each (enemies :at (pos-of caster) :radius 24)
                      ;; 电弧
                      ;; 射线从施法者头顶直接连向当前敌人，造成 12 点雷霆伤害。
                      (ray :from (above (pos-of caster) 2) :to target
                        (on-hit (damage target 12 lightning)))
                      (lightning here))))
                """);

        add(GRAND, "burning-veins", """
                ;; 焚城游脉
                ;; 以施法者为起点，四道火环相继贴地向四周扩散，烧过的路径在地面上保留 8 秒；随后再向前方放出五道蛇行的火脉。碰到火焰或踏入残火的敌人受到火焰伤害并被点燃。
                ;; 游脉能迈上 1 格高的台阶、顺着地面下坡，宜在平地或缓坡上对付成群的敌人。
                (spell :name "焚城游脉" :mana 800
                  ;; 四重火环
                  ;; 「重复」每隔 1 秒放出一道环扩游脉，共四道。:pierce 99 让火环穿过所有敌人；同一道游脉对每个敌人只结算一次。
                  (repeat 4 :every 1
                    (vein :motion ring :range 20 :speed 0.5 :width 3 :height 2 :pierce 99 :linger 8
                      (on-hit
                        (damage target 8 fire)
                        (ignite target 6))))
                  ;; 火蛇追击
                  ;; 第四道火环放出后，向视线方向扇形放出五道左右摆动的蛇行游脉，追击逃离火环的敌人。
                  (delay 4
                    (split 5 :spread 90
                      (vein :motion snake :range 24 :speed 0.6 :width 2 :pierce 99 :linger 4
                        (on-hit
                          (damage target 10 fire)
                          (ignite target 6))))))
                """);

        add(GRAND, "sword-array", """
                ;; 万象剑阵
                ;; 先在身周布下八柄环绕的护身剑，再向视线方向分 16 波射出飞剑，每波六柄，自动追踪敌人，每柄最多穿透三个目标。
                ;; 飞剑借用钻石剑与铁剑的模型；:look 与 :spin 只改变外观，不影响伤害与行为。本术式注入 3200 点魔力，需天穹或更高品级的载页。
                (spell :name "万象剑阵" :mana 3200
                  ;; 护身剑环
                  ;; 八柄钻石剑以 :spread 360 均匀分布，环绕施法者 10 秒，反复击伤并推开靠近的敌人。
                  (split 8 :spread 360
                    (projectile :motion orbit :speed 1 :life 10 :pierce 99 :look &diamond_sword
                      (on-hit
                        (damage target 4 physical)
                        (push target 0.8 :from (pos-of caster)))))
                  ;; 万剑齐发
                  ;; 每 0.5 秒一波，共 16 波；每波以 40° 扇形射出六柄翻转的铁剑，追踪最近的敌人。
                  (repeat 16 :every 0.5
                    (split 6 :spread 40
                      (projectile :motion homing :speed 1.6 :life 3 :pierce 3 :look &iron_sword :spin 3
                        (on-hit (damage target 12 physical))))))
                """);

        add(GRAND, "absolute-zero", """
                ;; 绝对零度
                ;; 在视线落点降下暴雪，冻结并停止 14 格内所有敌人的时间。时停的 6 秒内冰锥不断坠落；敌人在时停中受到的伤害会先存下，待时间恢复时一次性结算，最后再以一次群体寒霜伤害收尾。
                ;; 本术式注入 3200 点魔力，需天穹或更高品级的载页。
                (spell :name "绝对零度" :mana 3200
                  ;; 锁定落点
                  (let $p (look-point 32)
                    ;; 暴雪
                    ;; 在落点降下半径 16 格、持续 14 秒的雪，地面积雪、水面结冰。
                    (weather snow :at $p :radius 16 :life 14)
                    ;; 冰封
                    ;; 「群体冻结」一次付费冻结范围内的全部敌人；「时间停止」需要逐个施加，每个敌人 6 秒。
                    (freeze-all (enemies :at $p :radius 14) 12)
                    (each (enemies :at $p :radius 14)
                      (time-stop target 6))
                    ;; 冰锥坠落
                    ;; 时停期间每 0.2 秒从落点上空随机一处落下一枚冰锥，共 32 枚；冰锥消散时对 3 格内的敌人造成寒霜伤害。
                    (repeat 32 :every 0.2
                      (projectile :from (above (spot :at $p :radius 13 :shape disk) 14) :dir down :speed 1.5
                                  :size 0.5 :look &packed_ice :spin 1
                        (on-expire
                          (damage-all (enemies :radius 3) 10 frost))))
                    ;; 碎裂
                    ;; 时停结束后，对范围内的敌人造成一次群体寒霜伤害。
                    (delay 6.5
                      (damage-all (enemies :at $p :radius 14) 40 frost))))
                """);

        add(GRAND, "judgment-pillar", """
                ;; 审判光柱
                ;; 一道光柱自天而降，落点始终跟随施法者的视线，持续 8 秒。每 0.25 秒灼烧光柱中的敌人，造成奥秘伤害并使其发光。施放期间施法者举杖维持光柱，转动视线即可引导光柱扫过敌群。
                ;; 本术式注入 1600 点魔力，需白垩或更高品级的载页。
                (spell :name "审判光柱" :mana 1600
                  ;; 天降光柱
                  ;; 光柱的起点写成视线落点上方 30 格，方向朝下。起点每刻重新求值，因此光柱随视线移动；它向下延伸，直到被方块挡住。
                  (beam :from (above (look-point 48) 30) :dir down :radius 2 :range 40 :life 8 :every 0.25 :trail %end_rod
                    (on-hit
                      (damage target 8 arcane)
                      (glow target 2))))
                """);

        add(GRAND, "bulwark", """
                ;; 不灭壁垒
                ;; 施放时立即获得 10 点伤害吸收，并以施法者为中心撑起一座半径 6 格的穹顶，随施法者移动，持续 30 秒。穹顶只阻挡从外侧来的生物、弹射物、法术与爆炸，施法者的法术可以从内侧穿出。
                ;; 穹顶存续期间，施法者每秒恢复 1 点生命；仍留在穹顶内的敌人每秒受到奥秘伤害，并被推向外侧。本术式注入 800 点魔力，需炼成或更高品级的载页。
                (spell :name "不灭壁垒" :mana 800
                  (shield caster 10)
                  ;; 穹顶
                  ;; :one-way 让穹顶只阻挡从外侧来的东西，:follow 让它随施法者平移。穹顶的生命值是展开魔力的 2 倍，承受的伤害超过生命值时破碎。
                  (construct :shape dome :radius 6 :life 30 :every 1 :one-way :follow
                    (on-tick
                      (heal caster 1)
                      ;; 驱逐
                      ;; 对穹顶内的每个敌人造成 4 点奥秘伤害，并从穹顶中心向外推开。
                      (each (enemies :at (pos-of form) :radius 6)
                        (damage target 4 arcane)
                        (push target 1.5 :from (pos-of form))))))
                """);
    }
}
