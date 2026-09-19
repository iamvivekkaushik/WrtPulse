package com.vivekkaushik.wrtpulse.net

/**
 * A router reached THROUGH another one. Every command is run on [via] as a `dbclient` call to
 * [target], with the password handed over in dropbear's environment variable so nothing
 * prompts. That is how a brand-new router, cabled by its LAN socket into the primary, gets
 * set up without the phone ever leaving the primary's Wi-Fi: its firewall admits SSH on the
 * LAN side, the primary sits on that side, and the primary's own dropbear ships the client.
 *
 * The address is IPv6 link-local with the primary's setup bridge as scope, which needs no
 * agreement about IPv4 subnets — the new router can be 192.168.1.1 while the primary is too.
 * Only [exec] and [execWithInput] mean anything here; there is no shell and no reconnect,
 * because there is no connection of its own to keep.
 */
class JumpSession(
    private val via: RouterSession,
    target: SshTarget,
    private val password: String,
) : RouterSession(target, NoClient, { SshAuth.Password(CharArray(0)) }, maxAttempts = 1) {

    override val isConnected: Boolean get() = via.isConnected

    /**
     * Two dropbears see this command: the primary's, as the `dbclient` line, and the new
     * router's, as what that line asks it to run. Past the exec limit on either, the command
     * goes over as a script on stdin, through both — the line the primary runs stays short.
     */
    override suspend fun exec(command: String, timeoutMs: Long): ExecResult {
        val line = wrap(target, password, command)
        return if (fitsExec(line)) via.exec("$line </dev/null", timeoutMs + HOP_MS)
        else via.execWithInput(wrap(target, password, STDIN_SCRIPT), command.toByteArray(), timeoutMs + HOP_MS)
    }

    override suspend fun execWithInput(command: String, input: ByteArray, timeoutMs: Long): ExecResult =
        via.execWithInput(wrap(target, password, command), input, timeoutMs + HOP_MS)

    /** Nothing of its own to close; the session it rides stays the caller's. */
    override suspend fun disconnect() {}

    private object NoClient : SshClient {
        override suspend fun probeHostKey(target: SshTarget): HostKey = throw SshException.Unreachable(target, null)
        override suspend fun connect(target: SshTarget, auth: SshAuth, connectTimeoutMs: Long): SshConnection =
            throw SshException.Unreachable(target, null)
    }

    companion object {
        /** The hop itself: dial, auth, and the remote shell starting, on top of the command's own budget. */
        const val HOP_MS = 8_000L

        /**
         * The line the primary runs. `-y -y` takes the host key without asking and without
         * remembering it — the primary is a conduit here, not the one doing the trusting; the app
         * pins the new router's key itself the first time it connects directly. The remote
         * command travels as one single-quoted argument, so dropbear hands it to the far shell
         * verbatim: the only character to escape is the quote.
         */
        fun wrap(target: SshTarget, password: String, command: String): String =
            "DROPBEAR_PASSWORD='" + shellQuote(password) + "' dbclient -y -y -p " + target.port +
                " '" + shellQuote(target.username) + "@" + shellQuote(target.host) + "' '" + shellQuote(command) + "'"

        fun shellQuote(value: String): String = value.replace("'", "'\\''")
    }
}
