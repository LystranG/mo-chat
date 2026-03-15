package com.github.lystran.mochat.runtime;

/**
 * 在原生镜像环境下补上一些更稳妥的默认 JVM 属性。
 */
public final class NativeRuntimeDefaults {
    private static final String NATIVE_IMAGE_CODE_PROPERTY = "org.graalvm.nativeimage.imagecode";

    /**
     * 防止被当成普通工具类实例化。
     */
    private NativeRuntimeDefaults() {
    }

    /**
     * 只在原生镜像运行时补默认属性，普通 JVM 运行不动现有配置。
     */
    public static void apply() {
        if (!"runtime".equals(System.getProperty(NATIVE_IMAGE_CODE_PROPERTY))) {
            return;
        }

        setDefault("io.netty.allocator.type", "unpooled");
        setDefault("io.netty.noUnsafe", "true");
        setDefault("io.netty.noPreferDirect", "true");
    }

    /**
     * 只有调用方还没手动设值时，才补默认属性。
     */
    private static void setDefault(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }
}
