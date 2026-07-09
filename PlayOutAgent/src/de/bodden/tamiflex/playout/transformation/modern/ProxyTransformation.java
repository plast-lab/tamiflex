/*******************************************************************************
 * Opt-in modern-reflection transformation: java.lang.reflect.Proxy.newProxyInstance.
 * Logs each proxied interface (calls on it are dispatched to the InvocationHandler),
 * which the classic transformation set does not record.
 ******************************************************************************/
package de.bodden.tamiflex.playout.transformation.modern;

import java.util.Collections;
import java.util.Map;

import org.objectweb.asm.commons.Method;

public class ProxyTransformation extends AbstractModernTransformation {

	private static final String SIG =
		"newProxyInstance(Ljava/lang/ClassLoader;[Ljava/lang/Class;Ljava/lang/reflect/InvocationHandler;)Ljava/lang/Object;";

	public ProxyTransformation() {
		super(java.lang.reflect.Proxy.class, injections(),
			new Method("newProxyInstance",
				"(Ljava/lang/ClassLoader;[Ljava/lang/Class;Ljava/lang/reflect/InvocationHandler;)Ljava/lang/Object;"));
	}

	private static Map<String, Inject> injections() {
		// static method: slot 1 = the Class[] interfaces array
		return Collections.singletonMap(SIG,
			new Inject("proxyNewProxyInstance", "([Ljava/lang/Class;)V", null, false, 1));
	}
}
