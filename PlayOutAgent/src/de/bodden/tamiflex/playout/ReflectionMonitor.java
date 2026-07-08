/*******************************************************************************
 * Copyright (c) 2010 Eric Bodden.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Eric Bodden - initial API and implementation
 *     Andreas Sewe - coverage of array creation and reflective field accesses
 ******************************************************************************/
package de.bodden.tamiflex.playout;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.Method;

import de.bodden.tamiflex.normalizer.NameExtractor;
import de.bodden.tamiflex.playout.transformation.AbstractTransformation;

public class ReflectionMonitor implements ClassFileTransformer {

	private List<AbstractTransformation> transformations = new LinkedList<AbstractTransformation>();

	// Internal names of the (few) JDK classes we actually rewrite. transform() bails out
	// for everything else, so that loading an unrelated class (e.g. a java.util iterator)
	// while this transformer is active can never re-enter our ASM pipeline and trigger a
	// ClassCircularityError.
	private final java.util.Set<String> affectedInternalNames = new java.util.HashSet<String>();

	public ReflectionMonitor(String instruments, boolean verbose) {
		List<String> split = new ArrayList<String>(Arrays.asList(instruments.split("[ ]+")));
		Collections.sort(split);
		if(verbose) {
			System.out.println("\nActive instruments:");
		}
		for (String className : split) {
			className = className.trim();
			if(className.isEmpty()) continue;
			try {				
				@SuppressWarnings("unchecked")
				Class<AbstractTransformation> c = (Class<AbstractTransformation>) Class.forName(className);
				AbstractTransformation transform = c.newInstance();
				transformations.add(transform);
				affectedInternalNames.add(transform.getAffectedClass().getName().replace('.', '/'));
				if(verbose) {
					System.out.print(className);
					System.out.println(": ");
					for(Method m: transform.getAffectedMethods()) {
						System.out.print("    ");
						System.out.print(transform.getAffectedClass().getName()+"."+m.getName()+m.getDescriptor()+"\n");
					}
				}
			} catch (Throwable e) {
				// Skip a single unusable instrument (e.g. one that targets a reflection
				// method absent on this JDK) rather than aborting the whole agent.
				System.err.println("TamiFlex: skipping instrument "+className+": "+e);
			}
		}
	}

	public List<Class<?>> getAffectedClasses() {
		List<Class<?>> affectedClasses = new ArrayList<Class<?>>();
		
		for (AbstractTransformation transformation : transformations)
			affectedClasses.add(transformation.getAffectedClass());
		
		return affectedClasses;
	}
	
	public byte[] transform(ClassLoader loader, String className,
			Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
			byte[] classfileBuffer) throws IllegalClassFormatException {
		if(className==null)
			className = NameExtractor.extractName(classfileBuffer);

		// Only the hooked JDK reflection classes need rewriting; skip everything else
		// untouched (both cheaper and reentrancy-safe).
		if(!affectedInternalNames.contains(className))
			return null;

		try {
			final ClassReader creader = new ClassReader(classfileBuffer);
			final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			ClassVisitor visitor = writer;
			
			for (AbstractTransformation transformation : transformations)
				visitor = transformation.getClassVisitor(className, visitor);
			
			// Do NOT skip frames: we only insert straight-line INVOKESTATIC calls before
			// existing returns, so the original StackMapTable stays valid and is copied
			// through unchanged. Dropping it would produce classfiles that fail
			// verification on major version >= 50 (Java 6+). COMPUTE_MAXS still fixes up
			// the (larger) operand stack; we avoid COMPUTE_FRAMES because its
			// getCommonSuperClass() loads classes and would deadlock while we are busy
			// instrumenting java.base itself.
			creader.accept(visitor, ClassReader.SKIP_DEBUG);
			return writer.toByteArray();
		} catch (IllegalStateException e) {
			throw new IllegalClassFormatException("Error: " + e.getMessage() + " on class " + className);
		} catch(RuntimeException e) {
			e.printStackTrace();
			throw e;
		}
	}
	
}
