package lissyachiy.implementor;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.jar.JarOutputStream;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

/**
 * Generates class code that implements the interface or extends the class
 */
public class Implementor implements JarImpler {
    /**
     * Collects the class code
     */
    private final StringBuilder builder;

    /**
     * Internal builder used for generating the source code of the class.
     */
    public Implementor() {
        builder = new StringBuilder();
    }

    /**
     * Generates class code that implements the specified interface or extends the specified class.,
     * and, if necessary, packages the generated class into a JAR file.
     * <p>
     * This class implements the {@link JarImpler} interface.
     * </p>
     *
     * @param args Usage: An optional parameter {@code -jar}, a {@code class} for implementation or extension,
     *             a {@code path} where to put the result, or the name of the jar file.
     */
    public static void main(String[] args) {
        if (args == null || args.length != 3 && args.length != 2) {
            System.err.println("Usage: java Implementor [-jar] <class-name> <directory/jar-file>");
            return;
        }

        int offset = args[0].equals("-jar") ? 1 : 0;
        try {
            Class<?> token = Class.forName(args[offset]);
            Path root = Path.of(args[1 + offset]);

            if (args[0].equals("-jar")) {
                new Implementor().implementJar(token, root);
            } else {
                new Implementor().implement(token, root);
            }
        } catch (ClassNotFoundException e) {
            System.err.println("Class " + args[offset] + " not found");
        } catch (ImplerException e) {
            System.err.println("Failed implementing " + args[offset] + ": " + e.getMessage());
        }
    }

    /**
     * Creates a JAR file containing the implementation or extension of the specified class or interface.
     *
     * @param token   the class that will be implemented or extended
     * @param jarFile the path to the resulting JAR file
     * @throws ImplerException if an error occurs during generation or packaging
     * @see #implement(Class, Path)
     */


    /**
     * {@inheritDoc}
     */
    @Override
    public void implementJar(Class<?> token, Path jarFile) throws ImplerException {
        Path temp;
        try {
            temp = Files.createDirectories(Path.of("temp"));

            Utils.tryToCreateDirectory(jarFile);
            try (JarOutputStream outStream = new JarOutputStream(Files.newOutputStream(jarFile))) {
                ZipEntry entry = new ZipEntry(Utils.getEntryName(token));
                implement(token, temp);
                Path file = temp.resolve(getPathGeneratedClass(token));
                Utils.compile(file.toAbsolutePath(), token);

                try {
                    outStream.putNextEntry(entry);
                    Files.copy(file.resolveSibling(
                            file.getFileName().toString().replace(".java", ".class")
                    ), outStream);
                } catch (IOException e) {
                    throw new ImplerException("Recording error", e);
                }
                outStream.closeEntry();
            } catch (IOException e) {
                throw new ImplerException("The problem with creating a Jar archive", e);
            } finally {
                Utils.clearTemp(temp);
            }
        } catch (IOException e) {
            throw new ImplerException("Failed to clear a directory for class generation", e);
        }
    }

    /**
     * Generates a class based on the specified {@link Class}.
     *
     * @param token the class that will be implemented or extended
     * @param root  the place where the generated class will be placed
     * @throws ImplerException if the class cannot be implemented (private, final, enum, etc.)
     */
    @Override
    public void implement(Class<?> token, Path root) throws ImplerException {
        if (token == Enum.class) {
            throw new ImplerException(token.getCanonicalName() + " is an enum");
        }
        if (token == Record.class) {
            throw new ImplerException(token.getCanonicalName() + " is a record");
        }
        if (Modifier.isPrivate(token.getModifiers())) {
            throw new ImplerException("Cannot extends of private class" + token.getCanonicalName());
        }
        if (Modifier.isFinal(token.getModifiers())) {
            throw new ImplerException("Cannot extends of final class" + token.getCanonicalName());
        }
        if (hasNonPrivateConstructors(token)) {
            throw new ImplerException(token.getCanonicalName() + "has non-private constructor");
        }

        Path path = root.resolve(getPathGeneratedClass(token));
        Utils.tryToCreateDirectory(path);
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            getStruct(token);
            try {
                writer.write(Utils.builderToUnicodeString(builder));
            } catch (IOException e) {
                throw new ImplerException("Failed to write record", e);
            }
        } catch (IOException e) {
            throw new ImplerException("Failed to open file: " + path, e);
        }
    }

    /**
     * Returns the relative path to the generated source file for the given token.
     *
     * @param token to implement or extended.
     * @return the path to the generated .java file
     */
    private static String getPathGeneratedClass(Class<?> token) {
        return (token.getPackageName() + "." + token.getSimpleName()).replace('.', File.separatorChar) + "Impl.java";
    }

    /**
     * Check the class has only private constructors. It is used to check the possibility of inheriting from a class.
     *
     * @param token the class to check
     * @return true if all constructors are private or if class is interface
     */
    private static boolean hasNonPrivateConstructors(Class<?> token) {
        return !token.isInterface() && Arrays.stream(token.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
    }


    /**
     * Returns the generated class name for a token.
     *
     * @param token to implement or extended.
     * @return the name of the generated class
     */
    private static String getName(Class<?> token) {
        return token.getSimpleName() + "Impl";
    }

    /**
     * Returns the succession clause for the generated class ("implements" or "extends").
     *
     * @param token to implement or extended.
     * @return the succession clause as a string
     */
    private static String getSuccession(Class<?> token) {
        return (token.isInterface() ? "implements " : "extends ") + token.getCanonicalName();
    }

    /**
     * Determines if a state class is assignable from a current class.
     *
     * @param state   the parent or interface class
     * @param current the current class
     * @return true if assignable
     */

    private static boolean checkAssignable(Class<?> state, Class<?> current) {
        if (state.isInterface() && !current.isInterface()) {
            return true;
        }
        if (current.isInterface() && !state.isInterface()) {
            return false;
        }
        return state.isAssignableFrom(current);
    }

    /**
     * Returns a stream of abstract methods that need to be overridden for a class.
     * It is selected by the furthest descendant, since the field of view can only be expanded.
     *
     * @param token to implement or extended.
     * @return stream of methods to implement
     * @see Implementor#checkAssignable(Class, Class)
     */
    private static Stream<Method> getStreamOverriddenMethods(Class<?> token) {
        Stream<Method> stream = Arrays.stream(token.getMethods());
        do {
            stream = Stream.concat(stream, Arrays.stream(token.getDeclaredMethods()));
            token = token.getSuperclass();
        } while (token != null);

        Map<Signature, Method> map = stream.collect(
                Collectors.groupingBy(Signature::new, HashMap::new, Utils.getMethodCollector())
        );
        return map.values().stream().filter(method -> Modifier.isAbstract(method.getModifiers()));
    }

    /**
     * Returns the public method code for a given method. Used to generate each method in
     * {@link Implementor#addMethods(Class)}
     *
     * @param method the constructor
     * @return constructor code as {@link String}
     */
    private static String getMethod(Method method) {
        return String.format("public %s %s(%s) %s{ return%s;}%n",
                method.getReturnType().getCanonicalName(), method.getName(),
                getParameters(method.getParameters()), getExceptions(method.getExceptionTypes()),
                getReturnValue(method.getReturnType()));
    }

    /**
     * Returns the default value for the type. It also checks whether the return value class can be accessed.
     *
     * @param returnType the type
     * @return a string representing the default value to be returned
     */
    private static String getReturnValue(Class<?> returnType) {
        if (Modifier.isPrivate(returnType.getModifiers())) {
            throw new AccessClassException("Private return type " + returnType.getCanonicalName());
        }

        if (returnType.isPrimitive()) {
            if (returnType == boolean.class) {
                return " false";
            } else if (returnType == void.class) {
                return "";
            } else {
                return " 0";
            }
        } else {
            return " null";
        }
    }

    /**
     * Collects method or constructor parameters. It also checks everyone's ability to access
     *
     * @param parameters the parameters array
     * @return comma-separated string of parameters with types
     */
    private static String getParameters(Parameter[] parameters) {
        for (Parameter param : parameters) {
            if (Modifier.isPrivate(param.getType().getModifiers())) {
                throw new AccessClassException("Private parameter " + param.getType().getCanonicalName());
            }
        }

        return Arrays.stream(parameters).map(
                parameter -> parameter.getType().getCanonicalName() + " " + parameter.getName()
        ).collect(Collectors.joining(", "));
    }

    /**
     * Collect a list of exceptions thrown by the method or constructor. If nothing is thrown, returns an empty string.
     *
     * @param exceptionTypes array of exception classes
     * @return string like "throws ..."
     */
    private static String getExceptions(Class<?>[] exceptionTypes) {
        if (exceptionTypes.length == 0) {
            return "";
        }
        return "throws " + Arrays.stream(exceptionTypes).map(Class::getCanonicalName).collect(Collectors.joining(", "));
    }

    /**
     * Generates the base code for this class. Adds a package and calls {@link Implementor#addClass(Class)}
     *
     * @param token to implement or extended.
     * @throws ImplerException if the generation fails. Catch the {@link AccessClassException} and wrap to the {@code ImplerException}
     */
    private void getStruct(Class<?> token) throws ImplerException {
        clear();
        builder.append(token.getPackage()).append(";").append(System.lineSeparator()).append(System.lineSeparator());
        try {
            addClass(token);
        } catch (AccessClassException e) {
            throw new ImplerException("Problems with private classes during generation", e);
        }
    }

    /**
     * Adds class. Adds signature and body to the builder.
     *
     * @param token to implement or extended.
     */
    private void addClass(Class<?> token) {
        builder.append(String.format("public class %s %s {%n", getName(token), getSuccession(token)));
        addBody(token);
        builder.append(System.lineSeparator()).append("}").append(System.lineSeparator());
    }

    /**
     * Adds class body. Adds constructors and methods to the builder.
     *
     * @param token to implement or extended.
     */
    private void addBody(Class<?> token) {
        addConstructors(token.getDeclaredConstructors(), getName(token));
        addMethods(token);
    }

    /**
     * Adds methods to the builder. For more information about the choice of methods see
     * {@link Implementor#getStreamOverriddenMethods(Class)}
     *
     * @param token to implement or extended.
     */
    private void addMethods(Class<?> token) {
        getStreamOverriddenMethods(token).forEach(
                method -> builder.append("    ").append(getMethod(method)).append(System.lineSeparator())
        );
    }

    /**
     * Adds non-private constructors to the builder.
     *
     * @param constructors array of constructors
     * @param className    name of the generated class
     */
    private void addConstructors(Constructor<?>[] constructors, String className) {
        Arrays.stream(constructors).filter(constructor -> !Modifier.isPrivate(constructor.getModifiers()))
                .forEach(constructor ->
                        builder.append("    ").append(getConstructor(constructor, className)).append(System.lineSeparator())
                );
    }

    /**
     * Returns the public constructor code for a given constructor. Used to generate each constructor in
     * {@link Implementor#addConstructors(Constructor[], String)}
     *
     * @param constructor the constructor
     * @param className   name of the generated class
     * @return constructor code as {@link String}
     */
    private static String getConstructor(Constructor<?> constructor, String className) {
        return String.format("public %s(%s) %s { super(%s); }",
                className, getParameters(constructor.getParameters()),
                getExceptions(constructor.getExceptionTypes()),
                Arrays.stream(constructor.getParameters()).map(Parameter::getName).collect(Collectors.joining(", "))
        );
    }

    /**
     * Clears the internal {@link StringBuilder}.
     */
    private void clear() {
        builder.setLength(0);
    }

    /**
     * An internal class for the possibility of using a {@link Method} in a {@link HashSet} based on the method signature.
     *
     * @see Implementor#getStreamOverriddenMethods(Class)
     */
    private static class Signature {
        /**
         * name of the method
         */
        private final String name;
        /**
         * return type of the method
         */
        private final Class<?> returnType;
        /**
         * array of parameter types of the method
         */
        private final Class<?>[] parameterTypes;
        /**
         * hash of Signature
         */
        private int hash;

        /**
         * Creating an instance using the {@link Method}
         *
         * @param method one of created signature
         */
        public Signature(Method method) {
            this.name = method.getName();
            this.returnType = method.getReturnType();
            this.parameterTypes = method.getParameterTypes();
            hash = 0;
        }

        /**
         * Redefined comparison method. Unlike the comparison, {@link Method} does not take into account the class.
         *
         * @see Method#equals(Object)
         */
        @Override
        public boolean equals(Object o) {
            if (o instanceof Signature sig) {
                if (name.equals(sig.name) && returnType.equals(sig.returnType)) {
                    return Arrays.equals(parameterTypes, sig.parameterTypes);
                }
                return false;
            }

            return false;
        }

        /**
         * An overridden method for calculating the hash. Unlike {@link Method} does not take into account the class.
         *
         * @see Method#hashCode()
         */
        @Override
        public int hashCode() {
            if (hash == 0) {
                hash = Objects.hash(name, returnType, Arrays.hashCode(parameterTypes));
            }
            return hash;
        }

    }

    /**
     * Utility class for compilation, file handling, and string formatting.
     */
    private static class Utils {
        /**
         * Private constructor for the inability to create an instance of the class.
         */
        private Utils() {
        }

        /**
         * Compiles the generated source file.
         *
         * @param file  path where will be the class-file
         * @param token class that will compile
         * @throws AssertionError if the compilation failed.
         */
        private static void compile(final Path file, final Class<?> token) {
            final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            final String classpath = getClassPath(token).toString();
            final String[] args = {"-cp", classpath, "-encoding", StandardCharsets.UTF_8.name(), file.toString()};
            final int exitCode = compiler.run(null, null, null, args);
            if (exitCode != 0) {
                throw new AssertionError("Compilation failed with exit code " + exitCode);
            }
        }

        /**
         * Converts the generated code into a unicode-escaped string.
         *
         * @param builder source of bytes which should be converted to Unicode symbols
         * @return Returns a string ready to be written to a file.
         */
        private static String builderToUnicodeString(StringBuilder builder) {
            final int border = 128;

            StringBuilder collectingUni = new StringBuilder();
            for (int i = 0; i < builder.length(); i++) {
                char c = builder.charAt(i);
                collectingUni.append(c < border ? c : String.format("\\u%04x", (int) c));
            }

            return collectingUni.toString();
        }

        /**
         * Returns JAR entry name for a class.
         *
         * @param token class for which it will be returned entry name
         * @return entry name like {@link String}
         */
        private static String getEntryName(Class<?> token) {
            return token.getPackageName().replace('.', '/') + '/' + getName(token) + ".class";
        }

        /**
         * Returns path of the class for classpath.
         *
         * @param dependency try to get class-path to this
         * @return path to this class
         */
        private static Path getClassPath(final Class<?> dependency) {
            try {
                return Path.of(dependency.getProtectionDomain().getCodeSource().getLocation().toURI());
            } catch (final URISyntaxException e) {
                throw new AssertionError(e);
            }
        }

        /**
         * Returns a collector to select a single method per signature. It is selected by the most expanding descendant
         *
         * @return {@link Collector} for {@link Method}
         */
        private static Collector<Method, ?, Method> getMethodCollector() {
            class State {
                Method method;
            }
            return Collector.of(
                    State::new,
                    (state, current) -> {
                        if (state.method == null) {
                            state.method = current;
                            return;
                        }

                        state.method = checkAssignable(state.method.getDeclaringClass(), current.getDeclaringClass()) ? current : state.method;
                    },
                    (_, _) -> null,
                    state -> state.method,
                    Collector.Characteristics.UNORDERED
            );
        }

        /**
         * Attempts to create parent directories if missing.
         *
         * @param directories {@link Path} the path that will be created.
         */
        private static void tryToCreateDirectory(Path directories) {
            final Path directory = directories.getParent();
            if (directory != null && !Files.exists(directory)) {
                try {
                    Files.createDirectories(directories.getParent());
                } catch (final IOException e) {
                    System.err.println("Error creating directory: " + e.getMessage());
                }
            }
        }

        /**
         * Deletes temporary files and directories.
         *
         * @param temp {@link Path} file or directory that needs to be deleted
         * @throws IOException if walk is failed
         */
        private static void clearTemp(Path temp) throws IOException {
            if (temp == null) {
                return;
            }

            Files.walkFileTree(temp, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /**
     * A local unchecked exception. It is thrown if it is impossible to access the classes present in the method signatures.
     */
    private static class AccessClassException extends RuntimeException {
        /**
         * Constructor for creating an exception.
         *
         * @param message {@link String} message with cause
         */
        private AccessClassException(String message) {
            super(message);
        }
    }
}