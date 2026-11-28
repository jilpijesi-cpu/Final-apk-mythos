#!/usr/bin/env python3
"""Smoke-test ordered Codex App Server streaming events from ModelCall.kt.

Requirements: Python 3 and Node.js. Uses a fake `codex` executable; no login/network is needed.
Run from the project root: python3 tools/test_codex_stream_protocol.py
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import tempfile
import textwrap
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODEL_CALL = ROOT / "app/src/main/java/com/build/mythos/ModelCall.kt"

FAKE_SERVER = r'''\
const readline = require('readline');
const rl = readline.createInterface({input: process.stdin, crlfDelay: Infinity});
let thread = 'thread-test';
function send(x) { process.stdout.write(JSON.stringify(x) + '\n'); }
rl.on('line', line => {
  let m; try { m = JSON.parse(line); } catch (_) { return; }
  if (m.method === 'initialize') send({id: m.id, result: {serverInfo: {name: 'fake'}}});
  else if (m.method === 'thread/start') send({id: m.id, result: {thread: {id: thread}}});
  else if (m.method === 'turn/start') {
    send({id: m.id, result: {turn: {id: 'turn-test'}}});
    send({method: 'item/started', params: {threadId: thread, item: {id: 'msg1', type: 'agentMessage'}}});
    send({method: 'item/agentMessage/delta', params: {threadId: thread, itemId: 'msg1', delta: 'Saya mulai memeriksa. '}});
    send({method: 'item/started', params: {threadId: thread, item: {id: 'cmd1', type: 'commandExecution', command: 'cat README.md', cwd: '/tmp'}}});
    send({method: 'item/completed', params: {threadId: thread, item: {id: 'cmd1', type: 'commandExecution', command: 'cat README.md', cwd: '/tmp', exitCode: 0, status: 'completed'}}});
    send({method: 'item/started', params: {threadId: thread, item: {id: 'msg2', type: 'agentMessage'}}});
    send({method: 'item/agentMessage/delta', params: {threadId: thread, itemId: 'msg2', delta: 'Perbaikan selesai. ✅'}});
    send({method: 'item/completed', params: {threadId: thread, item: {id: 'msg2', type: 'agentMessage', text: 'Perbaikan selesai. ✅'}}});
    send({method: 'turn/completed', params: {threadId: thread, turn: {id: 'turn-test', status: 'completed'}}});
  }
});
'''


def main() -> int:
    node = shutil.which("node")
    if not node:
        print("SKIP: Node.js is required for this smoke test.")
        return 2
    src = MODEL_CALL.read_text(encoding="utf-8")
    match = re.search(r'val js = """\n(.*?)\n"""\.trimIndent\(\)', src, re.S)
    if not match:
        raise AssertionError("Could not extract embedded Codex App Server wrapper from ModelCall.kt")
    wrapper = textwrap.dedent(match.group(1)) + "\n"
    with tempfile.TemporaryDirectory(prefix="mythos-codex-protocol-") as td:
        tmp = Path(td)
        js_path = tmp / "wrapper.js"
        js_path.write_text(wrapper, encoding="utf-8")
        check = subprocess.run([node, "--check", str(js_path)], capture_output=True, text=True)
        if check.returncode:
            raise AssertionError("Node wrapper syntax error: " + check.stderr)
        server_path = tmp / "fake-server.js"
        server_path.write_text(textwrap.dedent(FAKE_SERVER).lstrip("\\\n"), encoding="utf-8")
        bin_dir = tmp / "bin"
        bin_dir.mkdir()
        codex = bin_dir / "codex"
        codex.write_text(f'#!/bin/sh\nexec "{node}" "{server_path}"\n', encoding="utf-8")
        codex.chmod(0o755)
        env = dict(os.environ)
        env["PATH"] = str(bin_dir) + os.pathsep + env.get("PATH", "")
        run = subprocess.run([node, "-e", wrapper, "--", "test prompt"], env=env, capture_output=True, text=True, timeout=15)
        if run.returncode != 0:
            raise AssertionError(f"Wrapper failed ({run.returncode}): stderr={run.stderr!r}, stdout={run.stdout!r}")
        rows = [json.loads(line) for line in run.stdout.splitlines() if line.strip()]
        delta_text = "".join(row.get("text", "") for row in rows if row.get("type") == "delta")
        activity_types = [row.get("event", {}).get("type") for row in rows if row.get("type") == "activity"]
        activity_items = [row.get("event", {}).get("item", {}) for row in rows if row.get("type") == "activity"]
        types = [row.get("type") for row in rows]
        assert delta_text == "Saya mulai memeriksa. Perbaikan selesai. ✅", delta_text
        assert "item_started" in activity_types and "item_completed" in activity_types, activity_types
        assert activity_types[-1] == "turn_completed", activity_types
        assert types.index("activity") < types.index("delta"), types
        assert "cat README.md" in json.dumps(activity_items, ensure_ascii=False), activity_items
        assert not run.stderr.strip(), run.stderr
        print("PASS: wrapper syntax, real delta sequence, tool activity order, final segment, UTF-8, and no duplicate text")
        print("Observed protocol frame order:", " -> ".join(types))
        print("Combined streamed answer:", delta_text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
