package dev.notecore.canvas.prototype

data class DisplacedEntry<K, V>(val key: K, val value: V)
sealed interface CacheAdmission<K, V> {
    data class Stored<K, V>(val displaced: List<DisplacedEntry<K, V>>) : CacheAdmission<K, V>
    class TooLarge<K, V> : CacheAdmission<K, V>
    class InvalidWeight<K, V> : CacheAdmission<K, V>
}

/**
 * Single-owner, resident-byte reference policy. No platform buffers or resource disposal.
 * Displacement is informational, NOT permission to free a value still leased by a reader.
 */
class WeightedLruCache<K : Any, V : Any>(val maxBytes: Long, private val weigh: (V) -> Long) {
    private data class Entry<V>(val value: V, val bytes: Long)
    private val entries = LinkedHashMap<K, Entry<V>>()
    var residentBytes: Long = 0
        private set
    val size: Int get() = entries.size
    init { require(maxBytes > 0) }

    fun get(key: K): V? {
        val entry = entries.remove(key) ?: return null
        entries[key] = entry
        return entry.value
    }

    fun put(key: K, value: V): CacheAdmission<K, V> {
        val bytes = weigh(value)
        if (bytes <= 0) return CacheAdmission.InvalidWeight()
        if (bytes > maxBytes) return CacheAdmission.TooLarge()
        val displaced = mutableListOf<DisplacedEntry<K, V>>()
        entries.remove(key)?.let {
            residentBytes -= it.bytes
            displaced.add(DisplacedEntry(key, it.value))
        }
        // Subtract before comparing to avoid overflowing Long when budgets are large.
        while (residentBytes > maxBytes - bytes) {
            val oldest = entries.entries.first()
            val oldestKey = oldest.key
            val oldestValue = oldest.value
            entries.remove(oldestKey)
            residentBytes -= oldestValue.bytes
            displaced.add(DisplacedEntry(oldestKey, oldestValue.value))
        }
        entries[key] = Entry(value, bytes)
        residentBytes += bytes
        return CacheAdmission.Stored(displaced.toList())
    }

    fun clear(): List<DisplacedEntry<K, V>> {
        val displaced = entries.map { DisplacedEntry(it.key, it.value.value) }
        entries.clear()
        residentBytes = 0
        return displaced
    }
}
