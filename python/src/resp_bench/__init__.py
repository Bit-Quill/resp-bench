"""resp-bench Python engine.

Async implementation of the resp-bench benchmark suite, at parity with the
Java (reference), Ruby, and C# engines. Drives async clients (valkey-glide,
redis-py asyncio) on a single asyncio event loop with one client per
connection.
"""

from .version import VERSION

__all__ = ["VERSION"]
