package com.github.lystran.mochat.runtime;

public final class NativeRuntimeDefaults {
    private static final String NATIVE_IMAGE_CODE_PROPERTY = "org.graalvm.nativeimage.imagecode";

    private NativeRuntimeDefaults() {
    }

    public static void apply() {
        if (!"runtime".equals(System.getProperty(NATIVE_IMAGE_CODE_PROPERTY))) {
            return;
        }

        setDefault("io.netty.allocator.type", "unpooled");
        setDefault("io.netty.noUnsafe", "true");
        setDefault("io.netty.noPreferDirect", "true");
    }

    private static void setDefault(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }
}
