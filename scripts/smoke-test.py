#!/usr/bin/env python3
"""Exercise an isolated installed plugin; creates and deletes unique test indices."""
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.request
import uuid

base = os.environ.get("ES_URL", "http://127.0.0.1:19200").rstrip("/")
# Must be a writable test file visible at config/synonym-smoke.txt in the isolated node.
rules = Path(os.environ["SYNONYM_FILE"])

def request(method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, method=method, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=5) as response:
        return json.load(response)

def await_result(check, seconds=60):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            if check():
                return
        except (OSError, urllib.error.HTTPError) as error:
            last = error
        time.sleep(0.2)
    raise AssertionError(f"Smoke test timed out; last error: {last}")

await_result(lambda: request("GET", "/").get("version", {}).get("number") == "8.7.1")
nodes = request("GET", "/_nodes/plugins")["nodes"]
assert nodes and all(any(plugin["name"] == "analysis-dynamic-synonym" for plugin in node["plugins"])
                     for node in nodes.values()), "Expected the packaged plugin to be installed on every test node"
for kind in ("dynamic_synonym", "dynamic_synonym_graph"):
    index = "synonym-smoke-" + uuid.uuid4().hex
    rules.write_text("", encoding="utf-8")
    request("PUT", "/" + index, {"settings": {"number_of_shards": 1, "number_of_replicas": 0, "analysis": {
        "filter": {"rules": {"type": kind, "synonyms_path": "synonym-smoke.txt", "interval": 1}},
        "analyzer": {"smoke": {"tokenizer": "whitespace", "filter": ["rules"]}}
    }}})
    def terms():
        result = request("POST", f"/{index}/_analyze", {"analyzer": "smoke", "text": "a"})
        return [token["token"] for token in result["tokens"]]
    try:
        assert terms() == ["a"]
        rules.write_text("a => first second", encoding="utf-8")
        await_result(lambda: terms() == ["first", "second"], 15)
        rules.write_text("a => b => c", encoding="utf-8")
        # Observe multiple polling intervals without losing the last valid map.
        until = time.monotonic() + 2.5
        while time.monotonic() < until:
            assert terms() == ["first", "second"]
            time.sleep(0.1)
        rules.write_text("", encoding="utf-8")
        await_result(lambda: terms() == ["a"], 15)
        rules.write_text("a => restored", encoding="utf-8")
        await_result(lambda: terms() == ["restored"], 15)
        print(f"PASS installed {kind}: empty, reload, invalid preservation, empty, recovery")
    finally:
        request("DELETE", "/" + index)
