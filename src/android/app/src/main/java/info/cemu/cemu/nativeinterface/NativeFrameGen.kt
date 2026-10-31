package info.cemu.cemu.nativeinterface

object NativeFrameGen {
    object LosslessDllStatus {
        const val OK: Int = 0
        const val NOT_INSTALLED: Int = 1
        const val UNREADABLE_FILE: Int = 2
        const val NOT_PORTABLE_EXECUTABLE: Int = 3
        const val MISSING_SHADERS: Int = 4
        const val TRANSLATION_FAILED: Int = 5
    }

    object RuntimeStatus {
        const val INACTIVE: Int = 0
        const val ACTIVE: Int = 1
        const val UNSUPPORTED_DEVICE: Int = 2
        const val DLL_UNAVAILABLE: Int = 3
        const val INIT_FAILED: Int = 4
    }

    const val FLOW_SCALE_AUTO: Int = 0

    @JvmStatic
    external fun getLosslessDllPath(): String

    @JvmStatic
    external fun validateLosslessDll(path: String): Int

    @JvmStatic
    external fun getInstalledLosslessDllStatus(): Int

    @JvmStatic
    external fun notifyLosslessDllChanged()

    @JvmStatic
    external fun removeLosslessDll(): Boolean

    @JvmStatic
    external fun getRuntimeStatus(): Int

    @JvmStatic
    external fun isEnabled(): Boolean

    @JvmStatic
    external fun setEnabled(enabled: Boolean)

    @JvmStatic
    external fun getMultiplier(): Int

    @JvmStatic
    external fun setMultiplier(multiplier: Int)

    @JvmStatic
    external fun getTargetRate(): Int

    @JvmStatic
    external fun setTargetRate(targetRate: Int)

    @JvmStatic
    external fun getFlowScale(): Int

    @JvmStatic
    external fun setFlowScale(flowScale: Int)
}
