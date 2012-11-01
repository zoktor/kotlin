package org.jetbrains.k2js.translate.context;

import gnu.trove.THashMap;
import gnu.trove.TIntArrayList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.descriptors.*;
import org.jetbrains.jet.lang.resolve.scopes.JetScope;

import java.util.*;

final class OverloadedMemberNameGenerator {
    private final Map<FunctionDescriptor, String> nameMap = new THashMap<FunctionDescriptor, String>();

    @NotNull
    public String forExtensionProperty(PropertyAccessorDescriptor accessor) {
        String resolvedName = nameMap.get(accessor);
        if (resolvedName != null) {
            return resolvedName;
        }

        String name = Namer.getNameForAccessor(accessor.getCorrespondingProperty().getName().getName(),
                                                       accessor instanceof PropertyGetterDescriptor);

        JetScope memberScope = getMemberScope(accessor.getCorrespondingProperty());
        if (memberScope != null) {
            return resolveExtensionPropertyName(accessor, name, memberScope);
        }
        return name;
    }

    // see testOverloadedFun
    private static int sortFunctions(FunctionDescriptor[] sorted, Collection<FunctionDescriptor> unsorted) {
        int index = 0;
        for (FunctionDescriptor function : unsorted) {
            if (function.getKind().isReal()) {
                sorted[index++] = function;
            }
        }
        Arrays.sort(sorted, new Comparator<FunctionDescriptor>() {
            @Override
            public int compare(FunctionDescriptor a, FunctionDescriptor b) {
                if (a == null) {
                    return b == null ? 0 : 1;
                }
                else if (b == null) {
                    return -1;
                }

                Integer result = Visibilities.compare(b.getVisibility(), a.getVisibility());
                if (result == null) {
                    return 0;
                }
                else if (result == 0) {
                    // open fun > not open fun
                    int aWeight = a.getModality().isOverridable() ? 1 : 0;
                    int bWeight = b.getModality().isOverridable() ? 1 : 0;
                    return bWeight - aWeight;
                }
                return result;
            }
        });
        return index;
    }

    @Nullable
    public String forClassOrNamespaceFunction(FunctionDescriptor function) {
        String resolvedName = nameMap.get(function);
        if (resolvedName != null) {
            return resolvedName;
        }

        JetScope memberScope = getMemberScope(function);
        if (memberScope == null) {
            return null;
        }

        Collection<FunctionDescriptor> functions = memberScope.getFunctions(function.getName());
        String name = function.getName().getName();
        if (functions.size() <= 1) {
            return name;
        }

        FunctionDescriptor[] sorted = new FunctionDescriptor[functions.size()];
        int realSize = sortFunctions(sorted, functions);
        int counter = -1;
        TIntArrayList occupiedIndices = null;
        for (int i = 0; i < realSize; i++) {
            FunctionDescriptor currentFunction = sorted[i];
            Set<? extends FunctionDescriptor> overriddenFunctions = currentFunction.getOverriddenDescriptors();
            String currentName;
            if (overriddenFunctions.isEmpty()) {
                if (occupiedIndices != null) {
                    while (occupiedIndices.contains(counter)) {
                        counter++;
                    }
                }
                currentName = counter == -1 ? name : name + '$' + counter;
                counter++;
            }
            else {
                FunctionDescriptor overriddenFunction = overriddenFunctions.iterator().next();
                String overriddenFunctionName = forClassOrNamespaceFunction(overriddenFunction);
                assert overriddenFunctionName != null;
                if (occupiedIndices == null) {
                    occupiedIndices = new TIntArrayList();
                }
                int index = overriddenFunctionName.lastIndexOf('$');
                if (index != -1) {
                    index = Integer.parseInt(overriddenFunctionName.substring(index + 1));
                }
                occupiedIndices.add(index);
                currentName = overriddenFunctionName;
            }

            if (currentFunction == function) {
                name = currentName;
            }
            nameMap.put(currentFunction, currentName);
        }
        return name;
    }

    private String resolveExtensionPropertyName(PropertyAccessorDescriptor accessor, String name, JetScope memberScope) {
        Collection<VariableDescriptor> properties = memberScope.getProperties(accessor.getCorrespondingProperty().getName());
        if (properties.size() <= 1) {
            return name;
        }

        int counter = -1;
        boolean isGetter = accessor instanceof PropertyGetterDescriptor;
        for (VariableDescriptor variable : properties) {
            if (!(variable instanceof PropertyDescriptor) || !variable.getReceiverParameter().exists()) {
                continue;
            }

            PropertyDescriptor property = (PropertyDescriptor) variable;
            PropertyAccessorDescriptor currentAccessor = isGetter ? property.getGetter() : property.getSetter();
            if (currentAccessor == null) {
                continue;
            }

            String currentName = counter == -1 ? name : name + '$' + counter;
            if (currentAccessor == accessor) {
                name = currentName;
            }
            nameMap.put(currentAccessor, currentName);
            counter++;
        }
        return name;
    }

    @Nullable
    private static JetScope getMemberScope(DeclarationDescriptor descriptor) {
        DeclarationDescriptor containing = descriptor.getContainingDeclaration();
        if (containing instanceof ClassDescriptor) {
            return ((ClassDescriptor) containing).getDefaultType().getMemberScope();
        }
        else if (containing instanceof NamespaceDescriptor) {
            return ((NamespaceDescriptor) containing).getMemberScope();
        }
        else {
            return null;
        }
    }
}