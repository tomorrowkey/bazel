#!/bin/bash
#
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
#
# Test the local_repository binding
#

# Load test environment
source $(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/test-setup.sh \
  || { echo "test-setup.sh not found!" >&2; exit 1; }

function test_glob_local_repository_dangling_symlink() {
  create_new_workspace
  r=$TEST_TMPDIR/r
  rm -fr $r
  mkdir -p $r
  touch $r/WORKSPACE
  cat > $r/BUILD <<EOF
filegroup(name='fg', srcs=glob(["fg/**"]), visibility=["//visibility:public"])
EOF

  mkdir -p $r/fg
  ln -s /doesnotexist $r/fg/symlink
  touch $r/fg/file

  cat > WORKSPACE <<EOF
local_repository(name="r", path="$r")
bind(name="e", actual="@r//:fg")
EOF

  cat > BUILD <<EOF
filegroup(name="mfg", srcs=["//external:e"])
EOF

  bazel build //:mfg
}

# Uses a glob from a different repository for a runfile.
# This create two repositories and populate them with basic build files:
#
# ${WORKSPACE_DIR}/
#     WORKSPACE
#     zoo/
#       BUILD
#       dumper.sh
#     red/
#       BUILD
#       day-keeper
# repo2/
#   red/
#     BUILD
#     baby-panda
#
# dumper.sh should be able to dump the contents of baby-panda.
function test_globbing_external_directory() {
  create_new_workspace
  repo2=${new_workspace_dir}

  mkdir -p red
  cat > red/BUILD <<EOF
filegroup(
    name = "panda",
    srcs = glob(['*-panda']),
    visibility = ["//visibility:public"],
)
EOF

  echo "rawr" > red/baby-panda

  cd ${WORKSPACE_DIR}
  mkdir -p {zoo,red}
  cat > WORKSPACE <<EOF
local_repository(name = 'pandas', path = '${repo2}')
EOF

  cat > zoo/BUILD <<EOF
sh_binary(
    name = "dumper",
    srcs = ["dumper.sh"],
    data = ["@pandas//red:panda", "//red:keepers"]
)
EOF

  cat > zoo/dumper.sh <<EOF
#!/bin/bash
cat external/pandas/red/baby-panda
cat red/day-keeper
EOF
  chmod +x zoo/dumper.sh

  cat > red/BUILD <<EOF
filegroup(
    name = "keepers",
    srcs = glob(['*-keeper']),
    visibility = ["//visibility:public"],
)
EOF

  echo "feed bamboo" > red/day-keeper

  bazel fetch //zoo:dumper || fail "Fetch failed"
  bazel run //zoo:dumper >& $TEST_log || fail "Failed to build/run zoo"
  expect_log "rawr" "//external runfile not cat-ed"
  expect_log "feed bamboo" \
    "runfile in the same package as //external runfiles not cat-ed"
}

# Tests using a Java dependency.
function test_local_repository_java() {
  create_new_workspace
  repo2=$new_workspace_dir

  mkdir -p carnivore
  cat > carnivore/BUILD <<EOF
java_library(
    name = "mongoose",
    srcs = ["Mongoose.java"],
    visibility = ["//visibility:public"],
)
EOF
  cat > carnivore/Mongoose.java <<EOF
package carnivore;
public class Mongoose {
    public static void frolic() {
        System.out.println("Tra-la!");
    }
}
EOF

  cd ${WORKSPACE_DIR}
  cat > WORKSPACE <<EOF
local_repository(name = 'endangered', path = '$repo2')
EOF

  mkdir -p zoo
  cat > zoo/BUILD <<EOF
java_binary(
    name = "ball-pit",
    srcs = ["BallPit.java"],
    main_class = "BallPit",
    deps = ["@endangered//carnivore:mongoose"],
)
EOF

  cat > zoo/BallPit.java <<EOF
import carnivore.Mongoose;

public class BallPit {
    public static void main(String args[]) {
        Mongoose.frolic();
    }
}
EOF

  bazel build @endangered//carnivore:mongoose >& $TEST_log || \
    fail "Expected build to succeed"
  bazel run //zoo:ball-pit >& $TEST_log
  expect_log "Tra-la!"
}

function test_non_existent_external_ref() {
  mkdir -p zoo
  touch zoo/BallPit.java
  cat > zoo/BUILD <<EOF
java_binary(
    name = "ball-pit",
    srcs = ["BallPit.java"],
    main_class = "BallPit",
    deps = ["@common//carnivore:mongoose"],
)
EOF

  bazel build //zoo:ball-pit >& $TEST_log && \
    fail "Expected build to fail"
  expect_log "no such package '@common//carnivore'"
}

function test_new_local_repository() {
  bazel clean

  # Create a non-Bazel directory.
  project_dir=$TEST_TMPDIR/project
  mkdir -p $project_dir
  outside_dir=$TEST_TMPDIR/outside
  mkdir -p $outside_dir
  package_dir=$project_dir/carnivore
  mkdir $package_dir
  # Be tricky with absolute symlinks to make sure that Bazel still acts as
  # though external repositories are immutable.
  ln -s $outside_dir/Mongoose.java $package_dir/Mongoose.java

  cat > $package_dir/Mongoose.java <<EOF
package carnivore;
public class Mongoose {
    public static void frolic() {
        System.out.println("Tra-la!");
    }
}
EOF

  build_file=BUILD.carnivore
  cat > WORKSPACE <<EOF
new_local_repository(
    name = 'endangered',
    path = '$project_dir',
    build_file = '$build_file',
)
EOF

   mkdir -p zoo
   cat > zoo/BUILD <<EOF
java_binary(
    name = "ball-pit",
    srcs = ["BallPit.java"],
    main_class = "BallPit",
    deps = ["@endangered//:mongoose"],
)
EOF

  cat > zoo/BallPit.java <<EOF
import carnivore.Mongoose;

public class BallPit {
    public static void main(String args[]) {
        Mongoose.frolic();
    }
}
EOF

  cat > $build_file <<EOF
java_library(
    name = "mongoose",
    srcs = ["carnivore/Mongoose.java"],
    visibility = ["//visibility:public"],
)
EOF
  bazel fetch //zoo:ball-pit || fail "Fetch failed"
  bazel run //zoo:ball-pit >& $TEST_log || fail "Failed to build/run zoo"
  expect_log "Tra-la!"

  cat > $package_dir/Mongoose.java <<EOF
package carnivore;
public class Mongoose {
    public static void frolic() {
        System.out.println("Growl!");
    }
}
EOF

  # Check that rebuilding this doesn't rebuild libmongoose.jar, even though it
  # has changed. Bazel assumes that files in external repositories are
  # immutable.
  bazel fetch //zoo:ball-pit || fail "Fetch failed"
  bazel run //zoo:ball-pit >& $TEST_log || fail "Failed to build/run zoo"
  expect_log "Tra-la!"
  expect_not_log "Building endangered/libmongoose.jar"
  expect_not_log "Growl!"
}

function test_default_ws() {
  bazel fetch //external:java || fail "Fetch failed"
  bazel build //external:java >& $TEST_log || fail "Failed to build java"
}

function test_external_hdrs() {
  local external_ws=$TEST_TMPDIR/path/to/my/lib
  mkdir -p $external_ws
  touch $external_ws/WORKSPACE
  cat > $external_ws/greet_lib.h <<EOF
void greet();
EOF
  cat > $external_ws/greet_lib.cc <<EOF
#include <stdio.h>
void greet() {
  printf("Hello");
}
EOF
  cat > $external_ws/BUILD <<EOF
cc_library(
    name = "greet_lib",
    srcs = ["greet_lib.cc"],
    hdrs = ["greet_lib.h"],
    includes = [
        ".",
    ],
    visibility = ["//visibility:public"],
)
EOF

  cat > greeter.cc <<EOF
#include "greet_lib.h"

int main() {
  greet();
  return 0;
}
EOF
  cat > BUILD <<EOF
cc_binary(
    name = "greeter",
    srcs = ["greeter.cc"],
    deps = ["@greet-ws//:greet_lib"],
)
EOF
  cat > WORKSPACE <<EOF
local_repository(
    name = "greet-ws",
    path = "$external_ws",
)
EOF

  bazel fetch //:greeter || fail "Fetch failed"
  bazel run //:greeter >& $TEST_log || fail "Failed to run greeter"
  expect_log "Hello"
}

# Creates an indirect dependency on X from A and make sure the error message
# refers to the correct label.
function test_indirect_dep_message() {
  local external_dir=$TEST_TMPDIR
  mkdir -p a b $external_dir/x
  cat > a/A.java <<EOF
package a;

import x.X;

public class A {
  public static void main(String args[]) {
    X.print();
  }
}
EOF
  cat > a/BUILD <<EOF
java_binary(
    name = "a",
    main_class = "a.A",
    srcs = ["A.java"],
    deps = ["//b"],
)
EOF


  cat > b/B.java <<EOF
package b;

public class B {
  public static void print() {
     System.out.println("B");
  }
}
EOF
  cat > b/BUILD <<EOF
java_library(
    name = "b",
    srcs = ["B.java"],
    deps = ["@x-repo//x"],
    visibility = ["//visibility:public"],
)
EOF

  touch $external_dir/WORKSPACE
  cat > $external_dir/x/X.java <<EOF
package x;

public class X {
  public static void print() {
    System.out.println("X");
  }
}
EOF
  cat > $external_dir/x/BUILD <<EOF
java_library(
    name = "x",
    srcs = ["X.java"],
    visibility = ["//visibility:public"],
)
EOF

  cat > WORKSPACE <<EOF
local_repository(
    name = "x-repo",
    path = "$external_dir",
)
EOF

  bazel fetch //a:a || fail "Fetch failed"
  bazel build //a:a >& $TEST_log && fail "Building //a:a should error out"
  expect_log "** Please add the following dependencies:"
  expect_log "@x-repo//x  to //a:a"
}

function test_external_includes() {
  clib=$TEST_TMPDIR/clib
  mkdir -p $clib/include
  cat > $clib/include/clib.h <<EOF
int x();
EOF
  cat > $clib/clib.cc <<EOF
#include "clib.h"
int x() {
  return 3;
}
EOF
  cat > $clib/BUILD <<EOF
cc_library(
    name = "clib",
    srcs = ["clib.cc"],
    hdrs = glob(["**/*.h"]),
    includes = ["include"],
    visibility = ["//visibility:public"],
)
EOF

  cat > WORKSPACE <<EOF
local_repository(
    name = "clib-repo",
    path = "$clib",
)
EOF
  cat > BUILD <<EOF
cc_binary(
    name = "printer",
    srcs = ["printer.cc"],
    deps = ["@clib-repo//:clib"],
)
EOF
  cat > printer.cc <<EOF
#include <stdio.h>

#include "clib.h"

int main() {
  printf("My number is %d\n", x());
  return 0;
}
EOF

  bazel fetch //:printer || fail "Fetch failed"
  bazel build @clib-repo//:clib >& $TEST_log \
    || fail "Building @clib-repo//:clib failed"
  bazel run //:printer >& $TEST_log || fail "Running //:printer failed"
  expect_log "My number is 3"
}

function test_external_query() {
  local external_dir=$TEST_TMPDIR/x
  mkdir -p $external_dir
  touch $external_dir/WORKSPACE
  cat > WORKSPACE <<EOF
local_repository(
    name = "my-repo",
    path = "$external_dir",
)
EOF
  bazel fetch //external:my-repo || fail "Fetch failed"
  bazel query 'deps(//external:my-repo)' >& $TEST_log || fail "query failed"
  expect_log "//external:my-repo"
}

function test_overlaid_build_file() {
  local mutant=$TEST_TMPDIR/mutant
  mkdir $mutant
  touch $mutant/WORKSPACE
  cat > WORKSPACE <<EOF
new_local_repository(
    name = "mutant",
    path = "$mutant",
    build_file = "mutant.BUILD"
)

bind(
    name = "best-turtle",
    actual = "@mutant//:turtle",
)
EOF
  cat > mutant.BUILD <<EOF
genrule(
    name = "turtle",
    outs = ["tmnt"],
    cmd = "echo 'Leonardo' > \$@",
    visibility = ["//visibility:public"],
)
EOF
  bazel fetch //external:best-turtle || fail "Fetch failed"
  bazel build //external:best-turtle &> $TEST_log || fail "First build failed"
  assert_contains "Leonardo" bazel-genfiles/external/mutant/tmnt

  cat > mutant.BUILD <<EOF
genrule(
    name = "turtle",
    outs = ["tmnt"],
    cmd = "echo 'Donatello' > \$@",
    visibility = ["//visibility:public"],
)
EOF
  bazel build //external:best-turtle &> $TEST_log || fail "Second build failed"
  assert_contains "Donatello" bazel-genfiles/external/mutant/tmnt
}

function test_external_deps_in_remote_repo() {
  local r=$TEST_TMPDIR/r
  rm -fr $r
  mkdir -p $r
  cat > WORKSPACE <<EOF
local_repository(
    name = "r",
    path = "$r",
)

bind(
    name = "e",
    actual = "@r//:g",
)
EOF

  cat > $r/BUILD <<EOF
genrule(
    name = "r",
    srcs = ["//external:e"],
    outs = ["r.out"],
    cmd = "cp \$< \$@",
)

genrule(
    name = "g",
    srcs = [],
    outs = ["g.out"],
    cmd = "echo GOLF > \$@",
    visibility = ["//visibility:public"],
)
EOF

 bazel build @r//:r || fail "build failed"
 assert_contains "GOLF" bazel-genfiles/external/r/r.out
}

function test_local_deps() {
  local r=$TEST_TMPDIR/r
  rm -fr $r
  mkdir -p $r
  cat > WORKSPACE <<EOF
local_repository(
    name = "r",
    path = "$r",
)
EOF

  mkdir -p $r/a
  cat > $r/a/BUILD <<'EOF'
genrule(
    name = "a",
    srcs = ["//b:b"],
    outs = ["a.out"],
    cmd = "cp $< $@",
)
EOF

  mkdir -p $r/b
  cat > $r/b/BUILD <<'EOF'
genrule(
    name = "b",
    srcs = [],
    outs = ["b.out"],
    cmd = "echo SHOUT > $@",
    visibility = ["//visibility:public"],
)
EOF

  bazel build @r//a || fail "build failed"
}

function test_globs() {
  local r=$TEST_TMPDIR/r
  rm -fr $r
  mkdir -p $r
  cat > WORKSPACE <<EOF
local_repository(
    name = "r",
    path = "$r",
)

EOF

  cat > $r/BUILD <<EOF
filegroup(
    name = "fg",
    srcs = glob(["**"]),
)
EOF

  touch $r/a
  mkdir -p $r/b
  touch $r/b/{BUILD,b}

  bazel build @r//:fg || fail "build failed"
}

function test_cc_binary_in_local_repository() {
  local r=$TEST_TMPDIR/r
  rm -fr $r
  mkdir $r
  touch $r/WORKSPACE
  cat > $r/BUILD <<EOF
cc_binary(
    name = "bin",
    srcs = ["bin.cc"],
)
EOF
  cat > $r/bin.cc <<EOF
int main() { return 0; };
EOF

  cat > WORKSPACE <<EOF
local_repository(
    name = "r",
    path = "$r",
)
EOF

  bazel build @r//:bin || fail "build failed"
}

function test_output_file_in_local_repository() {
  local r=$TEST_TMPDIR/r
  rm -fr $r
  mkdir $r
  touch $r/WORKSPACE
  cat > $r/BUILD <<'EOF'
genrule(
    name="r",
    srcs=[],
    outs=["r.out"],
    cmd="touch $@",
    visibility=["//visibility:public"])
EOF

  cat > WORKSPACE <<EOF
local_repository(name="r", path="$r")
EOF

  cat > BUILD <<'EOF'
genrule(name="m", srcs=["@r//:r.out"], outs=["m.out"], cmd="touch $@")
EOF

  bazel build //:m
}

function test_remote_pkg_boundaries() {
  other_ws=$TEST_TMPDIR/ws
  mkdir -p $other_ws/a
  touch $other_ws/WORKSPACE
  cat > $other_ws/a/b <<EOF
abcxyz
EOF
  cat > $other_ws/BUILD <<EOF
exports_files(["a/b"])
EOF
  cat > WORKSPACE <<EOF
local_repository(
    name = "other",
    path = "$other_ws",
)
EOF
  cat > BUILD <<EOF
load('/sample', 'sample_bin')

sample_bin(
    name = "x",
)
EOF
  cat > sample.bzl <<EOF
def impl(ctx):
    ctx.action(
        command = "cat %s > %s" % (ctx.file._dep.path, ctx.outputs.sh.path),
        inputs = [ctx.file._dep],
        outputs = [ctx.outputs.sh]
    )

sample_bin = rule(
    attrs = {
        '_dep': attr.label(
            default=Label("@other//:a/b"),
            executable=True,
            allow_files=True,
            single_file=True)
    },
    outputs = {'sh': "%{name}.sh"},
    implementation = impl,
)
EOF

  bazel build -s //:x
  assert_contains "abcxyz" bazel-bin/x.sh
}

function test_visibility_through_bind() {
  local r=$TEST_TMPDIR/r
  rm -fr $r
  mkdir $r

  cat > $r/BUILD <<EOF
genrule(
    name = "public",
    srcs = ["//external:public"],
    outs = ["public.out"],
    cmd = "cp \$< \$@",
)

genrule(
    name = "private",
    srcs = ["//external:private"],
    outs = ["private.out"],
    cmd = "cp \$< \$@",
)
EOF

  cat > WORKSPACE <<EOF
local_repository(
    name = "r",
    path = "$r",
)

bind(
    name = "public",
    actual = "//:public",
)

bind(
    name = "private",
    actual = "//:private",
)
EOF

  cat > BUILD <<EOF
genrule(
    name = "public",
    srcs = [],
    outs = ["public.out"],
    cmd = "echo PUBLIC > \$@",
    visibility = ["//visibility:public"],
)

genrule(
    name = "private",
    srcs = [],
    outs = ["private.out"],
    cmd = "echo PRIVATE > \$@",
)
EOF

  bazel build @r//:public >& $TEST_log || fail "failed to build public target"
  bazel build @r//:private >& $TEST_log && fail "could build private target"
  expect_log "Target '//:private' is not visible from target '@r//:private'"
}

function test_load_in_remote_repository() {
  local r=$TEST_TMPDIR/r
  rm -fr $r
  mkdir -p $r
  cat > $r/BUILD <<EOF
package(default_visibility=["//visibility:public"])
load("r", "r_filegroup")
r_filegroup(name="rfg", srcs=["rfgf"])
EOF

  cat > $r/r.bzl <<EOF
def r_filegroup(name, srcs):
    native.filegroup(name=name, srcs=srcs)
EOF

  touch $r/rfgf

  cat > WORKSPACE <<EOF
local_repository(name="r", path="$r")
EOF

  cat > BUILD <<EOF
filegroup(name="fg", srcs=["@r//:rfg"])
EOF

  bazel build //:fg || fail "failed to build target"
}

function test_python_in_remote_repository() {
  local r=$TEST_TMPDIR/r
  rm -fr $r
  mkdir -p $r/bin
  cat > $r/bin/BUILD <<EOF
package(default_visibility=["//visibility:public"])
py_binary(name="bin", srcs=["bin.py"], deps=["//lib:lib"])
EOF

  cat > $r/bin/bin.py <<EOF
import lib.lib

print "Hello " + lib.lib.User()
EOF

  chmod +x $r/bin/bin.py

  mkdir -p $r/lib
  cat > $r/lib/BUILD <<EOF
package(default_visibility=["//visibility:public"])
py_library(name="lib", srcs=["lib.py"])
EOF

  cat > $r/lib/lib.py <<EOF
def User():
  return "User"
EOF

  cat > WORKSPACE <<EOF
local_repository(name="r", path="$r")
EOF

  bazel run @r//bin:bin >& $TEST_log || fail "build failed"
  expect_log "Hello User"
}

function test_package_wildcard_in_remote_repository() {
  local r=$TEST_TMPDIR/r
  rm -fr $r
  mkdir -p $r/a
  touch $r/{x,y,a/g,a/h}
  cat > $r/BUILD <<EOF
exports_files(["x", "y"])
EOF

  cat > $r/a/BUILD <<EOF
exports_files(["g", "h"])
EOF

  cat > WORKSPACE <<EOF
local_repository(name="r", path="$r")
EOF

  bazel query @r//:all-targets + @r//a:all-targets >& $TEST_log || fail "query failed"
  expect_log "@r//:x"
  expect_log "@r//:y"
  expect_log "@r//a:g"
  expect_log "@r//a:h"
}

function test_recursive_wildcard_in_remote_repository() {
  local r=$TEST_TMPDIR/r
  rm -fr $r
  mkdir -p $r/a/{x,y/z}
  touch $r/a/{x,y/z}/{m,n}

  echo 'exports_files(["m", "n"])' > $r/a/x/BUILD
  echo 'exports_files(["m", "n"])' > $r/a/y/z/BUILD

  echo "local_repository(name='r', path='$r')" > WORKSPACE
  bazel query @r//...:all-targets >& $TEST_log || fail "query failed"
  expect_log "@r//a/x:m"
  expect_log "@r//a/x:n"
  expect_log "@r//a/y/z:m"
  expect_log "@r//a/y/z:n"

  bazel query @r//a/x:all-targets >& $TEST_log || fail "query failed"
  expect_log "@r//a/x:m"
  expect_log "@r//a/x:n"
  expect_not_log "@r//a/y/z:m"
  expect_not_log "@r//a/y/z:n"
}

function test_package_name_constants() {
  local r=$TEST_TMPDIR/r
  rm -fr $r
  mkdir -p $r/a
  cat > $r/a/BUILD <<'EOF'
genrule(
  name = 'b',
  srcs = [],
  outs = ['bo'],
  cmd = 'echo ' + REPOSITORY_NAME + ' ' + PACKAGE_NAME + ' > $@')
EOF

  cat > WORKSPACE <<EOF
local_repository(name='r', path='$r')
EOF

  bazel build @r//a:b || fail "build failed"
  cat bazel-genfiles/external/r/a/bo > $TEST_log
  expect_log "@r a"
}

function test_slash_in_repo_name() {
  local r=$TEST_TMPDIR/r
  rm -fr $r
  mkdir -p $r/a

  touch $r/a/WORKSPACE
  cat > $r/a/BUILD <<EOF
cc_binary(
    name = "bin",
    srcs = ["bin.cc"],
)
EOF
  cat > $r/a/bin.cc <<EOF
int main() { return 0; };
EOF

  cat > WORKSPACE <<EOF
local_repository(
    name = "r/a",
    path = "$r/a",
)
EOF

  bazel build @r/a//:bin &> $TEST_log && fail "expected build failure, but succeeded"
  expect_log "workspace names may contain only A-Z, a-z, 0-9, '-', '_' and '.'"
}

run_suite "local repository tests"
