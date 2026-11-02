@echo off
rem 双击预览做好的全部游脉特效；也可以带元素：preview-vein.cmd fire,life
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0preview-vein.ps1" %*
