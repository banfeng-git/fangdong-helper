@echo off
chcp 65001 >nul
REM 在 Windows 上把 jar 打成真正的 .exe 安装包（需要 JDK 17+，自带 jpackage）
setlocal
cd /d %~dp0

if not exist dist\房东小助手-电脑版.jar (
  echo 先运行一次 build.sh（或在 Git Bash 里执行）生成 jar。
  pause
  exit /b 1
)

jpackage --type exe ^
  --name "房东小助手" ^
  --app-version 2.10 ^
  --input dist ^
  --main-jar 房东小助手-电脑版.jar ^
  --main-class com.rent.pc.Main ^
  --win-shortcut ^
  --win-menu ^
  --win-dir-chooser ^
  --vendor "房东小助手" ^
  --description "房东水电燃气记账（电脑版）"

echo 完成，安装包在本目录。
pause
