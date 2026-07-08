package de.bodden.tamiflex.playout;

import java.io.File;
import java.io.IOException;

public class DBDumper {

	/**
	 * Launches the database-dumper JAR in a separate JVM to load the given log
	 * file into the database. Historically this used Ant's {@code <java>} task;
	 * it now shells out with {@link ProcessBuilder} so the agent carries no Ant
	 * dependency.
	 */
	public static void dumpFileToDatabase(File jarfile, File logFile) {
		String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
		ProcessBuilder pb = new ProcessBuilder(
				javaBin, "-jar", jarfile.getAbsolutePath(), logFile.getAbsolutePath());
		pb.directory(jarfile.getParentFile());
		pb.inheritIO();
		try {
			// Fire-and-forget, matching the old spawn=true behaviour.
			pb.start();
		} catch (IOException e) {
			System.err.println("Could not launch database dumper: " + e.getMessage());
			e.printStackTrace();
		}
	}

}
