#!/usr/bin/env bash
cd "$(dirname "$0")"
if command -v java >/dev/null 2>&1; then
  exec java -jar "dist/房东小助手-电脑版.jar"
else
  echo "没有检测到 Java，请先安装 Java 17（或 11 以上）：https://adoptium.net/"
fi
