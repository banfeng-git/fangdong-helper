#!/usr/bin/env bash
# 把仓库里的源码分门别类打成 zip，产物放 release/ 目录
# 用法：./scripts/package-release.sh [版本号]
set -euo pipefail
cd "$(dirname "$0")/.."

VER="${1:-2.10}"
OUT="release"
rm -rf "$OUT"
mkdir -p "$OUT"

EXCLUDES=(--exclude '.git' --exclude 'build' --exclude '.gradle' --exclude 'out'
          --exclude 'out-test' --exclude 'node_modules' --exclude '*.keystore'
          --exclude 'keystore.properties' --exclude 'local.properties' --exclude '*.db')

zip_dir() { # zip_dir 输出文件名 目录...
  local name="$1"; shift
  local tmp; tmp=$(mktemp -d)
  for src in "$@"; do
    local base; base=$(basename "$src")
    rsync -a "${EXCLUDES[@]}" "$src/" "$tmp/$base/"
  done
  ( cd "$tmp" && zip -qr9 "$OLDPWD/$OUT/$name" . )
  rm -rf "$tmp"
}

# 1) 安卓 App 源码（完整工程，可直接用 Android Studio 打开）
zip_dir "fangdong-helper-v$VER-android-source.zip" android-app

# 2) 电脑版源码（含已编译 jar 与启动脚本）
zip_dir "fangdong-helper-v$VER-pc.zip" pc-app

# 3) 后端 · Cloudflare Workers + D1
zip_dir "fangdong-helper-v$VER-backend-01-cloudflare.zip" backend/cf-workers backend/shared backend/scripts

# 4) 后端 · 腾讯 EdgeOne
zip_dir "fangdong-helper-v$VER-backend-02-edgeone.zip" backend/tencent-edgeone backend/tencent-edgeone-pages

# 5) 后端 · 阿里云 ESA
zip_dir "fangdong-helper-v$VER-backend-03-esa.zip" backend/aliyun-esa-er

# 6) 后端 · PHP + MySQL / SQLite
zip_dir "fangdong-helper-v$VER-backend-04-php.zip" backend/php

# 7) 全量源码（含文档与 Actions）
tmp=$(mktemp -d)
rsync -a "${EXCLUDES[@]}" --exclude 'release' ./* "$tmp/"
( cd "$tmp" && zip -qr9 "$OLDPWD/$OUT/fangdong-helper-v$VER-source-all.zip" . )
rm -rf "$tmp"

echo "产物："
ls -lh "$OUT" | sed 's/^/  /'
