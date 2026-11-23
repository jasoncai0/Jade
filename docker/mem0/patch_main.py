"""Patch the Mem0 API server config for a local Qdrant + OpenAI-compatible stack.

The upstream image hard-codes pgvector/Neo4j/OpenAI. For local use we replace
only DEFAULT_CONFIG and leave the FastAPI routes untouched.
"""
from pathlib import Path

p = Path("/app/main.py")
s = p.read_text()
start = s.index("DEFAULT_CONFIG = {")
end = s.index("\n\n\nMEMORY_INSTANCE", start)
config = '''DEFAULT_CONFIG = {
    "version": "v1.1",
    "vector_store": {
        "provider": "qdrant",
        "config": {
            "collection_name": "memories",
            "embedding_model_dims": 384,
            "path": "/app/qdrant",
            "on_disk": True,
        },
    },
    "llm": {
        "provider": "openai",
        "config": {
            "api_key": OPENAI_API_KEY,
            "temperature": 0.2,
            "model": os.environ.get("MEM0_LLM_MODEL", "deepseek-chat"),
            "openai_base_url": os.environ.get("MEM0_LLM_BASE_URL", "https://api.deepseek.com"),
        },
    },
    "embedder": {
        "provider": "openai",
        "config": {
            "api_key": "local-embedding",
            "model": "bge-small-en-v1.5",
            "embedding_dims": 384,
            "openai_base_url": os.environ.get("MEM0_EMBED_BASE_URL", "http://jade-embed:18092/v1"),
        },
    },
    "history_db_path": HISTORY_DB_PATH,
}'''
p.write_text(s[:start] + config + s[end:])
print("patched", p)
