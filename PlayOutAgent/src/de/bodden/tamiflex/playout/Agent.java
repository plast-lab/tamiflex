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
package de.bodden.tamiflex.playout;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarFile;

import de.bodden.tamiflex.normalizer.Hasher;
import de.bodden.tamiflex.playout.rt.ReflLogger;
import de.bodden.tamiflex.playout.rt.ShutdownStatus;

public class Agent {
	
	public final static String PKGNAME = Agent.class.getPackage().getName().replace('.', '/');
	
	private static final boolean CAN_RETRANSFORM = true;
	
	private static ClassDumper classDumper;
	private static boolean dontDump = false;
	private static boolean dontNormalize = false;
	private static boolean count = false;
	private static boolean useDeclaredTypes;
	private static boolean verbose = false;
	private static String outPath = "out";
	private static String transformations = "";
	private static boolean modernReflection = false;
	private static Socket socket;

	// Opt-in extra transformations for modern dynamic-dispatch mechanisms the classic
	// java.lang.reflect set does not cover. Appended to `transformations` ONLY when the
	// modernReflection flag is on, so default runs are unchanged. Any of these that don't
	// apply on the current JDK are skipped by ReflectionMonitor (it catches per-instrument).
	private static final String MODERN_TRANSFORMATIONS =
		"de.bodden.tamiflex.playout.transformation.modern.LookupTransformation "
		+ "de.bodden.tamiflex.playout.transformation.modern.ProxyTransformation "
		+ "de.bodden.tamiflex.playout.transformation.modern.ClassForNameModuleTransformation";

	
	public static void premain(String agentArgs, Instrumentation inst) throws IOException, ClassNotFoundException, UnmodifiableClassException, URISyntaxException, InterruptedException {
		if(!inst.isRetransformClassesSupported()) {
			throw new RuntimeException("retransformation not supported");
		}
		
		System.out.println("============================================================");
		System.out.println("TamiFlex Play-Out Agent Version "+Agent.class.getPackage().getImplementationVersion());

		loadProperties();

		// Extra flag (default off): -Dtamiflex.modernReflection=true (or modernReflection=true
		// in poa.properties) appends the modern-reflection transformations. Nothing changes
		// unless it is set, so existing runs are unaffected.
		if(Boolean.getBoolean("tamiflex.modernReflection"))
			modernReflection = true;
		if(modernReflection) {
			transformations = (transformations.trim() + " " + MODERN_TRANSFORMATIONS).trim();
			System.out.println("TamiFlex: modern-reflection capture ENABLED (MethodHandle/VarHandle lookups, Proxy, Class.forName(Module,String)).");
		}

		appendRtJarToBootClassPath(inst);

		ReflLogger.setMustCount(count);		
		ReflLogger.setuseDeclaredTypes(useDeclaredTypes);		
		if(dontNormalize) Hasher.dontNormalize();

		String hostAndPort = System.getenv("TAMIFLEX_ECLIPSE");
		if(hostAndPort!=null && !hostAndPort.isEmpty()) {
			//online mode, open Socket
			String[] split = hostAndPort.split(":");
			if(split.length!=2) {
				System.err.println("Illegal argument: "+agentArgs);
				System.err.println("Expected format: hostname:socket");
				System.exit(1);
			}
			String host = split[0];
			int port = Integer.parseInt(split[1]);
			socket = new Socket(host, port);
			ReflLogger.setSocket(socket);
		} 
				
		if(outPath==null||outPath.isEmpty()) {
			System.err.println("No outDir given!");
		}
		
		File outDir = new File(outPath);
		if(outDir.exists()) {
			if(!outDir.isDirectory()) {
				System.err.println(outDir+ "is not a directory");
				System.exit(1);
			}
		} else {
			boolean res = outDir.mkdirs();
			if(!res) {
				System.err.println("Cannot create directory "+outDir);
				System.exit(1);
			}
		}
		
		final File logFile = new File(outDir,"refl.log");
		
		dumpLoadedClasses(inst,outDir,dontDump,verbose);
		
		ReflLogger.setLogFile(logFile);
		
		if(!transformations.isEmpty())
			instrumentClassesForLogging(inst);
		
		inst.addTransformer(classDumper, CAN_RETRANSFORM);
		
		final boolean verboseOutput = verbose;
		Runtime.getRuntime().addShutdownHook(new Thread() {
			
			@Override
			public void run() {
				ShutdownStatus.hasShutDown = true;
				if(socket!=null) {
					try {
						socket.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
				classDumper.writeClassesToDisk();
				ReflLogger.writeLogfileToDisk(verboseOutput,classDumper.newClasses);
				
				String agentJarDir = agentJarFilePath.substring(0, agentJarFilePath.lastIndexOf('/'));
				String version = Agent.class.getPackage().getImplementationVersion();
				String dbJarPath = agentJarDir+'/'+"dbdumper-"+version+".jar";
				
				try {
					File jarfile = new File(new URI(dbJarPath));
					if(jarfile.exists()) {
						System.out.println("Database JAR file found. Will attempt to dump log file to database.");
						DBDumper.dumpFileToDatabase(jarfile,logFile);
					} 
				} catch (URISyntaxException e) {
					e.printStackTrace();
				}
			}
			
		});
		
		System.out.println("============================================================");		
	}

	private static void loadProperties() {
		String propFileName = "poa.properties";
		String userPropFilePath = System.getProperty("user.home")+File.separator+".tamiflex"+File.separator+propFileName;
		copyPropFileIfMissing(userPropFilePath);
		String[] paths = { propFileName, userPropFilePath };
		InputStream is = null;
		File foundFile= null;
		for (String path : paths) {
			File file = new File(path);
			if(file.exists() && file.canRead()) {
				try {
					is = new FileInputStream(file);
					foundFile = file;
					break;
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				}
			} 
		}
		if(is==null) throw new InternalError("No properties files found!");
		
		Properties props =  new Properties();
		try {
			props.load(is);

			if(!props.containsKey("quiet") || !props.get("quiet").equals("true")) {
				String path = (foundFile!=null) ? foundFile.getAbsolutePath() : "<JAR FILE>!/"+propFileName;
				System.out.println("Loaded properties from "+path);
			}
			if(props.containsKey("count") && props.get("count").equals("true"))
				count = true;
			if(props.containsKey("dontDumpClasses") && props.get("dontDumpClasses").equals("true"))
				dontDump = true;
			if(props.containsKey("dontNormalize") && props.get("dontNormalize").equals("true"))
				dontNormalize = true;
			if(props.containsKey("verbose") && props.get("verbose").equals("true"))
				verbose = true;
			if(props.containsKey("useDeclaredTypes") && props.get("useDeclaredTypes").equals("true"))
				useDeclaredTypes = true;
			if(props.containsKey("outDir"))
				outPath = (String) props.get("outDir"); 
			if(props.containsKey("transformations"))
				transformations = (String) props.get("transformations");
			if(props.containsKey("modernReflection") && props.get("modernReflection").equals("true"))
				modernReflection = true;
		} catch (IOException e) {
			throw new InternalError("Error loading default properties file: "+e.getMessage()); 
		}		
	}

	private static void copyPropFileIfMissing(String userPropFilePath) {
		File f = new File(userPropFilePath);
		if(!f.exists()) {
			File dir = f.getParentFile();
			if(!dir.exists()) dir.mkdirs();
			try {
				FileOutputStream fos = new FileOutputStream(f);
				InputStream is = Agent.class.getClassLoader().getResourceAsStream(f.getName());
				if(is==null) {
					fos.close();
					throw new InternalError("No default properties file found in agent JAR file!");
				}
				int i;
				while((i=is.read())!=-1) {
					fos.write(i);
				}
				fos.close();
				is.close();				
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	private static void dumpLoadedClasses(Instrumentation inst, File outDir, boolean dontReallyDump, boolean verbose)
			throws UnmodifiableClassException {
		classDumper = new ClassDumper(outDir,dontReallyDump,verbose);
		inst.addTransformer(classDumper, CAN_RETRANSFORM);
		//dump all classes that are already loaded
		for (Class<?> c : inst.getAllLoadedClasses()) {
			if(inst.isModifiableClass(c)) {
				inst.retransformClasses(c);
			} else {
				if(!c.isPrimitive() && !c.isArray() && (c.getPackage()==null || !c.getPackage().getName().startsWith("java.lang"))){
					System.err.println("WARNING: Cannot dump class "+c.getName());
				}
			}
		}
		inst.removeTransformer(classDumper);
	}

	private static void instrumentClassesForLogging(Instrumentation inst) throws UnmodifiableClassException {
		ReflectionMonitor reflMonitor = new ReflectionMonitor(transformations, verbose);

		// Resolve the affected classes (which iterates internal collections and may load
		// their iterator classes) BEFORE the transformer is active, so that lazy loading
		// cannot re-enter transform() and cause a ClassCircularityError on newer JDKs.
		List<Class<?>> affectedClasses = reflMonitor.getAffectedClasses();

		inst.addTransformer(reflMonitor, CAN_RETRANSFORM);
		// Retransform per-class so that one class that cannot be modified (e.g. a modern
		// target absent/locked on this JDK) is skipped rather than aborting the whole batch.
		for (Class<?> c : affectedClasses) {
			try {
				inst.retransformClasses(c);
			} catch (Throwable t) {
				System.err.println("TamiFlex: cannot instrument "+c.getName()+": "+t);
			}
		}

		inst.removeTransformer(reflMonitor);
	}

	private static void appendRtJarToBootClassPath(Instrumentation inst) throws URISyntaxException, IOException {
		// Resolve the agent jar straight from its code source rather than parsing a
		// "jar:file:...!/..." URL by hand; the latter breaks on JDK 9+ URL forms and on
		// Windows paths.
		URI jarUri;
		try {
			jarUri = Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI();
		} catch (NullPointerException e) {
			System.err.println("Support library for reflection log not found on classpath.");
			System.exit(1);
			return;
		}
		File jar = new File(jarUri);
		if(!jar.isFile()) {
			System.err.println("TamiFlex agent must be run from a jar file (found: "+jar+").");
			System.exit(1);
		}
		agentJarFilePath = jarUri.toString();
		inst.appendToBootstrapClassLoaderSearch(new JarFile(jar));

		// On the boot loader the runtime log classes land in that loader's *unnamed*
		// module, which the named java.base module does not read by default. The code we
		// weave into java.lang.Class/Method/... would then fail to link its
		// INVOKESTATIC into ReflLogger. Add the read edge on Java 9+ (reflectively, so
		// this class still compiles for release 8).
		addBootLoggerReadEdge(inst);
	}

	/**
	 * Makes java.base read the (bootstrap, unnamed) module that now hosts
	 * {@code de.bodden.tamiflex.playout.rt.*}, so instrumented java.base methods may
	 * call into ReflLogger. No-op on Java 8, where there is no module system.
	 */
	private static void addBootLoggerReadEdge(Instrumentation inst) {
		try {
			Class<?> moduleClass = Class.forName("java.lang.Module"); // absent on Java 8
			java.lang.reflect.Method getModule = Class.class.getMethod("getModule");
			Object javaBaseModule = getModule.invoke(Object.class);
			// The bootstrap-loaded copy of ReflLogger (null loader == bootstrap).
			Class<?> bootLogger = Class.forName("de.bodden.tamiflex.playout.rt.ReflLogger", false, null);
			Object bootModule = getModule.invoke(bootLogger);
			java.lang.reflect.Method redefineModule = Instrumentation.class.getMethod(
					"redefineModule", moduleClass, java.util.Set.class, java.util.Map.class,
					java.util.Map.class, java.util.Set.class, java.util.Map.class);
			redefineModule.invoke(inst, javaBaseModule,
					java.util.Collections.singleton(bootModule),
					java.util.Collections.emptyMap(),
					java.util.Collections.emptyMap(),
					java.util.Collections.emptySet(),
					java.util.Collections.emptyMap());
		} catch (ClassNotFoundException e) {
			// Java 8: no modules, nothing to wire up.
		} catch (Exception e) {
			System.err.println("TamiFlex: could not grant java.base access to the reflection log runtime: "+e);
		}
	}
	
	public static void main(String[] args) {
		usage();
	}

	private static void usage() {
		System.out.println("============================================================");
		System.out.println("TamiFlex Play-Out Agent Version "+Agent.class.getPackage().getImplementationVersion());
		System.out.println(DISCLAIMER);
		System.out.println("============================================================");
		System.exit(1);
	}
	
	private final static String DISCLAIMER=
		"\n\nCopyright (c) 2010-2011 Eric Bodden and others.\n" +
		"\n" +
		"DISCLAIMER: USE OF THIS SOFTWARE IS AT OWN RISK.\n" +
		"\n" +
		"All rights reserved. This program and the accompanying materials\n" +
		"are made available under the terms of the Eclipse Public License v1.0\n" +
		"which accompanies this distribution, and is available at\n" +
		"http://www.eclipse.org/legal/epl-v10.html";
	private static String agentJarFilePath;

}
