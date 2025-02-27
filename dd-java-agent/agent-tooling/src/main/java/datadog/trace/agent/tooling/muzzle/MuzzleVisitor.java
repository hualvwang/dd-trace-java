package datadog.trace.agent.tooling.muzzle;

import static datadog.trace.util.Strings.getClassName;

import datadog.trace.agent.tooling.Instrumenter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;

/** Visit a class and add: a private instrumenationMuzzle field and getter */
public class MuzzleVisitor implements AsmVisitorWrapper {
  public static final String MUZZLE_FIELD_NAME = "instrumentationMuzzle";
  public static final String MUZZLE_METHOD_NAME = "getInstrumentationMuzzle";
  public static final String POST_PROCESS_REFERENCE_MATCHER_METHOD = "postProcessReferenceMatcher";

  private final File targetDir;

  public MuzzleVisitor(File targetDir) {
    this.targetDir = targetDir;
  }

  @Override
  public int mergeWriter(int flags) {
    return flags | ClassWriter.COMPUTE_MAXS;
  }

  @Override
  public int mergeReader(int flags) {
    return flags;
  }

  @Override
  public ClassVisitor wrap(
      TypeDescription instrumentedType,
      ClassVisitor classVisitor,
      Implementation.Context implementationContext,
      TypePool typePool,
      FieldList<FieldDescription.InDefinedShape> fields,
      MethodList<?> methods,
      int writerFlags,
      int readerFlags) {
    return new InsertSafetyMatcher(
        classVisitor,
        implementationContext.getClassFileVersion().isAtLeast(ClassFileVersion.JAVA_V6));
  }

  public class InsertSafetyMatcher extends ClassVisitor {
    private final boolean frames;

    private String instrumentationClassName;
    private Instrumenter.Default instrumenter;

    public InsertSafetyMatcher(ClassVisitor classVisitor, boolean frames) {
      super(Opcodes.ASM7, classVisitor);
      this.frames = frames;
    }

    @Override
    public void visit(
        final int version,
        final int access,
        final String name,
        final String signature,
        final String superName,
        final String[] interfaces) {
      this.instrumentationClassName = name;
      try {
        instrumenter =
            (Instrumenter.Default)
                MuzzleVisitor.class
                    .getClassLoader()
                    .loadClass(getClassName(instrumentationClassName))
                    .getDeclaredConstructor()
                    .newInstance();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(
        final int access,
        final String name,
        final String descriptor,
        final String signature,
        final String[] exceptions) {
      if (MUZZLE_METHOD_NAME.equals(name)) {
        // muzzle getter has been generated by previous compilation, ignore and replace in visitEnd
        return null;
      }
      return super.visitMethod(access, name, descriptor, signature, exceptions);
    }

    @Override
    public void visitEnd() {
      File muzzleClass = new File(targetDir, instrumentationClassName + "$Muzzle.class");
      try {
        muzzleClass.getParentFile().mkdirs();
        Files.write(muzzleClass.toPath(), generateMuzzleClass());
      } catch (IOException e) {
        throw new RuntimeException(e);
      }

      MethodVisitor mv =
          super.visitMethod(
              Opcodes.ACC_PROTECTED,
              MUZZLE_METHOD_NAME,
              "()" + Type.getDescriptor(IReferenceMatcher.class),
              null,
              null);

      mv.visitCode();

      mv.visitFieldInsn(
          Opcodes.GETSTATIC,
          instrumentationClassName + "$Muzzle",
          MUZZLE_FIELD_NAME,
          Type.getDescriptor(ReferenceMatcher.class));

      boolean hasPostProcessReferenceMatcher = false;
      try {
        instrumenter
            .getClass()
            .getDeclaredMethod(POST_PROCESS_REFERENCE_MATCHER_METHOD, ReferenceMatcher.class);
        hasPostProcessReferenceMatcher = true;
      } catch (NoSuchMethodException e) {
      }

      if (hasPostProcessReferenceMatcher) {
        mv.visitIntInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.SWAP);
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            instrumentationClassName,
            POST_PROCESS_REFERENCE_MATCHER_METHOD,
            "("
                + Type.getDescriptor(ReferenceMatcher.class)
                + ")"
                + Type.getDescriptor(IReferenceMatcher.class),
            false);
      }

      mv.visitInsn(Opcodes.ARETURN);
      mv.visitMaxs(0, 0);
      mv.visitEnd();

      super.visitEnd();
    }

    private Reference[] generateReferences() {
      // track sources we've generated references from to avoid recursion
      final Set<String> referenceSources = new HashSet<>();
      final Map<String, Reference> references = new LinkedHashMap<>();
      final Set<String> adviceClasses = new HashSet<>();
      instrumenter.adviceTransformations(
          new Instrumenter.AdviceTransformation() {
            @Override
            public void applyAdvice(
                ElementMatcher<? super MethodDescription> matcher, String name) {
              adviceClasses.add(name);
            }
          });
      for (String adviceClass : adviceClasses) {
        if (referenceSources.add(adviceClass)) {
          for (Map.Entry<String, Reference> entry :
              ReferenceCreator.createReferencesFrom(
                      adviceClass, ReferenceMatcher.class.getClassLoader())
                  .entrySet()) {
            Reference toMerge = references.get(entry.getKey());
            if (null == toMerge) {
              references.put(entry.getKey(), entry.getValue());
            } else {
              references.put(entry.getKey(), toMerge.merge(entry.getValue()));
            }
          }
        }
      }
      return references.values().toArray(new Reference[0]);
    }

    /**
     * This code is generated in a separate side-class to take advantage of
     * initialization-on-demand:
     *
     * <pre>
     * static final ReferenceMatcher instrumentationMuzzle = new ReferenceMatcher(
     *     new String[] {
     *       // helper class names
     *     },
     *     new Reference[] {
     *       // reference builders
     *     });
     * </pre>
     */
    private byte[] generateMuzzleClass() {
      String muzzleClassName = instrumentationClassName + "$Muzzle";

      ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
      cw.visit(Opcodes.V1_7, Opcodes.ACC_ABSTRACT, muzzleClassName, null, "java/lang/Object", null);

      cw.visitField(
          Opcodes.ACC_STATIC + Opcodes.ACC_FINAL,
          MUZZLE_FIELD_NAME,
          Type.getDescriptor(ReferenceMatcher.class),
          null,
          null);

      try {
        final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);

        mv.visitCode();

        mv.visitTypeInsn(Opcodes.NEW, "datadog/trace/agent/tooling/muzzle/ReferenceMatcher");
        mv.visitInsn(Opcodes.DUP);

        writeStrings(mv, instrumenter.muzzleIgnoredClassNames());

        Reference[] references = generateReferences();
        mv.visitLdcInsn(references.length);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "datadog/trace/agent/tooling/muzzle/Reference");

        int i = 0;
        for (Reference reference : references) {
          mv.visitInsn(Opcodes.DUP);
          mv.visitLdcInsn(i++);

          mv.visitTypeInsn(Opcodes.NEW, "datadog/trace/agent/tooling/muzzle/Reference");
          mv.visitInsn(Opcodes.DUP);

          writeStrings(mv, reference.sources);
          mv.visitLdcInsn(reference.flags);
          mv.visitLdcInsn(reference.className);
          if (null != reference.superName) {
            mv.visitLdcInsn(reference.superName);
          } else {
            mv.visitInsn(Opcodes.ACONST_NULL);
          }
          writeStrings(mv, reference.interfaces);
          writeFields(mv, reference.fields);
          writeMethods(mv, reference.methods);

          mv.visitMethodInsn(
              Opcodes.INVOKESPECIAL,
              "datadog/trace/agent/tooling/muzzle/Reference",
              "<init>",
              "([Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;[Ljava/lang/String;"
                  + "[Ldatadog/trace/agent/tooling/muzzle/Reference$Field;"
                  + "[Ldatadog/trace/agent/tooling/muzzle/Reference$Method;)V",
              false);

          mv.visitInsn(Opcodes.AASTORE);
        }

        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "datadog/trace/agent/tooling/muzzle/ReferenceMatcher",
            "<init>",
            "([Ljava/lang/String;[Ldatadog/trace/agent/tooling/muzzle/Reference;)V",
            false);

        mv.visitFieldInsn(
            Opcodes.PUTSTATIC,
            muzzleClassName,
            MUZZLE_FIELD_NAME,
            Type.getDescriptor(ReferenceMatcher.class));

        mv.visitInsn(Opcodes.RETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }

      return cw.toByteArray();
    }

    private void writeStrings(MethodVisitor mv, String[] strings) {
      mv.visitLdcInsn(strings.length);
      mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String");
      int i = 0;
      for (String string : strings) {
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(i++);
        mv.visitLdcInsn(string);
        mv.visitInsn(Opcodes.AASTORE);
      }
    }

    private void writeFields(MethodVisitor mv, Reference.Field[] fields) {
      mv.visitLdcInsn(fields.length);
      mv.visitTypeInsn(Opcodes.ANEWARRAY, "datadog/trace/agent/tooling/muzzle/Reference$Field");
      int i = 0;
      for (Reference.Field field : fields) {
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(i++);
        mv.visitTypeInsn(Opcodes.NEW, "datadog/trace/agent/tooling/muzzle/Reference$Field");
        mv.visitInsn(Opcodes.DUP);
        writeStrings(mv, field.sources);
        mv.visitLdcInsn(field.flags);
        mv.visitLdcInsn(field.name);
        mv.visitLdcInsn(field.fieldType);
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "datadog/trace/agent/tooling/muzzle/Reference$Field",
            "<init>",
            "([Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;)V",
            false);
        mv.visitInsn(Opcodes.AASTORE);
      }
    }

    private void writeMethods(MethodVisitor mv, Reference.Method[] methods) {
      mv.visitLdcInsn(methods.length);
      mv.visitTypeInsn(Opcodes.ANEWARRAY, "datadog/trace/agent/tooling/muzzle/Reference$Method");
      int i = 0;
      for (Reference.Method method : methods) {
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(i++);
        mv.visitTypeInsn(Opcodes.NEW, "datadog/trace/agent/tooling/muzzle/Reference$Method");
        mv.visitInsn(Opcodes.DUP);
        writeStrings(mv, method.sources);
        mv.visitLdcInsn(method.flags);
        mv.visitLdcInsn(method.name);
        mv.visitLdcInsn(method.methodType);
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "datadog/trace/agent/tooling/muzzle/Reference$Method",
            "<init>",
            "([Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;)V",
            false);
        mv.visitInsn(Opcodes.AASTORE);
      }
    }
  }
}
