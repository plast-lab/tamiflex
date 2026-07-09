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
package de.bodden.tamiflex.playout.rt;
import static de.bodden.tamiflex.playout.rt.ShutdownStatus.hasShutDown;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ReflLogger {
	
	//holds hashed names
	protected static Map<PersistedLogEntry,PersistedLogEntry> oldContainerMethodToEntries = new HashMap<PersistedLogEntry,PersistedLogEntry>();

	//holds actual names
	protected static Map<String,Map<RuntimeLogEntry,RuntimeLogEntry>> containerMethodToEntries = new HashMap<String, Map<RuntimeLogEntry,RuntimeLogEntry>>();
	
	//is initialized by the agent
	private static File logFile;
	
	//is initialized by the agent
	private static boolean doCount;

	//is initialized by the agent
	private static boolean useDeclaredTypes;

	//is initialized by the agent
	private static PrintWriter newLineWriter = new PrintWriter(new OutputStream() {
		
		@Override
		public void write(int b) throws IOException {
			//by default, do nothing
		}
	});
	
	/** This field is used to guard against infinite recursion during logging. */
	private static ThreadLocal<Integer> nestingDepth = new ThreadLocal<Integer>() {
		@Override
		protected Integer initialValue() {
			return 0;
		}
	};
	
	public static void enteringReflectionAPI() {
		nestingDepth.set(nestingDepth.get()+1);
	}

	private static void leavingReflectionAPI() {
		nestingDepth.set(nestingDepth.get()-1);
	}
	
	private static void logAndIncrementTargetClassEntry(String containerMethod, int lineNumber, Kind kind, String targetClass) {
		if(hasShutDown) return;
		TargetClassLogEntry newEntry = new TargetClassLogEntry(containerMethod, lineNumber, kind, targetClass);
		RuntimeLogEntry entry;
		synchronized (ReflLogger.class) {
			entry = pullOrCreateEntry(containerMethod, newEntry);
			if(doCount)
				entry.incrementCounter();		
		}
	}

	private static void logAndIncrementTargetMethodEntry(String containerMethod, int lineNumber, Kind kind, String declaringClass, String returnType, String name, boolean isAccessible, String... paramTypes) {
		if(hasShutDown) return;
		TargetMethodLogEntry newEntry = new TargetMethodLogEntry(containerMethod, lineNumber, kind, declaringClass, returnType, name, isAccessible, paramTypes);
		RuntimeLogEntry entry;
		synchronized (ReflLogger.class) {
			entry = pullOrCreateEntry(containerMethod, newEntry);
			if(doCount)
				entry.incrementCounter();
		}
	}
	
    private static void logAndIncrementTargetArrayEntry(String containerMethod, int lineNumber, Kind kind, String componentType, int... dimensions) {
        if(hasShutDown) return;
        TargetArrayLogEntry newEntry = new TargetArrayLogEntry(containerMethod, lineNumber, kind, componentType, dimensions);
        RuntimeLogEntry entry;
        synchronized (ReflLogger.class) {
            entry = pullOrCreateEntry(containerMethod, newEntry);
            if(doCount)
                entry.incrementCounter();
        }
    }
    
    private static void logAndIncrementTargetFieldEntry(String containerMethod, int lineNumber, Kind kind, String declaringClass, String fieldType, String name, boolean isAccessible) {
        if(hasShutDown) return;
        TargetFieldLogEntry newEntry = new TargetFieldLogEntry(containerMethod, lineNumber, kind, declaringClass, fieldType, name, isAccessible);
        RuntimeLogEntry entry;
        synchronized (ReflLogger.class) {
            entry = pullOrCreateEntry(containerMethod, newEntry);
            if(doCount)
                entry.incrementCounter();       
        }
    }

	private static RuntimeLogEntry pullOrCreateEntry(String containerMethod, RuntimeLogEntry newEntry) {
		Map<RuntimeLogEntry,RuntimeLogEntry> entries = containerMethodToEntries.get(containerMethod);
		if(entries==null) {
			entries = new HashMap<RuntimeLogEntry,RuntimeLogEntry>();
			containerMethodToEntries.put(containerMethod, entries);
		}
		RuntimeLogEntry sameEntry = entries.get(newEntry);
		if(sameEntry==null) {
			//found a new entry
			sameEntry = newEntry;
			entries.put(newEntry,newEntry);
			newLineWriter.println(newEntry.toString());
			newLineWriter.flush();			
		}
		return sameEntry;
	}

	public static void classMethodInvoke(Class<?> c, Kind classMethodKind) {
		if(isReentrant()) return;
		try {
			StackTraceElement frame = getInvokingFrame();
			if(frame==null) return; // reflection issued internally by the JVM: no app caller
			logAndIncrementTargetClassEntry(frame.getClassName()+"."+frame.getMethodName(),frame.getLineNumber(),classMethodKind,c.getName());
		} finally {
			leavingReflectionAPI();
		}
	}

	public static void classForName(String typeName) {
		if(isReentrant()) return;
		try {
			StackTraceElement frame = getInvokingFrame();
			if(frame==null) return;
			logAndIncrementTargetClassEntry(frame.getClassName()+"."+frame.getMethodName(),frame.getLineNumber(),Kind.ClassForName,handleArrayTypes(typeName));
		} finally {
			leavingReflectionAPI();
		}
	}

	public static void constructorMethodInvoke(Constructor<?> c, Kind constructorMethodKind) {		
		if(isReentrant()) return;
		try {
			StackTraceElement frame = getInvokingFrame();
			if(frame==null) return;
			String[] paramTypes = classesToTypeNames(c.getParameterTypes());
			String className = c.getDeclaringClass().getName();
			// Lambda / hidden proxy classes carry a per-run suffix after a '/':
			//   JDK 8:   "<pkg>.<class>$$Lambda$<count>/<decimal hashCode>"
			//   JDK 9+:  "<pkg>.<class>$$Lambda$<count>/0x<hex address>"
			//   JDK 15+: "<pkg>.<class>$$Lambda/0x<hex address>" (hidden classes)
			// The dumped bytecode name has no suffix, and the '/<...>' is non-deterministic
			// across runs, so strip everything from the last '/' for any lambda/hidden class.
			// (Previously this only matched the old "/<decimal hashCode>" form and printed
			// "unexpected lambda proxy class" on JDK 9+, leaving a non-deterministic name.)
			if (className.contains("$$Lambda") && className.lastIndexOf('/') >= 0)
			{
				className = className.substring(0, className.lastIndexOf('/'));
			}
			logAndIncrementTargetMethodEntry(frame.getClassName()+"."+frame.getMethodName(),frame.getLineNumber(),constructorMethodKind,className,"void","<init>", c.isAccessible(), paramTypes);

		} finally {
			leavingReflectionAPI();
		}
	}

	private static String[] classesToTypeNames(Class<?>[] params) {
		String[] paramTypes = new String[params.length];
		int i=0;
		for (Class<?> type : params) {
			paramTypes[i]=getTypeName(type);
			i++;
		}
		return paramTypes;
	}
	

	public static void methodMethodInvoke(Object receiver, Method m, Kind methodKind) {
		methodMethodInvoke(receiver, m, methodKind, null);
	}
	
	public static void methodMethodInvoke(Object receiver, Method m, Kind methodKind, Class<?> getMethodReceiverClass) {
		if(isReentrant()) return;
		StackTraceElement frame = getInvokingFrame();
				
		//There appears to be a call to Method.getModifiers() issued by the
		//VM in order to call the program's main method.
		//For this call there is no calling context and hence no frame.
		//We here simply ignore this call, returning early in this case.
		if(frame==null) {
			leavingReflectionAPI();
			return;		
		}
				
		Class<?> receiverClass = methodKind!=Kind.MethodInvoke || Modifier.isStatic(m.getModifiers())
		  ? m.getDeclaringClass() : receiver.getClass();
		try {
			//resolve virtual call
			Method resolved = null;
			Class<?> c = receiverClass;
			do {
				try {
					resolved = c.getDeclaredMethod(m.getName(), m.getParameterTypes());
				} catch(NoSuchMethodException e) {
					c = c.getSuperclass();
				}				
			} while(resolved==null && c!=null);
			if(resolved==null) {
				// Could not resolve via getDeclaredMethod up the hierarchy. This is expected
				// for methods hidden by the JDK reflection filter (e.g. sun.misc.Unsafe.getUnsafe,
				// filtered by jdk.internal.reflect.Reflection since JDK 9) and for synthetic/hidden
				// methods. Fall back to the Method the caller actually holds rather than throwing
				// a (previously uncaught) Error and then NPEing on resolved.* below, which aborted
				// benchmarks that reflect over such methods (jython, cassandra, ...).
				resolved = m;
			}

			String[] paramTypes = classesToTypeNames(resolved.getParameterTypes());
			
			String className = resolved.getDeclaringClass().getName();
			if (useDeclaredTypes) {
				if (methodKind==Kind.MethodInvoke && !Modifier.isStatic(m.getModifiers()))
					className = receiver.getClass().getName();
				else if(methodKind==Kind.ClassGetMethod) {
					className = getMethodReceiverClass.getName();
				}
			} 
			
			logAndIncrementTargetMethodEntry(frame.getClassName()+"."+frame.getMethodName(),frame.getLineNumber(),methodKind,className,getTypeName(resolved.getReturnType()),resolved.getName(), m.isAccessible(), paramTypes);
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			leavingReflectionAPI();
		}
	}
	
   public static void arrayNewInstance(Class<?> componentType, int dimension) {
		arrayMultiNewInstance(componentType, dimension);
    }
   
   public static void arrayMultiNewInstance(Class<?> componentType, int... dimensions) {
	   if(isReentrant()) return;
       try {
           StackTraceElement frame = getInvokingFrame();
           if(frame==null) return;
           logAndIncrementTargetArrayEntry(
                   frame.getClassName()+"."+frame.getMethodName(),
                   frame.getLineNumber(),
                   Kind.ArrayNewInstance,
                   getTypeName(componentType),
                   dimensions);
       } catch (Exception e) {
           e.printStackTrace();
       } finally {
    	   leavingReflectionAPI();
       }
   }
	
	public static void fieldMethodInvoke(Field f, Kind fieldMethodKind) {
		fieldMethodInvoke(f, fieldMethodKind, null);
	}
	
	public static void fieldMethodInvoke(Field f, Kind fieldMethodKind, Class<?> getFieldReceiverClass) {
		if(isReentrant()) return;
	    try {
	        StackTraceElement frame = getInvokingFrame();
	        if(frame==null) return;
	        Class<?> fieldClass = (useDeclaredTypes && fieldMethodKind==Kind.ClassGetField) ?
	        		getFieldReceiverClass : f.getDeclaringClass();
			logAndIncrementTargetFieldEntry(
	                frame.getClassName()+"."+frame.getMethodName(),
	                frame.getLineNumber(),
	                fieldMethodKind,
	                getTypeName(fieldClass),
	                getTypeName(f.getType()),
	                f.getName(),
	                f.isAccessible());
	    } catch (Exception e) {
	        e.printStackTrace();
	    } finally {
			leavingReflectionAPI();
	    }	    
	}
		
    // Strip the per-run "/0x<hex>" (or ".0x<hex>") suffix that hidden/lambda classes carry
    // (e.g. java.lang.String$$StringConcat/0x...), so modern-reflection entries are
    // deterministic across runs — mirroring the $$Lambda normalization in constructorMethodInvoke.
    private static String stripHidden(String className) {
        if(className==null) return null;
        int i = className.indexOf("/0x");
        if(i<0) i = className.indexOf(".0x");
        return i>=0 ? className.substring(0, i) : className;
    }

    // A large share of MethodHandle lookups come from the JVM's own machinery — lambda
    // metafactory (java.lang.X$$Lambda...) and string concat (java.lang.String$$StringConcat...).
    // These synthetic classes are not app reflection and are useless to a static analysis, so
    // modern-reflection logging skips them (keeping real application/library targets).
    private static boolean isSyntheticMHTarget(String className) {
        return className != null && (className.contains("$$Lambda") || className.contains("$$StringConcat"));
    }

    // ===== modern-reflection capture (opt-in) =========================================
    // These log at the RESOLUTION point of alternative dynamic-dispatch mechanisms
    // (java.lang.invoke.MethodHandles.Lookup, Proxy, Unsafe) so the target identity is
    // recovered the same way TamiFlex already logs Class.getMethod/getField.

    // MethodHandles.Lookup.findVirtual/findStatic/findSpecial: target is (refc,name,type).
    public static void methodHandleMethod(Class<?> refc, String name, java.lang.invoke.MethodType type, Kind kind) {
        if(isReentrant()) return;
        try {
            StackTraceElement frame = getInvokingFrame();
            if(frame==null) return;
            String dc = stripHidden(refc.getName());
            if(isSyntheticMHTarget(dc)) return;
            logAndIncrementTargetMethodEntry(frame.getClassName()+"."+frame.getMethodName(), frame.getLineNumber(),
                kind, dc, getTypeName(type.returnType()), name, false, classesToTypeNames(type.parameterArray()));
        } catch (Throwable e) { e.printStackTrace(); } finally { leavingReflectionAPI(); }
    }

    // MethodHandles.Lookup.findConstructor: (refc,type) -> <init> with type's params.
    public static void methodHandleConstructor(Class<?> refc, java.lang.invoke.MethodType type, Kind kind) {
        if(isReentrant()) return;
        try {
            StackTraceElement frame = getInvokingFrame();
            if(frame==null) return;
            String dc = stripHidden(refc.getName());
            if(isSyntheticMHTarget(dc)) return;
            logAndIncrementTargetMethodEntry(frame.getClassName()+"."+frame.getMethodName(), frame.getLineNumber(),
                kind, dc, "void", "<init>", false, classesToTypeNames(type.parameterArray()));
        } catch (Throwable e) { e.printStackTrace(); } finally { leavingReflectionAPI(); }
    }

    // MethodHandles.Lookup.find[Static]Getter/Setter and find[Static]VarHandle: field target.
    public static void methodHandleField(Class<?> refc, String name, Class<?> fieldType, Kind kind) {
        if(isReentrant()) return;
        try {
            StackTraceElement frame = getInvokingFrame();
            if(frame==null) return;
            String dc = stripHidden(getTypeName(refc));
            if(isSyntheticMHTarget(dc)) return;
            logAndIncrementTargetFieldEntry(frame.getClassName()+"."+frame.getMethodName(), frame.getLineNumber(),
                kind, dc, getTypeName(fieldType), name, false);
        } catch (Throwable e) { e.printStackTrace(); } finally { leavingReflectionAPI(); }
    }

    // Proxy.newProxyInstance: log each proxied interface (calls on it reach the handler).
    public static void proxyNewProxyInstance(Class<?>[] interfaces) {
        if(isReentrant()) return;
        try {
            StackTraceElement frame = getInvokingFrame();
            if(frame==null || interfaces==null) return;
            for (Class<?> itf : interfaces)
                logAndIncrementTargetClassEntry(frame.getClassName()+"."+frame.getMethodName(), frame.getLineNumber(),
                    Kind.ProxyNewProxyInstance, itf.getName());
        } catch (Throwable e) { e.printStackTrace(); } finally { leavingReflectionAPI(); }
    }

    // Unsafe.allocateInstance(Class): an instance of the class is created (no ctor run).
    public static void unsafeAllocateInstance(Class<?> c) {
        if(isReentrant()) return;
        try {
            StackTraceElement frame = getInvokingFrame();
            if(frame==null || c==null) return;
            logAndIncrementTargetClassEntry(frame.getClassName()+"."+frame.getMethodName(), frame.getLineNumber(),
                Kind.UnsafeAllocateInstance, c.getName());
        } catch (Throwable e) { e.printStackTrace(); } finally { leavingReflectionAPI(); }
    }

    private static boolean isReentrant() {
    	//this method is called at every entry point to
    	//the TamiFlex runtime; at this point we are entering the reflection API
    	enteringReflectionAPI();
    	
    	//check if we have a recursive call caused by TamiFlex itself;
    	//this is the case if depth is >1
    	Integer depth = nestingDepth.get();
    	if(depth>1) {
    		
    		//by convention, when this method returns true,
    		//we will be leaving the TamiFlex runtime (callers must    		
    		//return immediately); hence we here flag that we leave the API
    		leavingReflectionAPI();
    		return true;
    	} else {
    		return false;
    	}
	}

	protected static String handleArrayTypes(String className) {
        
        int arrDepth = 0;
        for(int i=0;i<className.length();i++) {
        	if(className.charAt(i)=='[') {
        		arrDepth++;
        	} else {
        		break;
        	}
        }
    	
        className = className.substring(arrDepth);
        
        if(className.endsWith(";")) {
        	//cut of leading "L" and trailing ";"
        	className = className.substring(1,className.indexOf(';'));
        }
        

        if("B".equals(className))
            className= byte.class.getName();
        if("C".equals(className))
            className= char.class.getName();
        if("D".equals(className))
            className= double.class.getName();
        if("F".equals(className))
            className= float.class.getName();
        if("I".equals(className))
        	className = int.class.getName();
        if("J".equals(className))
        	className = long.class.getName();
        if("S".equals(className))
        	className = short.class.getName();
        if("Z".equals(className))
        	className = boolean.class.getName();
        if("V".equals(className))
            className= void.class.getName();

        for(int i=0; i<arrDepth; i++) {
        	className += "[]";
        }
        
        return className;
    }

	private static String getTypeName(Class<?> type) {
		//copied from java.lang.reflect.Field.getTypeName(Class)
		if (type.isArray()) {
		    try {
			Class<?> cl = type;
			int dimensions = 0;
			while (cl.isArray()) {
			    dimensions++;
			    cl = cl.getComponentType();
			}
			StringBuffer sb = new StringBuffer();
			sb.append(cl.getName());
			for (int i = 0; i < dimensions; i++) {
			    sb.append("[]");
			}
			return sb.toString();
		    } catch (Throwable e) { /*FALLTHRU*/ }
		}
		return type.getName();
	}

	/**
	 * Returns the stack frame two frames above any frame related
	 * to this class. (The frame just above is the call we are tracing,
	 * but we want to return the invoking frame, hence two frames above.)
	 */
	private static StackTraceElement getInvokingFrame() {
		StackTraceElement[] stackTrace = new Throwable().getStackTrace();
		// Return the first frame that is neither TamiFlex's own runtime nor a
		// reflection-infrastructure frame. Walking by name (rather than a fixed
		// "two frames up" offset) is robust to the extra frames the reflection
		// implementation introduces across JDK versions (e.g. MethodHandle-based
		// accessors on Java 18+) and to chained JDK methods (forName -> forName).
		for (StackTraceElement frame : stackTrace) {
			if (!isInfrastructureFrame(frame.getClassName())) {
				return frame;
			}
		}
		return null;
	}

	private static boolean isInfrastructureFrame(String className) {
		return className.startsWith("de.bodden.tamiflex.")
			|| className.equals("java.lang.Class")
			|| className.startsWith("java.lang.reflect.")
			|| className.startsWith("jdk.internal.reflect.")
			|| className.startsWith("sun.reflect.")
			|| className.startsWith("java.lang.invoke.");
	}
	
	public static synchronized void writeLogfileToDisk(boolean verbose, int newClasses) {
		Set<PersistedLogEntry> mergedEntries = mergeOldAndNewLog(verbose, newClasses);
		//printStatistics();
		try {
			PrintWriter pw = new PrintWriter(logFile);

			List<String> lines = new ArrayList<String>();
			
			for (PersistedLogEntry entry : mergedEntries) {
				lines.add(entry.toString());
			}
			
			Collections.sort(lines);
			
			for (String line : lines) {
				pw.println(line);
			}
			pw.flush();
			pw.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}		
	}
	
	public static void setMustCount(boolean mustCount) {
		doCount = mustCount;		
	}
	
	public static void setLogFile(File f) {
		logFile = f;
		
		//send path of log file over Socket (if connected)
		newLineWriter.println(f.getAbsolutePath());
	}
	
	public static void setSocket(Socket s) throws IOException {
		newLineWriter = new PrintWriter(s.getOutputStream());
	}
	
	public static void setuseDeclaredTypes(boolean on) {
		useDeclaredTypes = on;
	}

	//is called by the agent
	private static void initializeLogFile() {
		File f = logFile;
		if(f.exists() && f.canRead()) {
			FileInputStream fis = null;
			BufferedReader reader = null;
			try {
				fis = new FileInputStream(f);
				reader = new BufferedReader(new InputStreamReader(fis));
				String line;
				while((line=reader.readLine())!=null) {
					String[] split = line.split(";",-1);
					Kind kind = Kind.kindForLabel(split[0]);
					String target = split[1];
					String containerMethod = split[2];
					int lineNumber = split[3].isEmpty()?-1:Integer.parseInt(split[3]);
					String metadata = split[4];
					int count = (split.length<6||split[5].isEmpty()||!doCount)?0:Integer.parseInt(split[5]);
					PersistedLogEntry entry = new PersistedLogEntry(containerMethod, lineNumber, kind, target, metadata, count);
					oldContainerMethodToEntries.put(entry,entry);
				}
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
				try {
					if(reader!=null) reader.close();
					if(fis!=null) fis.close();
				} catch (IOException e) {
				}
			}
		} 
	}
	
	private static Set<PersistedLogEntry> mergeOldAndNewLog(boolean verbose, int newClasses) {
		initializeLogFile();
		Set<RuntimeLogEntry> newLogSet = new HashSet<RuntimeLogEntry>();
		for(Map<RuntimeLogEntry,RuntimeLogEntry> values: containerMethodToEntries.values()) {
			newLogSet.addAll(values.keySet());
		}
		
		Set<PersistedLogEntry> merged = new HashSet<PersistedLogEntry>();
		
		for (RuntimeLogEntry newLogEntry : newLogSet) {
			PersistedLogEntry persistedEntry = newLogEntry.toPersistedEntry();
			PersistedLogEntry correspondingOldEntry = oldContainerMethodToEntries.get(persistedEntry);
			if(correspondingOldEntry!=null) {
				PersistedLogEntry mergedEntry = PersistedLogEntry.merge(persistedEntry, correspondingOldEntry);
				merged.add(mergedEntry);
			} else {
				merged.add(persistedEntry);
			}
		}
		
		for (PersistedLogEntry oldLogEntry : oldContainerMethodToEntries.keySet()) {
			//if no corresponding merged entry contained yet, add the old one
			if(!merged.contains(oldLogEntry)) {
				merged.add(oldLogEntry);
			}
		}
		
		Set<PersistedLogEntry> newEntries = new HashSet<PersistedLogEntry>(merged);
		newEntries.removeAll(oldContainerMethodToEntries.keySet());
		
		System.out.println("\n============================================================");
		System.out.println("TamiFlex Play-Out Agent Version "+ReflLogger.class.getPackage().getImplementationVersion());
		if(newEntries.isEmpty()) {
			System.out.println("Found no new log entries.");
		} else {
			System.out.println("Found "+newEntries.size()+" new log entries.");
		}
		if(newClasses>0) {
			System.out.println("Dumped "+newClasses+" new classes.");
		} else {
			System.out.println("Dumped no new classes.");
		}
		if(verbose) {
			System.out.println("New Entries: ");
			for (PersistedLogEntry logEntry : newEntries) {
				System.out.println(logEntry);
			}
		}
		System.out.println("Log file written to: "+logFile.getAbsolutePath());
		System.out.println("============================================================");
		
		return merged;
	}
}
