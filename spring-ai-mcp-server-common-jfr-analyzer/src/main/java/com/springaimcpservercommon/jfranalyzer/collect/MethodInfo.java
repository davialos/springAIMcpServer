package com.springaimcpservercommon.jfranalyzer.collect;

/**
 * A resolved Java method, shared by every frame that refers to it.
 *
 * @param key        unique key: class, name and descriptor
 * @param className  fully qualified (binary) class name
 * @param methodName method name
 * @param display    {@code pkg.Class.method(int, String)}
 * @param fileName   source file inferred from the outermost class name, e.g. {@code Foo.java}
 * @param inPackage  whether the class is in a requested package
 */
public record MethodInfo(String key, String className, String methodName, String display, String fileName,
                         boolean inPackage) {

    /**
     * @param line      source line, {@code <= 0} if unknown
     * @param frameType JFR frame type
     * @return stack-trace style location, e.g. {@code com.acme.Foo.bar(Foo.java:42)}
     */
    public String location(int line, String frameType) {
        String where = "Native".equals(frameType) ? "Native Method"
                : line > 0 ? fileName + ":" + line : fileName;
        return className + "." + methodName + "(" + where + ")";
    }
}
