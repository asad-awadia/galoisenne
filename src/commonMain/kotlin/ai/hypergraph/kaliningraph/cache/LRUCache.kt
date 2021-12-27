package ai.hypergraph.kaliningraph.cache

// TODO: LFU Cache
class LRUCache<K, V>(private val maxSize: Int,
                     private val sizeOf: (key: K, value: V) -> Int = { _, _ -> 1 }) {
  private val map: LinkedHashMap<K, V> = LinkedHashMap(0, .75f)
  private var size: Int = 0

  fun getOrPut(key: K, value: () -> V) =
    if (key in map) map[key] else value().also { put(key, it) }

  fun get(key: K) = map[key]

  fun put(key: K, value: V): V? {
    size += sizeOf(key, value)
    val prev: V? = map.put(key, value)
    prev?.let { size -= sizeOf(key, it) }

    trimToSize()
    return prev
  }

  fun remove(key: K): V? {
    val prev = map.remove(key)
    prev?.let { size -= sizeOf(key, it) }
    return prev
  }

  private tailrec fun trimToSize() {
    if (size <= maxSize || map.isEmpty()) return

    val toEvict = map.entries.iterator().next()
    val key = toEvict.key
    val value = toEvict.value
    map.remove(key)
    size -= sizeOf(key, value)
    trimToSize()
  }

  override fun toString() = "$size/$maxSize cached=$map"
}