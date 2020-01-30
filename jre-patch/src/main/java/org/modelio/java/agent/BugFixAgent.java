package org.modelio.java.agent;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public class BugFixAgent {
    public static void premain(String args, Instrumentation inst) {
        ClassFileTransformer transformer = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader,
                    String className,
                    Class<?> classBeingRedefined,
                    ProtectionDomain protectionDomain,
                    byte[] classfileBuffer) {
                try {
                    final String patchedClassName = "sun/nio/ch/EPollArrayWrapper";
                    final String absPatchPath = "patch/"+patchedClassName+".class";
                    if (className.equals(patchedClassName)) {
                        ByteArrayOutputStream os = new ByteArrayOutputStream();
                        ClassLoader myLoader = BugFixAgent.class.getClassLoader();
                        if (myLoader == null)
                            myLoader = loader;
                        if (myLoader == null) {
                            myLoader = ClassLoader.getSystemClassLoader();
                        }

                        try (InputStream is = myLoader.getResourceAsStream(absPatchPath);) {
                            if (is == null) {
                                System.err.println("not found "+myLoader.getResource(absPatchPath));
                                throw new FileNotFoundException(absPatchPath);
                            }
                            byte[] buf = new byte[4096];
                            int r;
                            while((r = is.read(buf)) > 0) {
                                os.write(buf, 0, r);
                            }

                            System.err.println("Replaced "+patchedClassName);

                            return os.toByteArray();
                        } catch (IOException e) {
                            e.printStackTrace();
                            throw new IOError(e);
                        }
                    } else {
                        //System.err.println("  - ignore "+className);

                        return null; // skips instrumentation for other classes
                    }
                } catch (RuntimeException | Error e) {
                    e.printStackTrace();
                    System.exit(255);
                    throw e;
                }
            }
        };


        inst.addTransformer(transformer);
        System.err.println("Added transformer.");

        // load native methods
        sun.nio.ch.IOUtil.load();

        try {
            Class.forName("sun.nio.ch.EPollArrayWrapper");

            inst.removeTransformer(transformer);
            System.err.println("Removed transformer.");
        } catch (ClassNotFoundException e) {
            throw new Error(e);
        }


      }
}
