"""Exercise unchanged Android production wire code against integrated Alice.

No Gradle, Android build, phone or network dependency resolution. Requires the
already cached project Kotlin/JVM dependencies; missing artifacts fail, never skip.
Only generated BuildConfig (a build-time constant) is supplied by this harness.
No production DTO, serializer, HTTP client or response parser is substituted.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def run(evidence: Path, cache: Path):
    evidence.mkdir(parents=True, exist_ok=True)
    def jar(group, name, version):
        matches = list((cache / group / name / version).glob(f"*/{name}-{version}.jar"))
        if len(matches) != 1:
            raise RuntimeError(f"exact cached dependency missing or ambiguous: {group}:{name}:{version}")
        return matches[0]
    compiler = jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.4.10")
    stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.10")
    coroutines = jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.11.0")
    annotations = jar("org.jetbrains", "annotations", "23.0.0")
    compiler_jars = [compiler, stdlib, coroutines, annotations,
        jar("org.jetbrains.kotlin", "kotlin-script-runtime", "2.4.10"),
        jar("org.jetbrains.kotlin", "kotlin-reflect", "2.3.21"),
        jar("org.jetbrains.kotlin", "kotlin-daemon-embeddable", "2.4.10")]
    runtime = [stdlib, coroutines, annotations,
        jar("com.google.code.gson", "gson", "2.10.1"),
        jar("com.squareup.okhttp3", "okhttp", "4.12.0"),
        jar("com.squareup.okio", "okio-jvm", "3.9.1"),
        jar("androidx.room", "room-common-jvm", "2.8.4")]
    main = ROOT / "app/src/main/java/com/polinalinen/madre"
    sources = [main / "integration/v2" / n for n in
        ("RosterV2.kt", "ContractHttp.kt", "StrictJson.kt", "InstallationPairConsent.kt")]
    sources += [main / "config/FeatureFlags.kt", main / "data/db/entities/VoiceCommandReceipt.kt",
        ROOT / "backend/tests/android_producer/Probe.kt"]
    cp = os.pathsep.join(map(str, runtime))
    with tempfile.TemporaryDirectory(prefix="madre-actual-producer-", dir=os.environ["TMPDIR"]) as tmp:
        work = Path(tmp)
        # The Android plugin normally generates this one build-time constant.
        generated = work / "BuildConfig.kt"
        generated.write_text("package com.polinalinen.madre\nobject BuildConfig { const val YANDEX_ID = false }\n")
        classes = work / "classes"
        args = ["java", "-Xmx512m", "-cp", os.pathsep.join(map(str, compiler_jars)),
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
            "-classpath", cp, "-d", str(classes), *map(str, sources), str(generated)]
        compiled = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, timeout=120)
        (evidence / "producer-compile.log").write_text(compiled.stdout + compiled.stderr)
        if compiled.returncode:
            raise RuntimeError(f"actual Kotlin source compile failed; see {evidence}/producer-compile.log")
        sys.path[:0] = [str(ROOT / "backend/alice"), str(ROOT / "backend/alice/tests")]
        import importlib
        InstallationPairHTTP = importlib.import_module("test_installation_pair").InstallationPairHTTP
        fixture = InstallationPairHTTP()
        fixture.setUp()
        errors = (evidence / "producer-stderr.log").open("w")
        process = subprocess.Popen(["java", "-Xmx256m", "-cp", str(classes)+os.pathsep+cp,
            "com.polinalinen.madre.integration.v2.ProbeKt"], stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=errors, text=True)
        assert process.stdin is not None and process.stdout is not None
        writer, reader = process.stdin, process.stdout
        checks = []
        def receive():
            import select
            if not select.select([reader], [], [], 15)[0]:
                raise RuntimeError("actual producer response timeout")
            line = reader.readline()
            if not line:
                raise RuntimeError("actual producer exited; inspect producer-stderr.log")
            return json.loads(line)
        def call(op):
            writer.write(json.dumps(dict(op=op))+"\n"); writer.flush()
            return receive()
        def check(name, condition):
            if not condition:
                raise AssertionError(name)
            checks.append(name)
        try:
            writer.write(json.dumps(dict(port=fixture.port, credential=fixture.credential))+"\n")
            writer.flush()
            check("real_jvm_ready", receive() == {"ready": True})
            check("no_implicit_authority", call("attach") == {"failure": "PAIR_REQUIRED"})
            check("actual_pair_serializer", call("pair")["requestId"] != "")
            code = fixture.code(consent=False)
            status, pair = fixture.exchange(code)
            check("real_oauth_redemption", status == 200)
            fixture.access = pair["access_token"]
            check("actual_attach_parser", call("attach") == {"attached": True})
            check("actual_empty_roster", call("empty")["commands"] == 0)
            check("actual_four_state_roster", call("poll")["commands"] == 0)
            check("repeat_attach_monotonic", call("attach") == {"attached": True} and call("poll")["commands"] == 0)
            for number, text in ((3, "ещё не идёт"), (4, "Впишите")):
                check(f"readonly_{number}", text in fixture.speech(f"пауза {number}"))
            check("numbered_confirmation", "Скажите" in fixture.speech("пауза один"))
            fixture.speech("да")
            command = call("poll")
            check("actual_command_parser", command == {"commands": 1, "operation": "PAUSE", "bake_number": 1})
            check("actual_ack_serializer", call("ack") == {"acknowledged": True})
            check("actual_duplicate_ack", call("ack") == {"acknowledged": True})
            check("no_command_replay", call("poll")["commands"] == 0)
            check("full_empty_replacement", call("empty")["commands"] == 0 and "ничего не печётся" in fixture.speech("что печется"))
            check("actual_disconnect_204", call("disconnect") == {"disconnected": True})
            check("revoked_poll_rejected", call("empty") == {"failure": "CONFLICT"})
            check("revoked_bearer_rejected", fixture.webhook("да", fixture.access)[0] == 401)
            writer.write('{"op":"stop"}\n'); writer.flush()
            check("jvm_clean_exit", process.wait(timeout=15) == 0)
        finally:
            if process.poll() is None:
                process.terminate(); process.wait(timeout=15)
            errors.close()
            fixture.tearDown()
    report = dict(status="passed", checks=checks, count=len(checks),
        source_sha256={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
        dependency_sha256={str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in set(runtime+compiler_jars)},
        limitation="Synthetic receipt only; not protected Android executor/Room/phone/Station acceptance. No Gradle.")
    (evidence / "producer-result.json").write_text(json.dumps(report, indent=2)+"\n")
    print(json.dumps(dict(status=report["status"], count=report["count"], checks=checks), indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", required=True, type=Path)
    parser.add_argument("--cache", type=Path, default=Path.home()/".gradle/caches/modules-2/files-2.1")
    options = parser.parse_args()
    run(options.evidence.resolve(), options.cache.resolve())