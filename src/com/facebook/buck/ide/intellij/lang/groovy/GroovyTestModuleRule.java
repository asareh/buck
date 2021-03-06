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
package com.facebook.buck.ide.intellij.lang.groovy;

import com.facebook.buck.ide.intellij.BaseIjModuleRule;
import com.facebook.buck.ide.intellij.ModuleBuildContext;
import com.facebook.buck.ide.intellij.model.IjModuleFactoryResolver;
import com.facebook.buck.ide.intellij.model.IjModuleType;
import com.facebook.buck.ide.intellij.model.IjProjectConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.groovy.GroovyTestDescription;
import com.facebook.buck.jvm.groovy.GroovyTestDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetNode;

public class GroovyTestModuleRule extends BaseIjModuleRule<GroovyTestDescriptionArg> {

  public GroovyTestModuleRule(
      ProjectFilesystem projectFilesystem,
      IjModuleFactoryResolver moduleFactoryResolver,
      IjProjectConfig projectConfig) {
    super(projectFilesystem, moduleFactoryResolver, projectConfig);
  }

  @Override
  public Class<? extends Description<?>> getDescriptionClass() {
    return GroovyTestDescription.class;
  }

  @Override
  public void apply(TargetNode<GroovyTestDescriptionArg, ?> target, ModuleBuildContext context) {
    addDepsAndTestSources(target, false /* wantsPackagePrefix */, context);
  }

  @Override
  public IjModuleType detectModuleType(TargetNode<GroovyTestDescriptionArg, ?> targetNode) {
    return IjModuleType.UNKNOWN_MODULE;
  }
}
