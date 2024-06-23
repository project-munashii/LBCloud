package me.munashii.lbcloud.db

import kotlinx.coroutines.*
import java.util.function.BiFunction
import java.util.function.Function

// yes, this could be done easier, no, I really don't care
class SaveOnWriteHashMap<KeyType, ValueType>(
    private val saveFunction: (HashMap<KeyType, ValueType>) -> Unit
) : HashMap<KeyType, ValueType>() {

    private var nextSave: Long = 0
    private var unsavedChangesPresent = false
    private var locked = false

    suspend fun init() {
        while (true) {
            if (locked) continue

            while (System.currentTimeMillis() < nextSave) {
                delay(250)
            }

            if (unsavedChangesPresent) {
                saveFunction(this@SaveOnWriteHashMap)
                unsavedChangesPresent = false
                println(":: Changes have been saved.")
            }

            while (!unsavedChangesPresent) {
                delay(1000)
            }
        }
    }

    fun lock() {
        locked = true
    }

    fun unlock() {
        locked = false
    }

    @Suppress("NOTHING_TO_INLINE") // micro optimizations matter, kids
    private inline fun incrementSaveTimer() {
        println(":: Are we trolling?")
        nextSave = System.currentTimeMillis() + 4000
        unsavedChangesPresent = true
        println(":: Changes have happened.")
    }

    override fun put(key: KeyType, value: ValueType): ValueType? {
        incrementSaveTimer()
        return super.put(key, value)
    }

    override fun putAll(from: Map<out KeyType, ValueType>) {
        incrementSaveTimer()
        super.putAll(from)
    }

    override fun replace(key: KeyType, oldValue: ValueType, newValue: ValueType): Boolean {
        incrementSaveTimer()
        return super.replace(key, oldValue, newValue)
    }

    override fun replace(key: KeyType, value: ValueType): ValueType? {
        incrementSaveTimer()
        return super.replace(key, value)
    }

    override fun putIfAbsent(key: KeyType, value: ValueType): ValueType? {
        incrementSaveTimer()
        return super.putIfAbsent(key, value)
    }

    override fun replaceAll(function: BiFunction<in KeyType, in ValueType, out ValueType>) {
        incrementSaveTimer()
        super.replaceAll(function)
    }

    override fun remove(key: KeyType): ValueType? {
        incrementSaveTimer()
        return super.remove(key)
    }

    override fun remove(key: KeyType, value: ValueType): Boolean {
        incrementSaveTimer()
        return super.remove(key, value)
    }

    override fun merge(
        key: KeyType,
        value: ValueType & Any,
        remappingFunction: BiFunction<in ValueType & Any, in ValueType & Any, out ValueType?>
    ): ValueType? {
        incrementSaveTimer()
        return super.merge(key, value, remappingFunction)
    }

    override fun computeIfAbsent(key: KeyType, mappingFunction: Function<in KeyType, out ValueType>): ValueType {
        incrementSaveTimer()
        return super.computeIfAbsent(key, mappingFunction)
    }

    override fun computeIfPresent(
        key: KeyType,
        remappingFunction: BiFunction<in KeyType, in ValueType & Any, out ValueType?>
    ): ValueType? {
        incrementSaveTimer()
        return super.computeIfPresent(key, remappingFunction)
    }

    override fun compute(
        key: KeyType,
        remappingFunction: BiFunction<in KeyType, in ValueType?, out ValueType?>
    ): ValueType? {
        incrementSaveTimer()
        return super.compute(key, remappingFunction)
    }

}