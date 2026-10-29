# 罐栽植物的地面基底扩展

`JarCropBlock` 的每种植物都有独立的基底方块标签：
`<植物方块的命名空间>:plantable_on/<植物方块的路径>`。
`JarCropBlock.plantableOn()` 返回对应的 `TagKey<Block>`，供附属模组查询。

Logos 为现有的 13 种罐栽植物提供空标签，默认不增加可种植的地面。
附属模组或数据包可以往相应标签中添加基底，分别控制每种植物。
例如，允许曼德拉草以耕地为基底，在
`data/logos/tags/block/plantable_on/mandrake_crop.json` 中添加：

```json
{
  "replace": false,
  "values": ["minecraft:farmland"]
}
```

汞菇和星辉苔分别使用 `logos:plantable_on/mercury_mushroom_crop` 和
`logos:plantable_on/starlight_moss_crop`。硝之芽、金属植物及其他草药也按各自的
方块 ID 使用独立标签，不会因为另一种植物开放了基底就一并开放。
标签值也可以引用其他方块标签，例如 `"#minecraft:dirt"`。

这个入口只改变 `mayPlaceOn` 的基底判断。原有的 `CropBlock` 光照、存活、生长及
NeoForge `canSustainPlant` 承托判断继续适用；后者仍可由基底方块显式覆盖。
种子仍是普通物品，标签不会自动提供右键种植或自然生成，这些玩法由附属模组配套实现。

五种元素草药（赤脉根、烛焰花、霜铃花、鸣雷蓟、隙影花）分别使用
`redvein_root_seed`、`candleflame_seed`、`frostbell_seed`、`thunder_thistle_seed`、
`riftshade_seed` 播种，嬗变配方也产出这些种子；原来的植物本体和炼金材料不再用于入罐。
它们共用地面和罐栽的方块掉落表：未成熟（`age=0..2`）掉 1 颗种子，成熟（`age=3`）
掉 2 颗种子和 2 份对应的炼金材料。种植罐从成熟掉落中扣 1 颗种子留种，实际输出
1 颗种子和 2 份材料，重置至第 0 阶段；手动、自动收获都走这套规则。

其他罐栽植物仍以 `noLootTable()` 注册，按原有配方收获；地面掉落由附属模组配套实现。
