// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.bazel.rules.android.ndkcrosstools;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.bazel.rules.android.ndkcrosstools.AndroidNdkCrosstools.NdkCrosstoolsException;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.util.ResourceFileLoader;
import com.google.devtools.build.lib.view.config.crosstool.CrosstoolConfig.CToolchain;
import com.google.devtools.build.lib.view.config.crosstool.CrosstoolConfig.CrosstoolRelease;
import com.google.devtools.build.lib.view.config.crosstool.CrosstoolConfig.DefaultCpuToolchain;
import com.google.devtools.build.lib.view.config.crosstool.CrosstoolConfig.ToolPath;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.Set;

/**
 * Tests for {@link AndroidNdkCrosstools}.
 */
@RunWith(JUnit4.class)
public class AndroidNdkCrosstoolsTest {

  private static final ImmutableSet<String> NDK_FILES, NDK_DIRECTORIES;

  private static final String REPOSITORY_NAME = "testrepository";
  private static final ApiLevel API_LEVEL =
      new ApiLevel(NullEventHandler.INSTANCE, REPOSITORY_NAME, "21");
  private static final NdkRelease NDK_RELEASE = NdkRelease.create("r10e (64-bit)");
  private static final ImmutableList<CrosstoolRelease> CROSSTOOL_RELEASES;
  private static final ImmutableMap<String, String> STL_FILEGROUPS;

  static {
    try {

      String hostPlatform = AndroidNdkCrosstools.getHostPlatform(NDK_RELEASE);
      NdkPaths ndkPaths = new NdkPaths(
          REPOSITORY_NAME,
          hostPlatform,
          API_LEVEL);

      ImmutableList.Builder<CrosstoolRelease> crosstools = ImmutableList.builder();
      ImmutableMap.Builder<String, String> stlFilegroups = ImmutableMap.builder();
      for (StlImpl ndkStlImpl : StlImpls.get(ndkPaths)) {
        // Protos are immutable, so this can be shared between tests.
        CrosstoolRelease crosstool = AndroidNdkCrosstools.create(
            NullEventHandler.INSTANCE,
            ndkPaths,
            REPOSITORY_NAME,
            API_LEVEL,
            NDK_RELEASE,
            ndkStlImpl,
            hostPlatform);
        crosstools.add(crosstool);
        stlFilegroups.putAll(ndkStlImpl.getFilegroupNamesAndFilegroupFileGlobPatterns());
      }

      CROSSTOOL_RELEASES = crosstools.build();
      STL_FILEGROUPS = stlFilegroups.build();

      // ndkfiles.txt contains a list of every file in the ndk, created using this command at the
      // root of the Android NDK for version r10e (64-bit):
      //     find . -xtype f | sed 's|^\./||' | sort
      // and similarly for ndkdirectories, except "-xtype d" is used.
      //
      // It's unfortunate to have files like these, since they're large and brittle, but since the
      // whole NDK can't be checked in to test against, it's about the most that can be done right
      // now.
      NDK_FILES = getFiles("ndkfiles.txt");
      NDK_DIRECTORIES = getFiles("ndkdirectories.txt");

    } catch (NdkCrosstoolsException e) {
      throw new RuntimeException(e);
    }
  }

  private static ImmutableSet<String> getFiles(String fileName) {

    String ndkFilesContent;
    try {
      ndkFilesContent = ResourceFileLoader.loadResource(
          AndroidNdkCrosstoolsTest.class, fileName);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }

    ImmutableSet.Builder<String> ndkFiles = ImmutableSet.builder();
    Scanner ndkFilesContentScanner = new Scanner(ndkFilesContent);
    while (ndkFilesContentScanner.hasNext()) {
      String path = ndkFilesContentScanner.nextLine();
      // The contents of the NDK are placed at "external/%repositoryName%/ndk".
      // The "external/%repositoryName%" part is removed using NdkPaths.stripRepositoryPrefix,
      // but to make it easier the "ndk/" part is added here.
      path = "ndk/" + path;
      ndkFiles.add(path);
    }
    ndkFilesContentScanner.close();
    return ndkFiles.build();
  }
  
  @Test
  public void testPathsExist() throws Exception {

    for (CrosstoolRelease crosstool : CROSSTOOL_RELEASES) {
      for (CToolchain toolchain : crosstool.getToolchainList()) {
  
        // Test that all tool paths exist.
        for (ToolPath toolpath : toolchain.getToolPathList()) {
          assertThat(NDK_FILES).contains(toolpath.getPath());
        }
  
        // Test that all cxx_builtin_include_directory paths exist.
        for (String includeDirectory : toolchain.getCxxBuiltinIncludeDirectoryList()) {
          // Special case for builtin_sysroot.
          if (!includeDirectory.equals("%sysroot%/usr/include")) {
            String path = NdkPaths.stripRepositoryPrefix(includeDirectory);
            assertThat(NDK_DIRECTORIES).contains(path);
          }
        }
  
        // Test that the builtin_sysroot path exists.
        {
          String builtinSysroot = NdkPaths.stripRepositoryPrefix(toolchain.getBuiltinSysroot());
          assertThat(NDK_DIRECTORIES).contains(builtinSysroot);
        }
  
        // Test that all include directories added through unfiltered_cxx_flag exist.
        for (String flag : toolchain.getUnfilteredCxxFlagList()) {
          if (!flag.equals("-isystem")) {
            flag = NdkPaths.stripRepositoryPrefix(flag);
            assertThat(NDK_DIRECTORIES).contains(flag);
          }
        }
      }
    }
  }

  @Test
  public void testStlFilegroupPathsExist() throws Exception {

    for (String fileglob : STL_FILEGROUPS.values()) {
      String fileglobNoWildcard = fileglob.substring(0, fileglob.lastIndexOf('/'));
      assertThat(NDK_DIRECTORIES).contains(fileglobNoWildcard);
      assertThat(findFileByPattern(fileglob)).isTrue();
    }
  }

  private static boolean findFileByPattern(String globPattern) {

    String start = globPattern.substring(0, globPattern.indexOf('*'));
    String end = globPattern.substring(globPattern.lastIndexOf('.'));
    for (String f : NDK_FILES) {
      if (f.startsWith(start) && f.endsWith(end)) {
        return true;
      }
    }
    return false;
  }

  @Test
  public void testAllToolchainsHaveRuntimesFilegroup() {
    for (CrosstoolRelease crosstool : CROSSTOOL_RELEASES) {
      for (CToolchain toolchain : crosstool.getToolchainList()) {
        assertThat(toolchain.getDynamicRuntimesFilegroup()).isNotEmpty();
        assertThat(toolchain.getStaticRuntimesFilegroup()).isNotEmpty();
      }
    }
  }

  @Test
  public void testDefaultToolchainsExist() {

    for (CrosstoolRelease crosstool : CROSSTOOL_RELEASES) {

      Set<String> toolchainNames = new HashSet<>();
      for (CToolchain toolchain : crosstool.getToolchainList()) {
        toolchainNames.add(toolchain.getToolchainIdentifier());
      }

      for (DefaultCpuToolchain defaultCpuToolchain : crosstool.getDefaultToolchainList()) {
        assertThat(toolchainNames).contains(defaultCpuToolchain.getToolchainIdentifier());
      }
    }
  }

  /**
   * Tests that each (cpu, compiler, glibc) triple in each crosstool is unique in that crosstool.
   */
  @Test
  public void testCrosstoolTriples() {

    StringBuilder errorBuilder = new StringBuilder();
    for (CrosstoolRelease crosstool : CROSSTOOL_RELEASES) {

      // Create a map of (cpu, compiler, glibc) triples -> toolchain.
      ImmutableMultimap.Builder<String, CToolchain> triples = ImmutableMultimap.builder();
      for (CToolchain toolchain : crosstool.getToolchainList()) {
        String triple = "(" + Joiner.on(", ").join(
            toolchain.getTargetCpu(),
            toolchain.getCompiler(),
            toolchain.getTargetLibc()) + ")";
        triples.put(triple, toolchain);
      }

      // Collect all the duplicate triples.
      for (Entry<String, Collection<CToolchain>> entry : triples.build().asMap().entrySet()) {
        if (entry.getValue().size() > 1) {
          errorBuilder.append(entry.getKey() + ": " + Joiner.on(", ").join(
              Collections2.transform(entry.getValue(), new Function<CToolchain, String>() {
                @Override public String apply(CToolchain toolchain) {
                  return toolchain.getToolchainIdentifier();
                }
              })));
          errorBuilder.append("\n");
        }
      }
      errorBuilder.append("\n");
    }

    // This is a rather awkward condition to test on, but collecting all the duplicates first is
    // the only way to make a useful error message rather than finding the errors one by one.
    String error = errorBuilder.toString().trim();
    if (!error.isEmpty()) {
      fail("Toolchains contain duplicate (cpu, compiler, glibc) triples:\n" + error);
    }
  }
}
