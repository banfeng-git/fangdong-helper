@echo off
chcp 65001 >nul
setlocal
set JAR=%~dp0dist\房东小助手-电脑版.jar

where javaw >nul 2>nul
if errorlevel 1 (
  if not defined JAVA_HOME (
    echo 没有检测到 Java。请先安装 Java 17（或 11 以上），安装时勾选"添加到 PATH"。
    echo 下载地址: https://adoptium.net/zh-CN/temurin/releases/
    pause
    exit /b 1
  )
  set JAVAW=%JAVA_HOME%\bin\javaw.exe
) else (
  set JAVAW=javaw
)

start "" "%JAVAW%" -jar "%JAR%"
