// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.psi.impl;

import com.intellij.codeInsight.AnnotationTargetUtil;
import com.intellij.psi.*;
import com.intellij.psi.augment.PsiAugmentProvider;
import com.intellij.psi.impl.light.LightMethod;
import com.intellij.psi.impl.light.LightRecordCanonicalConstructor;
import com.intellij.psi.impl.light.LightRecordField;
import com.intellij.psi.impl.light.LightRecordMethod;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import com.intellij.psi.util.JavaPsiRecordUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class RecordAugmentProvider extends PsiAugmentProvider {
  @NotNull
  @Override
  protected <Psi extends PsiElement> List<Psi> getAugments(@NotNull PsiElement element, @NotNull Class<Psi> type) {
    if (element instanceof PsiExtensibleClass) {
      PsiExtensibleClass aClass = (PsiExtensibleClass)element;
      if (!aClass.isRecord()) return Collections.emptyList();
      if (type == PsiMethod.class) {
        return getAccessorsAugments(element, aClass);
      }
      if (type == PsiField.class) {
        return getFieldAugments(element, aClass);
      }
    }
    return Collections.emptyList();
  }

  @NotNull
  private static <Psi extends PsiElement> List<Psi> getAccessorsAugments(@NotNull PsiElement element, PsiExtensibleClass aClass) {
    PsiRecordHeader header = aClass.getRecordHeader();
    if (header == null) return Collections.emptyList();
    PsiRecordComponent[] components = aClass.getRecordComponents();
    PsiElementFactory factory = JavaPsiFacade.getInstance(element.getProject()).getElementFactory();
    ArrayList<Psi> methods = new ArrayList<>(components.length);
    List<PsiMethod> ownMethods = aClass.getOwnMethods();
    for (PsiRecordComponent component : components) {
      if (!shouldGenerateMethod(component, ownMethods)) continue;
      PsiMethod recordMethod = createRecordMethod(component, factory);
      if (recordMethod == null) continue;
      LightMethod method = new LightRecordMethod(element.getManager(), recordMethod, aClass, component);
      //noinspection unchecked
      methods.add((Psi)method);
    }
    PsiMethod constructor = getCanonicalConstructor(aClass, ownMethods, header);
    if (constructor != null) {
      //noinspection unchecked
      methods.add((Psi)constructor);
    }
    return methods;
  }

  @Nullable
  private static PsiMethod getCanonicalConstructor(PsiExtensibleClass aClass,
                                                   List<PsiMethod> ownMethods,
                                                   @NotNull PsiRecordHeader recordHeader) {
    String className = aClass.getName();
    if (className == null) return null;
    for (PsiMethod method : ownMethods) {
      if (JavaPsiRecordUtil.isCompactConstructor(method) || JavaPsiRecordUtil.isExplicitCanonicalConstructor(method)) return null;
    }
    PsiElementFactory factory = JavaPsiFacade.getElementFactory(recordHeader.getProject());
    String sb = "public " + className + recordHeader.getText() + "{}";
    PsiMethod nonPhysical = factory.createMethodFromText(sb, recordHeader.getContainingClass());
    return new LightRecordCanonicalConstructor(nonPhysical, aClass);
  }

  private static boolean shouldGenerateMethod(PsiRecordComponent component, List<PsiMethod> ownMethods) {
    String componentName = component.getName();
    if (componentName == null) return false;
    for (PsiMethod method : ownMethods) {
      // Return type is not checked to avoid unnecessary warning about clashing signatures in case of different return types
      if (componentName.equals(method.getName())) return false;
    }
    return true;
  }

  @NotNull
  private static <Psi extends PsiElement> List<Psi> getFieldAugments(@NotNull PsiElement element, PsiClass aClass) {
    PsiRecordComponent[] components = aClass.getRecordComponents();
    PsiElementFactory factory = JavaPsiFacade.getInstance(element.getProject()).getElementFactory();
    ArrayList<Psi> fields = new ArrayList<>(components.length);
    for (PsiRecordComponent component : components) {
      PsiField recordField = createRecordField(component, factory);
      if (recordField == null) continue;
      LightRecordField field = new LightRecordField(element.getManager(), recordField, aClass, component);
      //noinspection unchecked
      fields.add((Psi)field);
    }
    return fields;
  }

  @Nullable
  private static PsiField createRecordField(@NotNull PsiRecordComponent component, @NotNull PsiElementFactory factory) {
    String name = component.getName();
    if (isForbiddenType(component)) return null;
    String typeText = getTypeText(component, RecordAugmentProvider::hasTargetApplicableForField);
    if (typeText == null) return null;
    return factory.createFieldFromText("private final " + typeText + " " + name + ";", component.getContainingClass());
  }

  @Nullable
  private static PsiMethod createRecordMethod(@NotNull PsiRecordComponent component, @NotNull PsiElementFactory factory) {
    String name = component.getName();
    if (name == null) return null;
    if (isForbiddenType(component)) return null;
    String typeText = getTypeText(component, RecordAugmentProvider::hasTargetApplicableForMethod);
    if (typeText == null) return null;
    return factory.createMethodFromText("public " + typeText + " " + name + "(){ return " + name + "; }", component.getContainingClass());
  }

  private static boolean isForbiddenType(@NotNull PsiRecordComponent component) {
    PsiTypeElement typeElement = component.getTypeElement();
    if (typeElement == null || typeElement.getText().equals(PsiKeyword.RECORD)) return true;
    return false;
  }

  @Nullable
  private static String getTypeText(@NotNull PsiRecordComponent component, Predicate<PsiAnnotation> annotationPredicate) {
    PsiType type = component.getType();
    if (type instanceof PsiEllipsisType) type = ((PsiEllipsisType)type).toArrayType();
    PsiTypeElement typeElement = component.getTypeElement();
    if (typeElement == null) return null;
    String annotations = Arrays.stream(component.getAnnotations())
        .filter(annotationPredicate)
        .map(annotation -> annotation.getText())
        .collect(Collectors.joining(" "));
    return annotations + " " + type.getCanonicalText();
  }

  private static boolean hasTargetApplicableForField(PsiAnnotation annotation) {
    return AnnotationTargetUtil.findAnnotationTarget(annotation, PsiAnnotation.TargetType.TYPE_USE, PsiAnnotation.TargetType.FIELD) != null;
  }

  private static boolean hasTargetApplicableForMethod(PsiAnnotation annotation) {
    return AnnotationTargetUtil.findAnnotationTarget(annotation, PsiAnnotation.TargetType.TYPE_USE, PsiAnnotation.TargetType.METHOD) != null;
  }
}
