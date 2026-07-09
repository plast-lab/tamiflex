/*******************************************************************************
 * Opt-in modern-reflection transformation: the Java 9+ module-aware overload
 * java.lang.Class.forName(Module, String). The classic ClassForName transformation
 * only hooks forName(String)/forName(String,boolean,ClassLoader). On Java 8 the
 * method is absent, so this simply never matches (no-op).
 ******************************************************************************/
package de.bodden.tamiflex.playout.transformation.modern;

import java.util.Collections;
import java.util.Map;

import org.objectweb.asm.commons.Method;

public class ClassForNameModuleTransformation extends AbstractModernTransformation {

	private static final String SIG = "forName(Ljava/lang/Module;Ljava/lang/String;)Ljava/lang/Class;";

	public ClassForNameModuleTransformation() {
		super(Class.class, injections(),
			new Method("forName", "(Ljava/lang/Module;Ljava/lang/String;)Ljava/lang/Class;"));
	}

	private static Map<String, Inject> injections() {
		// static method: slot 0 = Module, slot 1 = String name. Reuse ReflLogger.classForName.
		return Collections.singletonMap(SIG,
			new Inject("classForName", "(Ljava/lang/String;)V", null, false, 1));
	}
}
