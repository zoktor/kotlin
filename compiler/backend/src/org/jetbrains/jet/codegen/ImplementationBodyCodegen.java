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

package org.jetbrains.jet.codegen;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.asm4.AnnotationVisitor;
import org.jetbrains.asm4.Label;
import org.jetbrains.asm4.MethodVisitor;
import org.jetbrains.asm4.Type;
import org.jetbrains.asm4.commons.InstructionAdapter;
import org.jetbrains.asm4.commons.Method;
import org.jetbrains.jet.codegen.binding.CalculatedClosure;
import org.jetbrains.jet.codegen.binding.CodegenBinding;
import org.jetbrains.jet.codegen.binding.MutableClosure;
import org.jetbrains.jet.codegen.context.ClassContext;
import org.jetbrains.jet.codegen.context.ConstructorContext;
import org.jetbrains.jet.codegen.context.MethodContext;
import org.jetbrains.jet.codegen.signature.*;
import org.jetbrains.jet.codegen.signature.kotlin.JetMethodAnnotationWriter;
import org.jetbrains.jet.codegen.state.GenerationState;
import org.jetbrains.jet.codegen.state.JetTypeMapper;
import org.jetbrains.jet.codegen.state.JetTypeMapperMode;
import org.jetbrains.jet.descriptors.serialization.*;
import org.jetbrains.jet.lang.descriptors.*;
import org.jetbrains.jet.lang.descriptors.impl.MutableClassDescriptor;
import org.jetbrains.jet.lang.psi.*;
import org.jetbrains.jet.lang.resolve.BindingContext;
import org.jetbrains.jet.lang.resolve.BindingContextUtils;
import org.jetbrains.jet.lang.resolve.DescriptorUtils;
import org.jetbrains.jet.lang.resolve.OverridingUtil;
import org.jetbrains.jet.lang.resolve.calls.model.ResolvedCall;
import org.jetbrains.jet.lang.resolve.constants.CompileTimeConstant;
import org.jetbrains.jet.lang.resolve.java.AsmTypeConstants;
import org.jetbrains.jet.lang.resolve.java.JvmAbi;
import org.jetbrains.jet.lang.resolve.java.JvmClassName;
import org.jetbrains.jet.lang.resolve.java.JvmStdlibNames;
import org.jetbrains.jet.lang.resolve.java.kt.DescriptorKindUtils;
import org.jetbrains.jet.lang.resolve.name.Name;
import org.jetbrains.jet.lang.types.JetType;
import org.jetbrains.jet.lang.types.TypeUtils;
import org.jetbrains.jet.lang.types.lang.KotlinBuiltIns;
import org.jetbrains.jet.lexer.JetTokens;
import org.jetbrains.jet.utils.ExceptionUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

import static org.jetbrains.asm4.Opcodes.*;
import static org.jetbrains.jet.codegen.AsmUtil.*;
import static org.jetbrains.jet.codegen.CodegenUtil.*;
import static org.jetbrains.jet.codegen.binding.CodegenBinding.*;
import static org.jetbrains.jet.descriptors.serialization.ClassSerializationUtil.constantSerializer;
import static org.jetbrains.jet.lang.resolve.BindingContextUtils.callableDescriptorToDeclaration;
import static org.jetbrains.jet.lang.resolve.BindingContextUtils.descriptorToDeclaration;
import static org.jetbrains.jet.lang.resolve.DescriptorUtils.*;
import static org.jetbrains.jet.lang.resolve.java.AsmTypeConstants.JAVA_STRING_TYPE;
import static org.jetbrains.jet.lang.resolve.java.AsmTypeConstants.OBJECT_TYPE;

public class ImplementationBodyCodegen extends ClassBodyCodegen {
    private static final String VALUES = "$VALUES";
    private JetDelegationSpecifier superCall;
    private Type superClassAsmType;
    @Nullable // null means java/lang/Object
    private JetType superClassType;
    private final Type classAsmType;

    private final FunctionCodegen functionCodegen;
    private final PropertyCodegen propertyCodegen;

    private List<PropertyDescriptor> classObjectPropertiesToCopy;

    public ImplementationBodyCodegen(
            @NotNull JetClassOrObject aClass,
            @NotNull  ClassContext context,
            @NotNull  ClassBuilder v,
            @NotNull  GenerationState state,
            @Nullable MemberCodegen parentCodegen
    ) {
        super(aClass, context, v, state, parentCodegen);
        this.classAsmType = typeMapper.mapType(descriptor.getDefaultType(), JetTypeMapperMode.IMPL);
        this.functionCodegen = new FunctionCodegen(context, v, state);
        this.propertyCodegen = new PropertyCodegen(context, v, this.functionCodegen, this);
    }

    @Override
    protected void generateDeclaration() {
        getSuperClass();

        JvmClassSignature signature = signature();

        boolean isAbstract = false;
        boolean isInterface = false;
        boolean isFinal = false;
        boolean isStatic = false;
        boolean isAnnotation = false;
        boolean isEnum = false;

        if (myClass instanceof JetClass) {
            JetClass jetClass = (JetClass) myClass;
            if (jetClass.hasModifier(JetTokens.ABSTRACT_KEYWORD)) {
                isAbstract = true;
            }
            if (jetClass.isTrait()) {
                isAbstract = true;
                isInterface = true;
            }
            else if (jetClass.isAnnotation()) {
                isAbstract = true;
                isInterface = true;
                isAnnotation = true;
                signature.getInterfaces().add("java/lang/annotation/Annotation");
            }
            else if (jetClass.isEnum()) {
                isAbstract = hasAbstractMembers(descriptor);
                isEnum = true;
            }

            if (descriptor.getKind() == ClassKind.OBJECT || descriptor.getKind() == ClassKind.CLASS_OBJECT) {
                isFinal = true;
            }

            if (!jetClass.hasModifier(JetTokens.OPEN_KEYWORD) && !isAbstract) {
                isFinal = true;
            }
            isStatic = !jetClass.isInner();
        }
        else {
            isStatic = myClass.getParent() instanceof JetClassObject;
            isFinal = true;
        }

        int access = 0;

        if (state.getClassBuilderMode() == ClassBuilderMode.SIGNATURES && !DescriptorUtils.isTopLevelDeclaration(descriptor)) {
            // ClassBuilderMode.SIGNATURES means we are generating light classes & looking at a nested or inner class
            // Light class generation is implemented so that Cls-classes only read bare code of classes,
            // without knowing whether these classes are inner or not (see ClassStubBuilder.EMPTY_STRATEGY)
            // Thus we must write full accessibility flags on inner classes in this mode
            access |= getVisibilityAccessFlag(descriptor);
            // Same for STATIC
            if (isStatic) {
                access |= ACC_STATIC;
            }
        }
        else {
            access |= getVisibilityAccessFlagForClass(descriptor);
        }
        if (isAbstract) {
            access |= ACC_ABSTRACT;
        }
        if (isInterface) {
            access |= ACC_INTERFACE; // ACC_SUPER
        }
        else {
            access |= ACC_SUPER;
        }
        if (isFinal) {
            access |= ACC_FINAL;
        }
        if (isAnnotation) {
            access |= ACC_ANNOTATION;
        }
        if (KotlinBuiltIns.getInstance().isDeprecated(descriptor)) {
            access |= ACC_DEPRECATED;
        }
        if (isEnum) {
            for (JetDeclaration declaration : myClass.getDeclarations()) {
                if (declaration instanceof JetEnumEntry) {
                    if (enumEntryNeedSubclass(state.getBindingContext(), (JetEnumEntry) declaration)) {
                        access &= ~ACC_FINAL;
                    }
                }
            }
            access |= ACC_ENUM;
        }
        List<String> interfaces = signature.getInterfaces();
        v.defineClass(myClass, V1_6,
                      access,
                      signature.getName(),
                      signature.getJavaGenericSignature(),
                      signature.getSuperclassName(),
                      interfaces.toArray(new String[interfaces.size()])
        );
        v.visitSource(myClass.getContainingFile().getName(), null);

        writeEnclosingMethod();

        writeOuterClasses();

        writeInnerClasses();

        AnnotationCodegen.forClass(v.getVisitor(), typeMapper).genAnnotations(descriptor);

        writeKotlinInfoIfNeeded();

        writeClassSignatureIfNeeded(signature);
    }

    private void writeKotlinInfoIfNeeded() {
        if (!(descriptor.getContainingDeclaration() instanceof ClassOrNamespaceDescriptor)) return;

        final AnnotationVisitor av = v.getVisitor().visitAnnotation("Ljet/KotlinInfo;", true);
        DescriptorSerializer serializer = new DescriptorSerializer(DescriptorNamer.DEFAULT);

        ClassSerializationUtil.serializeClass(descriptor, constantSerializer(serializer), new ClassSerializationUtil.Sink() {
            @Override
            public void writeClass(@NotNull ClassDescriptor classDescriptor, @NotNull ProtoBuf.Class classProto) {
                av.visit("data", classProto.toByteArray());
            }
        });

        ByteArrayOutputStream nameTable = new ByteArrayOutputStream();
        NameSerializationUtil.serializeNameTable(nameTable, serializer.getNameTable());
        av.visit("nameTable", nameTable.toByteArray());

        av.visitEnd();
    }

    private void writeEnclosingMethod() {
        //JVMS7: A class must have an EnclosingMethod attribute if and only if it is a local class or an anonymous class.
        DeclarationDescriptor parentDescriptor = descriptor.getContainingDeclaration();

        boolean isObjectLiteral = DescriptorUtils.isAnonymous(descriptor);

        boolean isLocalOrAnonymousClass = isObjectLiteral ||
                                          !(parentDescriptor instanceof NamespaceDescriptor || parentDescriptor instanceof ClassDescriptor);
        if (isLocalOrAnonymousClass) {
            String outerClassName = getOuterClassName(descriptor, typeMapper);
            FunctionDescriptor function = DescriptorUtils.getParentOfType(descriptor, FunctionDescriptor.class);

            if (function != null) {
                Method method = typeMapper.mapSignature(function).getAsmMethod();
                v.visitOuterClass(outerClassName, method.getName(), method.getDescriptor());
            }
            else {
                assert isObjectLiteral
                        : "Function descriptor could be null only for object literal in package namespace: " + descriptor.getName();
                v.visitOuterClass(outerClassName, null, null);
            }
        }
    }

    @NotNull
    private static String getOuterClassName(
            @NotNull ClassDescriptor classDescriptor,
            @NotNull JetTypeMapper typeMapper
    ) {
        ClassDescriptor container = DescriptorUtils.getParentOfType(classDescriptor, ClassDescriptor.class);
        if (container != null) {
            return typeMapper.mapType(container.getDefaultType(), JetTypeMapperMode.IMPL).getInternalName();
        }
        else {
            JetFile containingFile = BindingContextUtils.getContainingFile(typeMapper.getBindingContext(), classDescriptor);
            assert containingFile != null : "Containing file should be present for " + classDescriptor;
            return NamespaceCodegen.getNamespacePartInternalName(containingFile);
        }
    }

    private void writeInnerClasses() {
        Collection<ClassDescriptor> result = bindingContext.get(INNER_CLASSES, descriptor);
        if (result != null) {
            for (ClassDescriptor innerClass : result) {
                writeInnerClass(innerClass);
            }
        }
    }

    private void writeOuterClasses() {
        // JVMS7 (4.7.6): a nested class or interface member will have InnerClasses information
        // for each enclosing class and for each immediate member
        DeclarationDescriptor inner = descriptor;
        while (true) {
            if (inner == null || isTopLevelDeclaration(inner)) {
                break;
            }
            if (inner instanceof ClassDescriptor && !isEnumClassObject(inner)) {
                writeInnerClass((ClassDescriptor) inner);
            }
            inner = inner.getContainingDeclaration();
        }
    }

    private void writeInnerClass(@NotNull ClassDescriptor innerClass) {
        // TODO: proper access
        int innerClassAccess = getVisibilityAccessFlag(innerClass);
        if (innerClass.getModality() == Modality.FINAL) {
            innerClassAccess |= ACC_FINAL;
        }
        else if (innerClass.getModality() == Modality.ABSTRACT) {
            innerClassAccess |= ACC_ABSTRACT;
        }

        if (innerClass.getKind() == ClassKind.TRAIT) {
            innerClassAccess |= ACC_INTERFACE;
        }
        else if (innerClass.getKind() == ClassKind.ENUM_CLASS) {
            innerClassAccess |= ACC_ENUM;
        }

        if (!innerClass.isInner()) {
            innerClassAccess |= ACC_STATIC;
        }

        // TODO: cache internal names
        DeclarationDescriptor containing = innerClass.getContainingDeclaration();
        String outerClassInternalName = containing instanceof ClassDescriptor ? getInternalNameForImpl((ClassDescriptor) containing) : null;

        String innerClassInternalName;
        String innerName;

        if (isClassObject(innerClass)) {
            innerName = JvmAbi.CLASS_OBJECT_CLASS_NAME;
            innerClassInternalName = outerClassInternalName + JvmAbi.CLASS_OBJECT_SUFFIX;
        }
        else {
            innerName = innerClass.getName().isSpecial() ? null : innerClass.getName().asString();
            innerClassInternalName = getInternalNameForImpl(innerClass);
        }

        v.visitInnerClass(innerClassInternalName, outerClassInternalName, innerName, innerClassAccess);
    }

    @NotNull
    private String getInternalNameForImpl(@NotNull ClassDescriptor descriptor) {
        return typeMapper.mapType(descriptor.getDefaultType(), JetTypeMapperMode.IMPL).getInternalName();
    }

    private void writeClassSignatureIfNeeded(JvmClassSignature signature) {
        AnnotationVisitor annotationVisitor = v.newAnnotation(JvmStdlibNames.JET_CLASS.getDescriptor(), true);
        annotationVisitor.visit(JvmStdlibNames.JET_CLASS_SIGNATURE, signature.getKotlinGenericSignature());
        int flags = getFlagsForVisibility(descriptor.getVisibility()) | getFlagsForClassKind(descriptor);
        if (JvmStdlibNames.FLAGS_DEFAULT_VALUE != flags) {
            annotationVisitor.visit(JvmStdlibNames.JET_FLAGS_FIELD, flags);
        }
        annotationVisitor.visit(JvmStdlibNames.ABI_VERSION_NAME, JvmAbi.VERSION);
        annotationVisitor.visitEnd();

        if (descriptor.getKind() == ClassKind.CLASS_OBJECT) {
            AnnotationVisitor classObjectVisitor = v.newAnnotation(JvmStdlibNames.JET_CLASS_OBJECT.getDescriptor(), true);
            classObjectVisitor.visitEnd();
        }
    }

    private JvmClassSignature signature() {
        List<String> superInterfaces;

        LinkedHashSet<String> superInterfacesLinkedHashSet = new LinkedHashSet<String>();

        // TODO: generics signature is not always needed
        BothSignatureWriter signatureVisitor = new BothSignatureWriter(BothSignatureWriter.Mode.CLASS, true);


        {   // type parameters
            List<TypeParameterDescriptor> typeParameters = descriptor.getTypeConstructor().getParameters();
            typeMapper.writeFormalTypeParameters(typeParameters, signatureVisitor);
        }

        signatureVisitor.writeSupersStart();

        {   // superclass
            signatureVisitor.writeSuperclass();
            if (superClassType == null) {
                signatureVisitor.writeClassBegin(superClassAsmType.getInternalName(), false, false);
                signatureVisitor.writeClassEnd();
            }
            else {
                typeMapper.mapType(superClassType, signatureVisitor, JetTypeMapperMode.TYPE_PARAMETER);
            }
            signatureVisitor.writeSuperclassEnd();
        }


        {   // superinterfaces
            superInterfacesLinkedHashSet.add(JvmStdlibNames.JET_OBJECT.getInternalName());

            for (JetDelegationSpecifier specifier : myClass.getDelegationSpecifiers()) {
                JetType superType = bindingContext.get(BindingContext.TYPE, specifier.getTypeReference());
                assert superType != null;
                ClassDescriptor superClassDescriptor = (ClassDescriptor) superType.getConstructor().getDeclarationDescriptor();
                if (isInterface(superClassDescriptor)) {
                    signatureVisitor.writeInterface();
                    Type jvmName = typeMapper.mapType(superType, signatureVisitor, JetTypeMapperMode.TYPE_PARAMETER);
                    signatureVisitor.writeInterfaceEnd();
                    superInterfacesLinkedHashSet.add(jvmName.getInternalName());
                }
            }

            superInterfaces = new ArrayList<String>(superInterfacesLinkedHashSet);
        }

        signatureVisitor.writeSupersEnd();

        return new JvmClassSignature(jvmName(), superClassAsmType.getInternalName(), superInterfaces, signatureVisitor.makeJavaGenericSignature(),
                                     signatureVisitor.makeKotlinClassSignature());
    }

    private String jvmName() {
        if (kind != OwnerKind.IMPLEMENTATION) {
            throw new IllegalStateException("must not call this method with kind " + kind);
        }
        return classAsmType.getInternalName();
    }

    protected void getSuperClass() {
        superClassAsmType = AsmTypeConstants.OBJECT_TYPE;
        superClassType = null;

        List<JetDelegationSpecifier> delegationSpecifiers = myClass.getDelegationSpecifiers();

        if (myClass instanceof JetClass && ((JetClass) myClass).isTrait()) {
            return;
        }

        if (kind != OwnerKind.IMPLEMENTATION) {
            throw new IllegalStateException("must be impl to reach this code: " + kind);
        }

        for (JetDelegationSpecifier specifier : delegationSpecifiers) {
            if (specifier instanceof JetDelegatorToSuperClass || specifier instanceof JetDelegatorToSuperCall) {
                JetType superType = bindingContext.get(BindingContext.TYPE, specifier.getTypeReference());
                assert superType != null;
                ClassDescriptor superClassDescriptor = (ClassDescriptor) superType.getConstructor().getDeclarationDescriptor();
                assert superClassDescriptor != null;
                if (!isInterface(superClassDescriptor)) {
                    superClassType = superType;
                    superClassAsmType = typeMapper.mapType(superClassDescriptor.getDefaultType(), JetTypeMapperMode.IMPL);
                    superCall = specifier;
                }
            }
        }

        if (superClassType == null) {
            if (descriptor.getKind() == ClassKind.ENUM_CLASS) {
                superClassType = KotlinBuiltIns.getInstance().getEnumType(descriptor.getDefaultType());
                superClassAsmType = typeMapper.mapType(superClassType);
            }
            if (descriptor.getKind() == ClassKind.ENUM_ENTRY) {
                superClassType = descriptor.getTypeConstructor().getSupertypes().iterator().next();
                superClassAsmType = typeMapper.mapType(superClassType);
            }
        }
    }

    @Override
    protected void generateSyntheticParts() {
        generateFieldForSingleton();

        generateClassObjectBackingFieldCopies();

        try {
            generatePrimaryConstructor();
        }
        catch (CompilationException e) {
            throw e;
        }
        catch (ProcessCanceledException e) {
            throw e;
        }
        catch (RuntimeException e) {
            throw new RuntimeException("Error generating primary constructor of class " + myClass.getName() + " with kind " + kind, e);
        }

        generateTraitMethods();

        generateSyntheticAccessors();

        generateEnumMethodsAndConstInitializers();

        generateFunctionsForDataClasses();

        genClosureFields(context.closure, v, state.getTypeMapper());
    }

    private List<PropertyDescriptor> getDataProperties() {
        ArrayList<PropertyDescriptor> result = Lists.newArrayList();
        for (JetParameter parameter : getPrimaryConstructorParameters()) {
            if (parameter.getValOrVarNode() == null) continue;

            PropertyDescriptor propertyDescriptor = DescriptorUtils.getPropertyDescriptor(parameter, bindingContext);

            result.add(propertyDescriptor);
        }
        return result;
    }

    private void generateFunctionsForDataClasses() {
        if (!KotlinBuiltIns.getInstance().isData(descriptor)) return;

        generateComponentFunctionsForDataClasses();
        generateCopyFunctionForDataClasses();

        List<PropertyDescriptor> properties = getDataProperties();
        if (!properties.isEmpty()) {
            generateDataClassToStringIfNeeded(properties);
            generateDataClassHashCodeIfNeeded(properties);
            generateDataClassEqualsIfNeeded(properties);
        }
    }

    private void generateCopyFunctionForDataClasses() {
        FunctionDescriptor copyFunction = bindingContext.get(BindingContext.DATA_CLASS_COPY_FUNCTION, descriptor);
        if (copyFunction != null) {
            generateCopyFunction(copyFunction);
        }
    }

    private void generateDataClassToStringIfNeeded(List<PropertyDescriptor> properties) {
        ClassDescriptor stringClass = KotlinBuiltIns.getInstance().getString();
        if (getDeclaredFunctionByRawSignature(descriptor, Name.identifier("toString"), stringClass) == null) {
            generateDataClassToStringMethod(properties);
        }
    }

    private void generateDataClassHashCodeIfNeeded(List<PropertyDescriptor> properties) {
        ClassDescriptor intClass = KotlinBuiltIns.getInstance().getInt();
        if (getDeclaredFunctionByRawSignature(descriptor, Name.identifier("hashCode"), intClass) == null) {
            generateDataClassHashCodeMethod(properties);
        }
    }

    private void generateDataClassEqualsIfNeeded(List<PropertyDescriptor> properties) {
        ClassDescriptor booleanClass = KotlinBuiltIns.getInstance().getBoolean();
        ClassDescriptor anyClass = KotlinBuiltIns.getInstance().getAny();
        FunctionDescriptor equalsFunction = getDeclaredFunctionByRawSignature(descriptor, Name.identifier("equals"), booleanClass, anyClass);
        if (equalsFunction == null) {
            generateDataClassEqualsMethod(properties);
        }
    }

    private void generateDataClassEqualsMethod(List<PropertyDescriptor> properties) {
        MethodVisitor mv = v.getVisitor().visitMethod(ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null);
        InstructionAdapter iv = new InstructionAdapter(mv);

        mv.visitCode();
        Label eq = new Label();
        Label ne = new Label();

        iv.load(0, OBJECT_TYPE);
        iv.load(1, AsmTypeConstants.OBJECT_TYPE);
        iv.ifacmpeq(eq);

        iv.load(1, AsmTypeConstants.OBJECT_TYPE);
        iv.instanceOf(classAsmType);
        iv.ifeq(ne);

        iv.load(1, AsmTypeConstants.OBJECT_TYPE);
        iv.checkcast(classAsmType);
        iv.store(2, AsmTypeConstants.OBJECT_TYPE);

        for (PropertyDescriptor propertyDescriptor : properties) {
            Type asmType = typeMapper.mapType(propertyDescriptor.getType());

            genPropertyOnStack(iv, propertyDescriptor, 0);
            genPropertyOnStack(iv, propertyDescriptor, 2);

            if (asmType.getSort() == Type.ARRAY) {
                Type elementType = correctElementType(asmType);
                if (elementType.getSort() == Type.OBJECT || elementType.getSort() == Type.ARRAY) {
                    iv.invokestatic("java/util/Arrays", "equals", "([Ljava/lang/Object;[Ljava/lang/Object;)Z");
                }
                else {
                    iv.invokestatic("java/util/Arrays", "equals", "([" + elementType.getDescriptor() + "[" + elementType.getDescriptor() + ")Z");
                }
            }
            else {
                StackValue value = genEqualsForExpressionsOnStack(iv, JetTokens.EQEQ, asmType, asmType);
                value.put(Type.BOOLEAN_TYPE, iv);
            }

            iv.ifeq(ne);
        }

        iv.mark(eq);
        iv.iconst(1);
        iv.areturn(Type.INT_TYPE);

        iv.mark(ne);
        iv.iconst(0);
        iv.areturn(Type.INT_TYPE);

        FunctionCodegen.endVisit(mv, "equals", myClass);
    }

    private void generateDataClassHashCodeMethod(List<PropertyDescriptor> properties) {
        MethodVisitor mv = v.getVisitor().visitMethod(ACC_PUBLIC, "hashCode", "()I", null, null);
        InstructionAdapter iv = new InstructionAdapter(mv);

        mv.visitCode();
        boolean first = true;
        for (PropertyDescriptor propertyDescriptor : properties) {
            if (!first) {
                iv.iconst(31);
                iv.mul(Type.INT_TYPE);
            }

            genPropertyOnStack(iv, propertyDescriptor, 0);

            Label ifNull = null;
            Type asmType = typeMapper.mapType(propertyDescriptor.getType());
            if (!isPrimitive(asmType)) {
                ifNull = new Label();
                iv.dup();
                iv.ifnull(ifNull);
            }

            genHashCode(mv, iv, asmType);

            if (ifNull != null) {
                Label end = new Label();
                iv.goTo(end);
                iv.mark(ifNull);
                iv.pop();
                iv.iconst(0);
                iv.mark(end);
            }

            if (first) {
                first = false;
            }
            else {
                iv.add(Type.INT_TYPE);
            }
        }

        mv.visitInsn(IRETURN);

        FunctionCodegen.endVisit(mv, "hashCode", myClass);
    }

    private void generateDataClassToStringMethod(List<PropertyDescriptor> properties) {
        MethodVisitor mv = v.getVisitor().visitMethod(ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
        InstructionAdapter iv = new InstructionAdapter(mv);

        mv.visitCode();
        genStringBuilderConstructor(iv);

        boolean first = true;
        for (PropertyDescriptor propertyDescriptor : properties) {
            if (first) {
                iv.aconst(descriptor.getName() + "(" + propertyDescriptor.getName().asString()+"=");
                first = false;
            }
            else {
                iv.aconst(", " + propertyDescriptor.getName().asString()+"=");
            }
            genInvokeAppendMethod(iv, JAVA_STRING_TYPE);

            Type type = genPropertyOnStack(iv, propertyDescriptor, 0);

            if (type.getSort() == Type.ARRAY) {
                Type elementType = correctElementType(type);
                if (elementType.getSort() == Type.OBJECT || elementType.getSort() == Type.ARRAY) {
                    iv.invokestatic("java/util/Arrays", "toString", "([Ljava/lang/Object;)Ljava/lang/String;");
                    type = JAVA_STRING_TYPE;
                }
                else {
                    if (elementType.getSort() != Type.CHAR) {
                        iv.invokestatic("java/util/Arrays", "toString", "(" + type.getDescriptor() + ")Ljava/lang/String;");
                        type = JAVA_STRING_TYPE;
                    }
                }
            }
            genInvokeAppendMethod(iv, type);
        }

        iv.aconst(")");
        genInvokeAppendMethod(iv, JAVA_STRING_TYPE);

        iv.invokevirtual("java/lang/StringBuilder", "toString", "()Ljava/lang/String;");
        iv.areturn(JAVA_STRING_TYPE);

        FunctionCodegen.endVisit(mv, "toString", myClass);
    }

    private Type genPropertyOnStack(InstructionAdapter iv, PropertyDescriptor propertyDescriptor, int index) {
        iv.load(index, classAsmType);
        Method
                method = typeMapper.mapGetterSignature(propertyDescriptor, OwnerKind.IMPLEMENTATION).getAsmMethod();

        iv.invokevirtual(classAsmType.getInternalName(), method.getName(), method.getDescriptor());
        return method.getReturnType();
    }

    private void generateComponentFunctionsForDataClasses() {
        if (!myClass.hasPrimaryConstructor() || !KotlinBuiltIns.getInstance().isData(descriptor)) return;

        ConstructorDescriptor constructor = descriptor.getConstructors().iterator().next();

        for (ValueParameterDescriptor parameter : constructor.getValueParameters()) {
            FunctionDescriptor function = bindingContext.get(BindingContext.DATA_CLASS_COMPONENT_FUNCTION, parameter);
            if (function != null) {
                generateComponentFunction(function, parameter);
            }
        }
    }

    private void generateComponentFunction(@NotNull FunctionDescriptor function, @NotNull final ValueParameterDescriptor parameter) {
        JetType returnType = function.getReturnType();
        assert returnType != null : "Return type of component function should not be null: " + function;
        final Type componentType = typeMapper.mapReturnType(returnType);

        JvmMethodSignature signature = typeMapper.mapSignature(function);

        FunctionCodegen fc = new FunctionCodegen(context, v, state);
        fc.generateMethod(myClass, signature, true, function, new FunctionGenerationStrategy() {
            @Override
            public void generateBody(
                    @NotNull MethodVisitor mv,
                    @NotNull JvmMethodSignature signature,
                    @NotNull MethodContext context
            ) {
                InstructionAdapter iv = new InstructionAdapter(mv);
                if (!componentType.equals(Type.VOID_TYPE)) {
                    iv.load(0, classAsmType);
                    String desc = "()" + componentType.getDescriptor();
                    iv.invokevirtual(classAsmType.getInternalName(), PropertyCodegen.getterName(parameter.getName()), desc);
                }
                iv.areturn(componentType);
            }
        });
    }

    private void generateCopyFunction(@NotNull final FunctionDescriptor function) {
        JvmMethodSignature methodSignature = typeMapper.mapSignature(function);

        final Type thisDescriptorType = typeMapper.mapType(descriptor.getDefaultType());

        FunctionCodegen fc = new FunctionCodegen(context, v, state);
        fc.generateMethod(myClass, methodSignature, true, function, new FunctionGenerationStrategy() {
            @Override
            public void generateBody(
                    @NotNull MethodVisitor mv,
                    @NotNull JvmMethodSignature signature,
                    @NotNull MethodContext context
            ) {
                InstructionAdapter iv = new InstructionAdapter(mv);

                iv.anew(thisDescriptorType);
                iv.dup();

                ConstructorDescriptor constructor = DescriptorUtils.getConstructorOfDataClass(descriptor);
                assert function.getValueParameters().size() == constructor.getValueParameters().size() :
                        "Number of parameters of copy function and constructor are different. " +
                        "Copy: " + function.getValueParameters().size() + ", " +
                        "constructor: " + constructor.getValueParameters().size();

                MutableClosure closure = ImplementationBodyCodegen.this.context.closure;
                if (closure != null && closure.getCaptureThis() != null) {
                    Type type = typeMapper.mapType(enclosingClassDescriptor(bindingContext, descriptor));
                    iv.load(0, classAsmType);
                    iv.getfield(JvmClassName.byType(classAsmType).getInternalName(), CAPTURED_THIS_FIELD, type.getDescriptor());
                }

                int parameterIndex = 1; // localVariable 0 = this
                for (ValueParameterDescriptor parameterDescriptor : function.getValueParameters()) {
                    Type type = typeMapper.mapType(parameterDescriptor.getType());
                    iv.load(parameterIndex, type);
                    parameterIndex += type.getSize();
                }

                String constructorJvmDescriptor = typeMapper.mapToCallableMethod(constructor).getSignature().getAsmMethod().getDescriptor();
                iv.invokespecial(thisDescriptorType.getInternalName(), "<init>", constructorJvmDescriptor);

                iv.areturn(thisDescriptorType);
            }
        });

        MethodContext functionContext = context.intoFunction(function);
        FunctionCodegen.generateDefaultIfNeeded(functionContext, state, v, methodSignature, function, OwnerKind.IMPLEMENTATION,
                    new DefaultParameterValueLoader() {
                        @Override
                        public void putValueOnStack(
                                ValueParameterDescriptor descriptor,
                                ExpressionCodegen codegen
                        ) {
                            assert (KotlinBuiltIns.getInstance().isData((ClassDescriptor) function.getContainingDeclaration()))
                                    : "Trying to create function with default arguments for function that isn't presented in code for class without data annotation";
                            PropertyDescriptor propertyDescriptor = codegen.getBindingContext().get(
                                    BindingContext.VALUE_PARAMETER_AS_PROPERTY, descriptor);
                            assert propertyDescriptor != null
                                    : "Trying to generate default value for parameter of copy function that doesn't correspond to any property";
                            codegen.v.load(0, thisDescriptorType);
                            Type propertyType = codegen.typeMapper.mapType(propertyDescriptor.getType());
                            codegen.intermediateValueForProperty(propertyDescriptor, false, null).put(propertyType, codegen.v);
                        }
                    });
}

    private void generateEnumMethodsAndConstInitializers() {
        if (!myEnumConstants.isEmpty()) {
            generateEnumMethods();

            if (state.getClassBuilderMode() == ClassBuilderMode.FULL) {
                initializeEnumConstants(createOrGetClInitCodegen());
            }
        }
    }

    private void generateEnumMethods() {
        if (myEnumConstants.size() > 0) {
            {
                Type type =
                        typeMapper.mapType(KotlinBuiltIns.getInstance().getArrayType(descriptor.getDefaultType()),
                                           JetTypeMapperMode.IMPL);

                MethodVisitor mv =
                        v.newMethod(myClass, ACC_PUBLIC | ACC_STATIC, "values", "()" + type.getDescriptor(), null, null);
                mv.visitCode();
                mv.visitFieldInsn(GETSTATIC, typeMapper.mapType(descriptor).getInternalName(),
                                  VALUES,
                                  type.getDescriptor());
                mv.visitMethodInsn(INVOKEVIRTUAL, type.getInternalName(), "clone", "()Ljava/lang/Object;");
                mv.visitTypeInsn(CHECKCAST, type.getInternalName());
                mv.visitInsn(ARETURN);
                FunctionCodegen.endVisit(mv, "values()", myClass);
            }
            {

                MethodVisitor mv =
                        v.newMethod(myClass, ACC_PUBLIC | ACC_STATIC, "valueOf", "(Ljava/lang/String;)" + classAsmType.getDescriptor(), null,
                                    null);
                mv.visitCode();
                mv.visitLdcInsn(classAsmType);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Enum", "valueOf", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;");
                mv.visitTypeInsn(CHECKCAST, classAsmType.getInternalName());
                mv.visitInsn(ARETURN);
                FunctionCodegen.endVisit(mv, "values()", myClass);
            }
        }
    }

    protected void generateSyntheticAccessors() {
        Map<DeclarationDescriptor, DeclarationDescriptor> accessors = context.getAccessors();
        for (Map.Entry<DeclarationDescriptor, DeclarationDescriptor> entry : accessors.entrySet()) {
            generateSyntheticAccessor(entry);
        }
    }

    private void generateSyntheticAccessor(Map.Entry<DeclarationDescriptor, DeclarationDescriptor> entry) {
        if (entry.getValue() instanceof FunctionDescriptor) {
            FunctionDescriptor bridge = (FunctionDescriptor) entry.getValue();
            final FunctionDescriptor original = (FunctionDescriptor) entry.getKey();
             functionCodegen.generateMethod(null, typeMapper.mapSignature(bridge), false, bridge,
                   new FunctionGenerationStrategy.CodegenBased<FunctionDescriptor>(state, bridge) {
                       @Override
                       public void doGenerateBody(ExpressionCodegen codegen, JvmMethodSignature signature) {
                           generateMethodCallTo(original, codegen.v);

                           codegen.v.areturn(signature.getAsmMethod().getReturnType());
                       }
                   });
        }
        else if (entry.getValue() instanceof PropertyDescriptor) {
            final PropertyDescriptor bridge = (PropertyDescriptor) entry.getValue();
            final PropertyDescriptor original = (PropertyDescriptor) entry.getKey();


            PropertyGetterDescriptor getter = bridge.getGetter();
            assert getter != null;
            functionCodegen.generateMethod(null, typeMapper.mapGetterSignature(bridge, OwnerKind.IMPLEMENTATION), false, getter,
                                           new FunctionGenerationStrategy.CodegenBased<PropertyGetterDescriptor>(state, getter) {
                @Override
                public void doGenerateBody(ExpressionCodegen codegen, JvmMethodSignature signature) {
                    InstructionAdapter iv = codegen.v;
                    boolean forceField = AsmUtil.isPropertyWithBackingFieldInOuterClass(original) && !isClassObject(bridge.getContainingDeclaration());
                    StackValue property = codegen.intermediateValueForProperty(original, forceField, null, MethodKind.SYNTHETIC_ACCESSOR);
                    if (!forceField) {
                        iv.load(0, OBJECT_TYPE);
                    }
                    property.put(property.type, iv);
                    iv.areturn(signature.getAsmMethod().getReturnType());
                }
            });


            if (bridge.isVar()) {
                PropertySetterDescriptor setter = bridge.getSetter();
                assert setter != null;

                functionCodegen.generateMethod(null, typeMapper.mapSetterSignature(bridge, OwnerKind.IMPLEMENTATION), false, setter,
                                               new FunctionGenerationStrategy.CodegenBased<PropertySetterDescriptor>(state, setter) {
                    @Override
                    public void doGenerateBody(ExpressionCodegen codegen, JvmMethodSignature signature) {
                        boolean forceField = AsmUtil.isPropertyWithBackingFieldInOuterClass(original) && !isClassObject(bridge.getContainingDeclaration());
                        StackValue property = codegen.intermediateValueForProperty(original, forceField, null, MethodKind.SYNTHETIC_ACCESSOR);
                        InstructionAdapter iv = codegen.v;

                        Type[] argTypes = signature.getAsmMethod().getArgumentTypes();
                        for (int i = 0, reg = 0; i < argTypes.length; i++) {
                            Type argType = argTypes[i];
                            iv.load(reg, argType);
                            //noinspection AssignmentToForLoopParameter
                            reg += argType.getSize();
                        }
                        property.store(property.type, iv);

                        iv.areturn(signature.getAsmMethod().getReturnType());
                    }
                });
            }
        }
        else {
            throw new UnsupportedOperationException();
        }
    }

    private void generateMethodCallTo(FunctionDescriptor functionDescriptor, InstructionAdapter iv) {
        boolean isConstructor = functionDescriptor instanceof ConstructorDescriptor;
        boolean callFromAccessor = !JetTypeMapper.isAccessor(functionDescriptor);
        CallableMethod callableMethod = isConstructor ?
                                        typeMapper.mapToCallableMethod((ConstructorDescriptor) functionDescriptor) :
                                        typeMapper.mapToCallableMethod(functionDescriptor, callFromAccessor,
                                                                       isCallInsideSameClassAsDeclared(functionDescriptor, context),
                                                                       isCallInsideSameModuleAsDeclared(functionDescriptor, context),
                                                                       context.getContextKind());

        Method method = callableMethod.getSignature().getAsmMethod();
        Type[] argTypes = method.getArgumentTypes();

        int reg = 1;
        if (isConstructor) {
            iv.anew(callableMethod.getOwner().getAsmType());
            iv.dup();
            reg = 0;
        }
        else if (callFromAccessor) {
            iv.load(0, OBJECT_TYPE);
        }

        for (int paramIndex = 0; paramIndex < argTypes.length; paramIndex++) {
            Type argType = argTypes[paramIndex];
            iv.load(reg, argType);
            //noinspection AssignmentToForLoopParameter
            reg += argType.getSize();
        }
        callableMethod.invokeWithoutAssertions(iv);
    }

    private void generateFieldForSingleton() {
        boolean hasClassObject = descriptor.getClassObjectDescriptor() != null;
        boolean isEnumClass = DescriptorUtils.isEnumClass(descriptor);

        if (!(isNonLiteralObject(myClass) || hasClassObject) || isEnumClass) return;

        ClassDescriptor fieldTypeDescriptor = hasClassObject ? descriptor.getClassObjectDescriptor() : descriptor;
        assert fieldTypeDescriptor != null;
        StackValue.Field field = StackValue.singleton(fieldTypeDescriptor, typeMapper);
        JetClassOrObject original = hasClassObject ? ((JetClass) myClass).getClassObject().getObjectDeclaration() : myClass;

        v.newField(original, ACC_PUBLIC | ACC_STATIC | ACC_FINAL, field.name, field.type.getDescriptor(), null, null);

        if (!AsmUtil.isClassObjectWithBackingFieldsInOuter(fieldTypeDescriptor)) {
            genInitSingleton(fieldTypeDescriptor, field);
        }
    }

    private void generateClassObjectBackingFieldCopies() {
        if (classObjectPropertiesToCopy != null) {
            for (PropertyDescriptor propertyDescriptor : classObjectPropertiesToCopy) {

                v.newField(null, ACC_STATIC | ACC_FINAL | ACC_PUBLIC, context.getFieldName(propertyDescriptor), typeMapper.mapType(propertyDescriptor).getDescriptor(), null, null);

                if (state.getClassBuilderMode() == ClassBuilderMode.FULL) {
                    ExpressionCodegen codegen = createOrGetClInitCodegen();
                    int classObjectIndex = putClassObjectInLocalVar(codegen);
                    StackValue.local(classObjectIndex, OBJECT_TYPE).put(OBJECT_TYPE, codegen.v);
                    copyFieldFromClassObject(propertyDescriptor);
                }
            }
        }
    }

    private int putClassObjectInLocalVar(ExpressionCodegen codegen) {
        FrameMap frameMap = codegen.myFrameMap;
        ClassDescriptor classObjectDescriptor = descriptor.getClassObjectDescriptor();
        int classObjectIndex = frameMap.getIndex(classObjectDescriptor);
        if (classObjectIndex == -1) {
            classObjectIndex = frameMap.enter(classObjectDescriptor, OBJECT_TYPE);
            StackValue classObject = StackValue.singleton(classObjectDescriptor, typeMapper);
            classObject.put(classObject.type, codegen.v);
            StackValue.local(classObjectIndex, classObject.type).store(classObject.type, codegen.v);
        }
        return classObjectIndex;
    }

    private void copyFieldFromClassObject(PropertyDescriptor propertyDescriptor) {
        ExpressionCodegen codegen = createOrGetClInitCodegen();
        StackValue property = codegen.intermediateValueForProperty(propertyDescriptor, false, null);
        property.put(property.type, codegen.v);
        StackValue.Field field = StackValue.field(property.type, JvmClassName.byClassDescriptor(descriptor),
                                                  propertyDescriptor.getName().asString(), true);
        field.store(field.type, codegen.v);
    }

    protected void genInitSingleton(ClassDescriptor fieldTypeDescriptor, StackValue.Field field) {
        if (state.getClassBuilderMode() == ClassBuilderMode.FULL) {
            ConstructorDescriptor constructorDescriptor = DescriptorUtils.getConstructorOfSingletonObject(fieldTypeDescriptor);
            ExpressionCodegen codegen = createOrGetClInitCodegen();
            FunctionDescriptor fd = codegen.accessableFunctionDescriptor(constructorDescriptor);
            generateMethodCallTo(fd, codegen.v);
            field.store(field.type, codegen.v);
        }
    }

    protected void generatePrimaryConstructor() {
        if (ignoreIfTraitOrAnnotation()) return;

        if (kind != OwnerKind.IMPLEMENTATION) {
            throw new IllegalStateException("incorrect kind for primary constructor: " + kind);
        }

        final MutableClosure closure = context.closure;
        ConstructorDescriptor constructorDescriptor = bindingContext.get(BindingContext.CONSTRUCTOR, myClass);

        ConstructorContext constructorContext = context.intoConstructor(constructorDescriptor);

        if (state.getClassBuilderMode() == ClassBuilderMode.FULL) {
            lookupConstructorExpressionsInClosureIfPresent(constructorContext);
        }

        final JvmMethodSignature constructorSignature = typeMapper.mapConstructorSignature(constructorDescriptor, closure);

        assert constructorDescriptor != null;

        functionCodegen.generateMethod(null, constructorSignature, true, constructorDescriptor, constructorContext,
                   new FunctionGenerationStrategy.CodegenBased<ConstructorDescriptor>(state, constructorDescriptor) {

                       @NotNull
                       @Override
                       protected FrameMap createFrameMap(@NotNull JetTypeMapper typeMapper, @NotNull MethodContext context) {
                           return new ConstructorFrameMap(constructorSignature);
                       }

                       @Override
                       public void doGenerateBody(ExpressionCodegen codegen, JvmMethodSignature signature) {
                           generatePrimaryConstructorImpl(callableDescriptor, codegen, closure);
                       }
                   }
        );

        FunctionCodegen.generateDefaultIfNeeded(constructorContext, state, v, constructorSignature, constructorDescriptor,
                                                OwnerKind.IMPLEMENTATION, DefaultParameterValueLoader.DEFAULT);

        CallableMethod callableMethod = typeMapper.mapToCallableMethod(constructorDescriptor, closure);
        FunctionCodegen.generateConstructorWithoutParametersIfNeeded(state, callableMethod, constructorDescriptor, v);

        if (isClassObject(descriptor)) {
            context.recordSyntheticAccessorIfNeeded(constructorDescriptor, typeMapper);
        }
    }

    private void generatePrimaryConstructorImpl(
            @Nullable ConstructorDescriptor constructorDescriptor,
            @NotNull ExpressionCodegen codegen,
            @Nullable MutableClosure closure
    ) {
        List<ValueParameterDescriptor> paramDescrs = constructorDescriptor != null
                                                     ? constructorDescriptor.getValueParameters()
                                                     : Collections.<ValueParameterDescriptor>emptyList();

        InstructionAdapter iv = codegen.v;

        JvmClassName classname = JvmClassName.byType(classAsmType);

        if (superCall == null) {
            genSimpleSuperCall(iv);
        }
        else if (superCall instanceof JetDelegatorToSuperClass) {
            genSuperCallToDelegatorToSuperClass(iv);
        }
        else {
            generateDelegatorToConstructorCall(iv, codegen, constructorDescriptor);
        }

        if (closure != null) {
            List<FieldInfo> argsFromClosure = ClosureCodegen.calculateConstructorParameters(typeMapper, closure, classAsmType);
            int k = 1;
            for (FieldInfo info : argsFromClosure) {
                k = AsmUtil.genAssignInstanceFieldFromParam(info, k, iv);
            }
        }

        int n = 0;
        for (JetDelegationSpecifier specifier : myClass.getDelegationSpecifiers()) {
            if (specifier == superCall) {
                continue;
            }

            if (specifier instanceof JetDelegatorByExpressionSpecifier) {
                genCallToDelegatorByExpressionSpecifier(iv, codegen, classAsmType, classname, n++, specifier);
            }
        }

        int curParam = 0;
        List<JetParameter> constructorParameters = getPrimaryConstructorParameters();
        for (JetParameter parameter : constructorParameters) {
            if (parameter.getValOrVarNode() != null) {
                VariableDescriptor descriptor = paramDescrs.get(curParam);
                Type type = typeMapper.mapType(descriptor);
                iv.load(0, classAsmType);
                iv.load(codegen.myFrameMap.getIndex(descriptor), type);
                iv.putfield(classAsmType.getInternalName(),
                            context.getFieldName(DescriptorUtils.getPropertyDescriptor(parameter, bindingContext)),
                            type.getDescriptor());
            }
            curParam++;
        }

        boolean generateInitializerInOuter = isClassObjectWithBackingFieldsInOuter(descriptor);
        if (generateInitializerInOuter) {
            ImplementationBodyCodegen parentCodegen = getParentBodyCodegen(this);
            //generate object$
            parentCodegen.genInitSingleton(descriptor, StackValue.singleton(descriptor, typeMapper));
            parentCodegen.generateInitializers(parentCodegen.createOrGetClInitCodegen(),
                                          myClass.getDeclarations(), bindingContext, state);
        } else {
            generateInitializers(codegen, myClass.getDeclarations(), bindingContext, state);
        }


        iv.visitInsn(RETURN);
    }

    private void genSuperCallToDelegatorToSuperClass(InstructionAdapter iv) {
        iv.load(0, superClassAsmType);
        JetType superType = bindingContext.get(BindingContext.TYPE, superCall.getTypeReference());
        List<Type> parameterTypes = new ArrayList<Type>();
        assert superType != null;
        ClassDescriptor superClassDescriptor = (ClassDescriptor) superType.getConstructor().getDeclarationDescriptor();
        if (CodegenBinding.hasThis0(bindingContext, superClassDescriptor)) {
            iv.load(1, OBJECT_TYPE);
            parameterTypes.add(typeMapper.mapType(
                    enclosingClassDescriptor(bindingContext, descriptor)));
        }
        Method superCallMethod = new Method("<init>", Type.VOID_TYPE, parameterTypes.toArray(new Type[parameterTypes.size()]));
        //noinspection ConstantConditions
        iv.invokespecial(typeMapper.mapType(superClassDescriptor).getInternalName(), "<init>",
                         superCallMethod.getDescriptor());
    }

    private void genSimpleSuperCall(InstructionAdapter iv) {
        iv.load(0, superClassAsmType);
        if (descriptor.getKind() == ClassKind.ENUM_CLASS || descriptor.getKind() == ClassKind.ENUM_ENTRY) {
            iv.load(1, JAVA_STRING_TYPE);
            iv.load(2, Type.INT_TYPE);
            iv.invokespecial(superClassAsmType.getInternalName(), "<init>", "(Ljava/lang/String;I)V");
        }
        else {
            iv.invokespecial(superClassAsmType.getInternalName(), "<init>", "()V");
        }
    }

    private void genCallToDelegatorByExpressionSpecifier(
            InstructionAdapter iv,
            ExpressionCodegen codegen,
            Type classType,
            JvmClassName classname,
            int n,
            JetDelegationSpecifier specifier
    ) {
        JetExpression expression = ((JetDelegatorByExpressionSpecifier) specifier).getDelegateExpression();
        PropertyDescriptor propertyDescriptor = null;
        if (expression instanceof JetSimpleNameExpression) {
            ResolvedCall<? extends CallableDescriptor> call = bindingContext.get(BindingContext.RESOLVED_CALL, expression);
            if (call != null) {
                CallableDescriptor callResultingDescriptor = call.getResultingDescriptor();
                if (callResultingDescriptor instanceof ValueParameterDescriptor) {
                    ValueParameterDescriptor valueParameterDescriptor = (ValueParameterDescriptor) callResultingDescriptor;
                    // constructor parameter
                    if (valueParameterDescriptor.getContainingDeclaration() instanceof ConstructorDescriptor) {
                        // constructor of my class
                        if (valueParameterDescriptor.getContainingDeclaration().getContainingDeclaration() == descriptor) {
                            propertyDescriptor = bindingContext.get(BindingContext.VALUE_PARAMETER_AS_PROPERTY, valueParameterDescriptor);
                        }
                    }
                }

                // todo: when and if frontend will allow properties defined not as constructor parameters to be used in delegation specifier
            }
        }

        JetType superType = bindingContext.get(BindingContext.TYPE, specifier.getTypeReference());
        assert superType != null;

        ClassDescriptor superClassDescriptor = (ClassDescriptor) superType.getConstructor().getDeclarationDescriptor();
        assert superClassDescriptor != null;

        Type superTypeAsmType = typeMapper.mapType(superType, JetTypeMapperMode.IMPL);

        StackValue field;
        if (propertyDescriptor != null &&
            !propertyDescriptor.isVar() &&
            Boolean.TRUE.equals(bindingContext.get(BindingContext.BACKING_FIELD_REQUIRED, propertyDescriptor))) {
            // final property with backing field
            field = StackValue.field(typeMapper.mapType(propertyDescriptor.getType()), classname,
                                     propertyDescriptor.getName().asString(), false);
        }
        else {
            iv.load(0, classType);
            codegen.genToJVMStack(expression);

            String delegateField = "$delegate_" + n;
            Type fieldType = typeMapper.mapType(superClassDescriptor);
            String fieldDesc = fieldType.getDescriptor();

            v.newField(specifier, ACC_PRIVATE|ACC_FINAL|ACC_SYNTHETIC, delegateField, fieldDesc, /*TODO*/null, null);

            field = StackValue.field(fieldType, classname, delegateField, false);
            field.store(fieldType, iv);
        }

        generateDelegates(superClassDescriptor, field);
    }

    private void lookupConstructorExpressionsInClosureIfPresent(final ConstructorContext constructorContext) {
        JetVisitorVoid visitor = new JetVisitorVoid() {
            @Override
            public void visitJetElement(JetElement e) {
                e.acceptChildren(this);
            }

            @Override
            public void visitSimpleNameExpression(JetSimpleNameExpression expr) {
                DeclarationDescriptor descriptor = bindingContext.get(BindingContext.REFERENCE_TARGET, expr);
                if (descriptor instanceof VariableDescriptor && !(descriptor instanceof PropertyDescriptor)) {
                    ConstructorDescriptor constructorDescriptor = (ConstructorDescriptor) constructorContext.getContextDescriptor();
                    for (ValueParameterDescriptor parameterDescriptor : constructorDescriptor.getValueParameters()) {
                        //noinspection ConstantConditions
                        if (descriptor.equals(parameterDescriptor)) {
                            return;
                        }
                    }
                    constructorContext.lookupInContext(descriptor, null, state, true);
                } else if (isLocalNamedFun(descriptor)) {
                    MutableClassDescriptor classDescriptor =
                            (MutableClassDescriptor) constructorContext.getParentContext().getContextDescriptor();

                    for (CallableMemberDescriptor memberDescriptor : classDescriptor.getAllCallableMembers()) {
                        if (descriptor.equals(memberDescriptor)) {
                            return;
                        }
                    }
                    constructorContext.lookupInContext(descriptor, null, state, true);
                }
            }

            @Override
            public void visitThisExpression(JetThisExpression expression) {
                DeclarationDescriptor descriptor = bindingContext.get(BindingContext.REFERENCE_TARGET, expression.getInstanceReference());
                if (descriptor instanceof ClassDescriptor) {
                    // @todo for now all our classes are inner so no need to lookup this. change it when we have real inners
                }
                else {
                    assert descriptor instanceof CallableDescriptor;
                    if (context.getCallableDescriptorWithReceiver() != descriptor) {
                        context.lookupInContext(descriptor, null, state, false);
                    }
                }
            }
        };

        for (JetDeclaration declaration : myClass.getDeclarations()) {
            if (declaration instanceof JetProperty) {
                JetProperty property = (JetProperty) declaration;
                JetExpression initializer = property.getInitializer();
                if (initializer != null) {
                    initializer.accept(visitor);
                }
            }
            else if (declaration instanceof JetClassInitializer) {
                JetClassInitializer initializer = (JetClassInitializer) declaration;
                initializer.accept(visitor);
            }
        }

        for (JetDelegationSpecifier specifier : myClass.getDelegationSpecifiers()) {
            if (specifier != superCall) {
                if (specifier instanceof JetDelegatorByExpressionSpecifier) {
                    JetExpression delegateExpression = ((JetDelegatorByExpressionSpecifier) specifier).getDelegateExpression();
                    assert delegateExpression != null;
                    delegateExpression.accept(visitor);
                }
            }
            else {
                if (superCall instanceof JetDelegatorToSuperCall) {
                    JetValueArgumentList argumentList = ((JetDelegatorToSuperCall) superCall).getValueArgumentList();
                    if (argumentList != null) {
                        argumentList.accept(visitor);
                    }
                }
            }
        }
    }

    private boolean ignoreIfTraitOrAnnotation() {
        if (myClass instanceof JetClass) {
            JetClass aClass = (JetClass) myClass;
            if (aClass.isTrait()) {
                return true;
            }
            if (aClass.isAnnotation()) {
                return true;
            }
        }
        return false;
    }

    private void generateTraitMethods() {
        if (JetPsiUtil.isTrait(myClass)) {
            return;
        }

        for (Pair<CallableMemberDescriptor, CallableMemberDescriptor> needDelegates : getTraitImplementations(descriptor)) {
            if (needDelegates.second instanceof SimpleFunctionDescriptor) {
                generateDelegationToTraitImpl((FunctionDescriptor) needDelegates.second, (FunctionDescriptor) needDelegates.first);
            }
            else if (needDelegates.second instanceof PropertyDescriptor) {
                PropertyDescriptor property = (PropertyDescriptor) needDelegates.second;
                List<PropertyAccessorDescriptor> inheritedAccessors = ((PropertyDescriptor) needDelegates.first).getAccessors();
                for (PropertyAccessorDescriptor accessor : property.getAccessors()) {
                    for (PropertyAccessorDescriptor inheritedAccessor : inheritedAccessors) {
                        if (inheritedAccessor.getClass() == accessor.getClass()) { // same accessor kind
                            generateDelegationToTraitImpl(accessor, inheritedAccessor);
                        }
                    }
                }
            }
        }
    }


    private void generateDelegationToTraitImpl(final @NotNull FunctionDescriptor fun, @NotNull FunctionDescriptor inheritedFun) {
        DeclarationDescriptor containingDeclaration = fun.getContainingDeclaration();
        if (!(containingDeclaration instanceof ClassDescriptor)) {
            return;
        }

        ClassDescriptor containingClass = (ClassDescriptor) containingDeclaration;
        if (containingClass.getKind() != ClassKind.TRAIT) {
            return;
        }

        int flags = ACC_PUBLIC; // TODO.

        TraitImplDelegateInfo delegateInfo = getTraitImplDelegateInfo(fun);
        Method methodToGenerate = delegateInfo.methodToGenerate;
        Method methodInTrait = delegateInfo.methodInTrait;

        PsiElement origin = descriptorToDeclaration(bindingContext, fun);
        MethodVisitor mv = v.newMethod(origin, flags, methodToGenerate.getName(), methodToGenerate.getDescriptor(), null, null);
        AnnotationCodegen.forMethod(mv, typeMapper).genAnnotations(fun);

        if (state.getClassBuilderMode() == ClassBuilderMode.STUBS) {
            genStubCode(mv);
        }
        else if (state.getClassBuilderMode() == ClassBuilderMode.FULL) {
            writeAnnotationForDelegateToTraitImpl(fun, inheritedFun, mv);

            Type returnType = methodToGenerate.getReturnType();

            mv.visitCode();
            FrameMap frameMap = context.prepareFrame(typeMapper);
            ExpressionCodegen codegen = new ExpressionCodegen(mv, frameMap, returnType, context.intoFunction(inheritedFun), state);
            codegen.generateThisOrOuter(descriptor, false);    // ??? wouldn't it be addClosureToConstructorParameters good idea to put it?

            Type[] argTypes = methodToGenerate.getArgumentTypes();
            Type[] originalArgTypes = methodInTrait.getArgumentTypes();

            InstructionAdapter iv = new InstructionAdapter(mv);
            iv.load(0, OBJECT_TYPE);
            for (int i = 0, reg = 1; i < argTypes.length; i++) {
                StackValue.local(reg, argTypes[i]).put(originalArgTypes[i], iv);
                //noinspection AssignmentToForLoopParameter
                reg += argTypes[i].getSize();
            }

            Type type = getTraitImplThisParameterType(containingClass, typeMapper);
            String functionDescriptor = methodInTrait.getDescriptor().replace("(", "(" + type.getDescriptor());

            Type tImplType = typeMapper.mapType(containingClass.getDefaultType(), JetTypeMapperMode.TRAIT_IMPL);

            iv.invokestatic(tImplType.getInternalName(), methodToGenerate.getName(), functionDescriptor);
            StackValue.onStack(methodInTrait.getReturnType()).put(returnType, iv);
            iv.areturn(returnType);

            FunctionCodegen.endVisit(iv, "trait method", callableDescriptorToDeclaration(bindingContext, fun));
        }

        FunctionCodegen.generateBridgeIfNeeded(context, state, v, methodToGenerate, fun);
    }

    private static class TraitImplDelegateInfo {
        private final Method methodToGenerate;
        private final Method methodInTrait;

        private TraitImplDelegateInfo(@NotNull Method methodToGenerate, @NotNull Method methodInTrait) {
            this.methodToGenerate = methodToGenerate;
            this.methodInTrait = methodInTrait;
        }
    }

    @NotNull
    private TraitImplDelegateInfo getTraitImplDelegateInfo(@NotNull FunctionDescriptor fun) {
        if (fun instanceof PropertyAccessorDescriptor) {
            PropertyDescriptor property = ((PropertyAccessorDescriptor) fun).getCorrespondingProperty();
            PropertyDescriptor original = property.getOriginal();
            if (fun instanceof PropertyGetterDescriptor) {
                JvmPropertyAccessorSignature toGenerate = typeMapper.mapGetterSignature(property, OwnerKind.IMPLEMENTATION);
                JvmPropertyAccessorSignature inTrait = typeMapper.mapGetterSignature(original, OwnerKind.IMPLEMENTATION);
                return new TraitImplDelegateInfo(
                        toGenerate.getAsmMethod(), inTrait.getAsmMethod());
            }
            else if (fun instanceof PropertySetterDescriptor) {
                JvmPropertyAccessorSignature toGenerate = typeMapper.mapSetterSignature(property, OwnerKind.IMPLEMENTATION);
                JvmPropertyAccessorSignature inTrait = typeMapper.mapSetterSignature(original, OwnerKind.IMPLEMENTATION);
                return new TraitImplDelegateInfo(
                        toGenerate.getAsmMethod(), inTrait.getAsmMethod());
            }
            else {
                throw new IllegalStateException("Accessor is neither getter, nor setter, what is it? " + fun);
            }
        }
        else {
            Method function = typeMapper.mapSignature(fun).getAsmMethod();
            Method functionOriginal = typeMapper.mapSignature(fun.getOriginal()).getAsmMethod();
            return new TraitImplDelegateInfo(function, functionOriginal);
        }
    }

    private void writeAnnotationForDelegateToTraitImpl(
            @NotNull FunctionDescriptor fun,
            @NotNull FunctionDescriptor inheritedFun,
            @NotNull MethodVisitor mv
    ) {
        JvmMethodSignature jvmSignature = typeMapper.mapSignature(inheritedFun, true, OwnerKind.IMPLEMENTATION);
        JetMethodAnnotationWriter aw = JetMethodAnnotationWriter.visitAnnotation(mv);
        int kotlinFlags = getFlagsForVisibility(fun.getVisibility());
        if (fun instanceof PropertyAccessorDescriptor) {
            kotlinFlags |= JvmStdlibNames.FLAG_PROPERTY_BIT;
            aw.writeTypeParameters(jvmSignature.getKotlinTypeParameter());
            aw.writePropertyType(jvmSignature.getKotlinReturnType());
        }
        else {
            aw.writeTypeParameters(jvmSignature.getKotlinTypeParameter());
            aw.writeReturnType(jvmSignature.getKotlinReturnType());
        }
        kotlinFlags |= DescriptorKindUtils.kindToFlags(inheritedFun.getKind());
        aw.writeFlags(kotlinFlags);
        aw.visitEnd();
    }

    private void generateDelegatorToConstructorCall(
            InstructionAdapter iv, ExpressionCodegen codegen,
            ConstructorDescriptor constructorDescriptor
    ) {
        ClassDescriptor classDecl = constructorDescriptor.getContainingDeclaration();

        iv.load(0, OBJECT_TYPE);

        if (classDecl.getKind() == ClassKind.ENUM_CLASS || classDecl.getKind() == ClassKind.ENUM_ENTRY) {
            iv.load(1, OBJECT_TYPE);
            iv.load(2, Type.INT_TYPE);
        }

        CallableMethod method = typeMapper.mapToCallableMethod(constructorDescriptor, context.closure);

        ResolvedCall<? extends CallableDescriptor> resolvedCall =
                bindingContext.get(BindingContext.RESOLVED_CALL, ((JetCallElement) superCall).getCalleeExpression());
        assert resolvedCall != null;
        ConstructorDescriptor superConstructor = (ConstructorDescriptor) resolvedCall.getResultingDescriptor();

        //noinspection SuspiciousMethodCalls
        CalculatedClosure closureForSuper = bindingContext.get(CLOSURE, superConstructor.getContainingDeclaration());
        CallableMethod superCallable = typeMapper.mapToCallableMethod(superConstructor, closureForSuper);

        if (closureForSuper != null && closureForSuper.getCaptureThis() != null) {
            iv.load(((ConstructorFrameMap)codegen.myFrameMap).getOuterThisIndex(), OBJECT_TYPE);
        }

        if (myClass instanceof JetObjectDeclaration &&
            superCall instanceof JetDelegatorToSuperCall &&
            ((JetObjectDeclaration) myClass).isObjectLiteral()) {
            int nextVar = findFirstSuperArgument(method);
            for (Type t : superCallable.getSignature().getAsmMethod().getArgumentTypes()) {
                iv.load(nextVar, t);
                nextVar += t.getSize();
            }
            superCallable.invokeWithNotNullAssertion(codegen.v, state, resolvedCall);
        }
        else {
            codegen.invokeMethodWithArguments(superCallable, resolvedCall, null, StackValue.none());
        }
    }

    private static int findFirstSuperArgument(CallableMethod method) {
        List<JvmMethodParameterSignature> types = method.getSignature().getKotlinParameterTypes();
        if (types != null) {
            int i = 0;
            for (JvmMethodParameterSignature type : types) {
                if (type.getKind() == JvmMethodParameterKind.SUPER_CALL_PARAM) {
                    return i + 1; // because of this
                }
                i += type.getAsmType().getSize();
            }
        }
        return -1;
    }

    @Override
    protected void generateDeclaration(PropertyCodegen propertyCodegen, JetDeclaration declaration) {
        if (declaration instanceof JetEnumEntry) {
            String name = declaration.getName();
            String desc = "L" + classAsmType.getInternalName() + ";";
            v.newField(declaration, ACC_PUBLIC | ACC_ENUM | ACC_STATIC | ACC_FINAL, name, desc, null, null);
            myEnumConstants.add((JetEnumEntry) declaration);
        }

        super.generateDeclaration(propertyCodegen, declaration);
    }

    private final List<JetEnumEntry> myEnumConstants = new ArrayList<JetEnumEntry>();

    private void initializeEnumConstants(ExpressionCodegen codegen) {
        InstructionAdapter iv = codegen.v;
        int ordinal = -1;
        JetType myType = descriptor.getDefaultType();
        Type myAsmType = typeMapper.mapType(myType, JetTypeMapperMode.IMPL);

        assert myEnumConstants.size() > 0;
        JetType arrayType = KotlinBuiltIns.getInstance().getArrayType(myType);
        Type arrayAsmType = typeMapper.mapType(arrayType, JetTypeMapperMode.IMPL);
        v.newField(myClass, ACC_PRIVATE | ACC_STATIC | ACC_FINAL | ACC_SYNTHETIC, "$VALUES", arrayAsmType.getDescriptor(), null, null);

        iv.iconst(myEnumConstants.size());
        iv.newarray(myAsmType);
        iv.dup();

        for (JetEnumEntry enumConstant : myEnumConstants) {
            ordinal++;

            iv.dup();
            iv.iconst(ordinal);

            ClassDescriptor classDescriptor = bindingContext.get(BindingContext.CLASS, enumConstant);
            assert classDescriptor != null;
            String implClass = typeMapper.mapType(classDescriptor.getDefaultType(), JetTypeMapperMode.IMPL).getInternalName();

            List<JetDelegationSpecifier> delegationSpecifiers = enumConstant.getDelegationSpecifiers();
            if (delegationSpecifiers.size() > 1) {
                throw new UnsupportedOperationException("multiple delegation specifiers for enum constant not supported");
            }

            iv.anew(Type.getObjectType(implClass));
            iv.dup();

            iv.aconst(enumConstant.getName());
            iv.iconst(ordinal);

            if (delegationSpecifiers.size() == 1 && !enumEntryNeedSubclass(state.getBindingContext(), enumConstant)) {
                JetDelegationSpecifier specifier = delegationSpecifiers.get(0);
                if (specifier instanceof JetDelegatorToSuperCall) {
                    ResolvedCall<? extends CallableDescriptor> resolvedCall =
                            bindingContext.get(BindingContext.RESOLVED_CALL, ((JetDelegatorToSuperCall) specifier).getCalleeExpression());
                    assert resolvedCall != null : "Enum entry delegation specifier is unresolved: " + specifier.getText();

                    ConstructorDescriptor constructorDescriptor = (ConstructorDescriptor) resolvedCall.getResultingDescriptor();
                    CallableMethod method = typeMapper.mapToCallableMethod(constructorDescriptor);

                    codegen.invokeMethodWithArguments(method, resolvedCall, null, StackValue.none());
                }
                else {
                    throw new UnsupportedOperationException("unsupported type of enum constant initializer: " + specifier);
                }
            }
            else {
                iv.invokespecial(implClass, "<init>", "(Ljava/lang/String;I)V");
            }
            iv.dup();
            iv.putstatic(myAsmType.getInternalName(), enumConstant.getName(), "L" + myAsmType.getInternalName() + ";");
            iv.astore(OBJECT_TYPE);
        }
        iv.putstatic(myAsmType.getInternalName(), "$VALUES", arrayAsmType.getDescriptor());
    }

    public static void generateInitializers(
            @NotNull ExpressionCodegen codegen, @NotNull List<JetDeclaration> declarations,
            @NotNull BindingContext bindingContext, @NotNull GenerationState state
    ) {
        JetTypeMapper typeMapper = state.getTypeMapper();
        for (JetDeclaration declaration : declarations) {
            if (declaration instanceof JetProperty) {
                if (shouldInitializeProperty((JetProperty) declaration, typeMapper)) {
                    initializeProperty(codegen, bindingContext, (JetProperty) declaration);
                }
            }
            else if (declaration instanceof JetClassInitializer) {
                codegen.gen(((JetClassInitializer) declaration).getBody(), Type.VOID_TYPE);
            }
        }
    }


    public static void initializeProperty(
            @NotNull ExpressionCodegen codegen,
            @NotNull BindingContext bindingContext,
            @NotNull JetProperty property
    ) {

        PropertyDescriptor propertyDescriptor = (PropertyDescriptor) bindingContext.get(BindingContext.VARIABLE, property);
        assert propertyDescriptor != null;

        JetExpression initializer = property.getDelegateExpressionOrInitializer();
        assert initializer != null : "shouldInitializeProperty must return false if initializer is null";

        JetType jetType = getPropertyOrDelegateType(bindingContext, property, propertyDescriptor);

        StackValue.StackValueWithSimpleReceiver propValue = codegen.intermediateValueForProperty(propertyDescriptor, true, null, MethodKind.INITIALIZER);

        if (!propValue.isStatic) {
            codegen.v.load(0, OBJECT_TYPE);
        }

        Type type = codegen.expressionType(initializer);
        if (jetType.isNullable()) {
            type = boxType(type);
        }
        codegen.gen(initializer, type);

        propValue.store(type, codegen.v);
    }

    public static boolean shouldInitializeProperty(
            @NotNull JetProperty property,
            @NotNull JetTypeMapper typeMapper
    ) {
        JetExpression initializer = property.getDelegateExpressionOrInitializer();
        if (initializer == null) return false;

        CompileTimeConstant<?> compileTimeValue = typeMapper.getBindingContext().get(BindingContext.COMPILE_TIME_VALUE, initializer);
        if (compileTimeValue == null) return true;

        PropertyDescriptor propertyDescriptor = (PropertyDescriptor) typeMapper.getBindingContext().get(BindingContext.VARIABLE, property);
        assert propertyDescriptor != null;

        Object value = compileTimeValue.getValue();
        JetType jetType = getPropertyOrDelegateType(typeMapper.getBindingContext(), property, propertyDescriptor);
        Type type = typeMapper.mapType(jetType);
        return !skipDefaultValue(propertyDescriptor, value, type);
    }

    @NotNull
    private static JetType getPropertyOrDelegateType(@NotNull BindingContext bindingContext, @NotNull JetProperty property, @NotNull PropertyDescriptor descriptor) {
        JetExpression delegateExpression = property.getDelegateExpression();
        if (delegateExpression != null) {
            JetType delegateType = bindingContext.get(BindingContext.EXPRESSION_TYPE, delegateExpression);
            assert delegateType != null : "Type of delegate expression should be recorded";
            return delegateType;
        }
        return descriptor.getType();
    }

    private static boolean skipDefaultValue(PropertyDescriptor propertyDescriptor, Object value, Type type) {
        if (isPrimitive(type)) {
            if (!propertyDescriptor.getType().isNullable() && value instanceof Number) {
                if (type == Type.INT_TYPE && ((Number) value).intValue() == 0) {
                    return true;
                }
                if (type == Type.BYTE_TYPE && ((Number) value).byteValue() == 0) {
                    return true;
                }
                if (type == Type.LONG_TYPE && ((Number) value).longValue() == 0L) {
                    return true;
                }
                if (type == Type.SHORT_TYPE && ((Number) value).shortValue() == 0) {
                    return true;
                }
                if (type == Type.DOUBLE_TYPE && ((Number) value).doubleValue() == 0d) {
                    return true;
                }
                if (type == Type.FLOAT_TYPE && ((Number) value).floatValue() == 0f) {
                    return true;
                }
            }
            if (type == Type.BOOLEAN_TYPE && value instanceof Boolean && !((Boolean) value)) {
                return true;
            }
            if (type == Type.CHAR_TYPE && value instanceof Character && ((Character) value) == 0) {
                return true;
            }
        }
        else {
            if (value == null) {
                return true;
            }
        }
        return false;
    }

    protected void generateDelegates(ClassDescriptor toClass, StackValue field) {
        for (DeclarationDescriptor declaration : descriptor.getDefaultType().getMemberScope().getAllDescriptors()) {
            if (declaration instanceof CallableMemberDescriptor) {
                CallableMemberDescriptor callableMemberDescriptor = (CallableMemberDescriptor) declaration;
                if (callableMemberDescriptor.getKind() == CallableMemberDescriptor.Kind.DELEGATION) {
                    Set<? extends CallableMemberDescriptor> overriddenDescriptors = callableMemberDescriptor.getOverriddenDescriptors();
                    for (CallableMemberDescriptor overriddenDescriptor : overriddenDescriptors) {
                        if (overriddenDescriptor.getContainingDeclaration() == toClass) {
                            if (declaration instanceof PropertyDescriptor) {
                                propertyCodegen
                                        .genDelegate((PropertyDescriptor) declaration, (PropertyDescriptor) overriddenDescriptor, field);
                            }
                            else if (declaration instanceof FunctionDescriptor) {
                                functionCodegen
                                        .genDelegate((FunctionDescriptor) declaration, (FunctionDescriptor) overriddenDescriptor, field);
                            }
                        }
                    }
                }
            }
        }
    }


    /**
     * Return pairs of descriptors. First is member of this that should be implemented by delegating to trait,
     * second is member of trait that contain implementation.
     */
    private List<Pair<CallableMemberDescriptor, CallableMemberDescriptor>> getTraitImplementations(@NotNull ClassDescriptor classDescriptor) {
        List<Pair<CallableMemberDescriptor, CallableMemberDescriptor>> r = Lists.newArrayList();

        root:
        for (DeclarationDescriptor decl : classDescriptor.getDefaultType().getMemberScope().getAllDescriptors()) {
            if (!(decl instanceof CallableMemberDescriptor)) {
                continue;
            }

            CallableMemberDescriptor callableMemberDescriptor = (CallableMemberDescriptor) decl;
            if (callableMemberDescriptor.getKind() != CallableMemberDescriptor.Kind.FAKE_OVERRIDE) {
                continue;
            }

            Collection<CallableMemberDescriptor> overriddenDeclarations =
                    OverridingUtil.getOverriddenDeclarations(callableMemberDescriptor);

            Collection<CallableMemberDescriptor> filteredOverriddenDeclarations =
                    OverridingUtil.filterOverrides(Sets.newLinkedHashSet(overriddenDeclarations));

            int count = 0;
            CallableMemberDescriptor candidate = null;

            for (CallableMemberDescriptor overriddenDeclaration : filteredOverriddenDeclarations) {
                if (isKindOf(overriddenDeclaration.getContainingDeclaration(), ClassKind.TRAIT) &&
                    overriddenDeclaration.getModality() != Modality.ABSTRACT) {
                    candidate = overriddenDeclaration;
                    count++;
                }
            }
            if (candidate == null) {
                continue;
            }

            assert count == 1 : "Ambiguous overriden declaration: " + callableMemberDescriptor.getName();


            Collection<JetType> superTypesOfSuperClass =
                    superClassType != null ? TypeUtils.getAllSupertypes(superClassType) : Collections.<JetType>emptySet();
            ReceiverParameterDescriptor expectedThisObject = candidate.getExpectedThisObject();
            assert expectedThisObject != null;
            JetType candidateType = expectedThisObject.getType();
            boolean implementedInSuperClass = superTypesOfSuperClass.contains(candidateType);

            if (!implementedInSuperClass) {
                r.add(Pair.create(callableMemberDescriptor, candidate));
            }
        }
        return r;
    }

    public void addClassObjectPropertyToCopy(PropertyDescriptor descriptor) {
        if (classObjectPropertiesToCopy == null) {
            classObjectPropertiesToCopy = new ArrayList<PropertyDescriptor>();
        }
        classObjectPropertiesToCopy.add(descriptor);
    }

}
