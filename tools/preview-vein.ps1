<#
.SYNOPSIS
游脉（vein）特效预览：在游戏窗口里把元素的展示场景 VeinTour 跑一遍，跑完打开截图文件夹。

.DESCRIPTION
每个元素依次演示：划过、撞墙、留 3 秒（冒出、停下、近看、消失）、环扩、爬墙、夜里。
窗口里能看到实时的动态，截图存在 build\ldlib2-uitest\screenshots\logos_<元素>_vein\。
做好外观的元素：fire、frost、life、physical；core 是公共晶体（灰色噪声加奥术配色）。
不写元素时把做好的四个都跑一遍。场景跑完窗口会自己关掉。

.EXAMPLE
tools\preview-vein.ps1 fire
tools\preview-vein.ps1 life,physical
tools\preview-vein.ps1 frost -Headless   # 不开窗口，只要截图
#>
param(
    [Parameter(Position = 0)][string[]]$Element = @('fire', 'frost', 'life', 'physical'),
    # 不开游戏窗口，只出截图。
    [switch]$Headless,
    # 跑完不打开截图文件夹。
    [switch]$NoOpen
)

$known = @('core', 'fire', 'frost', 'life', 'physical')
$root = Split-Path -Parent $PSScriptRoot
$scenes = foreach ($e in ($Element -split ',')) {
    $e = $e.Trim().ToLower()
    if ($e -eq '') { continue }
    if ($known -notcontains $e) { throw "没有 $e 的预览场景，可选：$($known -join '、')" }
    if ($e -eq 'core') { 'logos_vein_core' } else { "logos_${e}_vein" }
}

$gradleArgs = @('runClient', "-PldTest=$($scenes -join ',')")
if ($Headless) { $gradleArgs += '-PldTestHeadless' }
Write-Host "预览：$($scenes -join '、')"
Push-Location $root
try {
    & .\gradlew.bat @gradleArgs
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}

if (-not $NoOpen) {
    foreach ($s in $scenes) {
        $dir = Join-Path $root "build\ldlib2-uitest\screenshots\$s"
        if (Test-Path $dir) { Invoke-Item $dir }
    }
}
exit $code
