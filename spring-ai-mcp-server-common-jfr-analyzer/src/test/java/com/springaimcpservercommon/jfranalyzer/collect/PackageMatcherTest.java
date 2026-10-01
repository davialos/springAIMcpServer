package com.springaimcpservercommon.jfranalyzer.collect;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PackageMatcherTest {

    @Test
    void prefixMatchesPackageAndSubPackagesOnly() {
        PackageMatcher m = new PackageMatcher(List.of("com.acme"), List.of());
        assertThat(m.matches("com.acme.Foo")).isTrue();
        assertThat(m.matches("com.acme.order.Bar$Inner")).isTrue();
        assertThat(m.matches("com.acmexyz.Baz")).isFalse();
        assertThat(m.matches("org.other.Foo")).isFalse();
        assertThat(m.restricted()).isTrue();
    }

    @Test
    void wildcardsSlashesAndClassNamesAreAccepted() {
        PackageMatcher m = new PackageMatcher(List.of("com.acme.*", "org/example/", "net.x.Service"), List.of());
        assertThat(m.matches("com.acme.Foo")).isTrue();
        assertThat(m.matches("org.example.Foo")).isTrue();
        assertThat(m.matches("net.x.Service$$SpringCGLIB$$0")).isTrue();
        assertThat(m.matches("net.x.ServiceImpl")).isFalse();
    }

    @Test
    void exclusionsWin() {
        PackageMatcher m = new PackageMatcher(List.of("com.acme"), List.of("com.acme.generated"));
        assertThat(m.matches("com.acme.Foo")).isTrue();
        assertThat(m.matches("com.acme.generated.Proxy")).isFalse();
    }

    @Test
    void noIncludesMatchesEverythingNotExcluded() {
        PackageMatcher m = new PackageMatcher(List.of(), List.of("java"));
        assertThat(m.matches("com.acme.Foo")).isTrue();
        assertThat(m.matches("java.lang.String")).isFalse();
        assertThat(m.restricted()).isFalse();
    }
}
