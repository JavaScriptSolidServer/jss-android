package live.jss.jss_android

/**
 * Thin Kotlin wrapper around the JNI entry point. Loads libnode.so + our
 * shim once per process (System.loadLibrary is idempotent, but a companion
 * object init is the conventional spot).
 */
object NodeBridge {

    init {
        System.loadLibrary("node")        // libnode.so from nodejs-mobile
        System.loadLibrary("native-lib")  // our JNI shim
    }

    /**
     * Boot Node with the given args. Blocks the calling thread for as long as
     * the Node main loop runs. Caller should invoke this from a dedicated
     * worker thread (see [JssService]).
     *
     * Returns Node's exit code; 0 on graceful shutdown, non-zero on error.
     */
    @JvmStatic
    external fun startNodeWithArguments(args: Array<String>): Int
}
