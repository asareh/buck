/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.go;

import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasContacts;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.HasSrcs;
import com.facebook.buck.rules.HasTestTimeout;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.Version;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.immutables.value.Value;

public class GoTestDescription
    implements Description<GoTestDescriptionArg>,
        Flavored,
        MetadataProvidingDescription<GoTestDescriptionArg>,
        ImplicitDepsInferringDescription<GoTestDescription.AbstractGoTestDescriptionArg> {

  private static final Flavor TEST_LIBRARY_FLAVOR = InternalFlavor.of("test-library");

  private final GoBuckConfig goBuckConfig;
  private final Optional<Long> defaultTestRuleTimeoutMs;

  public GoTestDescription(GoBuckConfig goBuckConfig, Optional<Long> defaultTestRuleTimeoutMs) {
    this.goBuckConfig = goBuckConfig;
    this.defaultTestRuleTimeoutMs = defaultTestRuleTimeoutMs;
  }

  @Override
  public Class<GoTestDescriptionArg> getConstructorArgType() {
    return GoTestDescriptionArg.class;
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return goBuckConfig.getPlatformFlavorDomain().containsAnyOf(flavors)
        || flavors.contains(TEST_LIBRARY_FLAVOR);
  }

  @Override
  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      final BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      GoTestDescriptionArg args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Class<U> metadataClass) {
    Optional<GoPlatform> platform = goBuckConfig.getPlatformFlavorDomain().getValue(buildTarget);

    if (metadataClass.isAssignableFrom(GoLinkable.class)
        && buildTarget.getFlavors().contains(TEST_LIBRARY_FLAVOR)) {
      Preconditions.checkState(platform.isPresent());

      Path packageName = getGoPackageName(resolver, buildTarget, args);

      SourcePath output = resolver.requireRule(buildTarget).getSourcePathToOutput();
      return Optional.of(
          metadataClass.cast(
              GoLinkable.builder().setGoLinkInput(ImmutableMap.of(packageName, output)).build()));
    } else if (buildTarget.getFlavors().contains(GoDescriptors.TRANSITIVE_LINKABLES_FLAVOR)
        && buildTarget.getFlavors().contains(TEST_LIBRARY_FLAVOR)) {
      Preconditions.checkState(platform.isPresent());

      ImmutableSet<BuildTarget> deps;
      if (args.getLibrary().isPresent()) {
        GoLibraryDescriptionArg libraryArg =
            resolver.requireMetadata(args.getLibrary().get(), GoLibraryDescriptionArg.class).get();
        deps =
            ImmutableSortedSet.<BuildTarget>naturalOrder()
                .addAll(args.getDeps())
                .addAll(libraryArg.getDeps())
                .build();
      } else {
        deps = args.getDeps();
      }

      return Optional.of(
          metadataClass.cast(
              GoDescriptors.requireTransitiveGoLinkables(
                  buildTarget, resolver, platform.get(), deps, /* includeSelf */ true)));
    } else {
      return Optional.empty();
    }
  }

  private GoTestMain requireTestMainGenRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      ImmutableSet<SourcePath> srcs,
      Path packageName) {
    Tool testMainGenerator =
        GoDescriptors.getTestMainGenerator(
            goBuckConfig, buildTarget, projectFilesystem, params, resolver);

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    BuildTarget buildTargetWithFlavor =
        buildTarget.withAppendedFlavors(InternalFlavor.of("test-main-src"));
    GoTestMain generatedTestMain =
        new GoTestMain(
            buildTargetWithFlavor,
            projectFilesystem,
            params
                .withDeclaredDeps(ImmutableSortedSet.copyOf(testMainGenerator.getDeps(ruleFinder)))
                .withoutExtraDeps(),
            testMainGenerator,
            srcs,
            packageName);
    resolver.addToIndex(generatedTestMain);
    return generatedTestMain;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      final BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      GoTestDescriptionArg args) {
    GoPlatform platform =
        goBuckConfig
            .getPlatformFlavorDomain()
            .getValue(buildTarget)
            .orElse(goBuckConfig.getDefaultPlatform());

    if (buildTarget.getFlavors().contains(TEST_LIBRARY_FLAVOR)) {
      return createTestLibrary(buildTarget, projectFilesystem, params, resolver, args, platform);
    }

    GoBinary testMain =
        createTestMainRule(buildTarget, projectFilesystem, params, resolver, args, platform);
    resolver.addToIndex(testMain);

    return new GoTest(
        buildTarget,
        projectFilesystem,
        params.withDeclaredDeps(ImmutableSortedSet.of(testMain)).withoutExtraDeps(),
        testMain,
        args.getLabels(),
        args.getContacts(),
        args.getTestRuleTimeoutMs().map(Optional::of).orElse(defaultTestRuleTimeoutMs),
        args.getRunTestSeparately(),
        args.getResources());
  }

  private GoBinary createTestMainRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      final BuildRuleResolver resolver,
      GoTestDescriptionArg args,
      GoPlatform platform) {
    Path packageName = getGoPackageName(resolver, buildTarget, args);

    BuildRule testLibrary =
        new NoopBuildRuleWithDeclaredAndExtraDeps(
            buildTarget.withAppendedFlavors(TEST_LIBRARY_FLAVOR), projectFilesystem, params);
    resolver.addToIndex(testLibrary);

    BuildRule generatedTestMain =
        requireTestMainGenRule(
            buildTarget, projectFilesystem, params, resolver, args.getSrcs(), packageName);
    BuildTarget testMainBuildTarget =
        buildTarget.withAppendedFlavors(InternalFlavor.of("test-main"));
    GoBinary testMain =
        GoDescriptors.createGoBinaryRule(
            testMainBuildTarget,
            projectFilesystem,
            params
                .withDeclaredDeps(ImmutableSortedSet.of(testLibrary))
                .withExtraDeps(ImmutableSortedSet.of(generatedTestMain)),
            resolver,
            goBuckConfig,
            ImmutableSet.of(generatedTestMain.getSourcePathToOutput()),
            args.getCompilerFlags(),
            args.getAssemblerFlags(),
            args.getLinkerFlags(),
            platform);
    resolver.addToIndex(testMain);
    return testMain;
  }

  private Path getGoPackageName(
      BuildRuleResolver resolver, BuildTarget target, GoTestDescriptionArg args) {
    target = target.withFlavors(); // remove flavors.

    if (args.getLibrary().isPresent()) {
      final Optional<GoLibraryDescriptionArg> libraryArg =
          resolver.requireMetadata(args.getLibrary().get(), GoLibraryDescriptionArg.class);
      if (!libraryArg.isPresent()) {
        throw new HumanReadableException(
            "Library specified in %s (%s) is not a go_library rule.",
            target, args.getLibrary().get());
      }

      if (args.getPackageName().isPresent()) {
        throw new HumanReadableException(
            "Test target %s specifies both library and package_name - only one should be specified",
            target);
      }

      if (!libraryArg.get().getTests().contains(target)) {
        throw new HumanReadableException(
            "go internal test target %s is not listed in `tests` of library %s",
            target, args.getLibrary().get());
      }

      return libraryArg
          .get()
          .getPackageName()
          .map(Paths::get)
          .orElse(goBuckConfig.getDefaultPackageName(args.getLibrary().get()));
    } else if (args.getPackageName().isPresent()) {
      return Paths.get(args.getPackageName().get());
    } else {
      Path packageName = goBuckConfig.getDefaultPackageName(target);
      return packageName.resolveSibling(packageName.getFileName() + "_test");
    }
  }

  private GoCompile createTestLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      final BuildRuleResolver resolver,
      GoTestDescriptionArg args,
      GoPlatform platform) {
    Path packageName = getGoPackageName(resolver, buildTarget, args);
    GoCompile testLibrary;
    if (args.getLibrary().isPresent()) {
      // We should have already type-checked the arguments in the base rule.
      final GoLibraryDescriptionArg libraryArg =
          resolver.requireMetadata(args.getLibrary().get(), GoLibraryDescriptionArg.class).get();

      final BuildRuleParams originalParams = params;
      BuildRuleParams testTargetParams =
          params
              .withDeclaredDeps(
                  () ->
                      ImmutableSortedSet.<BuildRule>naturalOrder()
                          .addAll(originalParams.getDeclaredDeps().get())
                          .addAll(resolver.getAllRules(libraryArg.getDeps()))
                          .build())
              .withExtraDeps(
                  () -> {
                    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
                    return ImmutableSortedSet.<BuildRule>naturalOrder()
                        .addAll(originalParams.getExtraDeps().get())
                        // Make sure to include dynamically generated sources as deps.
                        .addAll(ruleFinder.filterBuildRuleInputs(libraryArg.getSrcs()))
                        .build();
                  });

      testLibrary =
          GoDescriptors.createGoCompileRule(
              buildTarget,
              projectFilesystem,
              testTargetParams,
              resolver,
              goBuckConfig,
              packageName,
              ImmutableSet.<SourcePath>builder()
                  .addAll(libraryArg.getSrcs())
                  .addAll(args.getSrcs())
                  .build(),
              ImmutableList.<String>builder()
                  .addAll(libraryArg.getCompilerFlags())
                  .addAll(args.getCompilerFlags())
                  .build(),
              ImmutableList.<String>builder()
                  .addAll(libraryArg.getAssemblerFlags())
                  .addAll(args.getAssemblerFlags())
                  .build(),
              platform,
              FluentIterable.from(params.getDeclaredDeps().get())
                  .transform(BuildRule::getBuildTarget));
    } else {
      testLibrary =
          GoDescriptors.createGoCompileRule(
              buildTarget,
              projectFilesystem,
              params,
              resolver,
              goBuckConfig,
              packageName,
              args.getSrcs(),
              args.getCompilerFlags(),
              args.getAssemblerFlags(),
              platform,
              FluentIterable.from(params.getDeclaredDeps().get())
                  .transform(BuildRule::getBuildTarget));
    }

    return testLibrary;
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractGoTestDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    // Add the C/C++ linker parse time deps.
    GoPlatform goPlatform =
        goBuckConfig
            .getPlatformFlavorDomain()
            .getValue(buildTarget)
            .orElse(goBuckConfig.getDefaultPlatform());
    Optional<CxxPlatform> cxxPlatform = goPlatform.getCxxPlatform();
    if (cxxPlatform.isPresent()) {
      extraDepsBuilder.addAll(CxxPlatforms.getParseTimeDeps(cxxPlatform.get()));
    }
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractGoTestDescriptionArg
      extends CommonDescriptionArg, HasContacts, HasDeclaredDeps, HasSrcs, HasTestTimeout {
    Optional<BuildTarget> getLibrary();

    Optional<String> getPackageName();

    ImmutableList<String> getCompilerFlags();

    ImmutableList<String> getAssemblerFlags();

    ImmutableList<String> getLinkerFlags();

    @Value.Default
    default boolean getRunTestSeparately() {
      return false;
    }

    @Value.NaturalOrder
    ImmutableSortedSet<SourcePath> getResources();
  }
}
