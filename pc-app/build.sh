#!/usr/bin/env bash
# 重新编译 PC 端并打包成可双击运行的 jar
set -e
cd "$(dirname "$0")"
rm -rf out dist
mkdir -p out dist
javac -encoding UTF-8 --release 11 -d out $(find src -name "*.java")
printf 'Main-Class: com.rent.pc.Main\n' > /tmp/fd_manifest.txt
jar cfm dist/房东小助手-电脑版.jar /tmp/fd_manifest.txt -C out .
echo "已生成 dist/房东小助手-电脑版.jar"
