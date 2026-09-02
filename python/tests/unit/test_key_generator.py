from resp_bench.config.keyspace_config import KeyspaceConfig
from resp_bench.engine.key_generator import Counter, KeyGenerator


def _keyspace(**kw):
    defaults = dict(keys_count=100, key_prefix="test:", generation_alg="sequential_int")
    defaults.update(kw)
    return KeyspaceConfig(**defaults)


def test_sequential_wraps_around():
    gen = KeyGenerator.create(_keyspace(keys_count=3))
    keys = [gen.next_key() for _ in range(6)]
    assert keys[0] == keys[3]
    assert keys[1] == keys[4]
    assert keys[2] == keys[5]
    assert keys[0] != keys[1]


def test_sequential_shared_counter_across_workers():
    # Java shares the sequential counter across a phase's workers so they
    # collectively emit 0, 1, 2, ... (populating the whole keyspace).
    counter = Counter()
    g0 = KeyGenerator.create_with_seed(_keyspace(keys_count=1000), 0, sequential_counter=counter)
    g1 = KeyGenerator.create_with_seed(_keyspace(keys_count=1000), 1, sequential_counter=counter)
    keys = []
    for _ in range(5):
        keys.append(g0.next_key())
        keys.append(g1.next_key())
    # 10 draws from a shared counter -> 10 distinct keys (indices 0..9).
    assert len(set(keys)) == 10


def test_uniform_rand_reproducible():
    ks = _keyspace(keys_count=1000, key_prefix="rand:", generation_alg="uniform_rand", seed=12345)
    a = [KeyGenerator.create(ks).next_key() for _ in range(20)]
    ks2 = _keyspace(keys_count=1000, key_prefix="rand:", generation_alg="uniform_rand", seed=12345)
    b = [KeyGenerator.create(ks2).next_key() for _ in range(20)]
    assert a == b


def test_uniform_rand_has_variety():
    ks = _keyspace(keys_count=1000, key_prefix="rand:", generation_alg="uniform_rand", seed=12345)
    gen = KeyGenerator.create(ks)
    keys = [gen.next_key() for _ in range(100)]
    assert len(set(keys)) > 50


def test_key_format_padding():
    # key_size_bytes=16, prefix "bench:" (6 chars) -> padding width 10.
    ks = KeyspaceConfig(keys_count=100, key_size_bytes=16, key_prefix="bench:")
    gen = KeyGenerator.create(ks)
    key = gen.next_key()
    assert key == "bench:0000000000"


def test_key_format_min_padding_width():
    # When prefix is longer than key_size_bytes, padding width is at least 1.
    ks = KeyspaceConfig(keys_count=100, key_size_bytes=2, key_prefix="longprefix:")
    gen = KeyGenerator.create(ks)
    assert gen.next_key() == "longprefix:0"
