package app.venus.kshim

/**
 * Minimal replacement for the kotlin runtime classes the extension dexes reference
 * (Intrinsics null checks, Ref closure boxes, TypeIntrinsics). Discord 351.11 ships the real
 * kotlin.jvm.internal.* classes unobfuscated, but 354.12 and newer obfuscate them away, which
 * made every extension callback die with NoClassDefFoundError. The extension dexes' references
 * are rewritten to this package by scripts/rename_dex.kt, so these classes can't collide with a
 * real kotlin stdlib on either target. This file is compiled into the i18n extension, which
 * Conduit settings always merges.
 */
public object Intrinsics {
    @JvmStatic
    public fun areEqual(a: Any?, b: Any?): Boolean = if (a == null) b == null else a.equals(b)

    @JvmStatic
    public fun checkNotNull(value: Any?): Unit {
        if (value == null) throw NullPointerException()
    }

    @JvmStatic
    public fun checkNotNull(value: Any?, message: String?): Unit {
        if (value == null) throw NullPointerException(message)
    }

    @JvmStatic
    public fun checkNotNullExpressionValue(value: Any?, message: String?): Unit {
        if (value == null) throw NullPointerException(message)
    }

    @JvmStatic
    public fun checkNotNullParameter(value: Any?, message: String?): Unit {
        if (value == null) throw NullPointerException("Parameter specified as non-null: " + message)
    }
}

public object TypeIntrinsics {
    @JvmStatic
    public fun isMutableMap(value: Any?): Boolean = value is java.util.Map<*, *>
}

public class `Ref$ObjectRef` {
    @JvmField public var element: Any? = null
}

public class `Ref$IntRef` {
    @JvmField public var element: Int = 0
}

public class `Ref$BooleanRef` {
    @JvmField public var element: Boolean = false
}

public class `Ref$LongRef` {
    @JvmField public var element: Long = 0L
}
