package com.springaimcpservercommon.jfranalyzer.collect;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StackResolverTest {

    @Test
    void descriptorsBecomeReadableParameterLists() {
        assertThat(StackResolver.parameters("()V")).isEmpty();
        assertThat(StackResolver.parameters("(I[Ljava/lang/String;J)V")).isEqualTo("int, String[], long");
        assertThat(StackResolver.parameters("([[DLjava/util/Map;Z)Ljava/lang/Object;"))
                .isEqualTo("double[][], Map, boolean");
    }

    @Test
    void fileNameComesFromTheOutermostClass() {
        assertThat(StackResolver.fileName("com.acme.Outer$Inner$1")).isEqualTo("Outer.java");
        assertThat(StackResolver.fileName("Top")).isEqualTo("Top.java");
        assertThat(StackResolver.fileName("com.acme.Foo$$Lambda/0x0000000801001234")).isEqualTo("Foo.java");
        assertThat(StackResolver.fileName("com.acme.Foo$$Lambda.0x0000000062046420")).isEqualTo("Foo.java");
    }

    @Test
    void arrayTypesReadAsSource() {
        assertThat(Fields.typeName("[B")).isEqualTo("byte[]");
        assertThat(Fields.typeName("[[Ljava.lang.String;")).isEqualTo("java.lang.String[][]");
        assertThat(Fields.typeName("java.util.HashMap$Node")).isEqualTo("java.util.HashMap$Node");
    }

    @Test
    void locationLooksLikeAStackTraceLine() {
        MethodInfo m = new MethodInfo("k", "com.acme.Foo", "bar", "com.acme.Foo.bar()", "Foo.java", true);
        assertThat(m.location(42, "JIT compiled")).isEqualTo("com.acme.Foo.bar(Foo.java:42)");
        assertThat(m.location(-1, "Interpreted")).isEqualTo("com.acme.Foo.bar(Foo.java)");
        assertThat(m.location(0, "Native")).isEqualTo("com.acme.Foo.bar(Native Method)");
    }
}
