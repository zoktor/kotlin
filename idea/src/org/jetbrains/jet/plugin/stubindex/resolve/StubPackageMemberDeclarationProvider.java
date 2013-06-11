/*
 * Copyright 2010-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.plugin.stubindex.resolve;

import com.google.common.collect.Iterables;
import com.intellij.openapi.project.Project;
import com.intellij.psi.NavigatablePsiElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jet.asJava.LightClassGenerationSupport;
import org.jetbrains.jet.lang.psi.*;
import org.jetbrains.jet.lang.resolve.lazy.declarations.PackageMemberDeclarationProvider;
import org.jetbrains.jet.lang.resolve.name.FqName;
import org.jetbrains.jet.lang.resolve.name.Name;
import org.jetbrains.jet.plugin.stubindex.JetAllPackagesIndex;
import org.jetbrains.jet.plugin.stubindex.JetTopLevelFunctionsFqnNameIndex;
import org.jetbrains.jet.plugin.stubindex.JetTopLevelPropertiesFqnNameIndex;
import org.jetbrains.jet.util.QualifiedNamesUtil;

import java.util.Collection;
import java.util.List;

public class StubPackageMemberDeclarationProvider extends AbstractStubDeclarationProvider implements PackageMemberDeclarationProvider {
    private final FqName fqName;
    private final Project project;
    private final GlobalSearchScope searchScope;

    public StubPackageMemberDeclarationProvider(@NotNull FqName fqName, @NotNull Project project, @NotNull GlobalSearchScope searchScope) {
        this.fqName = fqName;
        this.project = project;
        this.searchScope = searchScope;
    }

    @Override
    public boolean isPackageDeclared(@NotNull Name name) {
        return LightClassGenerationSupport.getInstance(project).packageExists(fqName, searchScope);
    }

    @NotNull
    @Override
    public Collection<JetNamedFunction> getFunctionDeclarations(@NotNull Name name) {
        return JetTopLevelFunctionsFqnNameIndex.getInstance().get(fqName.child(name).toString(), project, searchScope);
    }

    @NotNull
    @Override
    public Collection<JetProperty> getPropertyDeclarations(@NotNull Name name) {
        return JetTopLevelPropertiesFqnNameIndex.getInstance().get(fqName.child(name).toString(), project, searchScope);
    }

    @Override
    public Collection<FqName> getAllDeclaredPackages() {
        // get all packages in this project
        Collection<String> allPackagesInProject = JetAllPackagesIndex.getInstance().getAllKeys(project);
        return ContainerUtil.mapNotNull(allPackagesInProject, new Function<String, FqName>() {
            @Override
            public FqName fun(String packageFqName) {
                // filter by the search scope
                Collection<JetFile> files = JetAllPackagesIndex.getInstance().get(packageFqName, project, searchScope);
                if (files.isEmpty()) return null;
                return new FqName(packageFqName);
            }
        });
    }

    @NotNull
    @Override
    public Collection<NavigatablePsiElement> getPackageDeclarations(final FqName name) {
        final String fqNameStr = name.asString();
        final int fqNameSize = QualifiedNamesUtil.numberOfSegments(name);

        Collection<String> allPackagesInProject = JetAllPackagesIndex.getInstance().getAllKeys(project);
        return ContainerUtil.mapNotNull(allPackagesInProject, new Function<String, NavigatablePsiElement>() {
            @Override
            public NavigatablePsiElement fun(String packageFqName) {
                if (name.isRoot()) {
                    return Iterables.getFirst(JetAllPackagesIndex.getInstance().get(packageFqName, project, searchScope), null);
                }


                if (packageFqName.startsWith(fqNameStr)) {
                    Collection<JetFile> files = JetAllPackagesIndex.getInstance().get(packageFqName, project, searchScope);

                    JetFile firstFile = Iterables.getFirst(files, null);

                    if (firstFile != null) {
                        JetNamespaceHeader header = firstFile.getNamespaceHeader();
                        assert header != null : "File has some package name, so header can't be null";

                        List<JetSimpleNameExpression> names = header.getParentNamespaceNames();
                        return (names.size() >= fqNameSize) ? names.get(fqNameSize - 1) : header.getLastPartExpression();
                    }
                }

                return null;
            }
        });
    }
}
