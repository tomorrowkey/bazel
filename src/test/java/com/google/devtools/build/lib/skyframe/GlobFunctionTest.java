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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.testing.EqualsTester;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.skyframe.GlobValue.InvalidGlobPatternException;
import com.google.devtools.build.lib.testutil.ManualClock;
import com.google.devtools.build.lib.testutil.MoreAsserts;
import com.google.devtools.build.lib.util.BlazeClock;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.UnixGlob;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.build.skyframe.ErrorInfo;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.RecordingDifferencer;
import com.google.devtools.build.skyframe.SequentialBuildDriver;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

/**
 * Tests for {@link GlobFunction}.
 */
public abstract class GlobFunctionTest {
  @RunWith(JUnit4.class)
  public static class GlobFunctionAlwaysUseDirListingTest extends GlobFunctionTest {
    @Override
    protected boolean alwaysUseDirListing() {
      return true;
    }
  }

  @RunWith(JUnit4.class)
  public static class RegularGlobFunctionTest extends GlobFunctionTest {
    @Override
    protected boolean alwaysUseDirListing() {
      return false;
    }
  }

  private CustomInMemoryFs fs;
  private MemoizingEvaluator evaluator;
  private SequentialBuildDriver driver;
  private RecordingDifferencer differencer;
  private Path root;
  private Path outputBase;
  private Path pkgPath;
  private AtomicReference<PathPackageLocator> pkgLocator;
  private TimestampGranularityMonitor tsgm;

  private static final PackageIdentifier PKG_PATH_ID = PackageIdentifier.createInDefaultRepo("pkg");

  @Before
  public void setUp() throws Exception {
    
    fs = new CustomInMemoryFs(new ManualClock());
    root = fs.getRootDirectory().getRelative("root/workspace");
    outputBase = fs.getRootDirectory().getRelative("output_base");
    pkgPath = root.getRelative(PKG_PATH_ID.getPackageFragment());

    pkgLocator = new AtomicReference<>(new PathPackageLocator(outputBase, ImmutableList.of(root)));
    tsgm = new TimestampGranularityMonitor(BlazeClock.instance());

    differencer = new RecordingDifferencer();
    evaluator = new InMemoryMemoizingEvaluator(createFunctionMap(), differencer);
    driver = new SequentialBuildDriver(evaluator);
    PrecomputedValue.BUILD_ID.set(differencer, UUID.randomUUID());
    PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, pkgLocator.get());

    createTestFiles();
  }

  private Map<SkyFunctionName, SkyFunction> createFunctionMap() {
    AtomicReference<ImmutableSet<PackageIdentifier>> deletedPackages =
        new AtomicReference<>(ImmutableSet.<PackageIdentifier>of());
    ExternalFilesHelper externalFilesHelper = new ExternalFilesHelper(pkgLocator);

    Map<SkyFunctionName, SkyFunction> skyFunctions = new HashMap<>();
    skyFunctions.put(SkyFunctions.GLOB, new GlobFunction(alwaysUseDirListing()));
    skyFunctions.put(
        SkyFunctions.DIRECTORY_LISTING_STATE,
        new DirectoryListingStateFunction(externalFilesHelper));
    skyFunctions.put(SkyFunctions.DIRECTORY_LISTING, new DirectoryListingFunction());
    skyFunctions.put(SkyFunctions.PACKAGE_LOOKUP, new PackageLookupFunction(deletedPackages));
    skyFunctions.put(
        SkyFunctions.FILE_STATE,
        new FileStateFunction(
            new TimestampGranularityMonitor(BlazeClock.instance()), externalFilesHelper));
    skyFunctions.put(SkyFunctions.FILE, new FileFunction(pkgLocator, tsgm, externalFilesHelper));
    return skyFunctions;
  }

  protected abstract boolean alwaysUseDirListing();

  private void createTestFiles() throws IOException {
    FileSystemUtils.createDirectoryAndParents(pkgPath);
    FileSystemUtils.createEmptyFile(pkgPath.getRelative("BUILD"));
    for (String dir :
        ImmutableList.of(
            "foo/bar/wiz", "foo/barnacle/wiz", "food/barnacle/wiz", "fool/barnacle/wiz")) {
      FileSystemUtils.createDirectoryAndParents(pkgPath.getRelative(dir));
    }
    FileSystemUtils.createEmptyFile(pkgPath.getRelative("foo/bar/wiz/file"));

    // Used for testing the behavior of globbing into nested subpackages.
    for (String dir : ImmutableList.of("a1/b1/c", "a2/b2/c")) {
      FileSystemUtils.createDirectoryAndParents(pkgPath.getRelative(dir));
    }
    FileSystemUtils.createEmptyFile(pkgPath.getRelative("a2/b2/BUILD"));
  }

  @Test
  public void testSimple() throws Exception {
    assertGlobMatches("food", /* => */ "food");
  }

  @Test
  public void testStartsWithStar() throws Exception {
    assertGlobMatches("*oo", /* => */ "foo");
  }

  @Test
  public void testStartsWithStarWithMiddleStar() throws Exception {
    assertGlobMatches("*f*o", /* => */ "foo");
  }

  @Test
  public void testSingleMatchEqual() throws Exception {
    assertGlobsEqual("*oo", "*f*o"); // both produce "foo"
  }

  @Test
  public void testEndsWithStar() throws Exception {
    assertGlobMatches("foo*", /* => */ "foo", "food", "fool");
  }

  @Test
  public void testEndsWithStarWithMiddleStar() throws Exception {
    assertGlobMatches("f*oo*", /* => */ "foo", "food", "fool");
  }

  @Test
  public void testMultipleMatchesEqual() throws Exception {
    assertGlobsEqual("foo*", "f*oo*"); // both produce "foo", "food", "fool"
  }

  @Test
  public void testMiddleStar() throws Exception {
    assertGlobMatches("f*o", /* => */ "foo");
  }

  @Test
  public void testTwoMiddleStars() throws Exception {
    assertGlobMatches("f*o*o", /* => */ "foo");
  }

  @Test
  public void testSingleStarPatternWithNamedChild() throws Exception {
    assertGlobMatches("*/bar", /* => */ "foo/bar");
  }

  @Test
  public void testDeepSubpackages() throws Exception {
    assertGlobMatches("*/*/c", /* => */ "a1/b1/c");
  }

  @Test
  public void testSingleStarPatternWithChildGlob() throws Exception {
    assertGlobMatches(
        "*/bar*", /* => */ "foo/bar", "foo/barnacle", "food/barnacle", "fool/barnacle");
  }

  @Test
  public void testSingleStarAsChildGlob() throws Exception {
    assertGlobMatches("foo/*/wiz", /* => */ "foo/bar/wiz", "foo/barnacle/wiz");
  }

  @Test
  public void testNoAsteriskAndFilesDontExist() throws Exception {
    // Note un-UNIX like semantics:
    assertGlobMatches("ceci/n'est/pas/une/globbe" /* => nothing */);
  }

  @Test
  public void testSingleAsteriskUnderNonexistentDirectory() throws Exception {
    // Note un-UNIX like semantics:
    assertGlobMatches("not-there/*" /* => nothing */);
  }

  @Test
  public void testDifferentGlobsSameResultEqual() throws Exception {
    // Once the globs are run, it doesn't matter what pattern ran; only the output.
    assertGlobsEqual("not-there/*", "syzygy/*"); // Both produce nothing.
  }

  @Test
  public void testGlobUnderFile() throws Exception {
    assertGlobMatches("foo/bar/wiz/file/*" /* => nothing */);
  }

  @Test
  public void testGlobEqualsHashCode() throws Exception {
    // Each "equality group" forms a set of elements that are all equals() to one another,
    // and also produce the same hashCode.
    new EqualsTester()
        .addEqualityGroup(runGlob(false, "no-such-file")) // Matches nothing.
        .addEqualityGroup(runGlob(false, "BUILD"), runGlob(true, "BUILD")) // Matches BUILD.
        .addEqualityGroup(runGlob(false, "**")) // Matches lots of things.
        .addEqualityGroup(
            runGlob(false, "f*o/bar*"),
            runGlob(false, "foo/bar*")) // Matches foo/bar and foo/barnacle.
        .testEquals();
  }

  @Test
  public void testGlobMissingPackage() throws Exception {
    // This is a malformed value key, because "missing" is not a package. Nevertheless, we have a
    // sanity check that building the corresponding GlobValue fails loudly. The test depends on
    // implementation details of ParallelEvaluator and GlobFunction.
    SkyKey skyKey =
        GlobValue.key(
            PackageIdentifier.createInDefaultRepo("missing"),
            "foo",
            false,
            PathFragment.EMPTY_FRAGMENT);
    try {
      driver.evaluate(
          ImmutableList.of(skyKey),
          false,
          SkyframeExecutor.DEFAULT_THREAD_COUNT,
          NullEventHandler.INSTANCE);
      fail();
    } catch (RuntimeException e) {
      assertThat(e.getMessage())
          .contains("Unrecoverable error while evaluating node '" + skyKey + "'");
      Throwable cause = e.getCause();
      assertThat(cause).isInstanceOf(IllegalStateException.class);
      assertThat(cause.getMessage()).contains("isn't an existing package");
    }
  }

  @Test
  public void testGlobDoesNotCrossPackageBoundary() throws Exception {
    FileSystemUtils.createEmptyFile(pkgPath.getRelative("foo/BUILD"));
    // "foo/bar" should not be in the results because foo is a separate package.
    assertGlobMatches("f*/*", /* => */ "food/barnacle", "fool/barnacle");
  }

  @Test
  public void testGlobDirectoryMatchDoesNotCrossPackageBoundary() throws Exception {
    FileSystemUtils.createEmptyFile(pkgPath.getRelative("foo/bar/BUILD"));
    // "foo/bar" should not be in the results because foo/bar is a separate package.
    assertGlobMatches("foo/*", /* => */ "foo/barnacle");
  }

  @Test
  public void testStarStarDoesNotCrossPackageBoundary() throws Exception {
    FileSystemUtils.createEmptyFile(pkgPath.getRelative("foo/bar/BUILD"));
    // "foo/bar" should not be in the results because foo/bar is a separate package.
    assertGlobMatches("foo/**", /* => */ "foo", "foo/barnacle", "foo/barnacle/wiz");
  }

  private void assertGlobMatches(String pattern, String... expecteds) throws Exception {
    assertGlobMatches(false, pattern, expecteds);
  }

  private void assertGlobWithoutDirsMatches(String pattern, String... expecteds) throws Exception {
    assertGlobMatches(true, pattern, expecteds);
  }

  private void assertGlobMatches(boolean excludeDirs, String pattern, String... expecteds)
      throws Exception {
    MoreAsserts.assertSameContents(
        ImmutableList.copyOf(expecteds),
        Iterables.transform(
            runGlob(excludeDirs, pattern).getMatches(), Functions.toStringFunction()));
  }

  private void assertGlobsEqual(String pattern1, String pattern2) throws Exception {
    GlobValue value1 = runGlob(false, pattern1);
    GlobValue value2 = runGlob(false, pattern2);
    assertEquals(
        "GlobValues "
            + value1.getMatches()
            + " and "
            + value2.getMatches()
            + " should be equal. "
            + "Patterns: "
            + pattern1
            + ","
            + pattern2,
        value1,
        value2);
    // Just to be paranoid:
    assertEquals(value1, value1);
    assertEquals(value2, value2);
  }

  private GlobValue runGlob(boolean excludeDirs, String pattern) throws Exception {
    SkyKey skyKey = GlobValue.key(PKG_PATH_ID, pattern, excludeDirs, PathFragment.EMPTY_FRAGMENT);
    EvaluationResult<SkyValue> result =
        driver.evaluate(
            ImmutableList.of(skyKey),
            false,
            SkyframeExecutor.DEFAULT_THREAD_COUNT,
            NullEventHandler.INSTANCE);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    return (GlobValue) result.get(skyKey);
  }

  @Test
  public void testGlobWithoutWildcards() throws Exception {
    String pattern = "foo/bar/wiz/file";

    assertGlobMatches(pattern, "foo/bar/wiz/file");
    // Ensure that the glob depends on the FileValue and not on the DirectoryListingValue.
    pkgPath.getRelative("foo/bar/wiz/file").delete();
    // Nothing has been invalidated yet, so the cached result is returned.
    assertGlobMatches(pattern, "foo/bar/wiz/file");

    if (alwaysUseDirListing()) {
      differencer.invalidate(
          ImmutableList.of(
              FileStateValue.key(
                  RootedPath.toRootedPath(root, pkgPath.getRelative("foo/bar/wiz/file")))));
      // The result should not rely on the FileStateValue, so it's still a cache hit.
      assertGlobMatches(pattern, "foo/bar/wiz/file");

      differencer.invalidate(
          ImmutableList.of(
              DirectoryListingStateValue.key(
                  RootedPath.toRootedPath(root, pkgPath.getRelative("foo/bar/wiz")))));
      // This should have invalidated the glob result.
      assertGlobMatches(pattern /* => nothing */);
    } else {
      differencer.invalidate(
          ImmutableList.of(
              DirectoryListingStateValue.key(
                  RootedPath.toRootedPath(root, pkgPath.getRelative("foo/bar/wiz")))));
      // The result should not rely on the DirectoryListingValue, so it's still a cache hit.
      assertGlobMatches(pattern, "foo/bar/wiz/file");

      differencer.invalidate(
          ImmutableList.of(
              FileStateValue.key(
                  RootedPath.toRootedPath(root, pkgPath.getRelative("foo/bar/wiz/file")))));
      // This should have invalidated the glob result.
      assertGlobMatches(pattern /* => nothing */);
    }
  }

  @Test
  public void testIllegalPatterns() throws Exception {
    assertIllegalPattern("(illegal) pattern");
    assertIllegalPattern("[illegal pattern");
    assertIllegalPattern("}illegal pattern");
    assertIllegalPattern("foo**bar");
    assertIllegalPattern("?");
    assertIllegalPattern("");
    assertIllegalPattern(".");
    assertIllegalPattern("/foo");
    assertIllegalPattern("./foo");
    assertIllegalPattern("foo/");
    assertIllegalPattern("foo/./bar");
    assertIllegalPattern("../foo/bar");
    assertIllegalPattern("foo//bar");
  }

  @Test
  public void testIllegalRecursivePatterns() throws Exception {
    for (String prefix : Lists.newArrayList("", "*/", "**/", "ba/")) {
      String suffix = ("/" + prefix).substring(0, prefix.length());
      for (String pattern : Lists.newArrayList("**fo", "fo**", "**fo**", "fo**fo", "fo**fo**fo")) {
        assertIllegalPattern(prefix + pattern);
        assertIllegalPattern(pattern + suffix);
      }
    }
  }

  private void assertIllegalPattern(String pattern) {
    try {
      GlobValue.key(PKG_PATH_ID, pattern, false, PathFragment.EMPTY_FRAGMENT);
      fail("invalid pattern not detected: " + pattern);
    } catch (InvalidGlobPatternException e) {
      // Expected.
    }
  }

  /**
   * Tests that globs can contain Java regular expression special characters
   */
  @Test
  public void testSpecialRegexCharacter() throws Exception {
    Path aDotB = pkgPath.getChild("a.b");
    FileSystemUtils.createEmptyFile(aDotB);
    FileSystemUtils.createEmptyFile(pkgPath.getChild("aab"));
    // Note: this contains two asterisks because otherwise a RE is not built,
    // as an optimization.
    assertThat(UnixGlob.forPath(pkgPath).addPattern("*a.b*").globInterruptible())
        .containsExactly(aDotB);
  }

  @Test
  public void testMatchesCallWithNoCache() {
    assertTrue(UnixGlob.matches("*a*b", "CaCb", null));
  }

  @Test
  public void testHiddenFiles() throws Exception {
    for (String dir : ImmutableList.of(".hidden", "..also.hidden", "not.hidden")) {
      FileSystemUtils.createDirectoryAndParents(pkgPath.getRelative(dir));
    }
    // Note that these are not in the result: ".", ".."
    assertGlobMatches(
        "*", "a1", "a2", "not.hidden", "foo", "fool", "food", "BUILD", ".hidden", "..also.hidden");
    assertGlobMatches("*.hidden", "not.hidden");
  }

  @Test
  public void testDoubleStar() throws Exception {
    assertGlobMatches(
        "**",
        "",
        "BUILD",
        "a1",
        "a1/b1",
        "a1/b1/c",
        "a2",
        "foo",
        "foo/bar",
        "foo/bar/wiz",
        "foo/bar/wiz/file",
        "foo/barnacle",
        "foo/barnacle/wiz",
        "food",
        "food/barnacle",
        "food/barnacle/wiz",
        "fool",
        "fool/barnacle",
        "fool/barnacle/wiz");
  }

  @Test
  public void testDoubleStarExcludeDirs() throws Exception {
    assertGlobWithoutDirsMatches("**", "BUILD", "foo/bar/wiz/file");
  }

  @Test
  public void testDoubleDoubleStar() throws Exception {
    assertGlobMatches(
        "**/**",
        "",
        "BUILD",
        "a1",
        "a1/b1",
        "a1/b1/c",
        "a2",
        "foo",
        "foo/bar",
        "foo/bar/wiz",
        "foo/bar/wiz/file",
        "foo/barnacle",
        "foo/barnacle/wiz",
        "food",
        "food/barnacle",
        "food/barnacle/wiz",
        "fool",
        "fool/barnacle",
        "fool/barnacle/wiz");
  }

  @Test
  public void testDirectoryWithDoubleStar() throws Exception {
    assertGlobMatches(
        "foo/**",
        "foo",
        "foo/bar",
        "foo/bar/wiz",
        "foo/bar/wiz/file",
        "foo/barnacle",
        "foo/barnacle/wiz");
  }

  @Test
  public void testDoubleStarPatternWithNamedChild() throws Exception {
    assertGlobMatches("**/bar", "foo/bar");
  }

  @Test
  public void testDoubleStarPatternWithChildGlob() throws Exception {
    assertGlobMatches("**/ba*", "foo/bar", "foo/barnacle", "food/barnacle", "fool/barnacle");
  }

  @Test
  public void testDoubleStarAsChildGlob() throws Exception {
    FileSystemUtils.createEmptyFile(pkgPath.getRelative("foo/barnacle/wiz/wiz"));
    FileSystemUtils.createDirectoryAndParents(pkgPath.getRelative("foo/barnacle/baz/wiz"));

    assertGlobMatches(
        "foo/**/wiz",
        "foo/bar/wiz",
        "foo/barnacle/baz/wiz",
        "foo/barnacle/wiz",
        "foo/barnacle/wiz/wiz");
  }

  @Test
  public void testDoubleStarUnderNonexistentDirectory() throws Exception {
    assertGlobMatches("not-there/**" /* => nothing */);
  }

  @Test
  public void testDoubleStarUnderFile() throws Exception {
    assertGlobMatches("foo/bar/wiz/file/**" /* => nothing */);
  }

  /** Regression test for b/13319874: Directory listing crash. */
  @Test
  public void testResilienceToFilesystemInconsistencies_DirectoryExistence() throws Exception {
    long nodeId = pkgPath.getRelative("BUILD").stat().getNodeId();
    // Our custom filesystem says "pkgPath/BUILD" exists but "pkgPath" does not exist.
    fs.stubStat(pkgPath, null);
    RootedPath pkgRootedPath = RootedPath.toRootedPath(root, pkgPath);
    FileStateValue pkgDirFileStateValue = FileStateValue.create(pkgRootedPath, tsgm);
    FileValue pkgDirValue =
        FileValue.value(pkgRootedPath, pkgDirFileStateValue, pkgRootedPath, pkgDirFileStateValue);
    differencer.inject(ImmutableMap.of(FileValue.key(pkgRootedPath), pkgDirValue));
    String expectedMessage =
        "Some filesystem operations implied /root/workspace/pkg/BUILD was a "
            + "regular file with size of 0 and mtime of 0 and nodeId of "
            + nodeId
            + " and mtime of 0 "
            + "but others made us think it was a nonexistent path";
    SkyKey skyKey = GlobValue.key(PKG_PATH_ID, "*/foo", false, PathFragment.EMPTY_FRAGMENT);
    EvaluationResult<GlobValue> result =
        driver.evaluate(
            ImmutableList.of(skyKey),
            false,
            SkyframeExecutor.DEFAULT_THREAD_COUNT,
            NullEventHandler.INSTANCE);
    assertTrue(result.hasError());
    ErrorInfo errorInfo = result.getError(skyKey);
    assertThat(errorInfo.getException()).isInstanceOf(InconsistentFilesystemException.class);
    assertThat(errorInfo.getException().getMessage()).contains(expectedMessage);
  }

  @Test
  public void testResilienceToFilesystemInconsistencies_SubdirectoryExistence() throws Exception {
    // Our custom filesystem says directory "pkgPath/foo/bar" contains a subdirectory "wiz" but a
    // direct stat on "pkgPath/foo/bar/wiz" says it does not exist.
    Path fooBarDir = pkgPath.getRelative("foo/bar");
    fs.stubStat(fooBarDir.getRelative("wiz"), null);
    RootedPath fooBarDirRootedPath = RootedPath.toRootedPath(root, fooBarDir);
    SkyValue fooBarDirListingValue =
        DirectoryListingStateValue.createForTesting(
            ImmutableList.of(new Dirent("wiz", Dirent.Type.DIRECTORY)));
    differencer.inject(
        ImmutableMap.of(
            DirectoryListingStateValue.key(fooBarDirRootedPath), fooBarDirListingValue));
    String expectedMessage = "/root/workspace/pkg/foo/bar/wiz is no longer an existing directory.";
    SkyKey skyKey = GlobValue.key(PKG_PATH_ID, "**/wiz", false, PathFragment.EMPTY_FRAGMENT);
    EvaluationResult<GlobValue> result =
        driver.evaluate(
            ImmutableList.of(skyKey),
            false,
            SkyframeExecutor.DEFAULT_THREAD_COUNT,
            NullEventHandler.INSTANCE);
    assertTrue(result.hasError());
    ErrorInfo errorInfo = result.getError(skyKey);
    assertThat(errorInfo.getException()).isInstanceOf(InconsistentFilesystemException.class);
    assertThat(errorInfo.getException().getMessage()).contains(expectedMessage);
  }

  @Test
  public void testResilienceToFilesystemInconsistencies_SymlinkType() throws Exception {
    RootedPath wizRootedPath = RootedPath.toRootedPath(root, pkgPath.getRelative("foo/bar/wiz"));
    RootedPath fileRootedPath =
        RootedPath.toRootedPath(root, pkgPath.getRelative("foo/bar/wiz/file"));
    final FileStatus realStat = fileRootedPath.asPath().stat();
    fs.stubStat(
        fileRootedPath.asPath(),
        new FileStatus() {

          @Override
          public boolean isFile() {
            // The stat says foo/bar/wiz/file is a real file, not a symlink.
            return true;
          }

          @Override
          public boolean isSpecialFile() {
            return false;
          }

          @Override
          public boolean isDirectory() {
            return false;
          }

          @Override
          public boolean isSymbolicLink() {
            return false;
          }

          @Override
          public long getSize() throws IOException {
            return realStat.getSize();
          }

          @Override
          public long getLastModifiedTime() throws IOException {
            return realStat.getLastModifiedTime();
          }

          @Override
          public long getLastChangeTime() throws IOException {
            return realStat.getLastChangeTime();
          }

          @Override
          public long getNodeId() throws IOException {
            return realStat.getNodeId();
          }
        });
    // But the dir listing say foo/bar/wiz/file is a symlink.
    SkyValue wizDirListingValue =
        DirectoryListingStateValue.createForTesting(
            ImmutableList.of(new Dirent("file", Dirent.Type.SYMLINK)));
    differencer.inject(
        ImmutableMap.of(DirectoryListingStateValue.key(wizRootedPath), wizDirListingValue));
    String expectedMessage =
        "readdir and stat disagree about whether " + fileRootedPath.asPath() + " is a symlink";
    SkyKey skyKey = GlobValue.key(PKG_PATH_ID, "foo/bar/wiz/*", false, PathFragment.EMPTY_FRAGMENT);
    EvaluationResult<GlobValue> result =
        driver.evaluate(
            ImmutableList.of(skyKey),
            false,
            SkyframeExecutor.DEFAULT_THREAD_COUNT,
            NullEventHandler.INSTANCE);
    assertTrue(result.hasError());
    ErrorInfo errorInfo = result.getError(skyKey);
    assertThat(errorInfo.getException()).isInstanceOf(InconsistentFilesystemException.class);
    assertThat(errorInfo.getException().getMessage()).contains(expectedMessage);
  }

  private class CustomInMemoryFs extends InMemoryFileSystem {

    private Map<Path, FileStatus> stubbedStats = Maps.newHashMap();

    public CustomInMemoryFs(ManualClock manualClock) {
      super(manualClock);
    }

    public void stubStat(Path path, @Nullable FileStatus stubbedResult) {
      stubbedStats.put(path, stubbedResult);
    }

    @Override
    public FileStatus stat(Path path, boolean followSymlinks) throws IOException {
      if (stubbedStats.containsKey(path)) {
        return stubbedStats.get(path);
      }
      return super.stat(path, followSymlinks);
    }
  }
}
