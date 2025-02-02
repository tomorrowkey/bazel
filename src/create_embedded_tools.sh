#!/bin/bash -eu

# Copyright 2015 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# This script is used to create the directory tree embedded into the Bazel
# binary that is used as the default source for the @bazel_tools repository.
# It shuffles around files compiled in other rules, then zips them up.

OUTPUT="${PWD}/$1"
shift

TMP_DIR=${TMPDIR:-/tmp}
PACKAGE_DIR="$(mktemp -d ${TMP_DIR%%/}/bazel.XXXXXXXX)"
mkdir -p "${PACKAGE_DIR}"
trap "rm -fr \"${PACKAGE_DIR}\"" EXIT

for i in $*; do
  OUTPUT_PATH=$(echo $i | sed 's_^.*bazel-out/[^/]*/bin/__')
  mkdir -p "${PACKAGE_DIR}/$(dirname "${OUTPUT_PATH}")"
  cp "$i" "${PACKAGE_DIR}/${OUTPUT_PATH}"
done

touch "${PACKAGE_DIR}/WORKSPACE"
mkdir -p "${PACKAGE_DIR}/tools/defaults"
touch "${PACKAGE_DIR}/tools/defaults/BUILD"
for i in $(find "${PACKAGE_DIR}" -name BUILD.tools); do
  mv "$i" "$(dirname "$i")/BUILD"
done
find "${PACKAGE_DIR}" -exec touch -t 198001010000.00 '{}' ';'
(cd "${PACKAGE_DIR}" && zip -qrD "${OUTPUT}" *)
