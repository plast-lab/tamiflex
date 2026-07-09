/*******************************************************************************
 * Opt-in modern-reflection transformation: java.lang.invoke.MethodHandles$Lookup.
 * Captures the RESOLUTION of MethodHandles/VarHandles (the find and unreflect families),
 * which name their target, the same way TamiFlex logs Class.getMethod. The opaque
 * MethodHandle.invoke site needs no instrumentation once the lookup is recorded.
 ******************************************************************************/
package de.bodden.tamiflex.playout.transformation.modern;

import java.util.LinkedHashMap;
import java.util.Map;

import org.objectweb.asm.commons.Method;

public class LookupTransformation extends AbstractModernTransformation {

	public LookupTransformation() throws ClassNotFoundException {
		super(Class.forName("java.lang.invoke.MethodHandles$Lookup"), injections(), methodsOf(injections()));
	}

	private static Map<String, Inject> injections() {
		// ReflLogger targets
		final String mhMethod = "methodHandleMethod",
			mhMethodDesc = "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;Lde/bodden/tamiflex/playout/rt/Kind;)V";
		final String mhCtor = "methodHandleConstructor",
			mhCtorDesc = "(Ljava/lang/Class;Ljava/lang/invoke/MethodType;Lde/bodden/tamiflex/playout/rt/Kind;)V";
		final String mhField = "methodHandleField",
			mhFieldDesc = "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;Lde/bodden/tamiflex/playout/rt/Kind;)V";
		final String mInvoke = "methodMethodInvoke",
			mInvokeDesc = "(Ljava/lang/Object;Ljava/lang/reflect/Method;Lde/bodden/tamiflex/playout/rt/Kind;)V";
		final String cInvoke = "constructorMethodInvoke",
			cInvokeDesc = "(Ljava/lang/reflect/Constructor;Lde/bodden/tamiflex/playout/rt/Kind;)V";
		final String fInvoke = "fieldMethodInvoke",
			fInvokeDesc = "(Ljava/lang/reflect/Field;Lde/bodden/tamiflex/playout/rt/Kind;)V";

		Map<String, Inject> m = new LinkedHashMap<String, Inject>();
		// ---- method lookups: (Class refc, String name, MethodType type) -> slots 1,2,3 ----
		m.put("findVirtual(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;",
			new Inject(mhMethod, mhMethodDesc, "FindVirtual", false, 1, 2, 3));
		m.put("findStatic(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;",
			new Inject(mhMethod, mhMethodDesc, "FindStatic", false, 1, 2, 3));
		m.put("findSpecial(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;",
			new Inject(mhMethod, mhMethodDesc, "FindSpecial", false, 1, 2, 3));
		// ---- constructor lookup: (Class refc, MethodType type) -> slots 1,2 ----
		m.put("findConstructor(Ljava/lang/Class;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;",
			new Inject(mhCtor, mhCtorDesc, "FindConstructor", false, 1, 2));
		// ---- field / VarHandle lookups: (Class refc, String name, Class type) -> slots 1,2,3 ----
		m.put("findGetter(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;",
			new Inject(mhField, mhFieldDesc, "FindGetter", false, 1, 2, 3));
		m.put("findSetter(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;",
			new Inject(mhField, mhFieldDesc, "FindSetter", false, 1, 2, 3));
		m.put("findStaticGetter(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;",
			new Inject(mhField, mhFieldDesc, "FindStaticGetter", false, 1, 2, 3));
		m.put("findStaticSetter(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;",
			new Inject(mhField, mhFieldDesc, "FindStaticSetter", false, 1, 2, 3));
		m.put("findVarHandle(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;",
			new Inject(mhField, mhFieldDesc, "FindVarHandle", false, 1, 2, 3));
		m.put("findStaticVarHandle(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;",
			new Inject(mhField, mhFieldDesc, "FindStaticVarHandle", false, 1, 2, 3));
		// ---- unreflect*: reuse the classic Method/Constructor/Field logging on slot 1 ----
		m.put("unreflect(Ljava/lang/reflect/Method;)Ljava/lang/invoke/MethodHandle;",
			new Inject(mInvoke, mInvokeDesc, "Unreflect", true, 1));         // null receiver
		m.put("unreflectSpecial(Ljava/lang/reflect/Method;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;",
			new Inject(mInvoke, mInvokeDesc, "UnreflectSpecial", true, 1));  // null receiver
		m.put("unreflectConstructor(Ljava/lang/reflect/Constructor;)Ljava/lang/invoke/MethodHandle;",
			new Inject(cInvoke, cInvokeDesc, "UnreflectConstructor", false, 1));
		m.put("unreflectGetter(Ljava/lang/reflect/Field;)Ljava/lang/invoke/MethodHandle;",
			new Inject(fInvoke, fInvokeDesc, "UnreflectGetter", false, 1));
		m.put("unreflectSetter(Ljava/lang/reflect/Field;)Ljava/lang/invoke/MethodHandle;",
			new Inject(fInvoke, fInvokeDesc, "UnreflectSetter", false, 1));
		return m;
	}
}
