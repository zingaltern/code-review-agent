#!/usr/bin/env python3
"""M4b 验收用 GitHub + OpenAI 兼容 stub（仅本地端到端演示；不是生产组件）。

- GitHub 面：暴露 cra-git-github 实际触达的端点（compare / contents / trees / user /
  pulls comments / issues comments / reviews / issue comments），返回一个零 finding 的 diff；
- LLM 面：任何 chat/completions 都返回 {"findings": []}——评审流水线以"干净 PR"完成，
  因此不需要构造 verifier/judge 响应；
- 捕获发布写请求到 /_stub/state，供验收断言"Summary 已发布"。
"""
import json
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

GITHUB_PORT = 18080
LLM_PORT = 18081
HEAD = "head-abc123"
BASE = "base-abc123"
FILE = "src/main/java/com/example/App.java"
FILE_TEXT = """package com.example;

public class App {
  public int value() {
    return 1;
  }
}
"""
PATCH = """@@ -0,0 +1,6 @@
+package com.example;
+
+public class App {
+  public int value() {
+    return 1;
+  }
+}
"""

state = {"reviews": [], "summaries": [], "patches": 0}
state_lock = threading.Lock()


class GitHubHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def _json(self, status, body):
        data = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _raw(self, status, data, content_type="text/plain"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        if path == "/_stub/state":
            with state_lock:
                self._json(200, state)
            return
        if path == "/user":
            self._json(200, {"login": "cra-stub-bot"})
            return
        if re.match(r"^/repos/[^/]+/[^/]+/compare/.+", path):
            with state_lock:
                state["patches"] += 1
            self._json(
                200,
                {
                    "status": "diverged",
                    "ahead_by": 1,
                    "total_commits": 1,
                    "commits": [{"sha": HEAD, "commit": {"message": "acceptance stub"}}],
                    "files": [
                        {
                            "filename": FILE,
                            "status": "added",
                            "patch": PATCH,
                        }
                    ],
                },
            )
            return
        m = re.match(r"^/repos/[^/]+/[^/]+/contents/(.+)$", path)
        if m:
            if m.group(1) == FILE:
                self._raw(200, FILE_TEXT.encode("utf-8"), "application/octet-stream")
            else:
                self._json(404, {"message": "Not Found"})
            return
        if re.match(r"^/repos/[^/]+/[^/]+/git/trees/.+", path):
            self._json(200, {"sha": HEAD, "tree": []})
            return
        if re.match(r"^/repos/[^/]+/[^/]+/(pulls|issues)/\d+/comments", path):
            self._json(200, [])
            return
        self._json(404, {"message": "Not Found: " + path})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length) if length else b""
        parsed = urlparse(self.path)
        path = parsed.path
        payload = json.loads(body.decode("utf-8")) if body else {}
        if re.match(r"^/repos/[^/]+/[^/]+/pulls/\d+/reviews$", path):
            with state_lock:
                state["reviews"].append(payload)
            comments = [
                {"id": 9000 + i}
                for i, _ in enumerate(payload.get("comments", []))
            ]
            self._json(200, {"id": "review-" + str(len(state["reviews"])), "comments": comments})
            return
        if re.match(r"^/repos/[^/]+/[^/]+/issues/\d+/comments$", path):
            with state_lock:
                state["summaries"].append(payload.get("body", ""))
            self._json(200, {"id": 7000 + len(state["summaries"])})
            return
        self._json(404, {"message": "Not Found: " + path})

    def do_PATCH(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length) if length else b""
        parsed = urlparse(self.path)
        if re.match(r"^/repos/[^/]+/[^/]+/issues/comments/\d+$", parsed.path):
            payload = json.loads(body.decode("utf-8")) if body else {}
            self._json(200, {"id": 1, "body": payload.get("body", "")})
            return
        self._json(404, {"message": "Not Found: " + parsed.path})


class LlmHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length:
            self.rfile.read(length)
        content = json.dumps({"findings": []})
        body = {
            "id": "chatcmpl-stub",
            "object": "chat.completion",
            "created": 1,
            "model": "stub-model",
            "choices": [
                {
                    "index": 0,
                    "message": {"role": "assistant", "content": content},
                    "finish_reason": "stop",
                }
            ],
            "usage": {"prompt_tokens": 100, "completion_tokens": 5, "total_tokens": 105},
        }
        data = json.dumps(body).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def serve(handler, port):
    ThreadingHTTPServer(("0.0.0.0", port), handler).serve_forever()


if __name__ == "__main__":
    threading.Thread(target=serve, args=(LlmHandler, LLM_PORT), daemon=True).start()
    serve(GitHubHandler, GITHUB_PORT)
