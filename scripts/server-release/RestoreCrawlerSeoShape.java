import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import jdk.internal.org.objectweb.asm.*;
import jdk.internal.org.objectweb.asm.tree.*;

/**
 * Reproduces one inert synthetic-method detail retained by the 2026-10-22
 * production patcher. The readable crawler source already has every current
 * production method body. This does not replace or modify business logic.
 *
 * The historical patch left lambda$reconcileBook$17 unused and installed an
 * identical mapper as lambda$reconcileBook$22. Plain javac emits one mapper.
 * Both input and output whole-class canonical identities are pinned, so this
 * tool refuses an edited source class rather than silently reverting it.
 */
public class RestoreCrawlerSeoShape {
  static final String OWNER = "com/java2nb/novel/seo/SeoRepository";
  static final String MAPPER_DESC = "(Ljava/sql/ResultSet;I)Ljava/lang/Boolean;";
  static final String BEFORE = "b04b0a183a7913b5af4808e219a28d195f6b3a9f9b35665ded4ca7ac43b5ce3f";
  static final String AFTER = "f9cf7bb967208826d4af38e99703d2dccf9114fea3834b676d5ac2a8d6dc28ff";

  static String canonicalHash(byte[] raw) throws Exception {
    var node = new ClassNode();
    new ClassReader(raw).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    node.methods.sort(Comparator.comparing(m -> m.name + m.desc));
    node.fields.sort(Comparator.comparing(f -> f.name + f.desc));
    node.innerClasses.sort(Comparator.comparing(c -> c.name));
    if (node.nestMembers != null) Collections.sort(node.nestMembers);
    var writer = new ClassWriter(0);
    node.accept(writer);
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(writer.toByteArray()));
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("Expected input.class output.class");
    byte[] raw = Files.readAllBytes(Path.of(args[0]));
    String inputHash = canonicalHash(raw);
    byte[] result = raw;
    if (!AFTER.equals(inputHash)) {
      if (!BEFORE.equals(inputHash)) throw new IllegalArgumentException("Crawler source class differs from the reviewed production-source recovery: " + inputHash);
      var node = new ClassNode();
      new ClassReader(raw).accept(node, 0);
      if (!OWNER.equals(node.name) || node.methods.size() != 79) throw new IllegalArgumentException("Unexpected crawler class shape");
      var mapper = node.methods.stream().filter(m -> m.name.equals("lambda$reconcileBook$17") && m.desc.equals(MAPPER_DESC)).findFirst().orElseThrow();
      int flags = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
      if (mapper.access != flags) throw new IllegalArgumentException("Unexpected mapper flags");
      var copy = new MethodNode(mapper.access, "lambda$reconcileBook$22", mapper.desc, mapper.signature, mapper.exceptions.toArray(String[]::new));
      mapper.accept(copy);
      node.methods.add(copy);
      var method = node.methods.stream().filter(m -> m.name.equals("reconcileBook") && m.desc.equals("(J)Z")).findFirst().orElseThrow();
      int rewrites = 0;
      for (var instruction : method.instructions) {
        if (!(instruction instanceof InvokeDynamicInsnNode dynamic)) continue;
        for (int i = 0; i < dynamic.bsmArgs.length; i++) {
          if (!(dynamic.bsmArgs[i] instanceof Handle handle)) continue;
          if (handle.getOwner().equals(OWNER) && handle.getName().equals(mapper.name) && handle.getDesc().equals(MAPPER_DESC)) {
            dynamic.bsmArgs[i] = new Handle(handle.getTag(), handle.getOwner(), copy.name, handle.getDesc(), handle.isInterface());
            rewrites++;
          }
        }
      }
      if (rewrites != 1) throw new IllegalArgumentException("Unexpected mapper reference count: " + rewrites);
      var writer = new ClassWriter(new ClassReader(raw), 0);
      node.accept(writer);
      result = writer.toByteArray();
    }
    String outputHash = canonicalHash(result);
    if (!AFTER.equals(outputHash)) throw new IllegalArgumentException("Recovered class does not match the actual production class: " + outputHash);
    Path destination = Path.of(args[1]);
    if (destination.getParent() != null) Files.createDirectories(destination.getParent());
    Files.write(destination, result);
    System.out.println("{\"class\":\"" + OWNER + "\",\"canonicalSha256\":\"" + outputHash + "\",\"productionEquivalent\":true}");
  }
}
