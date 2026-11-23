import json, os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from fastembed import TextEmbedding

MODEL = os.environ.get("EMBED_MODEL", "BAAI/bge-small-en-v1.5")
os.environ.setdefault("FASTEMBED_CACHE_PATH", "/models")
model = TextEmbedding(model_name=MODEL, cache_dir="/models")

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_POST(self):
        n = int(self.headers.get("content-length", "0"))
        body = json.loads(self.rfile.read(n) or b"{}")
        inputs = body.get("input", "")
        if isinstance(inputs, str): inputs = [inputs]
        vectors = [v.tolist() for v in model.embed(inputs)]
        out = {"object": "list", "model": MODEL,
               "data": [{"object": "embedding", "index": i, "embedding": v} for i, v in enumerate(vectors)],
               "usage": {"prompt_tokens": sum(len(x.split()) for x in inputs), "total_tokens": 0}}
        data = json.dumps(out).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)

ThreadingHTTPServer(("0.0.0.0", int(os.environ.get("EMBED_PORT", "18092"))), H).serve_forever()
