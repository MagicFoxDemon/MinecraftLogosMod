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
种子仍是普通物品，植物仍以 `noLootTable()` 注册；标签不会自动提供右键种植、
收获或地面掉落，这些玩法由附属模组配套实现。种植罐内的配方和生长逻辑不变。
