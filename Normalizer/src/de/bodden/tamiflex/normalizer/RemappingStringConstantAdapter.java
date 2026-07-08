/*******************************************************************************
 * Copyright (c) 2010 Eric Bodden.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Eric Bodden - initial API and implementation
 ******************************************************************************/
package de.bodden.tamiflex.normalizer;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * A {@link MethodVisitor} that calls the provided {@link StringRemapper} to re-map
 * string constants.
 */
public class RemappingStringConstantAdapter extends MethodVisitor  {

	protected final StringRemapper rm;

	public RemappingStringConstantAdapter(MethodVisitor mv, StringRemapper rm) {
		super(Opcodes.ASM9, mv);
		this.rm = rm;
	}
	
	@Override
	public void visitLdcInsn(Object cst) {
		if(cst instanceof String) {
			cst = rm.remapStringConstant((String) cst);
		}
		super.visitLdcInsn(cst);
	}

}
