#!/usr/bin/env bash
# 构建单文件 exe（自包含 win-x64），按版本规则 26.10.8.18（年月日小时）命名并复制到项目根目录
set -euo pipefail

cd "$(dirname "$0")"

# 版本号：YY.M.D.H（年月日小时）
VERSION="$(date +%-y.%-m.%-d.%-H)"
PUBDIR="publish/singlefile-${VERSION}"
OUT_EXE="AirScanQR-${VERSION}.exe"

echo "==> 版本号: ${VERSION}"
echo "==> 发布目录: ${PUBDIR}"

dotnet publish -c Release -r win-x64 --self-contained true \
  -p:PublishSingleFile=true \
  -p:EnableCompressionInSingleFile=true \
  -p:IncludeNativeLibrariesForSelfExtract=true \
  -p:Version="${VERSION}" \
  -o "${PUBDIR}"

cp "${PUBDIR}/AirScanQR.exe" "${OUT_EXE}"

echo "==> 已生成: ${OUT_EXE}"
ls -lh "${OUT_EXE}"
