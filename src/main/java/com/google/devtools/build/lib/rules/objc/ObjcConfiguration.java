// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.objc;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.CompilationMode;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.rules.apple.Platform;
import com.google.devtools.build.lib.rules.objc.ReleaseBundlingSupport.SplitArchTransition.ConfigurationDistinguisher;
import com.google.devtools.build.lib.vfs.Path;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * A compiler configuration containing flags required for Objective-C compilation.
 */
public class ObjcConfiguration extends BuildConfiguration.Fragment {
  @VisibleForTesting
  static final ImmutableList<String> DBG_COPTS = ImmutableList.of("-O0", "-DDEBUG=1",
      "-fstack-protector", "-fstack-protector-all", "-D_GLIBCXX_DEBUG_PEDANTIC", "-D_GLIBCXX_DEBUG",
      "-D_GLIBCPP_CONCEPT_CHECKS");

  @VisibleForTesting
  static final ImmutableList<String> OPT_COPTS =
      ImmutableList.of(
          "-Os", "-DNDEBUG=1", "-Wno-unused-variable", "-Winit-self", "-Wno-extra");

  private static final String XCODE_VERSION_ENV_NAME = "XCODE_VERSION_OVERRIDE";
  /**
   * Environment variable name for the apple SDK version. If unset, uses the system default of the
   * host for the platform in the value of {@link #APPLE_SDK_PLATFORM_ENV_NAME}.
   **/
  public static final String APPLE_SDK_VERSION_ENV_NAME = "APPLE_SDK_VERSION_OVERRIDE";
  /**
   * Environment variable name for the apple SDK platform. This should be set for all actions that
   * require an apple SDK. The valid values consist of {@link Platform} names.
   **/
  public static final String APPLE_SDK_PLATFORM_ENV_NAME = "APPLE_SDK_PLATFORM";

  private final String iosSdkVersion;
  private final String iosMinimumOs;
  private final String iosSimulatorVersion;
  private final String iosSimulatorDevice;
  private final String iosCpu;
  private final Optional<String> xcodeVersionOverride;
  private final boolean generateDebugSymbols;
  private final boolean runMemleaks;
  private final List<String> copts;
  private final CompilationMode compilationMode;
  private final List<String> iosMultiCpus;
  private final String iosSplitCpu;
  private final boolean perProtoIncludes;
  private final List<String> fastbuildOptions;
  private final boolean enableBinaryStripping;
  private final boolean moduleMapsEnabled;
  private final ConfigurationDistinguisher configurationDistinguisher;
  @Nullable private final String signingCertName;
  @Nullable private final Path clientWorkspaceRoot;
  private final String xcodeOverrideWorkspaceRoot;

  // We only load these labels if the mode which uses them is enabled. That is known as part of the
  // BuildConfiguration. This label needs to be part of a configuration because only configurations
  // can conditionally cause loading.
  // They are referenced from late bound attributes, and if loading wasn't forced in a
  // configuration, the late bound attribute will fail to be initialized because it hasn't been
  // loaded.
  @Nullable private final Label gcovLabel;
  @Nullable private final Label experimentalGcovLabel;
  @Nullable private final Label dumpSymsLabel;
  @Nullable private final Label defaultProvisioningProfileLabel;

  ObjcConfiguration(ObjcCommandLineOptions objcOptions, BuildConfiguration.Options options,
      @Nullable BlazeDirectories directories) {
    this.iosSdkVersion = Preconditions.checkNotNull(objcOptions.iosSdkVersion, "iosSdkVersion");
    String xcodeVersionFlag = Preconditions.checkNotNull(objcOptions.xcodeVersion, "xcodeVersion");
    this.xcodeVersionOverride =
        xcodeVersionFlag.isEmpty() ? Optional.<String>absent() : Optional.of(xcodeVersionFlag);
    this.iosMinimumOs = Preconditions.checkNotNull(objcOptions.iosMinimumOs, "iosMinimumOs");
    this.iosSimulatorDevice =
        Preconditions.checkNotNull(objcOptions.iosSimulatorDevice, "iosSimulatorDevice");
    this.iosSimulatorVersion =
        Preconditions.checkNotNull(objcOptions.iosSimulatorVersion, "iosSimulatorVersion");
    this.iosCpu = Preconditions.checkNotNull(objcOptions.iosCpu, "iosCpu");
    this.generateDebugSymbols = objcOptions.generateDebugSymbols;
    this.runMemleaks = objcOptions.runMemleaks;
    this.copts = ImmutableList.copyOf(objcOptions.copts);
    this.compilationMode = Preconditions.checkNotNull(options.compilationMode, "compilationMode");
    this.gcovLabel = options.objcGcovBinary;
    this.experimentalGcovLabel = options.experimentalObjcGcovBinary;
    this.dumpSymsLabel = objcOptions.dumpSyms;
    this.defaultProvisioningProfileLabel = objcOptions.defaultProvisioningProfile;
    this.iosMultiCpus = Preconditions.checkNotNull(objcOptions.iosMultiCpus, "iosMultiCpus");
    this.iosSplitCpu = Preconditions.checkNotNull(objcOptions.iosSplitCpu, "iosSplitCpu");
    this.perProtoIncludes = objcOptions.perProtoIncludes;
    this.fastbuildOptions = ImmutableList.copyOf(objcOptions.fastbuildOptions);
    this.enableBinaryStripping = objcOptions.enableBinaryStripping;
    this.moduleMapsEnabled = objcOptions.enableModuleMaps;
    this.configurationDistinguisher = objcOptions.configurationDistinguisher;
    this.clientWorkspaceRoot = directories != null ? directories.getWorkspace() : null;
    this.signingCertName = objcOptions.iosSigningCertName;
    this.xcodeOverrideWorkspaceRoot = objcOptions.xcodeOverrideWorkspaceRoot;
  }

  public Map<String, String> getEnvironmentForDarwin() {
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    if (xcodeVersionOverride.isPresent()) {
      builder.put(XCODE_VERSION_ENV_NAME, xcodeVersionOverride.get());
    }
    builder.put(APPLE_SDK_VERSION_ENV_NAME, iosSdkVersion);
    builder.put(APPLE_SDK_PLATFORM_ENV_NAME, IosSdkCommands.getPlatformPlistName(this));
    return builder.build();
  }

  public String getIosSdkVersion() {
    return iosSdkVersion;
  }
  
  public Optional<String> getXcodeVersionOverride() {
    return xcodeVersionOverride;
  }

  /**
   * Returns the minimum iOS version supported by binaries and libraries. Any dependencies on newer
   * iOS version features or libraries will become weak dependencies which are only loaded if the
   * runtime OS supports them.
   */
  public String getMinimumOs() {
    return iosMinimumOs;
  }

  /**
   * Returns the type of device (e.g. 'iPhone 6') to simulate when running on the simulator.
   */
  public String getIosSimulatorDevice() {
    return iosSimulatorDevice;
  }

  public String getIosSimulatorVersion() {
    return iosSimulatorVersion;
  }

  public String getIosCpu() {
    return iosCpu;
  }

  /**
   * Returns the platform of the configuration for the current bundle, based on configured
   * architectures (for example, {@code i386} maps to {@link Platform#IOS_SIMULATOR}).
   *
   * <p>If {@link #getIosMultiCpus()} is set, returns {@link Platform#IOS_DEVICE} if any of the
   * architectures matches it, otherwise returns the mapping for {@link #getIosCpu()}.
   *
   * <p>Note that this method should not be used to determine the platform for code compilation.
   * Derive the platform from {@link #getIosCpu()} instead.
   */
  // TODO(bazel-team): This method should be enabled to return multiple values once all call sites
  // (in particular actool, bundlemerge, momc) have been upgraded to support multiple values.
  public Platform getBundlingPlatform() {
    for (String architecture : getIosMultiCpus()) {
      if (Platform.forArch(architecture) == Platform.IOS_DEVICE) {
        return Platform.IOS_DEVICE;
      }
    }
    return Platform.forArch(getIosCpu());
  }

  public boolean generateDebugSymbols() {
    return generateDebugSymbols;
  }

  public boolean runMemleaks() {
    return runMemleaks;
  }

  /**
   * Returns the current compilation mode.
   */
  public CompilationMode getCompilationMode() {
    return compilationMode;
  }

  /**
   * Returns the default set of clang options for the current compilation mode.
   */
  public List<String> getCoptsForCompilationMode() {
    switch (compilationMode) {
      case DBG:
        return DBG_COPTS;
      case FASTBUILD:
        return fastbuildOptions;
      case OPT:
        return OPT_COPTS;
      default:
        throw new AssertionError();
    }
  }

  /**
   * Returns options passed to (Apple) clang when compiling Objective C. These options should be
   * applied after any default options but before options specified in the attributes of the rule.
   */
  public List<String> getCopts() {
    return copts;
  }

  /**
   * Returns the label of the gcov binary, used to get test coverage data. Null iff not in coverage
   * mode.
   */
  @Nullable public Label getGcovLabel() {
    return gcovLabel;
  }

  /**
   * Returns the label of the experimental gcov binary, used to get test coverage data for {@code
   * experimental_ios_test}. Null iff not in coverage mode.
   */
  @Nullable public Label getExperimentalGcovLabel() {
    return experimentalGcovLabel;
  }

  /**
   * Returns the label of the dump_syms binary, used to get debug symbols from a binary. Null iff
   * !{@link #generateDebugSymbols}.
   */
  @Nullable public Label getDumpSymsLabel() {
    return dumpSymsLabel;
  }

  /**
   * Returns the label of the default provisioning profile to use when bundling/signing the
   * application. Null iff iOS CPU indicates a simulator is being targeted.
   */
  @Nullable public Label getDefaultProvisioningProfileLabel() {
    return defaultProvisioningProfileLabel;
  }

  /**
   * List of all CPUs that this invocation is being built for. Different from {@link #getIosCpu()}
   * which is the specific CPU <b>this target</b> is being built for.
   */
  public List<String> getIosMultiCpus() {
    return iosMultiCpus;
  }

  /**
   * Returns the architecture for which we keep dependencies that should be present only once (in a
   * single architecture).
   *
   * <p>When building with multiple architectures there are some dependencies we want to avoid
   * duplicating: they would show up more than once in the same location in the final application
   * bundle which is illegal. Instead we pick one architecture for which to keep all dependencies
   * and discard any others.
   */
  public String getDependencySingleArchitecture() {
    if (!getIosMultiCpus().isEmpty()) {
      return getIosMultiCpus().get(0);
    }
    return getIosCpu();
  }

  /**
   * Whether module map generation and interpretation is enabled.
   */
  public boolean moduleMapsEnabled() {
    return moduleMapsEnabled;
  }

  /**
   * Returns the unique identifier distinguishing configurations that are otherwise the same.
   *
   * <p>Use this value for situations in which two configurations create two outputs that are the
   * same but are not collapsed due to their different configuration owners.
   */
  public ConfigurationDistinguisher getConfigurationDistinguisher() {
    return configurationDistinguisher;
  }

  @Nullable
  @Override
  public String getOutputDirectoryName() {
    List<String> components = new ArrayList<>();
    if (!iosSplitCpu.isEmpty()) {
      components.add("ios-" + iosSplitCpu);
    }
    if (configurationDistinguisher != ConfigurationDistinguisher.UNKNOWN) {
      components.add(configurationDistinguisher.toString().toLowerCase(Locale.US));
    }

    if (components.isEmpty()) {
      return null;
    }
    return Joiner.on('-').join(components);
  }

  /**
   * @return whether to add include path entries for every proto file's containing directory.
   */
  public boolean perProtoIncludes() {
    return this.perProtoIncludes;
  }

  /**
   * Returns whether to perform symbol and dead-code strippings on linked binaries. The strippings
   * are performed iff --compilation_mode=opt and --objc_enable_binary_stripping are specified.
   */
  public boolean shouldStripBinary() {
    return this.enableBinaryStripping && getCompilationMode() == CompilationMode.OPT;
  }

  /**
   * Returns the path to be used for workspace_root (and path of pbxGroup mainGroup) in xcodeproj.
   * This usually will be the absolute path of the root of Bazel client workspace or null if
   * passed-in {@link BlazeDirectories} is null or Bazel fails to find the workspace root directory.
   * It can also be overridden by the {@code --xcode_override_workspace_root} flag, in which case
   * the path can be absolute or relative.
   */
  @Nullable
  public String getXcodeWorkspaceRoot() {
    if (!this.xcodeOverrideWorkspaceRoot.isEmpty()) {
      return this.xcodeOverrideWorkspaceRoot;
    }
    if (this.clientWorkspaceRoot == null) {
      return null;
    }
    return this.clientWorkspaceRoot.getPathString();
  }

  /**
   * Returns the flag-supplied certificate name to be used in signing or {@code null} if no such
   * certificate was specified.
   */
  @Nullable
  public String getSigningCertName() {
    return this.signingCertName;
  }
}
