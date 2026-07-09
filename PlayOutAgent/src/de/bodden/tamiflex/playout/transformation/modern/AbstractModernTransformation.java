/*******************************************************************************
 * Opt-in "modern reflection" transformations for the TamiFlex Play-Out Agent.
 * Enabled only when -Dtamiflex.modernReflection=true (or modernReflection=true in
 * poa.properties); the default transformation set is unchanged.
 ******************************************************************************/
package de.bodden.tamiflex.playout.transformation.modern;

import static org.objectweb.asm.Opcodes.ACONST_NULL;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.RETURN;

import java.util.Map;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Method;

import de.bodden.tamiflex.playout.transformation.AbstractTransformation;

/**
 * Base for the modern-reflection transformations. Like the classic transformations it
 * rewrites specific JDK methods, but a single instance can hook several methods of the
 * same class, each with its own logging call. At every RETURN of a hooked method it
 * injects a straight-line INVOKESTATIC into
 * {@code de.bodden.tamiflex.playout.rt.ReflLogger} (no branches, so the existing
 * StackMapTable stays valid — see ReflectionMonitor).
 */
public abstract class AbstractModernTransformation extends AbstractTransformation {

	protected static final String LOGGER = "de/bodden/tamiflex/playout/rt/ReflLogger";
	protected static final String KIND = "de/bodden/tamiflex/playout/rt/Kind";
	protected static final String KIND_DESC = "Lde/bodden/tamiflex/playout/rt/Kind;";

	/** How to log one hooked method. */
	protected static final class Inject {
		final String loggerMethod, loggerDesc, kind;
		final int[] slots;
		final boolean pushNull;
		/**
		 * @param loggerMethod ReflLogger static method to call
		 * @param loggerDesc   its descriptor
		 * @param kind         Kind enum constant name to GETSTATIC after the args, or null
		 * @param pushNull     push ACONST_NULL before the args (for a null receiver arg)
		 * @param slots        local-variable slots (this=0) to ALOAD as arguments
		 */
		public Inject(String loggerMethod, String loggerDesc, String kind, boolean pushNull, int... slots) {
			this.loggerMethod = loggerMethod; this.loggerDesc = loggerDesc; this.kind = kind;
			this.pushNull = pushNull; this.slots = slots;
		}
	}

	private final Map<String, Inject> injections; // key = methodName + methodDescriptor

	protected AbstractModernTransformation(Class<?> affected, Map<String, Inject> injections, Method... methods) {
		super(affected, methods);
		this.injections = injections;
	}

	/** Unused: this class overrides getClassVisitor for per-method dispatch. */
	@Override
	protected MethodVisitor getMethodVisitor(MethodVisitor parent) {
		return parent;
	}

	@Override
	public ClassVisitor getClassVisitor(String name, ClassVisitor parent) {
		if (!name.equals(Type.getInternalName(getAffectedClass())))
			return parent;
		return new ClassVisitor(Opcodes.ASM9, parent) {
			@Override
			public MethodVisitor visitMethod(int access, String mname, String desc, String sig, String[] exc) {
				MethodVisitor parentMv = super.visitMethod(access, mname, desc, sig, exc);
				final Inject inj = injections.get(mname + desc);
				if (inj == null) return parentMv;
				return new MethodVisitor(Opcodes.ASM9, parentMv) {
					@Override
					public void visitInsn(int opcode) {
						if (IRETURN <= opcode && opcode <= RETURN) {
							if (inj.pushNull) mv.visitInsn(ACONST_NULL);
							for (int slot : inj.slots) mv.visitVarInsn(ALOAD, slot);
							if (inj.kind != null) mv.visitFieldInsn(GETSTATIC, KIND, inj.kind, KIND_DESC);
							mv.visitMethodInsn(INVOKESTATIC, LOGGER, inj.loggerMethod, inj.loggerDesc, false);
						}
						super.visitInsn(opcode);
					}
				};
			}
		};
	}

	/** Helper: build the Method[] (for AbstractTransformation/verbose) from injection keys. */
	protected static Method[] methodsOf(Map<String, Inject> injections) {
		java.util.List<Method> l = new java.util.ArrayList<Method>();
		for (String key : injections.keySet()) {
			int p = key.indexOf('(');
			l.add(new Method(key.substring(0, p), key.substring(p)));
		}
		return l.toArray(new Method[0]);
	}
}
