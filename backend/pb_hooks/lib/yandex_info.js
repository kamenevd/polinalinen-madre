/** Fixed-origin, no-redirect Yandex boundary. Never accepts a URL from input/env.
 * PB 0.39.11 $http.send follows redirects and buffers unbounded responses.
 * Use the installed curl with config on an anonymous stdin pipe instead:
 * no shell, no token in argv/env/files, no curlrc/proxy/cookie/cache/verbose.
 * There is intentionally NO test endpoint, token allowlist or auth bypass.
 */
module.exports = function (token) {
    // ASCII token validation is done before constructing curl's quoted config.
    const config = [
        'url = "https://login.yandex.ru/info?format=json"',
        'request = "GET"',
        'header = "Authorization: OAuth ' + token + '"',
        'header = "Accept: application/json"',
        'proto = "=https"',
        'max-redirs = 0',
        'connect-timeout = 3',
        'max-time = 5',
        'max-filesize = 65536',
        'proxy = ""',
        'noproxy = "*"',
        'silent',
        'write-out = "\\n%{http_code}"',
    ].join("\n") + "\n";
    const cmd = $os.cmd("/usr/bin/curl", "-q", "--config", "-");
    cmd.env = ["PATH=/usr/bin:/bin", "LANG=C"];
    const pipe = cmd.stdinPipe();
    const stdout = cmd.stdoutPipe();
    cmd.start();
    let raw;
    try {
        // The token never appears in command arguments, stderr or a temp file.
        try { pipe.write(toBytes(config)); } finally { pipe.close(); }
        // PB 0.39.11 exposes Go's bounded-reader conversion. This also bounds
        // responses without Content-Length on older curl installations.
        raw = toString(stdout, 65541);
    } finally {
        stdout.close();
        cmd.wait(); // reap this child even on a bounded-read/transport failure
    }
    const split = raw.lastIndexOf("\n");
    if (split < 0 || split > 65536) { throw new Error("provider_unavailable"); }
    const status = raw.slice(split + 1);
    if (status === "401" || status === "403") { throw new Error("unauthorized"); }
    if (status !== "200") { throw new Error("provider_unavailable"); }
    return JSON.parse(raw.slice(0, split));
};
