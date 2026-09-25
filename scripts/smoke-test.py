#!/usr/bin/env python3
"""Exercise an isolated installed plugin; creates and deletes unique test indices."""
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread
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

version = os.environ.get("ES_VERSION", "9.5.4")
await_result(lambda: request("GET", "/").get("version", {}).get("number") == version)
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

http_host = os.environ.get("SYNONYM_HTTP_HOST")
if http_host:
    source = {"rules": "a => initial", "status": 200, "requests": 0}

    class DictionaryHandler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def do_HEAD(self):
            source["requests"] += 1
            self.send_response(200)
            self.end_headers()

        def do_GET(self):
            source["requests"] += 1
            body = source["rules"].encode("utf-8")
            self.send_response(source["status"])
            self.send_header("Content-Type", "text/plain; charset=UTF-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    server = ThreadingHTTPServer((os.environ.get("SYNONYM_HTTP_BIND", "127.0.0.1"), 0), DictionaryHandler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        for kind in ("dynamic_synonym", "dynamic_synonym_graph"):
            source.update(rules="a => initial", status=200)
            index = "synonym-http-smoke-" + uuid.uuid4().hex
            request("PUT", "/" + index, {"settings": {"number_of_shards": 1, "number_of_replicas": 0, "analysis": {
                "filter": {"rules": {"type": kind,
                                     "synonyms_path": f"http://{http_host}:{server.server_port}/rules", "interval": 1}},
                "analyzer": {"smoke": {"tokenizer": "whitespace", "filter": ["rules"]}}
            }}})
            def terms():
                result = request("POST", f"/{index}/_analyze", {"analyzer": "smoke", "text": "a"})
                return [token["token"] for token in result["tokens"]]
            try:
                assert terms() == ["initial"]
                source["rules"] = "a => changed"
                await_result(lambda: terms() == ["changed"], 15)
                source["status"] = 503
                time.sleep(2.5)
                assert terms() == ["changed"]
                source.update(rules="a => restored", status=200)
                await_result(lambda: terms() == ["restored"], 15)
            finally:
                request("DELETE", "/" + index)
            time.sleep(1.5)
            after_delete = source["requests"]
            time.sleep(2.5)
            assert source["requests"] == after_delete, "Deleted index still polls the HTTP dictionary"
            print(f"PASS installed {kind} HTTP: refresh, failure retention, recovery, index cleanup")
    finally:
        server.shutdown()
        server.server_close()
