# bytemsg233-lib-kotlin

Kotlin runtime for `bytemsg233` generated code.

This repository provides encode/decode helpers, object pool, and enum utilities for generated Kotlin data classes. Works on JVM and Android.

## Features

- `enum class` helpers for type-safe enum restore
- Companion object `acquire()` / `release()` pooling
- Varint, zigzag, string, bytes, list, map, nested message support
- Single-threaded by design: no locks, no concurrent collections, no thread pools
- JVM and Android compatible
- Zero external dependencies

## Install

Copy-based install from the main repository:

```bash
bytemsg233 install-lib kotlin --to ./libs/bytemsg233
```

Or add as a git submodule:

```bash
git submodule add https://github.com/neko233-com/bytemsg233-lib-kotlin.git libs/bytemsg233
```

## Quick Start

```kotlin
import com.neko233.bytemsg233.*

enum class HeroState(val value: Int) {
    Idle(0),
    Moving(1),
    Dead(2);

    companion object {
        fun fromValue(v: Int) = entries.firstOrNull { it.value == v } ?: Idle
    }
}

data class Hero(
    var id: Long = 0,
    var name: String = "",
    var state: HeroState = HeroState.Idle,
    val tags: MutableList<String> = mutableListOf()
) {
    fun reset() {
        id = 0
        name = ""
        state = HeroState.Idle
        tags.clear()
    }

    companion object {
        private val pool = ByteMsgPool { Hero() }

        fun acquire(): Hero = pool.acquire()
        fun release(hero: Hero) {
            hero.reset()
            pool.release(hero)
        }
    }

    fun encode(): ByteArray {
        val writer = ByteMsgWriter()
        writer.writeUintField(1, id)
        writer.writeStringField(2, name)
        writer.writeEnumField(3, state.value)
        writer.writeListField(4, tags) { w, v -> w.writeString(v) }
        return writer.finish()
    }

    companion object Decoder {
        fun decode(data: ByteArray): Hero {
            val hero = Hero.acquire()
            val reader = ByteMsgReader(data)
            while (!reader.eof) {
                val (tag, wireType) = reader.readFieldHeader()
                when (tag) {
                    1 -> hero.id = reader.readVarintLong()
                    2 -> hero.name = reader.readString()
                    3 -> hero.state = HeroState.fromValue(reader.readVarintInt())
                    4 -> hero.tags.addAll(reader.readList { it.readString() })
                    else -> reader.skipField(wireType)
                }
            }
            return hero
        }
    }
}
```

## API

- `ByteMsgWriter`: field header, scalar, string, bytes, list, map, nested message writing
- `ByteMsgReader`: field header, scalar reading, field skipping with bounded length checks
- `ByteMsgPool<T>`: single-threaded object pool with `acquire()` / `release()`
- Enum helpers for `enum class` value restore and validation

## Development

```bash
./gradlew test
```
