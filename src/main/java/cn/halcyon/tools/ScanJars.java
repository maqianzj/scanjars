package cn.halcyon.tools;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.StringOptionHandler;
import static org.kohsuke.args4j.ExampleMode.ALL;

/**
 * 列出或者移除工程中无用的jar包
 * @author maqian
 * @version 1.0
 */
public class ScanJars {
	private static final Pattern importClassPattern = Pattern.compile("\\s*import\\s+(\\S+)\\s*;", Pattern.DOTALL);

	@Option(name="-u", usage="list useless jars")
    private boolean uselessFlag;
	
	@Option(name="-l", usage="list useful jars")
    private boolean usefulFlag;

	@Option(name="-src", handler=StringOptionHandler.class, usage="java source path")
	private String src;
	
	@Option(name="-lib", handler=StringOptionHandler.class, usage="java library path")
	private String lib;
	
	@Option(name="-class", handler=StringOptionHandler.class, usage="java class")
	private String clazz;
	
	/**
	 * @param args
	 * @throws IOException 
	 */
	public static void main(String[] args) throws IOException {
		ScanJars app = new ScanJars();
		app.process(args);
	}
	
	public void process(String[] args) throws IOException {
        processArgs(args);
        File libDir = new File(lib);
        if (!libDir.exists()) {
            System.err.println("path("+lib+") is not exists");
            System.exit(-1);
        }
        if (clazz == null) {
        	File srcDir = new File(src);
            if (!srcDir.exists()) {
                System.err.println("path("+src+") is not exists");
                System.exit(-1);
            }
        	processDirs(libDir, srcDir);
        }
        else {
        	processDirs(libDir, clazz);
        }
	}

    private void processArgs(String[] args) {
        CmdLineParser parser = new CmdLineParser(this);
        try {
            parser.parseArgument(args);
            if (lib == null) {
                throw new CmdLineException(parser,"-lib argument is not given");
            }
            if (src==null && clazz==null) {
            	throw new CmdLineException(parser,"-src or -class argument is not given");
            }
            else if (src!=null && clazz!=null) {
            	throw new CmdLineException(parser,"conflicting options: -src, -class");
            }
            if (uselessFlag&&usefulFlag) {
            	throw new CmdLineException(parser,"conflicting options: -u, -l");
            }
            else if (!uselessFlag&&!usefulFlag) {
            	usefulFlag = true;
            }
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            printUsage(parser);
            System.exit(-1);
        }
    }
    
    private void processDirs(File libDir, String clazz) throws IOException {
    	Set<String> usedClasses = new HashSet<String>();
    	processDirs(libDir, usedClasses);
    }
    

    private void processDirs(File srcDir, File libDir) throws IOException {
        List<File> files = listFiles(srcDir);
        Set<String> usedClasses = filterImportClasses(files);
        processDirs(libDir, usedClasses);
    }
    
    private void processDirs(File libDir, Set<String> usedClasses) throws IOException {
    	 Map<JarFile, String> jars = listJarFiles(libDir);
         Map<String, JarFile> classesJarDict = createClassJarDict(jars);
         tagUsedJars(classesJarDict, jars, usedClasses);
    	if (usefulFlag) {
	        for (Map.Entry<JarFile, String> entry : jars.entrySet()) {
	            if ("used".equals(entry.getValue())) {
	            	System.out.println("useful: "+entry.getKey().getName());
	            }
	        }
        }
        else {
        	for (Map.Entry<JarFile, String> entry : jars.entrySet()) {
	            if (!"used".equals(entry.getValue())) {
	            	System.out.println("useless: "+entry.getKey().getName());
	            }
	        }
        }
    }

    private void printUsage(CmdLineParser parser) {
        System.err.println("java -jar uselessjars.jar [options...] arguments...");
        parser.printUsage(System.err);
        System.err.println();
        System.err.println("example: java -jar uselessjars.jar"+parser.printExample(ALL));
    }
	
	private void tagUsedJars(Map<String, JarFile> classesJarDict, 
			Map<JarFile, String> jars, Set<String> usedClasses) {
		for (String className : usedClasses) {
			JarFile jar = classesJarDict.get(className);
			if (jar != null) {
				jars.put(jar, "used");
			}
		}
	}
	
	private Map<String, JarFile> createClassJarDict(Map<JarFile, String> jars) 
			throws IOException {
		Map<String, JarFile> dict = new HashMap<String, JarFile>();
		for (Map.Entry<JarFile, String> entry : jars.entrySet()) {
			Set<String> classes = listJarClasses(entry.getKey());
			for (String name : classes) {
				dict.put(name, entry.getKey());
			}
		}
		return dict;
	}
	
	private Set<String> listJarClasses(JarFile jar) throws IOException {
		Set<String> classes = new HashSet<String>();
		Enumeration<JarEntry> entries = jar.entries();
		while (entries.hasMoreElements()) {
			JarEntry entry = entries.nextElement();
			String name = entry.getName();
			if (name.endsWith(".class")) {
				String className = name.substring(0, name.lastIndexOf('.')).replace('/', '.');
				classes.add(className);
				if (className.lastIndexOf('.') > 0) {
					classes.add(className.substring(0, className.lastIndexOf('.'))+".*");
				}
				else {
					classes.add(className);
				}
			}
		}
		return classes;
	}
	
	private Map<JarFile, String> listJarFiles(File dir) throws IOException {
		File[] files = dir.listFiles(new FileFilter() {
			@Override
			public boolean accept(File pathname) {
				if (pathname.isFile()&&pathname.getName().endsWith(".jar")) {
					return true;
				}
				return false;
			}
		});
		Map<JarFile, String> jars = new HashMap<JarFile, String>();
		for (File file : files) {
			jars.put(new JarFile(file), "");
		}
		return jars;
	}
	
	private Set<String> filterImportClasses(List<File> files) throws IOException {
		Set<String> classNames = new HashSet<String>();
		for (File file : files) {
			String content = FileUtils.readFileToString(file);
			Matcher matcher = importClassPattern.matcher(content);
			while (matcher.find()) {
				String className = matcher.group(1);
				if (!className.startsWith("java.")&&!className.startsWith("javax.")
						&&!className.startsWith("com.sun.")&&!className.startsWith("sun.misc.")) {
					classNames.add(matcher.group(1));
				}
			}
		}
		return classNames;
	}
	
	private List<File> listFiles(File file) {
		List<File> files = new LinkedList<File>();
		if (file.isDirectory()) {
			File[] fs = file.listFiles(new FileFilter() {
				@Override
				public boolean accept(File pathname) {
					if (pathname.isDirectory()||pathname.getName().endsWith(".java")) {
						return true;
					}
					return false;
				}
			});
			for (File f : fs) {
				files.addAll(listFiles(f));
			}
		}
		else {
			files.add(file);
		}
		return files;
	}

}
